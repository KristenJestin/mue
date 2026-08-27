import { type AuthConfig, type AuthHandle, createAuth } from "@mue/auth";
import { type DatabaseHandle, createTestDatabase, migrate } from "@mue/db";
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import type { Hono } from "hono";
import { createApiApp } from "./app";

/**
 * The router as a client meets it: sign in through the mounted Better Auth
 * routes, then synchronise with the token that sign-in returned. Nothing here
 * reaches into a session table -- if the mount is wrong, the push is 401.
 */

const BASE_URL = "http://localhost:3000";
const EMAIL = "api-route-test@mue.test";
const PASSWORD = "correct-horse-battery-staple";

const config: AuthConfig = {
  // The same secret `packages/auth`'s own tests use, and it has to be: the JWT
  // plugin's signing key is encrypted with it and stored in `mue_auth.jwks`,
  // which both suites share on the one development cluster. A second secret
  // makes whichever suite runs later fail to decrypt a key it did not mint.
  secret: "test-secret-that-is-long-enough-32+",
  baseUrl: BASE_URL,
  trustedOrigins: [BASE_URL],
  mcpResource: `${BASE_URL}/mcp`,
  loginPage: "/sign-in",
  consentPage: "/consent",
  // Loopback development origin: a Secure cookie would never be stored over
  // plain http, and every other environment fails `readAuthConfig` first.
  secureCookies: false,
};

let database: DatabaseHandle;
let authHandle: AuthHandle;
let app: Hono;
let bearer: string;

async function call(path: string, init: RequestInit = {}): Promise<Response> {
  return app.fetch(new Request(`${BASE_URL}${path}`, init));
}

function measurement(date: string, weightCg: number): unknown {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision: null,
    origin: { type: "android", id: "device-api-test" },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "measurement",
    aggregateId: date,
    op: "upsert",
    payloadSchemaVersion: 1,
    payload: { date, weightCg },
  };
}

beforeAll(async () => {
  database = createTestDatabase();
  await migrate(database);
  await database.sql`delete from mue_auth."user" where "email" = ${EMAIL}`;

  // The line `mcp/mcp.integration.test.ts` already carries, for the same reason
  // the comment on `config.secret` above gives: the signing key in
  // `mue_auth.jwks` is encrypted with whichever secret minted it, and the
  // development cluster is shared with every other process that boots Better
  // Auth -- including a platform server running from `.env`. A key this suite
  // cannot decrypt makes `getSession` throw, which arrives here as a 401 on
  // every authenticated route rather than as anything that names the cause.
  await database.sql`delete from mue_auth.jwks`;

  authHandle = createAuth({ config, database });
  app = createApiApp({ auth: authHandle.auth, database }) as unknown as Hono;

  const signUp = await call("/api/auth/sign-up/email", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD, name: "Route test" }),
  });
  expect(signUp.status).toBe(200);

  const signIn = await call("/api/auth/sign-in/email", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
  });
  expect(signIn.status).toBe(200);

  // The `bearer` plugin returns the session token in this header, and the
  // Android client presents it as `Authorization: Bearer` (section 15.1).
  const token = signIn.headers.get("set-auth-token");
  if (token === null) throw new Error("sign-in returned no set-auth-token header");
  bearer = token;
});

afterAll(async () => {
  await database.sql`delete from mue_auth."user" where "email" = ${EMAIL}`;
  await authHandle.close();
  await database.close();
});

describe("the mounted auth routes", () => {
  test("answer Better Auth's own endpoints", async () => {
    const session = await call("/api/auth/get-session", {
      headers: { authorization: `Bearer ${bearer}` },
    });
    expect(session.status).toBe(200);
    expect(await session.json()).toMatchObject({ user: { email: EMAIL } });
  });
});

describe("POST /api/v1/sync/push", () => {
  test("refuses an anonymous caller in the one wire error shape", async () => {
    const response = await call("/api/v1/sync/push", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ mutations: [measurement("2028-01-01", 7000)] }),
    });
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toContain("Bearer");
    expect(await response.json()).toMatchObject({
      error: { code: "auth.unauthenticated", retryable: false },
    });
  });

  test("applies a batch presented with the sign-in bearer", async () => {
    const mutation = measurement("2028-01-02", 7150);
    const send = () =>
      call("/api/v1/sync/push", {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
        body: JSON.stringify({ mutations: [mutation] }),
      });

    const first = await send();
    expect(first.status).toBe(200);
    const firstBody = (await first.json()) as { results: { status: string; revision: string }[] };
    expect(firstBody.results[0]?.status).toBe("applied");

    // FR-SYNC-006 over the wire, which is where a lost response actually happens.
    const second = await send();
    expect(second.status).toBe(200);
    const secondBody = (await second.json()) as { results: { status: string; revision: string }[] };
    expect(secondBody.results[0]?.status).toBe("duplicate");
    expect(secondBody.results[0]?.revision).toBe(String(firstBody.results[0]?.revision));
  });

  test("answers 200 with a rejected result, never a non-2xx", async () => {
    const response = await call("/api/v1/sync/push", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({ mutations: [measurement("2028-01-03", 42)] }),
    });
    // A default Ktor client throws on a non-2xx before the body is parsed, so a
    // 4xx here would hide the very error FR-SYNC-007 asks the client to show.
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      results: [{ status: "rejected", error: { code: "sync.invalid_payload" } }],
    });
  });

  test("rejects a malformed request body with the error envelope", async () => {
    const response = await call("/api/v1/sync/push", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: "{not json",
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ error: { code: "sync.invalid_payload" } });
  });
});

describe("POST /api/v1/sync/pull", () => {
  test("returns the pushed changes and a cursor that resumes", async () => {
    const first = await call("/api/v1/sync/pull", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({
        cursor: null,
        limit: 1,
        supportedSchemaVersions: { measurement: [1] },
      }),
    });
    expect(first.status).toBe(200);
    const page = (await first.json()) as {
      status: string;
      changes: { aggregateId: string }[];
      nextCursor: string;
      hasMore: boolean;
      lastAndroidSyncAt: string | null;
    };
    expect(page.status).toBe("ok");
    expect(page.changes).toHaveLength(1);
    // FR-SYNC-008: every pull carries it, so no reader infers a freshness
    // guarantee the server cannot give.
    expect(page.lastAndroidSyncAt).not.toBeNull();

    const resumed = await call("/api/v1/sync/pull", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({
        cursor: page.nextCursor,
        supportedSchemaVersions: { measurement: [1] },
      }),
    });
    const rest = (await resumed.json()) as { changes: { aggregateId: string }[] };
    expect(rest.changes.map((change) => change.aggregateId)).not.toContain(
      page.changes[0]?.aggregateId,
    );
  });

  test("refuses an unreadable cursor with 400 rather than restarting at zero", async () => {
    const response = await call("/api/v1/sync/pull", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({
        cursor: "bm90LWEtY3Vyc29y",
        supportedSchemaVersions: { measurement: [1] },
      }),
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ error: { code: "sync.invalid_cursor" } });
  });

  test("refuses an anonymous caller", async () => {
    const response = await call("/api/v1/sync/pull", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ cursor: null, supportedSchemaVersions: { measurement: [1] } }),
    });
    expect(response.status).toBe(401);
  });
});
