import { createFileRoute, useRouterState } from "@tanstack/react-router";
import { useState } from "react";
import type { ReactElement } from "react";

/**
 * `/sign-in` -- the page `readAuthConfig().loginPage` points at (PRD section 15.1).
 *
 * Two callers reach it. A person opening the platform in a browser, and
 * `@better-auth/oauth-provider`, which redirects an unauthenticated authorization
 * request here as `<loginPage>?<signed query>` and expects that query to come back:
 * `redirectWithPromptCode` signs the OAuth parameters with the Better Auth secret and
 * hands the signature along as `sig`. So this page never rebuilds an OAuth request --
 * it carries the received query, byte for byte, to `/api/auth/oauth2/authorize` once a
 * session exists, and the provider picks the flow back up at whatever step it left.
 *
 * Nothing here is an authorization. Section 16 is explicit that a browser-side guard
 * never is: the session is created by Better Auth against the database, the cookie is
 * `HttpOnly` so this code cannot read it, and every subsequent decision is re-checked
 * server-side against the signature.
 *
 * The form posts with `fetch` rather than as a native form submission because Better
 * Auth's endpoints read JSON. The consent page of section 15.2 already talks to
 * `/api/auth/*` the same way, and doing it here too means the whole shell needs no
 * Better Auth client bundle in the browser -- which is the cheapest possible proof of
 * section 15.1's "aucun client ne reçoit le secret maître".
 */

/** What the authorization server asked us to finish, if anything. */
export interface SignInContinuation {
  /** The signed query, handed back verbatim. */
  readonly oauthQuery: string;
  readonly clientId: string;
}

/**
 * The provider always sends `client_id`; its absence means a person navigated here.
 * The query is never parsed for meaning beyond that -- re-serialising it is how a
 * login page silently starts failing signature verification.
 */
export function readSignInContinuation(search: string): SignInContinuation | null {
  const query = search.replace(/^\?/, "");
  if (query === "") return null;
  const clientId = new URLSearchParams(query).get("client_id");
  if (clientId === null) return null;
  return { oauthQuery: query, clientId };
}

type Mode = "sign-in" | "sign-up";

interface Credentials {
  readonly email: string;
  readonly password: string;
  readonly name: string;
}

/**
 * Better Auth answers a rejected credential with a non-2xx and a `message`. The text is
 * shown as sent: it is the server's own wording about what the caller supplied, and it
 * carries nothing the caller did not already know.
 */
async function authenticate(mode: Mode, credentials: Credentials): Promise<void> {
  const response = await fetch(
    mode === "sign-up" ? "/api/auth/sign-up/email" : "/api/auth/sign-in/email",
    {
      method: "POST",
      credentials: "same-origin",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(
        mode === "sign-up"
          ? { name: credentials.name, email: credentials.email, password: credentials.password }
          : { email: credentials.email, password: credentials.password },
      ),
    },
  );
  if (response.ok) return;

  const body = (await response.json().catch(() => null)) as { message?: string } | null;
  throw new Error(body?.message ?? "Those details were refused.");
}

export const Route = createFileRoute("/sign-in")({ component: SignInPage });

export function SignInPage(): ReactElement {
  /**
   * The router's location, not `window.location`. The server renders this page too, and
   * a component that reads `window` during render produces one tree on the server and a
   * different one in the browser -- React discards the server's, and the OAuth query
   * would be lost on the way through.
   */
  const searchStr = useRouterState({ select: (state) => state.location.searchStr });
  const continuation = readSignInContinuation(searchStr);

  const [mode, setMode] = useState<Mode>("sign-in");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setProblem(null);

    authenticate(mode, {
      email: String(form.get("email") ?? ""),
      password: String(form.get("password") ?? ""),
      name: String(form.get("name") ?? ""),
    })
      .then(() => {
        // A full navigation, not a router one: the next stop is either the Hono half of
        // the entry point or a page that must be rendered with the new session cookie.
        window.location.assign(
          continuation === null ? "/" : `/api/auth/oauth2/authorize?${continuation.oauthQuery}`,
        );
      })
      .catch((error: unknown) => {
        setProblem(error instanceof Error ? error.message : "Signing in failed.");
        setBusy(false);
      });
  };

  return (
    <main>
      <h1>{mode === "sign-up" ? "Create your Mue account" : "Sign in to Mue"}</h1>
      {continuation === null ? (
        <p>This is your own server. Signing in links this browser to it.</p>
      ) : (
        <p>
          <code>{continuation.clientId}</code> is asking for access to your Mue data. Sign in first;
          you will be asked what to allow on the next screen.
        </p>
      )}

      <form onSubmit={submit}>
        <fieldset disabled={busy}>
          <legend>{mode === "sign-up" ? "New account" : "Your account"}</legend>

          {mode === "sign-up" && (
            <label>
              <span>Name</span>
              <input type="text" name="name" autoComplete="name" required />
            </label>
          )}

          <label>
            <span>Email</span>
            <input type="email" name="email" autoComplete="username" required autoFocus />
          </label>

          <label>
            <span>Password</span>
            <input
              type="password"
              name="password"
              autoComplete={mode === "sign-up" ? "new-password" : "current-password"}
              // Mirrors `emailAndPassword.minPasswordLength` in packages/auth. The
              // server refuses a shorter one either way; this only saves a round trip.
              minLength={12}
              required
            />
          </label>

          <button type="submit">
            {busy ? "Working…" : mode === "sign-up" ? "Create account" : "Sign in"}
          </button>
        </fieldset>
      </form>

      {problem !== null && <p role="alert">{problem}</p>}

      <p className="quiet">
        {mode === "sign-up" ? "Already set this server up? " : "First time on this server? "}
        <button
          type="button"
          onClick={() => {
            setProblem(null);
            setMode(mode === "sign-up" ? "sign-in" : "sign-up");
          }}
        >
          {mode === "sign-up" ? "Sign in instead" : "Create the account"}
        </button>
      </p>
    </main>
  );
}
