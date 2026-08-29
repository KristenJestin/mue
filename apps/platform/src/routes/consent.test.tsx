import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createAuth, type AuthHandle } from "@mue/auth";
import { createTestDatabase } from "@mue/db";
import { getRouter } from "../router";
import { readConsentRequest, signedOAuthQuery } from "./consent";

describe("the consent request", () => {
  const search =
    "?response_type=code&client_id=agent-1&scope=offline_access+weight%3Aread+activity%3Awrite" +
    "&redirect_uri=http%3A%2F%2F127.0.0.1%3A9876%2Fcallback&resource=https%3A%2F%2Fmue.home%2Fmcp&sig=abc";

  test("reads what the agent asked for", () => {
    const request = readConsentRequest(search);
    expect(request).not.toBeNull();
    expect(request!.clientId).toBe("agent-1");
    expect(request!.scopes).toEqual(["offline_access", "weight:read", "activity:write"]);
    expect(request!.resource).toBe("https://mue.home/mcp");
  });

  test("shows nothing when opened directly", () => {
    expect(readConsentRequest("")).toBeNull();
    expect(readConsentRequest("?scope=weight%3Aread")).toBeNull();
  });
});

describe("the query the page hands back", () => {
  test("is the address bar, not the router's account of it", () => {
    const addressBar = "?client_id=a&ba_param=client_id&ba_param=exp&sig=z";
    // What the router would have given the page instead. The point of the function is
    // that it is ignored whenever the browser has the real thing.
    expect(signedOAuthQuery('?client_id=a&ba_param=["client_id","exp"]&sig=z', addressBar)).toBe(
      "client_id=a&ba_param=client_id&ba_param=exp&sig=z",
    );
  });

  test("falls back to the router where there is no address bar to read", () => {
    // Server rendering. Nothing is submitted there, so this value reaches no endpoint;
    // returning it keeps the function total rather than throwing on a path that a future
    // caller might legitimately reach.
    expect(signedOAuthQuery("?client_id=a&sig=z")).toBe("client_id=a&sig=z");
  });
});

/**
 * The page's one hard obligation, proven against a real authorization server.
 *
 * `@better-auth/oauth-provider` signs the authorization query, redirects the owner here
 * with it, and re-signs whatever comes back to compare. Nothing about that is visible
 * from inside this page: a test that posts a query it built itself proves only that the
 * server can verify its own signature. So this drives the real thing -- a real Better
 * Auth on a real PostgreSQL, a real authorization request, the real 302 -- and then
 * hands the query back the way the browser does, through the application's own router.
 *
 * `getRouter()` is the router the browser runs, and `parseLocation` is the function
 * behind `useRouterState({ select: (state) => state.location.searchStr })`, which is
 * where this page reads its query. Calling it with the location the 302 produced gives,
 * byte for byte, the string the page had in hand when a real client got
 * `invalid_signature` under the Allow button.
 *
 * `createTestDatabase()` is not optional: without it `createAuth` falls back to
 * `DATABASE_URL`, which is the development cluster a phone pairs with.
 */
describe("answering a real authorization request", () => {
  const OWNER_PASSWORD = "correct horse battery staple";
  const REDIRECT_URI = "http://127.0.0.1:9876/callback";
  const REQUESTED_SCOPES = "openid offline_access profile:read weight:read weight:write";

  let handle: AuthHandle;
  let server: ReturnType<typeof Bun.serve>;
  let base = "";
  let cookie = "";
  /** The `Location` of the 302, i.e. what the browser's address bar then holds. */
  let consentUrl: URL;

  function ownerHeaders(): Record<string, string> {
    return { "content-type": "application/json", origin: base, cookie };
  }

  /** What `useRouterState` hands the page, produced by the application's own router. */
  function routerSearchStr(url: URL): string {
    return getRouter().parseLocation({
      href: `${url.pathname}${url.search}`,
      pathname: url.pathname,
      search: url.search,
      hash: "",
      state: { __TSR_index: 0 },
    }).searchStr;
  }

  async function postConsent(oauthQuery: string, scope: string): Promise<Response> {
    return fetch(`${base}/api/auth/oauth2/consent`, {
      method: "POST",
      headers: ownerHeaders(),
      body: JSON.stringify({ accept: true, scope, oauth_query: oauthQuery }),
    });
  }

  beforeAll(async () => {
    // The port is learned before the auth instance is built: `baseUrl` is the OAuth
    // issuer, and the redirect this test follows is derived from it.
    let auth: AuthHandle | undefined;
    server = Bun.serve({
      port: 0,
      hostname: "127.0.0.1",
      fetch: (request) => auth!.auth.handler(request),
    });
    base = `http://127.0.0.1:${server.port}`;

    handle = createAuth({
      config: {
        secret: "consent-page-test-secret-at-least-32-characters",
        baseUrl: base,
        trustedOrigins: [base],
        mcpResource: `${base}/mcp`,
        loginPage: "/sign-in",
        consentPage: "/consent",
        secureCookies: false,
      },
      // `mue_test`, never `DATABASE_URL`. See the block comment above.
      database: createTestDatabase(),
    });
    auth = handle;

    // A signing key encrypted under another secret cannot be decrypted, and the test
    // database is shared with every other suite that boots Better Auth.
    await handle.database.sql`delete from jwks`;

    const email = `consent-owner+${Date.now()}@mue.test`;
    const signUp = await fetch(`${base}/api/auth/sign-up/email`, {
      method: "POST",
      headers: { "content-type": "application/json", origin: base },
      body: JSON.stringify({ email, password: OWNER_PASSWORD, name: "Consent owner" }),
    });
    expect(signUp.status).toBe(200);
    cookie = signUp.headers
      .getSetCookie()
      .map((value) => value.split(";")[0])
      .join("; ");

    const created = await fetch(`${base}/api/auth/oauth2/create-client`, {
      method: "POST",
      headers: ownerHeaders(),
      body: JSON.stringify({
        client_name: "Consent page client",
        redirect_uris: [REDIRECT_URI],
        token_endpoint_auth_method: "none",
        scope: REQUESTED_SCOPES,
        application_type: "native",
      }),
    });
    expect(created.status).toBe(201);
    const clientId = ((await created.json()) as { client_id: string }).client_id;

    const authorize = new URL(`${base}/api/auth/oauth2/authorize`);
    for (const [key, value] of Object.entries({
      response_type: "code",
      client_id: clientId,
      redirect_uri: REDIRECT_URI,
      scope: REQUESTED_SCOPES,
      state: "8Kd1yWq0LpZ2",
      code_challenge: "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      code_challenge_method: "S256",
      resource: `${base}/mcp`,
    })) {
      authorize.searchParams.set(key, value);
    }

    const redirected = await fetch(authorize, { headers: { cookie }, redirect: "manual" });
    expect(redirected.status).toBe(302);
    consentUrl = new URL(redirected.headers.get("location")!, base);
    expect(consentUrl.pathname).toBe("/consent");
  }, 30_000);

  afterAll(async () => {
    server.stop(true);
    await handle.close();
  });

  test("the router cannot carry the signed query, which is the whole bug", () => {
    const raw = consentUrl.search.replace(/^\?/, "");
    const throughRouter = routerSearchStr(consentUrl).replace(/^\?/, "");
    expect(throughRouter).not.toBe(raw);

    // Named precisely, because "they differ" would still pass if the difference moved.
    // The server repeats `ba_param` once per signed parameter name; the router's
    // `encode` writes every key once, with `URLSearchParams.set` and a JSON value.
    const sent = new URLSearchParams(raw);
    const back = new URLSearchParams(throughRouter);
    const changed = [...new Set([...sent.keys(), ...back.keys()])].filter(
      (key) => JSON.stringify(sent.getAll(key)) !== JSON.stringify(back.getAll(key)),
    );
    expect(changed).toEqual(["ba_param"]);
    expect(sent.getAll("ba_param").length).toBeGreaterThan(1);
    expect(back.getAll("ba_param")).toEqual([JSON.stringify(sent.getAll("ba_param"))]);
  });

  test("the router's version of it is refused, exactly as the owner saw", async () => {
    const refused = await postConsent(routerSearchStr(consentUrl).replace(/^\?/, ""), "openid");
    expect(refused.status).toBe(400);
    expect(await refused.json()).toEqual({ error: "invalid_signature" });
  });

  test("what the page hands back is accepted, and the grant produces a code", async () => {
    // Precisely what `OAuthConsentPage` posts: the router's string is what it was given,
    // the address bar is what it sends.
    const handedBack = signedOAuthQuery(routerSearchStr(consentUrl), consentUrl.search);

    const granted = await postConsent(handedBack, "openid offline_access weight:read");
    expect(granted.status).toBe(200);

    const { url } = (await granted.json()) as { url: string };
    const callback = new URL(url);
    expect(`${callback.origin}${callback.pathname}`).toBe(REDIRECT_URI);
    expect(callback.searchParams.get("code")).not.toBeNull();
    expect(callback.searchParams.get("state")).toBe("8Kd1yWq0LpZ2");
  });
});
