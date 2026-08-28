import { type AuthConfig, type AuthHandle, createAuth } from "@mue/auth";
import { type DatabaseHandle, createTestDatabase, migrate, schema } from "@mue/db";
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { eq } from "drizzle-orm";
import type { Hono } from "hono";
import { createApiApp } from "./app";

/**
 * The agent listing and revocation of section 15.3, as the Settings -> Agents page
 * meets them: over HTTP, behind the same session guard as every other `/api/v1/*`
 * route, and answering the same `listAgents`/`revokeAgent` the documented admin
 * command calls.
 *
 * The rows an authorised agent leaves behind are written directly, exactly as
 * `packages/auth/src/administration.test.ts` writes them: driving a whole OAuth
 * authorization belongs to the MCP suite, and what is under test here is the route.
 */

const BASE_URL = "http://localhost:3000";
const EMAIL = "agents-route-test@mue.test";
const PASSWORD = "correct-horse-battery-staple";

/** A CIMD client id is an https URL, so the path segment carries `%2F`. */
const CLIENT = "https://agent.example/agents-route-test";
const CLIENT_ROW = "client-row-agents-route-test";

const config: AuthConfig = {
  // The same secret every other suite uses. The JWT plugin's signing key is
  // encrypted with it and stored in the shared `mue_auth.jwks`; a second secret
  // makes whichever suite runs later fail to decrypt a key it did not mint.
  secret: "test-secret-that-is-long-enough-32+",
  baseUrl: BASE_URL,
  trustedOrigins: [BASE_URL],
  mcpResource: `${BASE_URL}/mcp`,
  loginPage: "/sign-in",
  consentPage: "/consent",
  secureCookies: false,
};

let database: DatabaseHandle;
let authHandle: AuthHandle;
let app: Hono;
let bearer: string;

async function call(path: string, init: RequestInit = {}): Promise<Response> {
  return app.fetch(new Request(`${BASE_URL}${path}`, init));
}

function asOwner(path: string, init: RequestInit = {}): Promise<Response> {
  return call(path, {
    ...init,
    headers: { ...init.headers, authorization: `Bearer ${bearer}` },
  });
}

interface ListedAgent {
  readonly clientId: string;
  readonly name: string | null;
  readonly scopes: readonly string[];
  readonly grantedScopes: readonly string[];
  readonly revoked: boolean;
  readonly discovered: boolean;
  readonly registeredAt: string | null;
  readonly lastUsedAt: string | null;
}

async function listed(): Promise<ListedAgent[]> {
  const response = await asOwner("/api/v1/agents");
  expect(response.status).toBe(200);
  const body = (await response.json()) as { agents: ListedAgent[] };
  return body.agents;
}

async function removeRows(): Promise<void> {
  await database.db
    .delete(schema.oauthAccessToken)
    .where(eq(schema.oauthAccessToken.clientId, CLIENT));
  await database.db
    .delete(schema.oauthRefreshToken)
    .where(eq(schema.oauthRefreshToken.clientId, CLIENT));
  await database.db.delete(schema.oauthConsent).where(eq(schema.oauthConsent.clientId, CLIENT));
  await database.db.delete(schema.oauthClient).where(eq(schema.oauthClient.clientId, CLIENT));
}

beforeAll(async () => {
  // `createTestDatabase` rewrites the database name to `mue_test`. Without it this
  // suite would sign accounts up in `mue_dev` -- the cluster the owner's phone pairs
  // with -- and delete the signing key it authenticates against.
  database = createTestDatabase();
  await migrate(database);
  await database.sql`delete from mue_auth."user" where "email" = ${EMAIL}`;
  await removeRows();
  // A key encrypted under another suite's secret cannot be decrypted, and the symptom
  // is a bare 401 on every authenticated route.
  await database.sql`delete from mue_auth.jwks`;

  authHandle = createAuth({ config, database });
  app = createApiApp({ auth: authHandle.auth, database }) as unknown as Hono;

  const signUp = await call("/api/auth/sign-up/email", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD, name: "Agents route test" }),
  });
  expect(signUp.status).toBe(200);
  const token = signUp.headers.get("set-auth-token");
  if (token === null) throw new Error("sign-up returned no set-auth-token header");
  bearer = token;

  const [owner] = await database.db
    .select({ id: schema.user.id })
    .from(schema.user)
    .where(eq(schema.user.email, EMAIL));
  if (owner === undefined) throw new Error("the owner was not created");

  const registeredAt = new Date("2026-08-01T09:30:00.000Z");
  const usedAt = new Date("2026-08-20T18:05:00.000Z");
  const expiresAt = new Date(Date.now() + 3_600_000);

  await database.db.insert(schema.oauthClient).values({
    id: CLIENT_ROW,
    clientId: CLIENT,
    name: "Example agent",
    scopes: ["weight:read", "activity:write"],
    redirectUris: ["http://127.0.0.1:33418/callback/abc"],
    userId: owner.id,
    createdAt: registeredAt,
    updatedAt: registeredAt,
  });

  await database.db.insert(schema.oauthRefreshToken).values({
    id: `${CLIENT_ROW}-refresh`,
    token: `${CLIENT_ROW}-refresh-token`,
    clientId: CLIENT,
    userId: owner.id,
    expiresAt,
    createdAt: usedAt,
    scopes: ["weight:read"],
  });

  await database.db.insert(schema.oauthAccessToken).values({
    id: `${CLIENT_ROW}-access`,
    token: `${CLIENT_ROW}-access-token`,
    clientId: CLIENT,
    userId: owner.id,
    refreshId: `${CLIENT_ROW}-refresh`,
    expiresAt,
    createdAt: usedAt,
    scopes: ["weight:read"],
  });

  await database.db.insert(schema.oauthConsent).values({
    id: `${CLIENT_ROW}-consent`,
    clientId: CLIENT,
    userId: owner.id,
    scopes: ["weight:read"],
    createdAt: usedAt,
    updatedAt: usedAt,
  });
});

afterAll(async () => {
  await removeRows();
  await database.sql`delete from mue_auth."user" where "email" = ${EMAIL}`;
  await authHandle.close();
  await database.close();
});

describe("GET /api/v1/agents", () => {
  test("refuses a caller without a session", async () => {
    const response = await call("/api/v1/agents");
    expect(response.status).toBe(401);
    // The same wire error every other guarded route answers with.
    expect(await response.json()).toMatchObject({ error: { code: "auth.unauthenticated" } });
  });

  test("names the agent, when it was registered, its scopes and its last use", async () => {
    const mine = (await listed()).find((agent) => agent.clientId === CLIENT);
    expect(mine).toBeDefined();
    expect(mine?.name).toBe("Example agent");
    expect(mine?.revoked).toBe(false);
    expect(mine?.discovered).toBe(false);
    expect(mine?.registeredAt).toBe("2026-08-01T09:30:00.000Z");
    expect(mine?.lastUsedAt).toBe("2026-08-20T18:05:00.000Z");
  });

  test("separates what the agent may ask for from what it was actually granted", async () => {
    // Better Auth stores the whole allowed scope set on a dynamic registration, so
    // `scopes` alone would show `data:delete` next to an agent the owner has granted
    // nothing at all. What it holds is its consent.
    const mine = (await listed()).find((agent) => agent.clientId === CLIENT);
    expect(mine?.scopes).toEqual(["weight:read", "activity:write"]);
    expect(mine?.grantedScopes).toEqual(["weight:read"]);
  });
});

describe("DELETE /api/v1/agents/:clientId", () => {
  test("refuses a caller without a session, and revokes nothing", async () => {
    const response = await call(`/api/v1/agents/${encodeURIComponent(CLIENT)}`, {
      method: "DELETE",
    });
    expect(response.status).toBe(401);

    const mine = (await listed()).find((agent) => agent.clientId === CLIENT);
    expect(mine?.revoked).toBe(false);
  });

  test("never answers for the pairing window, whatever the mount order", async () => {
    // `createClientRegistrationApp` owns `DELETE /api/v1/agents/pairing` and is
    // mounted ahead of this router in `apps/platform/src/runtime.ts`. Closing the
    // window must never be able to arrive here as "revoke the agent called pairing".
    const response = await asOwner("/api/v1/agents/pairing", { method: "DELETE" });
    expect(response.status).toBe(404);
    expect(await response.json()).toMatchObject({ error: { code: "http.not_found" } });
  });

  test("reports an agent it does not know without inventing one", async () => {
    const response = await asOwner(
      `/api/v1/agents/${encodeURIComponent("https://nobody.example/id")}`,
      { method: "DELETE" },
    );
    expect(response.status).toBe(404);
    expect(await response.json()).toMatchObject({ error: { code: "agent.not_found" } });
  });

  test("disables the client, stamps its live tokens and drops its consent", async () => {
    const response = await asOwner(`/api/v1/agents/${encodeURIComponent(CLIENT)}`, {
      method: "DELETE",
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      clientId: CLIENT,
      accessTokensRevoked: 1,
      refreshTokensRevoked: 1,
      consentsRemoved: 1,
    });

    const [access] = await database.db
      .select({ revoked: schema.oauthAccessToken.revoked })
      .from(schema.oauthAccessToken)
      .where(eq(schema.oauthAccessToken.id, `${CLIENT_ROW}-access`));
    expect(access?.revoked).not.toBeNull();

    // Section 14.7: the client row stays so the audit trail stays readable, and the
    // page has to be able to show that it was revoked.
    const mine = (await listed()).find((agent) => agent.clientId === CLIENT);
    expect(mine?.revoked).toBe(true);
    // The consent is gone, so the agent holds nothing -- which is the sentence the
    // page has to be able to write next to a revoked identity.
    expect(mine?.grantedScopes).toEqual([]);
  });

  test("revoking a second time is harmless", async () => {
    const response = await asOwner(`/api/v1/agents/${encodeURIComponent(CLIENT)}`, {
      method: "DELETE",
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({ accessTokensRevoked: 0 });
  });
});
