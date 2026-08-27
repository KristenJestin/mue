import { type AuthConfig, type AuthHandle, createAuth } from "@mue/auth";
import { type DatabaseHandle, createTestDatabase, migrate } from "@mue/db";
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Hono } from "hono";
import { createApiApp } from "./app";
import type { AuthedEnv } from "./auth-routes";
import { createSyncEventRoutes } from "./sync-events";

/**
 * The live channel, proved on the two things it is: a route behind the same
 * guard as every other `/api/v1` route, and a stream that says *pull* and
 * nothing else.
 *
 * The intervals are milliseconds here rather than seconds. What is being proved
 * is that a change reaches the wire and that an idle stream keeps itself alive —
 * neither of which is a statement about the production constants, which are
 * documented in `sync-events.ts` and asserted nowhere because asserting a
 * literal against itself proves nothing.
 */

const BASE_URL = "http://localhost:3000";
const EMAIL = "sync-events-test@mue.test";
const PASSWORD = "correct-horse-battery-staple";

const config: AuthConfig = {
  // The shared development secret. See `app.test.ts`: the JWT plugin's signing
  // key lives in `mue_auth.jwks` and a second secret cannot decrypt it.
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
let userId: string;

/** The event routes with no auth in front, so the intervals can be made small. */
let fast: Hono<AuthedEnv>;

function measurement(date: string, weightCg: number): unknown {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision: null,
    origin: { type: "android", id: "device-sync-events-test" },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "measurement",
    aggregateId: date,
    op: "upsert",
    payloadSchemaVersion: 1,
    payload: { date, weightCg },
  };
}

async function push(date: string, weightCg: number): Promise<void> {
  const response = await app.fetch(
    new Request(`${BASE_URL}/api/v1/sync/push`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${bearer}` },
      body: JSON.stringify({ mutations: [measurement(date, weightCg)] }),
    }),
  );
  expect(response.status).toBe(200);
}

/**
 * Reads the stream until `predicate` is satisfied by the text read so far, and
 * returns that text. It always aborts the request, whether it matched or timed
 * out: a stream left open holds a poll loop and a database connection for the
 * rest of the suite.
 */
async function readUntil(
  response: Response,
  controller: AbortController,
  predicate: (text: string) => boolean,
  timeoutMs = 5_000,
): Promise<string> {
  const body = response.body;
  if (body === null) throw new Error("the event stream had no body");
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const deadline = Date.now() + timeoutMs;
  let text = "";

  try {
    while (!predicate(text)) {
      if (Date.now() > deadline) throw new Error(`timed out waiting; read so far: ${text}`);
      const chunk = await Promise.race([
        reader.read(),
        new Promise<{ done: true; value: undefined }>((resolve) =>
          setTimeout(() => resolve({ done: true, value: undefined }), deadline - Date.now()),
        ),
      ]);
      if (chunk.done) break;
      text += decoder.decode(chunk.value, { stream: true });
    }
  } finally {
    reader.cancel().catch(() => {});
    controller.abort();
  }

  if (!predicate(text)) throw new Error(`the stream ended without matching; read: ${text}`);
  return text;
}

function openFastStream(): { response: Promise<Response>; controller: AbortController } {
  const controller = new AbortController();
  const response = fast.fetch(
    new Request(`${BASE_URL}/api/v1/sync/events`, { signal: controller.signal }),
  );
  return { response: Promise.resolve(response), controller };
}

beforeAll(async () => {
  database = createTestDatabase();
  await migrate(database);
  await database.sql`delete from mue_auth."user" where "email" = ${EMAIL}`;
  // See `app.test.ts`: a signing key minted under another secret cannot be
  // decrypted, and every authenticated route answers 401 without saying so.
  await database.sql`delete from mue_auth.jwks`;

  authHandle = createAuth({ config, database });
  app = createApiApp({ auth: authHandle.auth, database }) as unknown as Hono;

  const signUp = await app.fetch(
    new Request(`${BASE_URL}/api/auth/sign-up/email`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: EMAIL, password: PASSWORD, name: "Live channel test" }),
    }),
  );
  expect(signUp.status).toBe(200);

  const signIn = await app.fetch(
    new Request(`${BASE_URL}/api/auth/sign-in/email`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
    }),
  );
  expect(signIn.status).toBe(200);
  const token = signIn.headers.get("set-auth-token");
  if (token === null) throw new Error("sign-in returned no set-auth-token header");
  bearer = token;

  // Read rather than asked for through `/api/auth/get-session`: that endpoint
  // signs a JWT, and the development cluster's `mue_auth.jwks` row is encrypted
  // with the deployment secret rather than this suite's. The identifier is only
  // needed to stand in for the guard below.
  const rows = await database.sql<{ id: string }[]>`
    select "id" from mue_auth."user" where "email" = ${EMAIL}
  `;
  const row = rows[0];
  if (row === undefined) throw new Error(`sign-up left no user row for ${EMAIL}`);
  userId = row.id;

  fast = new Hono<AuthedEnv>();
  fast.use("/api/v1/*", async (c, next) => {
    c.set("userId", userId);
    c.set("sessionId", "sync-events-test");
    await next();
  });
  fast.route(
    "/api/v1/sync",
    createSyncEventRoutes({
      database,
      pollIntervalMs: 25,
      heartbeatIntervalMs: 60,
      maxStreamMs: 60_000,
    }),
  );
});

afterAll(async () => {
  await database.sql`delete from mue_auth."user" where "email" = ${EMAIL}`;
  await authHandle.close();
  await database.close();
});

describe("GET /api/v1/sync/events", () => {
  test("refuses an anonymous caller, like every other /api/v1 route", async () => {
    const response = await app.fetch(new Request(`${BASE_URL}/api/v1/sync/events`));
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toContain("Bearer");
    expect(await response.json()).toMatchObject({
      error: { code: "auth.unauthenticated", retryable: false },
    });
  });

  test("opens an event stream for the paired bearer and greets it", async () => {
    const controller = new AbortController();
    const response = await app.fetch(
      new Request(`${BASE_URL}/api/v1/sync/events`, {
        headers: { authorization: `Bearer ${bearer}` },
        signal: controller.signal,
      }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/event-stream");
    // A proxy that buffers or transforms this response turns a live channel
    // into a batch delivered at close.
    expect(response.headers.get("cache-control")).toContain("no-transform");
    expect(response.headers.get("x-accel-buffering")).toBe("no");

    const text = await readUntil(response, controller, (read) => read.includes("event: hello"));
    expect(text).toContain("event: hello");
  });

  test("announces a change written through the API while the stream is open", async () => {
    const { response, controller } = openFastStream();
    const opened = await response;
    expect(opened.status).toBe(200);

    // Pushed after the stream is open, so what is observed is the announcement
    // and not a baseline the stream happened to be holding.
    const reading = readUntil(opened, controller, (read) => read.includes("event: change"));
    await push("2029-03-01", 7420);

    expect(await reading).toContain("event: change");
  });

  test("carries no sequence, no cursor and no payload — only the word `pull`", async () => {
    const { response, controller } = openFastStream();
    const opened = await response;

    const reading = readUntil(opened, controller, (read) => read.includes("event: change"));
    await push("2029-03-02", 7430);
    const text = await reading;

    // Section 12.3: the sequence is the server's and the client cursor is
    // opaque. Every `data:` line on this channel is the empty object, so there
    // is nothing here a client could ever mistake for a position.
    const data = text
      .split("\n")
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice("data:".length).trim());
    expect(data.length).toBeGreaterThan(0);
    expect(new Set(data)).toEqual(new Set(["{}"]));
  });

  test("keeps an idle stream alive with comments rather than events", async () => {
    const { response, controller } = openFastStream();
    const opened = await response;

    // Nothing is pushed. The only thing that may appear after the greeting is a
    // comment: a spurious `change` would make the phone pull for no reason, on
    // a loop, for as long as it is in the foreground.
    const text = await readUntil(opened, controller, (read) => read.includes(": heartbeat"));
    expect(text).toContain(": heartbeat");
    expect(text.slice(text.indexOf("event: hello"))).not.toContain("event: change");
  });

  test("closes itself so the credential goes back through the guard", async () => {
    const controller = new AbortController();
    const bounded = new Hono<AuthedEnv>();
    bounded.use("/api/v1/*", async (c, next) => {
      c.set("userId", userId);
      c.set("sessionId", "sync-events-test");
      await next();
    });
    bounded.route(
      "/api/v1/sync",
      createSyncEventRoutes({
        database,
        pollIntervalMs: 25,
        heartbeatIntervalMs: 10_000,
        // Already expired when the first tick checks it.
        maxStreamMs: 1,
      }),
    );

    const response = await bounded.fetch(
      new Request(`${BASE_URL}/api/v1/sync/events`, { signal: controller.signal }),
    );
    const body = response.body;
    if (body === null) throw new Error("the event stream had no body");

    // Read to completion: a stream that does not end here is one that would
    // outlive a revoked session (section 15.3).
    const text = await new Response(body).text();
    expect(text).toContain("event: hello");
    controller.abort();
  });
});
