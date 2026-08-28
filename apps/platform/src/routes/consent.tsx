import { createFileRoute, useRouterState } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";

/**
 * The OAuth consent page an MCP client is redirected to (PRD sections 15.1 and 15.2).
 *
 * `@better-auth/mcp` sends the signed authorization query here as the query string and
 * expects it back, unchanged, on `POST /api/auth/oauth2/consent`. So this page never
 * builds an OAuth request: it displays what was asked for, lets the owner narrow it,
 * and hands the same signed query back. The signature is what makes that safe -- a
 * tampered `scope` or `redirect_uri` fails verification on the server.
 *
 * Those are two different strings, and this file keeps them apart on purpose. What is
 * *displayed* is read from the router, the only source both halves of a server-rendered
 * page agree on. What is *handed back* is read from the address bar, because the router
 * cannot carry it -- see {@link signedOAuthQuery}.
 *
 * The file is named for the route it serves. `readAuthConfig().consentPage` defaults to
 * `/consent`, so with file-based routing the default configuration now resolves to a
 * page that exists; `MUE_CONSENT_PAGE` only has to be set to move it somewhere else.
 *
 * This page is deliberately not an authorization. Section 16 is explicit that a
 * browser-side guard never is: every decision here is re-checked by Better Auth against
 * the session cookie and the signed query.
 */

/**
 * One line per scope, in the words a human would use.
 *
 * TODO: these mirror `SCOPE_DESCRIPTIONS` in `packages/auth/src/scopes.ts` and should
 * not be written twice. They are not imported because `@mue/auth`'s entry point pulls
 * in Better Auth and Drizzle, and nothing server-side belongs in a browser bundle. A
 * client-safe home for the scope vocabulary -- a `@mue/auth/scopes` subpath, or
 * `@mue/contracts` -- removes the duplication; both files are owned by another chunk.
 */
const SCOPE_LABELS: Readonly<Record<string, string>> = {
  openid: "Identify the account you are signing in with",
  offline_access: "Stay connected without asking you again",
  "profile:read": "Read your height and date of birth",
  "profile:write": "Update your height and date of birth",
  "weight:read": "Read your weight measurements",
  "weight:write": "Record and update weight measurements",
  "activity:read": "Read your finished activity sessions",
  "activity:write": "Create and update activity sessions",
  "nutrition:read": "Read your food data",
  "nutrition:write": "Write your food data",
  "data:delete": "Delete synchronised data",
};

/**
 * Scopes the owner cannot untick.
 *
 * `openid` and `offline_access` are OAuth machinery, not Mue permissions: unticking
 * `offline_access` costs the agent its refresh token and produces a puzzling
 * re-authorisation an hour later rather than a privacy gain.
 */
const MACHINERY_SCOPES = new Set(["openid", "offline_access"]);

/** A deletion is the one grant that cannot be undone by a later mutation. */
function isDangerous(scope: string): boolean {
  return scope === "data:delete";
}

export interface ConsentRequest {
  readonly clientId: string;
  readonly clientName: string;
  readonly scopes: readonly string[];
  readonly resource: string | null;
}

/**
 * What to show the owner: derived, lossy, and sent nowhere.
 *
 * It deliberately no longer carries the query to hand back. It used to, taken from the
 * same string, and that is precisely how this page came to fail verification: the string
 * it is given is the router's, and the router's is not the server's.
 */
export function readConsentRequest(search: string): ConsentRequest | null {
  const params = new URLSearchParams(search);
  const clientId = params.get("client_id");
  if (clientId === null) return null;

  const scopes = (params.get("scope") ?? "").split(" ").filter((scope) => scope.length > 0);
  return {
    clientId,
    // Replaced by the registered name once the server answers; the id is what is
    // certainly true before then.
    clientName: clientId,
    scopes,
    resource: params.get("resource"),
  };
}

/**
 * The signed query to hand back: the address bar, byte for byte.
 *
 * `useRouterState().location.searchStr` is *not* the query the server sent. TanStack
 * Router computes it, in `parseLocation`, as
 *
 *     stringifySearch(parseSearch(search))
 *
 * -- it parses the query into a plain object and serialises that object back out. The
 * default pair is lossy in two ways this particular query trips over
 * (`@tanstack/router-core`, `src/qss.ts` and `src/searchParams.ts`):
 *
 *  - `decode` collects a repeated key into an array, and `encode` writes every key
 *    exactly once, with `URLSearchParams.set` and a JSON value. This query repeats one:
 *    `setSignedOAuthQueryParameterNames` appends `ba_param` once per signed parameter
 *    name, so the eleven `ba_param=ba_iat&ba_param=client_id&...` the server sent come
 *    back as a single `ba_param=["ba_iat","client_id",...]`;
 *  - `parseSearchWith(JSON.parse)` replaces any value that happens to be valid JSON with
 *    what it parses to, so a `state` of `12e5` would return as `1200000`.
 *
 * `verifyOAuthQueryParams` sorts what comes back, re-signs it and compares. Ordering and
 * percent-encoding are therefore free -- both sides end at `URLSearchParams.toString()`,
 * so `%20` against `+` and `%3A` against `:` all survive -- but a key that arrived eleven
 * times and left once is a different message, and the answer is `invalid_signature`,
 * rendered in red under the Allow button.
 *
 * The address bar has no opinion to impose: it holds the bytes the 302 put there. Nothing
 * rewrites it, because TanStack touches history only in `commitLocation`, which runs on a
 * navigation and never on the load that brought the owner here.
 *
 * `addressBar` is a parameter rather than a read of `window` buried in the body, so that
 * a test can hand one in. The page leaves it out: there is no `window` while the page is
 * server-rendered, and no submission there either, so the router's own string is returned
 * in that case as a value nothing displays and nobody posts.
 */
export function signedOAuthQuery(routerSearch: string, addressBar?: string): string {
  const raw = addressBar ?? (typeof window === "undefined" ? routerSearch : window.location.search);
  return raw.replace(/^\?/, "");
}

async function loadClientName(clientId: string): Promise<string | null> {
  try {
    const response = await fetch(
      `/api/auth/oauth2/public-client?client_id=${encodeURIComponent(clientId)}`,
      { credentials: "same-origin" },
    );
    if (!response.ok) return null;
    const client = (await response.json()) as { client_name?: string; name?: string };
    return client.client_name ?? client.name ?? null;
  } catch {
    // A missing display name is not a reason to block a decision the owner can still
    // make from the client id.
    return null;
  }
}

async function decide(
  oauthQuery: string,
  accept: boolean,
  grantedScopes: readonly string[],
): Promise<string> {
  const response = await fetch("/api/auth/oauth2/consent", {
    method: "POST",
    credentials: "same-origin",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      accept,
      // Sent only on acceptance: on a refusal there is nothing to narrow, and Better
      // Auth rejects a scope that was not originally requested.
      ...(accept ? { scope: grantedScopes.join(" ") } : {}),
      oauth_query: oauthQuery,
    }),
  });

  const body = (await response.json()) as { url?: string; redirect_uri?: string; error?: string };
  const url = body.url ?? body.redirect_uri;
  if (url === undefined) throw new Error(body.error ?? "The server refused this authorization.");
  return url;
}

export const Route = createFileRoute("/consent")({ component: OAuthConsentPage });

export function OAuthConsentPage(): React.ReactElement {
  /**
   * The router's location, not `window.location`.
   *
   * This page is server-rendered now that it is mounted, and `window` does not exist
   * there. Reading it during render produced an empty request on the server and a
   * populated one in the browser: React would discard the server tree on that mismatch,
   * and until it did, the first thing the owner saw of an authorization request was
   * "Nothing to authorize". The router carries the same query on both sides.
   */
  const searchStr = useRouterState({ select: (state) => state.location.searchStr });
  const request = useMemo(() => readConsentRequest(searchStr), [searchStr]);

  const [clientName, setClientName] = useState<string | null>(null);
  const [granted, setGranted] = useState<readonly string[]>(request?.scopes ?? []);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (request === null) return;
    void loadClientName(request.clientId).then(setClientName);
  }, [request]);

  if (request === null) {
    return (
      <main>
        <h1>Nothing to authorize</h1>
        <p>Open this page from an application asking for access, not directly.</p>
      </main>
    );
  }

  const submit = (accept: boolean) => {
    setBusy(true);
    setProblem(null);
    // Read here rather than during render: a submission only ever happens in a browser,
    // which is the one place the address bar is both present and still untouched.
    decide(signedOAuthQuery(searchStr), accept, granted)
      .then((url) => {
        window.location.assign(url);
      })
      .catch((error: unknown) => {
        setProblem(error instanceof Error ? error.message : "The authorization failed.");
        setBusy(false);
      });
  };

  const toggle = (scope: string) => {
    setGranted((current) =>
      current.includes(scope) ? current.filter((value) => value !== scope) : [...current, scope],
    );
  };

  return (
    <main>
      <h1>Authorize {clientName ?? request.clientName}</h1>
      <p>
        This application is asking to use your Mue data. It runs outside Mue, and it will only be
        able to do what you leave ticked below.
      </p>
      {request.resource !== null && (
        <p>
          It will connect to <code>{request.resource}</code>.
        </p>
      )}

      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit(true);
        }}
      >
        <fieldset disabled={busy}>
          <legend>What it may do</legend>
          <ul>
            {request.scopes.map((scope) => {
              const fixed = MACHINERY_SCOPES.has(scope);
              return (
                <li key={scope}>
                  <label>
                    <input
                      type="checkbox"
                      name="scope"
                      value={scope}
                      checked={granted.includes(scope)}
                      disabled={fixed}
                      onChange={() => {
                        toggle(scope);
                      }}
                    />{" "}
                    {SCOPE_LABELS[scope] ?? scope}
                    {isDangerous(scope) && <strong> — this one cannot be undone</strong>}
                  </label>
                </li>
              );
            })}
          </ul>
        </fieldset>
        {/* Allow is the submit so the keyboard does what the mouse does; refusing is
            never the default action of a form the owner may have opened by accident. */}
        <button type="submit" className="primary" disabled={busy}>
          Allow
        </button>{" "}
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            submit(false);
          }}
        >
          Refuse
        </button>
      </form>

      {problem !== null && <p role="alert">{problem}</p>}
    </main>
  );
}

export default OAuthConsentPage;
