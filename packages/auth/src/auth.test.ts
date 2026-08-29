import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createTestDatabase, migrate, type DatabaseHandle } from "@mue/db";
import { listSessions, revokeSession } from "./administration";
import { createAuth, type AuthHandle } from "./auth";
import type { AuthConfig } from "./config";
import { MUE_SCOPES } from "./scopes";

/**
 * Better Auth against the real development PostgreSQL, through the real
 * Drizzle adapter, on the tables where the connection's `search_path` puts
 * them. Nothing is mocked: if the adapter could not address that table set,
 * sign-up would fail here with `relation "user" does not exist`.
 */

const BASE_URL = "http://localhost:3000";
const CONFIG: AuthConfig = {
  secret: "test-secret-that-is-long-enough-32+",
  baseUrl: BASE_URL,
  trustedOrigins: [BASE_URL],
  mcpResource: `${BASE_URL}/mcp`,
  loginPage: "/sign-in",
  consentPage: "/consent",
  secureCookies: false,
};

let database: DatabaseHandle;
let handle: AuthHandle;
const email = `bearer-${Date.now()}@mue.test`;
const password = "correct-horse-battery";

const call = (path: string, init?: RequestInit) =>
  handle.auth.handler(new Request(`${BASE_URL}${path}`, init));

beforeAll(async () => {
  database = createTestDatabase();
  await migrate(database);
  // AGENTS.md §9.2. La clé de signature JWKS est chiffrée avec le secret qui
  // l'a émise, et `mue_test` est la seule base que toutes les suites partagent.
  // `packages/api/src/mcp/mcp.integration.test.ts` en laisse une derrière lui,
  // chiffrée sous « integration-test-secret-… » ; sans ce nettoyage, la suite
  // suivante dans l'ordre du §10 échoue sur « Failed to decrypt private key »,
  // ce qui ne dit rien de ce qu'elle teste. Les suites de `@mue/api` vident
  // déjà cette table en `beforeAll` pour exactement cette raison.
  await database.sql`delete from jwks`;
  handle = createAuth({ config: CONFIG, database });
});

afterAll(async () => {
  await database.close();
});

describe("the Drizzle adapter against the Better Auth tables", () => {
  test("creates an account", async () => {
    const response = await call("/api/auth/sign-up/email", {
      method: "POST",
      headers: { "content-type": "application/json", origin: BASE_URL },
      body: JSON.stringify({ email, password, name: "Bearer Test" }),
    });
    expect(response.status).toBe(200);

    const rows = await database.sql<{ id: string }[]>`
      select id from "user" where email = ${email}
    `;
    expect(rows).toHaveLength(1);
  });

  test("refuses a second account with the same email", async () => {
    const response = await call("/api/auth/sign-up/email", {
      method: "POST",
      headers: { "content-type": "application/json", origin: BASE_URL },
      body: JSON.stringify({ email, password, name: "Bearer Test" }),
    });
    expect(response.status).toBeGreaterThanOrEqual(400);
  });
});

describe("the Android bearer session", () => {
  let token: string;
  let sessionId: string;

  test("sign-in returns the token in set-auth-token", async () => {
    const response = await call("/api/auth/sign-in/email", {
      method: "POST",
      headers: { "content-type": "application/json", origin: BASE_URL },
      body: JSON.stringify({ email, password }),
    });
    expect(response.status).toBe(200);

    const header = response.headers.get("set-auth-token");
    expect(header).toBeTruthy();
    token = header as string;
  });

  test("the token authenticates a request", async () => {
    const response = await call("/api/auth/get-session", {
      headers: { authorization: `Bearer ${token}`, origin: BASE_URL },
    });
    expect(response.status).toBe(200);
    const body = (await response.json()) as { session: { id: string }; user: { email: string } };
    expect(body.user.email).toBe(email);
    sessionId = body.session.id;
  });

  test("the session is one row, so it is one revocable device", async () => {
    // Sign-up already opened a session of its own, which is the point: each
    // sign-in is a separate row and revoking one leaves the others alone.
    const mine = (await listSessions(database)).filter((item) => item.userEmail === email);
    expect(mine.length).toBeGreaterThanOrEqual(2);
    expect(mine.map((item) => item.id)).toContain(sessionId);
  });

  test("revoking it makes the next call fail, and reveals nothing", async () => {
    expect(await revokeSession(database, sessionId)).toBe(true);

    const response = await call("/api/auth/get-session", {
      headers: { authorization: `Bearer ${token}`, origin: BASE_URL },
    });
    // Better Auth answers an unknown token as "no session" rather than
    // explaining that one was revoked: section 15.3, no data revealed.
    const body = await response.text();
    expect(body === "null" || body === "" || response.status === 401).toBe(true);
    expect(body).not.toContain(email);
  });

  test("a second device gets its own session and is unaffected", async () => {
    const before = (await listSessions(database)).filter((item) => item.userEmail === email).length;
    const first = await call("/api/auth/sign-in/email", {
      method: "POST",
      headers: { "content-type": "application/json", origin: BASE_URL },
      body: JSON.stringify({ email, password }),
    });
    const second = await call("/api/auth/sign-in/email", {
      method: "POST",
      headers: { "content-type": "application/json", origin: BASE_URL },
      body: JSON.stringify({ email, password }),
    });
    const firstToken = first.headers.get("set-auth-token") as string;
    const secondToken = second.headers.get("set-auth-token") as string;
    expect(firstToken).not.toBe(secondToken);

    const sessions = (await listSessions(database)).filter((item) => item.userEmail === email);
    expect(sessions).toHaveLength(before + 2);

    const target = sessions[0] as { id: string };
    await revokeSession(database, target.id);
    const remaining = (await listSessions(database)).filter((item) => item.userEmail === email);
    expect(remaining).toHaveLength(before + 1);
    expect(remaining.map((item) => item.id)).not.toContain(target.id);
  });
});

describe("the Web cookie", () => {
  test("is HttpOnly, SameSite and, off loopback, Secure", async () => {
    const response = await call("/api/auth/sign-in/email", {
      method: "POST",
      headers: { "content-type": "application/json", origin: BASE_URL },
      body: JSON.stringify({ email, password }),
    });
    const cookie = response.headers.get("set-cookie") ?? "";
    expect(cookie).toContain("HttpOnly");
    expect(cookie.toLowerCase()).toContain("samesite");

    // The loopback config under test deliberately says Secure=false; a config
    // built for any other origin must not be able to.
    const secured = createAuth({
      config: { ...CONFIG, baseUrl: "https://mue.example", secureCookies: true },
      database,
    });
    const overHttps = await secured.auth.handler(
      new Request("https://mue.example/api/auth/sign-in/email", {
        method: "POST",
        headers: { "content-type": "application/json", origin: "https://mue.example" },
        body: JSON.stringify({ email, password }),
      }),
    );
    expect(overHttps.headers.get("set-cookie") ?? "").toContain("Secure");
  });
});

describe("the MCP authorization server", () => {
  test("advertises PKCE and the section 15.2 scopes", async () => {
    // At the origin root, because `oauthIssuer` made the issuer the origin. It
    // used to be `/api/auth/.well-known/oauth-authorization-server`, which is
    // where OpenID Connect Discovery puts it for an issuer carrying a path --
    // correct, and looked for by nobody.
    const response = await call("/.well-known/oauth-authorization-server");
    expect(response.status).toBe(200);
    const metadata = (await response.json()) as {
      code_challenge_methods_supported: string[];
      scopes_supported: string[];
    };
    expect(metadata.code_challenge_methods_supported).toContain("S256");
    for (const scope of MUE_SCOPES) expect(metadata.scopes_supported).toContain(scope);
  });

  /**
   * RFC 8414 section 3.3: a client derives the metadata URL from the issuer and
   * then checks that the document names that same issuer. Both documents have to
   * pass that check from the origin, which is the whole reason the issuer moved
   * rather than the documents being copied to a second location.
   */
  test("names the origin as its issuer, at both discovery paths", async () => {
    for (const path of [
      "/.well-known/oauth-authorization-server",
      "/.well-known/openid-configuration",
    ]) {
      const response = await call(path);
      const metadata = (await response.json()) as { issuer: string };
      expect({ path, status: response.status, issuer: metadata.issuer }).toEqual({
        path,
        status: 200,
        issuer: BASE_URL,
      });
    }
  });

  /**
   * The endpoints stay under Better Auth's base path. An issuer is a name, not a
   * directory, and a client reads the endpoint out of the document rather than
   * assuming it sits beside the issuer.
   */
  test("keeps its endpoints under the auth base path", async () => {
    const metadata = (await (await call("/.well-known/oauth-authorization-server")).json()) as {
      authorization_endpoint: string;
      token_endpoint: string;
      registration_endpoint?: string;
    };
    expect(metadata.authorization_endpoint).toBe(`${BASE_URL}/api/auth/oauth2/authorize`);
    expect(metadata.token_endpoint).toBe(`${BASE_URL}/api/auth/oauth2/token`);
    // Present at all only because dynamic registration is enabled; a client with
    // no pre-configured `client_id` reads this field or gives up.
    expect(metadata.registration_endpoint).toBe(`${BASE_URL}/api/auth/oauth2/register`);
  });

  test("publishes the protected resource metadata for /mcp", async () => {
    // RFC 9728 puts this at the site root, not under the auth base path: the
    // MCP plugin serves it from an onRequest hook rather than an endpoint.
    const response = await call("/.well-known/oauth-protected-resource");
    expect(response.status).toBe(200);
    const metadata = (await response.json()) as { resource: string };
    expect(metadata.resource).toBe(`${BASE_URL}/mcp`);
  });
});
