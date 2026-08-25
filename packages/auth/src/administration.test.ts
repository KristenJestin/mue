import { afterAll, beforeAll, expect, test } from "bun:test";
import { createTestDatabase, migrate, schema, seedUser, type DatabaseHandle } from "@mue/db";
import { eq } from "drizzle-orm";
import { listAgents, revokeAgent } from "./administration";
import { MUE_SCOPES } from "./scopes";

/**
 * The agent half of section 15.3. There is no MCP client to drive the full
 * OAuth flow from here -- that lives with the MCP endpoint -- so the rows an
 * authorised agent leaves behind are written directly and then revoked through
 * the command the PRD requires.
 */

let database: DatabaseHandle;
const USER = "user-admin-test";
const CLIENT = "https://agent.example/id";

beforeAll(async () => {
  database = createTestDatabase();
  await migrate(database);
  await seedUser(database, USER);

  const now = new Date();
  const later = new Date(now.getTime() + 3_600_000);
  await database.db
    .insert(schema.oauthClient)
    .values({
      id: "client-row-1",
      clientId: CLIENT,
      clientDiscoveryId: "cimd",
      name: "Example agent",
      scopes: [...MUE_SCOPES],
      redirectUris: ["https://agent.example/callback"],
      userId: USER,
      createdAt: now,
      updatedAt: now,
    })
    .onConflictDoNothing();

  await database.db
    .insert(schema.oauthRefreshToken)
    .values({
      id: "refresh-1",
      token: "refresh-token-1",
      clientId: CLIENT,
      userId: USER,
      expiresAt: later,
      createdAt: now,
      scopes: ["weight:read"],
    })
    .onConflictDoNothing();

  await database.db
    .insert(schema.oauthAccessToken)
    .values({
      id: "access-1",
      token: "access-token-1",
      clientId: CLIENT,
      userId: USER,
      refreshId: "refresh-1",
      expiresAt: later,
      createdAt: now,
      scopes: ["weight:read"],
    })
    .onConflictDoNothing();

  await database.db
    .insert(schema.oauthConsent)
    .values({
      id: "consent-1",
      clientId: CLIENT,
      userId: USER,
      scopes: ["weight:read"],
      createdAt: now,
      updatedAt: now,
    })
    .onConflictDoNothing();
});

afterAll(async () => {
  await database.db
    .delete(schema.oauthAccessToken)
    .where(eq(schema.oauthAccessToken.id, "access-1"));
  await database.db
    .delete(schema.oauthRefreshToken)
    .where(eq(schema.oauthRefreshToken.id, "refresh-1"));
  await database.db.delete(schema.oauthClient).where(eq(schema.oauthClient.clientId, CLIENT));
  await database.close();
});

test("an agent is listed with its scopes and last use", async () => {
  const agents = await listAgents(database);
  const mine = agents.find((agent) => agent.clientId === CLIENT);
  expect(mine).toBeDefined();
  expect(mine?.disabled).toBe(false);
  expect(mine?.discovered).toBe(true);
  expect(mine?.scopes).toContain("weight:read");
  expect(mine?.lastUsedAt).not.toBeNull();
});

test("revoking it disables the client and stamps every live token", async () => {
  const result = await revokeAgent(database, CLIENT);
  expect(result.found).toBe(true);
  expect(result.accessTokensRevoked).toBe(1);
  expect(result.refreshTokensRevoked).toBe(1);
  expect(result.consentsRemoved).toBe(1);

  const [access] = await database.db
    .select({ revoked: schema.oauthAccessToken.revoked })
    .from(schema.oauthAccessToken)
    .where(eq(schema.oauthAccessToken.id, "access-1"));
  expect(access?.revoked).not.toBeNull();

  const listed = (await listAgents(database)).find((agent) => agent.clientId === CLIENT);
  expect(listed?.disabled).toBe(true);
});

test("revoking again is harmless, and an unknown agent is simply not found", async () => {
  const second = await revokeAgent(database, CLIENT);
  expect(second.found).toBe(true);
  expect(second.accessTokensRevoked).toBe(0);

  const missing = await revokeAgent(database, "https://nobody.example/id");
  expect(missing.found).toBe(false);
});
