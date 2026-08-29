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
  // plugin's signing key is encrypted with it and stored in `jwks`,
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

/**
 * A day comfortably inside `pastEventDay`, computed rather than written down.
 *
 * The fixtures here used to be literal dates in 2028. That was harmless while nothing judged a
 * date and is not any more: a weighing is a record of something that happened, so the push path
 * now refuses one dated years ahead. A relative day keeps the fixture correct for as long as the
 * suite exists, which a literal one could not be.
 */
function daysAgo(days: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() - days);
  return day.toISOString().slice(0, 10);
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
  await database.sql`delete from "user" where "email" = ${EMAIL}`;

  // The line `mcp/mcp.integration.test.ts` already carries, for the same reason
  // the comment on `config.secret` above gives: the signing key in
  // `jwks` is encrypted with whichever secret minted it, and the
  // development cluster is shared with every other process that boots Better
  // Auth -- including a platform server running from `.env`. A key this suite
  // cannot decrypt makes `getSession` throw, which arrives here as a 401 on
  // every authenticated route rather than as anything that names the cause.
  await database.sql`delete from jwks`;

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
  await database.sql`delete from "user" where "email" = ${EMAIL}`;
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
      body: JSON.stringify({ mutations: [measurement(daysAgo(30), 7000)] }),
    });
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toContain("Bearer");
    expect(await response.json()).toMatchObject({
      error: { code: "auth.unauthenticated", retryable: false },
    });
  });

  test("applies a batch presented with the sign-in bearer", async () => {
    const mutation = measurement(daysAgo(31), 7150);
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
      body: JSON.stringify({ mutations: [measurement(daysAgo(32), 42)] }),
    });
    // A default Ktor client throws on a non-2xx before the body is parsed, so a
    // 4xx here would hide the very error FR-SYNC-007 asks the client to show.
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      results: [{ status: "rejected", error: { code: "sync.invalid_payload" } }],
    });
  });

  test("refuses a weighing dated beyond the clock-skew tolerance, naming the field", async () => {
    // The half of F-02 that no MCP fix could have reached. `mue.upsert_weight_measurement` and
    // this endpoint are two authoring paths onto one journal, and a rule enforced only in the
    // tool would leave the phone's own push accepting exactly what the tool had just refused.
    // Both go through `submitMutation`, so both meet `pastEventDay` here.
    const response = await call("/api/v1/sync/push", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({ mutations: [measurement("2099-12-01", 7000)] }),
    });

    expect(response.status).toBe(200);
    const body = (await response.json()) as {
      results: { status: string; error: { code: string; field: string; message: string } }[];
    };
    expect(body.results[0]?.status).toBe("rejected");
    expect(body.results[0]?.error.code).toBe("sync.invalid_payload");
    // Named, so a client can put the message beside the field the person typed in.
    expect(body.results[0]?.error.field).toBe("payload.date");
    expect(body.results[0]?.error.message).toContain("payload.date");

    // Nothing reached the aggregate.
    const rows = await database.sql`
      select 1 from measurements where "date" = '2099-12-01'
    `;
    expect(rows).toHaveLength(0);
  });

  test("still accepts a day one ahead of UTC, which is a device abroad and not a bad date", async () => {
    // The tolerance earning its place: a phone at UTC+14 writes a calendar date the server
    // reading UTC has not reached, and that phone is right. Refusing it would be F-02 the
    // other way round -- a correct row, stranded in an outbox, refused for ever because
    // `push` replays a stored rejection verbatim.
    const tomorrow = new Date();
    tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
    const response = await call("/api/v1/sync/push", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({
        mutations: [measurement(tomorrow.toISOString().slice(0, 10), 7200)],
      }),
    });
    expect(response.status).toBe(200);
    const body = (await response.json()) as { results: { status: string }[] };
    expect(body.results[0]?.status).toBe("applied");
  });

  test("a meal proposal is not judged by the push path, so an offline phone keeps its plan", async () => {
    // `planningWindow` is deliberately absent here. A proposal journalled for tomorrow on a
    // phone that then spends three days offline arrives with a day that is now behind, and
    // refusing it would strand a row the phone has already stored -- permanently, since a
    // rejection is recorded under its `mutationId` and replayed. The window is checked where a
    // proposal is *made*, which is `mue.plan_meal`.
    const plannedOn = daysAgo(3);
    const response = await call("/api/v1/sync/push", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({
        mutations: [
          {
            mutationId: Bun.randomUUIDv7(),
            baseRevision: null,
            origin: { type: "android", id: "device-api-test" },
            clientOccurredAt: new Date().toISOString(),
            aggregateType: "mealPlanEntry",
            aggregateId: `${plannedOn}:dinner`,
            op: "upsert",
            payloadSchemaVersion: 1,
            payload: {
              plannedOn,
              slot: "dinner",
              recipeId: crypto.randomUUID(),
              plannedServingsThousandths: 1000,
            },
          },
        ],
      }),
    });

    expect(response.status).toBe(200);
    const body = (await response.json()) as { results: { status: string }[] };
    expect(body.results[0]?.status).toBe("applied");
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
        supportedSchemaVersions: { measurement: [1], mealPlanEntry: [1] },
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
        supportedSchemaVersions: { measurement: [1], mealPlanEntry: [1] },
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
