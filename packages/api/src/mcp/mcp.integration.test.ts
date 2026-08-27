import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createAuth, MUE_SCOPES, OAUTH_SCOPES, revokeAgent, type AuthHandle } from "@mue/auth";
import { createTestDatabase, schema } from "@mue/db";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { OAuthClientProvider } from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type {
  OAuthClientInformation,
  OAuthClientMetadata,
  OAuthTokens,
} from "@modelcontextprotocol/sdk/shared/auth.js";
import { and, desc, eq } from "drizzle-orm";
import { Hono } from "hono";
import { createMcpApp, createOAuthDiscoveryApp, MUE_TOOLS } from "./index";
import { MUE_MCP_PROTOCOL_VERSION, PRD_REQUESTED_PROTOCOL_VERSION } from "./protocol";

/**
 * The MCP endpoint proven the only way it can be: a real MCP client, the real SDK
 * transport, the real Better Auth authorization server and a real PostgreSQL.
 *
 * A mock would prove nothing here. Every interesting failure in this slice lives in a
 * seam -- SEP-835 scope selection, the RFC 9728 challenge, PKCE, whether the audience
 * of a token matches the resource, whether `structuredContent` survives the client's
 * own validation -- and a mock is precisely the thing that has no seams.
 *
 * Requires the development PostgreSQL of `infra/compose.dev.yml` and the `.env` that
 * `bun --env-file=../../.env test` loads.
 */

/**
 * The SDK client transport is not assignable to the SDK client's own `Transport`
 * interface under `exactOptionalPropertyTypes`: the class declares `sessionId?: string`
 * and the interface requires `string`. That is the SDK disagreeing with itself, so the
 * cast is pinned to one place here rather than the repository relaxing a strict flag.
 */
function asTransport(transport: StreamableHTTPClientTransport): Parameters<Client["connect"]>[0] {
  return transport as unknown as Parameters<Client["connect"]>[0];
}

const REDIRECT_URI = "http://127.0.0.1:9876/callback";
const OWNER_PASSWORD = "correct horse battery staple";

/**
 * The budget for anything that drives a whole OAuth 2.1 authorization.
 *
 * A full flow is a failed connection, a discovery, a redirect, a consent post and a token
 * exchange, each of them a real HTTP round trip against a real PostgreSQL. Bun's five-second
 * default is enough when this file runs alone and is not when the rest of the package's suites
 * are competing for the same cluster -- and a hook that times out reports as "(unnamed)" with
 * no indication that nothing was wrong with the test at all.
 */
const OAUTH_TIMEOUT_MS = 30_000;

let handle: AuthHandle;
let server: ReturnType<typeof Bun.serve>;
let base = "";
let cookie = "";
let userId = "";

interface Agent {
  readonly clientId: string;
  readonly tokens: OAuthTokens;
}

async function json(response: Response): Promise<Record<string, unknown>> {
  return (await response.json()) as Record<string, unknown>;
}

/** Better Auth refuses a cross-origin write, so every call presents the server origin. */
function ownerHeaders(): Record<string, string> {
  return { "content-type": "application/json", origin: base, cookie };
}

async function createOAuthClient(name: string): Promise<string> {
  const response = await fetch(`${base}/api/auth/oauth2/create-client`, {
    method: "POST",
    headers: ownerHeaders(),
    body: JSON.stringify({
      client_name: name,
      redirect_uris: [REDIRECT_URI],
      // A public native client: no secret to leak, PKCE instead.
      token_endpoint_auth_method: "none",
      // Registered wide, granted narrow. Section 15.2 lets a personal configuration
      // trust an agent with everything; what it actually receives is decided on the
      // consent page, which is what `grantedScopes` below drives.
      scope: OAUTH_SCOPES.join(" "),
      application_type: "native",
    }),
  });
  expect(response.status).toBe(201);
  return (await json(response))["client_id"] as string;
}

/**
 * One complete OAuth 2.1 + PKCE authorization, driven through the SDK's own client.
 *
 * The SDK builds the authorization URL, generates and keeps the code verifier and
 * performs the token exchange; the test only does what a browser would -- follow the
 * redirect while signed in, and answer the consent page.
 */
async function authorize(clientId: string, grantedScopes: string): Promise<OAuthTokens> {
  let authorizationUrl: URL | undefined;
  let tokens: OAuthTokens | undefined;
  let verifier = "";

  const provider: OAuthClientProvider = {
    get redirectUrl() {
      return REDIRECT_URI;
    },
    get clientMetadata(): OAuthClientMetadata {
      return {
        client_name: "Mue integration client",
        redirect_uris: [REDIRECT_URI],
        grant_types: ["authorization_code", "refresh_token"],
        response_types: ["code"],
        token_endpoint_auth_method: "none",
      };
    },
    clientInformation(): OAuthClientInformation {
      return { client_id: clientId };
    },
    tokens() {
      return tokens;
    },
    saveTokens(next) {
      tokens = next;
    },
    redirectToAuthorization(url) {
      authorizationUrl = url;
    },
    saveCodeVerifier(next) {
      verifier = next;
    },
    codeVerifier() {
      return verifier;
    },
  };

  const transport = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), {
    authProvider: provider,
  });
  const client = new Client({ name: "mue-integration", version: "0.0.0" });

  // The first connection is expected to fail: it is what triggers discovery of the
  // protected-resource metadata and the authorization request.
  await expect(client.connect(asTransport(transport))).rejects.toThrow();
  expect(authorizationUrl).toBeDefined();

  const authorizeResponse = await fetch(authorizationUrl!.toString(), {
    headers: { cookie },
    redirect: "manual",
  });
  expect(authorizeResponse.status).toBe(302);

  const consentUrl = new URL(authorizeResponse.headers.get("location")!, base);
  // The consent page of `apps/platform/src/routes/oauth-consent.tsx`.
  expect(consentUrl.pathname).toBe("/oauth-consent");

  const consent = await fetch(`${base}/api/auth/oauth2/consent`, {
    method: "POST",
    headers: ownerHeaders(),
    body: JSON.stringify({
      accept: true,
      scope: grantedScopes,
      oauth_query: consentUrl.search.replace(/^\?/, ""),
    }),
  });
  expect(consent.status).toBe(200);

  const redirect = (await json(consent))["url"] as string;
  const code = new URL(redirect).searchParams.get("code");
  expect(code).not.toBeNull();

  await transport.finishAuth(code!);
  await transport.close();
  expect(tokens).toBeDefined();
  return tokens!;
}

async function connect(agent: Agent): Promise<Client> {
  const transport = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), {
    requestInit: { headers: { authorization: `Bearer ${agent.tokens.access_token}` } },
  });
  const client = new Client({ name: "mue-integration", version: "0.0.0" });
  await client.connect(asTransport(transport));
  return client;
}

async function newAgent(name: string, grantedScopes: string): Promise<Agent> {
  const clientId = await createOAuthClient(name);
  return { clientId, tokens: await authorize(clientId, grantedScopes) };
}

/**
 * Every scope this build's catalogue can actually be granted, derived the way the server
 * derives its own 401 challenge: the union of what the tools declare, plus `offline_access`.
 *
 * It is computed rather than written out, and that is not tidiness. A client picks its scopes
 * from the challenge, and Better Auth refuses to grant a scope *"not originally requested"* —
 * so a hand-written list that named a scope no tool declares would fail the authorization,
 * not the assertion, and the failure would look like a broken OAuth flow.
 *
 * `nutrition:read` is the one member of `MUE_SCOPES` that does not appear here, and its
 * absence is the read gap this build leaves open: PRD_FOOD 21.5's six food *read* tools are
 * not shipped, so nothing declares the scope and nothing can ask for it. When they land, this
 * constant picks them up on its own.
 */
const CATALOGUE_SCOPES = [
  "offline_access",
  ...[...new Set(MUE_TOOLS.flatMap((tool) => tool.scopes))].sort(),
].join(" ");

/**
 * The structured error of section 14.4, as an agent reads it off the wire.
 *
 * `field` is the load-bearing one and the reason this type is written out rather than
 * inlined: an error that names `payload.movement` is one an agent fixes in a turn, and
 * `"something went wrong"` is one it guesses at.
 */
interface ToolError {
  code: string;
  message: string;
  retryable: boolean;
  field?: string;
  aggregateType?: string;
  aggregateId?: string;
}

interface ToolEnvelope {
  status: string;
  data: unknown;
  error: ToolError | null;
}

/** Call a tool the way an agent does, and assert it succeeded. Returns `data`. */
async function callOk(
  client: Client,
  name: string,
  args: Record<string, unknown>,
): Promise<unknown> {
  const result = await client.callTool({ name, arguments: args });
  const envelope = result.structuredContent as ToolEnvelope | undefined;
  // Reported as one object so a failure names the tool *and* shows the error it returned,
  // rather than "expected ok, got error" with twenty-eight candidates.
  expect({ name, status: envelope?.status, error: envelope?.error ?? null }).toEqual({
    name,
    status: "ok",
    error: null,
  });
  return (envelope as ToolEnvelope).data;
}

/** Call a tool and assert it refused with a structured business error. */
async function callError(
  client: Client,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolError> {
  const result = await client.callTool({ name, arguments: args });
  const envelope = result.structuredContent as ToolEnvelope | undefined;
  expect({ name, isError: result.isError === true, status: envelope?.status }).toEqual({
    name,
    isError: true,
    status: "error",
  });
  return (envelope as ToolEnvelope).error as ToolError;
}

beforeAll(async () => {
  // The port is learned before the auth instance is built, because `BETTER_AUTH_URL`
  // is the OAuth issuer and every discovery document is derived from it.
  let app: Hono | undefined;
  server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: (request) => app!.fetch(request) });
  base = `http://127.0.0.1:${server.port}`;

  handle = createAuth({
    config: {
      secret: "integration-test-secret-at-least-32-characters",
      baseUrl: base,
      trustedOrigins: [base],
      mcpResource: `${base}/mcp`,
      loginPage: "/sign-in",
      consentPage: "/oauth-consent",
      secureCookies: false,
    },
    // Without this, `createAuth` falls back to `createDatabase()` — that is,
    // `DATABASE_URL`, which is the development cluster a phone pairs with. This
    // suite then deleted its signing key and signed up accounts in it, and the
    // owner's phone could not authenticate until the row was removed by hand:
    // a key encrypted under the secret above cannot be decrypted by the running
    // server, and the symptom is a bare 401 with nothing in any log.
    //
    // `createTestDatabase` rewrites the database name to `mue_test`, so the
    // redirect that protects `resetSchemas` protects this too. Any other suite
    // that builds its own `createAuth` needs the same argument.
    database: createTestDatabase(),
  });

  // A signing key encrypted under a different secret cannot be decrypted, and the
  // test database is shared with every other suite that boots Better Auth.
  await handle.database.sql`delete from mue_auth.jwks`;

  app = new Hono();
  app.route("/", createOAuthDiscoveryApp(handle));
  app.all("/api/auth/*", (c) => handle.auth.handler(c.req.raw));
  app.route("/", createMcpApp({ auth: handle }));

  const email = `owner+${Date.now()}@mue.test`;
  const signUp = await fetch(`${base}/api/auth/sign-up/email`, {
    method: "POST",
    headers: { "content-type": "application/json", origin: base },
    body: JSON.stringify({ email, password: OWNER_PASSWORD, name: "Owner" }),
  });
  expect(signUp.status).toBe(200);
  cookie = signUp.headers
    .getSetCookie()
    .map((value) => value.split(";")[0])
    .join("; ");

  const users = await handle.database.db
    .select({ id: schema.user.id })
    .from(schema.user)
    .where(eq(schema.user.email, email));
  userId = users[0]!.id;
}, OAUTH_TIMEOUT_MS);

afterAll(async () => {
  server.stop(true);
  await handle.close();
});

describe("discovery", () => {
  test("answers an unauthenticated call with the RFC 9728 challenge a client needs", async () => {
    const response = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} }),
    });

    expect(response.status).toBe(401);
    const challenge = response.headers.get("www-authenticate") ?? "";
    expect(challenge).toContain(
      `resource_metadata="${base}/.well-known/oauth-protected-resource/mcp"`,
    );
    // SEP-835: the challenge is the first place a client looks for the scopes to ask
    // for, and it is the only place `offline_access` appears -- without it there is no
    // refresh token and section 22.4's refresh case cannot be reached.
    expect(challenge).toContain("offline_access");
  });

  test("publishes protected-resource metadata pointing at the authorization server", async () => {
    const response = await fetch(`${base}/.well-known/oauth-protected-resource/mcp`);
    expect(response.status).toBe(200);

    const metadata = await json(response);
    expect(metadata["resource"]).toBe(`${base}/mcp`);
    expect(metadata["authorization_servers"]).toEqual([`${base}/api/auth`]);
  });

  test("publishes authorization-server metadata with PKCE and the Mue scopes", async () => {
    const response = await fetch(`${base}/.well-known/oauth-authorization-server/api/auth`);
    expect(response.status).toBe(200);

    const metadata = await json(response);
    expect(metadata["code_challenge_methods_supported"]).toContain("S256");
    expect(metadata["scopes_supported"]).toContain("activity:write");
    // `@better-auth/cimd` is composed into the same plugin set, and this flag is how a
    // client learns it may present a Client ID Metadata Document URL as its client_id.
    expect(metadata["client_id_metadata_document_supported"]).toBe(true);
  });

  test("refuses the historical SSE stream: section 8.3 implements it nowhere", async () => {
    const response = await fetch(`${base}/mcp`, {
      method: "GET",
      headers: { accept: "text/event-stream" },
    });
    expect(response.status).toBe(405);
  });
});

describe("a real MCP client", () => {
  let agent: Agent;
  let client: Client;

  beforeAll(async () => {
    agent = await newAgent("Full agent", "weight:read activity:write offline_access");
    client = await connect(agent);
  }, OAUTH_TIMEOUT_MS);

  afterAll(async () => {
    await client.close();
  });

  test("completes discovery, PKCE and consent, and receives a refresh token", () => {
    expect(agent.tokens.token_type).toBe("Bearer");
    expect(agent.tokens.scope).toContain("activity:write");
    expect(agent.tokens.refresh_token).toBeDefined();
  });

  test("negotiates 2025-11-25, which is not the revision the PRD asks for", async () => {
    const response = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json, text/event-stream",
        authorization: `Bearer ${agent.tokens.access_token}`,
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: MUE_MCP_PROTOCOL_VERSION,
          capabilities: {},
          clientInfo: { name: "raw", version: "0" },
        },
      }),
    });

    const body = (await json(response)) as { result: { protocolVersion: string } };
    expect(body.result.protocolVersion).toBe("2025-11-25");
    expect(body.result.protocolVersion).not.toBe(PRD_REQUESTED_PROTOCOL_VERSION);
  });

  test("lists the tools with the four section 14.1 annotations", async () => {
    const { tools } = await client.listTools();
    const names = tools.map((tool) => tool.name).sort();
    // This agent holds `weight:read` and `activity:write` and nothing else, so it sees the
    // three weight reads and the one activity write. The whole catalogue is asserted below,
    // against an agent that holds every scope.
    expect(names).toEqual([
      "mue.create_activity",
      "mue.create_custom_exercise",
      "mue.get_weight_measurement",
      "mue.get_weight_statistics",
      "mue.list_weight_measurements",
      "mue.update_activity",
      "mue.update_custom_exercise",
    ]);

    const create = tools.find((tool) => tool.name === "mue.create_activity")!;
    expect(create.annotations).toMatchObject({
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    });
    expect(create.outputSchema).toBeDefined();
  });

  test("creates a final activity session from section 14.4's own sentence", async () => {
    // "Hier, j'ai couru pendant 35 minutes a partir de 18 h."
    const result = await client.callTool({
      name: "mue.create_activity",
      arguments: {
        movement: "running",
        startedOn: "2026-08-24",
        durationMinutes: 35,
        startedAtTime: "18:00",
      },
    });

    const structured = result.structuredContent as {
      status: string;
      data: { activity: { id: string; revision: string }; created: boolean; mutationId: string };
    };
    expect(structured.status).toBe("ok");
    expect(structured.data.created).toBe(true);

    const rows = await handle.database.db
      .select()
      .from(schema.activitySessions)
      .where(
        and(
          eq(schema.activitySessions.userId, userId),
          eq(schema.activitySessions.id, structured.data.activity.id),
        ),
      );

    const row = rows[0];
    expect(row).toBeDefined();
    expect(row!.movement).toBe("running");
    expect(row!.startedOn).toBe("2026-08-24");
    expect(row!.startedAtTime).toBe("18:00");
    expect(row!.durationSeconds).toBe(35 * 60);
    // Final, not a draft: the row is in `activity_sessions` itself, it carries a
    // revision, and nothing on the phone has to confirm it.
    expect(row!.revision).toBe(1n);
    expect(row!.deletedAt).toBeNull();
    expect(row!.originType).toBe("agent");
    expect(row!.originId).toBe(agent.clientId);
    // Absent values stayed absent instead of being invented.
    expect(row!.perceivedEffort).toBeNull();
    expect(row!.notes).toBeNull();
    expect(row!.customMovementName).toBeNull();

    const audits = await handle.database.db
      .select()
      .from(schema.agentAudit)
      .where(eq(schema.agentAudit.mutationId, structured.data.mutationId));

    // Section 14.7's eight fields, all of them, and nothing resembling a prompt.
    const audit = audits[0];
    expect(audit).toBeDefined();
    expect(audit!.agentId).toBe(agent.clientId);
    expect(audit!.toolName).toBe("mue.create_activity");
    expect(audit!.occurredAt).toBeInstanceOf(Date);
    expect(audit!.aggregates).toEqual([
      { type: "activitySession", id: structured.data.activity.id },
    ]);
    expect(audit!.result).toBe("ok");
    expect(audit!.revision).toBe(1n);
    expect(audit!.error).toBeNull();
  });

  test("replaying an idempotency key creates exactly one session", async () => {
    const idempotencyKey = crypto.randomUUID();
    const args = {
      movement: "cycling",
      startedOn: "2026-08-23",
      durationMinutes: 50,
      idempotencyKey,
    };

    const first = await client.callTool({ name: "mue.create_activity", arguments: args });
    const second = await client.callTool({ name: "mue.create_activity", arguments: args });

    const one = first.structuredContent as { data: { activity: { id: string }; created: boolean } };
    const two = second.structuredContent as {
      data: { activity: { id: string }; created: boolean };
    };

    expect(one.data.created).toBe(true);
    expect(two.data.created).toBe(false);
    expect(two.data.activity.id).toBe(one.data.activity.id);

    const rows = await handle.database.db
      .select({ id: schema.activitySessions.id })
      .from(schema.activitySessions)
      .where(
        and(
          eq(schema.activitySessions.userId, userId),
          eq(schema.activitySessions.startedOn, "2026-08-23"),
        ),
      );
    expect(rows).toHaveLength(1);
  });

  test("refuses a missing required field with an actionable error and writes nothing", async () => {
    const before = await handle.database.db
      .select({ id: schema.activitySessions.id })
      .from(schema.activitySessions)
      .where(eq(schema.activitySessions.userId, userId));

    const result = await client.callTool({
      name: "mue.create_activity",
      arguments: { movement: "running", durationMinutes: 35 },
    });

    expect(result.isError).toBe(true);
    const structured = result.structuredContent as {
      status: string;
      error: { code: string; field: string; retryable: boolean; message: string };
    };
    expect(structured.status).toBe("error");
    expect(structured.error.code).toBe("sync.missing_required_field");
    // The field is what lets the agent ask the person for exactly the missing thing.
    expect(structured.error.field).toBe("startedOn");
    expect(structured.error.retryable).toBe(false);
    expect(structured.error.message.length).toBeGreaterThan(20);

    const after = await handle.database.db
      .select({ id: schema.activitySessions.id })
      .from(schema.activitySessions)
      .where(eq(schema.activitySessions.userId, userId));
    expect(after).toHaveLength(before.length);

    // The refusal is still audited, with no aggregate and no revision.
    const audits = await handle.database.db
      .select()
      .from(schema.agentAudit)
      .where(
        and(eq(schema.agentAudit.agentId, agent.clientId), eq(schema.agentAudit.result, "error")),
      );
    expect(audits.length).toBeGreaterThan(0);
    expect(audits[0]!.aggregates).toEqual([]);
    expect(audits[0]!.revision).toBeNull();
    expect((audits[0]!.error as { code: string }).code).toBe("sync.missing_required_field");
  });

  test("never invents a mandatory value it could have guessed", async () => {
    // A movement of `other` with no name is the case where a server could plausibly
    // fill in "Other" and be wrong forever.
    const result = await client.callTool({
      name: "mue.create_activity",
      arguments: { movement: "other", startedOn: "2026-08-22", durationMinutes: 20 },
    });
    const structured = result.structuredContent as { error: { field: string } };
    expect(structured.error.field).toBe("customMovementName");
  });

  test("walks a whole history with no filters and more than one page", async () => {
    const dates = ["2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05"];
    await handle.database.db.insert(schema.measurements).values(
      dates.map((date, index) => ({
        userId,
        date,
        weightCg: 7_000 + index * 5,
        revision: 1n,
        createdAt: new Date(),
        updatedAt: new Date(),
        deletedAt: null,
        originType: "android",
        originId: "device-1",
        lastMutationId: `seed-${date}`,
        payloadSchemaVersion: 1,
      })),
    );

    const seen: string[] = [];
    let cursor: string | null = null;
    let pages = 0;

    do {
      const result: Awaited<ReturnType<Client["callTool"]>> = await client.callTool({
        name: "mue.list_weight_measurements",
        // No `from`, no `to`: section 14.2 forbids an imposed time window.
        arguments: cursor === null ? { limit: 2 } : { limit: 2, cursor },
      });
      const structured = result.structuredContent as {
        data: {
          measurements: { date: string; originType: string; revision: string }[];
          nextCursor: string | null;
          lastAndroidSyncAt: string | null;
        };
      };
      for (const measurement of structured.data.measurements) {
        seen.push(measurement.date);
        // Section 14.2: provenance and revision travel with every row.
        expect(measurement.originType).toBe("android");
        expect(measurement.revision).toBe("1");
      }
      cursor = structured.data.nextCursor;
      pages += 1;
      expect(pages).toBeLessThan(10);
    } while (cursor !== null);

    expect(pages).toBeGreaterThan(1);
    expect(seen.sort()).toEqual(dates);
  });

  test("reports the last known Android sync instant rather than implying freshness", async () => {
    // FR-SYNC-008: an agent gets no false guarantee. Before the phone has written
    // anything the answer is null, and it must be present in the payload either way.
    const empty = await client.callTool({
      name: "mue.list_weight_measurements",
      arguments: { limit: 1 },
    });
    expect(
      (empty.structuredContent as { data: { lastAndroidSyncAt: string | null } }).data,
    ).toHaveProperty("lastAndroidSyncAt");

    const recordedAt = new Date("2026-08-20T06:30:00.000Z");
    await handle.database.db.insert(schema.syncJournal).values({
      userId,
      sequence: 9_001n,
      aggregateType: "measurement",
      aggregateId: "2026-08-20",
      operation: "upsert",
      revision: 1n,
      payloadSchemaVersion: 1,
      payload: { date: "2026-08-20", weightCg: 7_000 },
      deletedAt: null,
      originType: "android",
      originId: "device-1",
      mutationId: "seed-journal",
      recordedAt,
    });

    const result = await client.callTool({
      name: "mue.list_weight_measurements",
      arguments: { limit: 1 },
    });
    const structured = result.structuredContent as { data: { lastAndroidSyncAt: string } };
    expect(structured.data.lastAndroidSyncAt).toBe(recordedAt.toISOString());
  });

  test("refuses a cursor it did not issue, without describing its encoding", async () => {
    const result = await client.callTool({
      name: "mue.list_weight_measurements",
      arguments: { cursor: "bm90LWEtY3Vyc29y" },
    });
    const structured = result.structuredContent as { error: { code: string; message: string } };
    expect(structured.error.code).toBe("sync.invalid_cursor");
    expect(structured.error.message).not.toContain("base64");
    expect(structured.error.message).not.toContain("JSON");
  });
});

describe("authorization", () => {
  test(
    "a read-only scope neither sees nor reaches a write tool",
    async () => {
      // Section 22.5: "Verification qu'un scope MCP de lecture ne peut appeler aucun
      // outil d'ecriture."
      const reader = await newAgent("Read-only agent", "weight:read");
      const client = await connect(reader);

      const { tools } = await client.listTools();
      expect(tools.map((tool) => tool.name).sort()).toEqual([
        "mue.get_weight_measurement",
        "mue.get_weight_statistics",
        "mue.list_weight_measurements",
      ]);
      for (const tool of tools) expect(tool.annotations?.readOnlyHint).toBe(true);

      const result = await client.callTool({
        name: "mue.create_activity",
        arguments: { movement: "running", startedOn: "2026-08-21", durationMinutes: 30 },
      });
      expect(result.isError).toBe(true);
      expect(JSON.stringify(result.content)).toContain("mue.create_activity");

      const rows = await handle.database.db
        .select({ id: schema.activitySessions.id })
        .from(schema.activitySessions)
        .where(
          and(
            eq(schema.activitySessions.userId, userId),
            eq(schema.activitySessions.startedOn, "2026-08-21"),
          ),
        );
      expect(rows).toHaveLength(0);

      await client.close();
    },
    OAUTH_TIMEOUT_MS,
  );

  test(
    "a revoked identity is refused everything, and told nothing",
    async () => {
      const doomed = await newAgent("Doomed agent", "weight:read activity:write");
      const client = await connect(doomed);
      expect((await client.listTools()).tools.length).toBeGreaterThan(0);
      await client.close();

      // Section 15.3, through the documented local administration command's own function.
      const revocation = await revokeAgent(handle.database, doomed.clientId);
      expect(revocation.found).toBe(true);

      const response = await fetch(`${base}/mcp`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          accept: "application/json, text/event-stream",
          authorization: `Bearer ${doomed.tokens.access_token}`,
        },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
      });

      // The access token is still signed, still unexpired and still audience-bound. Only
      // a database check can refuse it, and it must, immediately.
      expect(response.status).toBe(401);
      const body = JSON.stringify(await json(response));
      expect(body).toContain("auth.unauthenticated");
      // Nothing about the account, the tools or why it failed.
      expect(body).not.toContain("mue.list_weight_measurements");
      expect(body).not.toContain(userId);
    },
    OAUTH_TIMEOUT_MS,
  );

  test("refuses a token that is absent, malformed or for another audience", async () => {
    for (const authorization of ["", "Bearer not-a-token", "Bearer "]) {
      const response = await fetch(`${base}/mcp`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          accept: "application/json",
          ...(authorization === "" ? {} : { authorization }),
        },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
      });
      expect(response.status).toBe(401);
    }
  });

  test("refuses a browser Origin that is not trusted, and allows a native client with none", async () => {
    // Section 16: "Le serveur valide les hotes et origines HTTP." A native MCP client
    // sends no Origin at all, so an absent header cannot be a refusal.
    const hostile = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        origin: "https://evil.example",
      },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
    });
    expect(hostile.status).toBe(403);

    const native = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} }),
    });
    // Unauthenticated, not forbidden: the origin check let it through and the token
    // check stopped it.
    expect(native.status).toBe(401);
  });
});

/**
 * The rest of section 14's catalogue, driven exactly as an agent drives it: a real MCP
 * client, the real SDK transport, a real Better Auth authorization and a real PostgreSQL.
 *
 * Every write test asserts the **row**, not the response. That is the whole point of the
 * suite: a value of the right shape and the wrong content -- a weight off the five-centigram
 * step, a nutrient stored as 0 instead of absent, a moment of the day chosen at random -- is
 * invisible to anything that compares shapes, and shows up only when a real value is pushed
 * through the whole path and the database is asked what it kept.
 */
describe("the whole section 14 catalogue", () => {
  let agent: Agent;
  let client: Client;

  /** A food and a recipe the food and journal tests build on, in call order. */
  let foodId = "";
  let recipeId = "";
  let exerciseId = "";
  let activityId = "";

  beforeAll(async () => {
    agent = await newAgent("Catalogue agent", CATALOGUE_SCOPES);
    client = await connect(agent);
  }, OAUTH_TIMEOUT_MS);

  afterAll(async () => {
    await client.close();
  });

  test("advertises every tool section 14 asks for, and nothing else", async () => {
    const { tools } = await client.listTools();
    expect(tools.map((tool) => tool.name).sort()).toEqual(
      MUE_TOOLS.map((tool) => tool.name).sort(),
    );
    expect(tools).toHaveLength(28);

    // Section 14.1's four annotations, on all twenty-eight, and section 14.6's rule that a
    // deletion is annotated destructive.
    for (const tool of tools) {
      expect(tool.annotations).toBeDefined();
      expect(tool.outputSchema).toBeDefined();
      if (tool.name.includes(".delete_")) {
        expect({ name: tool.name, destructive: tool.annotations?.destructiveHint }).toEqual({
          name: tool.name,
          destructive: true,
        });
      }
    }
  });

  test("declares every section 15.2 scope except the one the read gap leaves unused", async () => {
    // Section 15.2 lists nine scopes and `scopes.ts` declares all nine. Eight of them are
    // reachable: some tool asks for them, so the 401 challenge offers them and an agent can
    // consent to them. `nutrition:read` is the ninth, and nothing declares it because
    // PRD_FOOD 21.5's food *read* tools are not in this build. Asserted rather than left as
    // a surprise in an OAuth error the day someone tries to grant it.
    const declared = new Set(MUE_TOOLS.flatMap((tool) => tool.scopes));
    const unused = MUE_SCOPES.filter((scope) => !declared.has(scope));
    expect(unused).toEqual(["nutrition:read"]);

    expect(agent.tokens.scope).toContain("data:delete");
    expect(agent.tokens.scope).toContain("nutrition:write");
  });

  test("a name no tool has is refused, which is what each of these looked like yesterday", async () => {
    // Every tool in this file failed exactly like this before it existed. Two names are used:
    // one that never will exist, and one that PRD_FOOD 21.5 names and this build deliberately
    // does not ship, so the gap is asserted rather than remembered.
    for (const name of ["mue.definitely_not_a_tool", "mue.list_food_logs"]) {
      const result = await client.callTool({ name, arguments: {} });
      expect({ name, errored: result.isError }).toEqual({ name, errored: true });
      expect(JSON.stringify(result.content)).toContain(name);
    }
  });

  // --- section 14.2, the reads ---------------------------------------------------------

  test("get_sync_status answers how fresh this copy is, and says nothing about health", async () => {
    const data = (await callOk(client, "mue.get_sync_status", {})) as {
      journalSequence: string;
      changeCount: number;
      lastAndroidSyncAt: string | null;
      supportedPayloadVersions: Record<string, number[]>;
    };

    expect(Number(data.journalSequence)).toBeGreaterThan(0);
    expect(data.changeCount).toBeGreaterThan(0);
    // FR-SYNC-008: the phone's own instant, seeded by an earlier test in this file.
    expect(data.lastAndroidSyncAt).toBe("2026-08-20T06:30:00.000Z");
    expect(data.supportedPayloadVersions["measurement"]).toEqual([1]);

    // No weight, no date, no count of anything a domain scope would gate.
    const body = JSON.stringify(data);
    expect(body).not.toContain("7000");
    expect(body).not.toContain("2026-01-01");
  });

  test("update_health_profile writes what was said and leaves the rest alone", async () => {
    const first = (await callOk(client, "mue.update_health_profile", { heightCm: 178 })) as {
      profile: { heightCm: number; birthDate: string | null; revision: string };
    };
    expect(first.profile.heightCm).toBe(178);
    // Not mentioned, so not invented: the birth date is absent rather than guessed.
    expect(first.profile.birthDate).toBeNull();

    // A second call that mentions only the birth date must not erase the height. That is
    // section 13.4's field merge, reached through the same handler a phone reaches.
    const second = (await callOk(client, "mue.update_health_profile", {
      birthDate: "1998-11-18",
    })) as { profile: { heightCm: number; birthDate: string } };
    expect(second.profile.heightCm).toBe(178);
    expect(second.profile.birthDate).toBe("1998-11-18");

    const rows = await handle.database.db
      .select()
      .from(schema.healthProfile)
      .where(eq(schema.healthProfile.userId, userId));
    expect(rows[0]!.heightCm).toBe(178);
    expect(rows[0]!.birthDate).toBe("1998-11-18");
    expect(rows[0]!.originType).toBe("agent");

    const read = (await callOk(client, "mue.get_health_profile", {})) as {
      profile: { heightCm: number; birthDate: string };
    };
    expect(read.profile).toMatchObject({ heightCm: 178, birthDate: "1998-11-18" });
  });

  test("update_health_profile refuses a call that changes nothing, naming a field", async () => {
    const error = await callError(client, "mue.update_health_profile", {});
    expect(error.code).toBe("sync.invalid_payload");
    expect(error.field).toBe("heightCm");
  });

  // --- weights --------------------------------------------------------------------------

  test("upsert_weight_measurement stores the integer the phone stores, on its own step", async () => {
    const data = (await callOk(client, "mue.upsert_weight_measurement", {
      date: "2026-07-01",
      weightKg: 70.13,
    })) as { measurement: { weightCg: number; weightKg: number }; rounded: boolean };

    // 70.13 kg is not on Mue's 0.05 kg resolution. Android's `Weight.ofKilogramsOrNull`
    // rounds it to 70.15, and so must this, or the same weight typed on the phone and told
    // to an agent would be two different rows.
    expect(data.measurement.weightCg).toBe(7015);
    expect(data.rounded).toBe(true);

    const rows = await handle.database.db
      .select()
      .from(schema.measurements)
      .where(
        and(eq(schema.measurements.userId, userId), eq(schema.measurements.date, "2026-07-01")),
      );
    expect(rows[0]!.weightCg).toBe(7015);
    expect(rows[0]!.originType).toBe("agent");
    expect(rows[0]!.originId).toBe(agent.clientId);
    expect(rows[0]!.deletedAt).toBeNull();
  });

  test("upsert_weight_measurement refuses an exact centigram off the step, naming it", async () => {
    // The other half of the same decision: a stated integer is a claim, and a claim that is
    // not representable is refused rather than moved.
    const error = await callError(client, "mue.upsert_weight_measurement", {
      date: "2026-07-02",
      weightCg: 7013,
    });
    expect(error.code).toBe("sync.invalid_payload");
    expect(error.field).toBe("weightCg");
    expect(error.message).toContain("5");

    const rows = await handle.database.db
      .select()
      .from(schema.measurements)
      .where(
        and(eq(schema.measurements.userId, userId), eq(schema.measurements.date, "2026-07-02")),
      );
    expect(rows).toHaveLength(0);
  });

  test("get_weight_measurement and get_weight_statistics read what was written", async () => {
    await callOk(client, "mue.upsert_weight_measurement", { date: "2026-07-03", weightKg: 69.5 });

    const one = (await callOk(client, "mue.get_weight_measurement", { date: "2026-07-01" })) as {
      measurement: { weightKg: number; originType: string };
    };
    expect(one.measurement.weightKg).toBe(70.15);
    expect(one.measurement.originType).toBe("agent");

    const absent = (await callOk(client, "mue.get_weight_measurement", {
      date: "1999-01-01",
    })) as { measurement: null };
    // A day with no measurement is null, not the nearest day that has one.
    expect(absent.measurement).toBeNull();

    const stats = (await callOk(client, "mue.get_weight_statistics", {
      from: "2026-07-01",
      to: "2026-07-31",
    })) as {
      count: number;
      minWeightKg: number;
      maxWeightKg: number;
      meanWeightKg: number;
      changeKg: number;
      firstDate: string;
      method: string;
    };
    expect(stats.count).toBe(2);
    expect(stats.minWeightKg).toBe(69.5);
    expect(stats.maxWeightKg).toBe(70.15);
    expect(stats.meanWeightKg).toBeCloseTo(69.825, 5);
    expect(stats.changeKg).toBeCloseTo(-0.65, 5);
    expect(stats.firstDate).toBe("2026-07-01");
    // Section 14.5: a computed figure says how it was obtained.
    expect(stats.method.length).toBeGreaterThan(20);

    const empty = (await callOk(client, "mue.get_weight_statistics", {
      from: "1990-01-01",
      to: "1990-12-31",
    })) as { count: number; meanWeightKg: number | null; changeKg: number | null };
    // A mean of nothing is not zero.
    expect(empty.count).toBe(0);
    expect(empty.meanWeightKg).toBeNull();
    expect(empty.changeKg).toBeNull();
  });

  test("delete_weight_measurement leaves a tombstone, and a retry deletes once", async () => {
    const key = crypto.randomUUID();
    const first = (await callOk(client, "mue.delete_weight_measurement", {
      date: "2026-07-03",
      idempotencyKey: key,
    })) as { deleted: boolean; revision: string; mutationId: string };
    const second = (await callOk(client, "mue.delete_weight_measurement", {
      date: "2026-07-03",
      idempotencyKey: key,
    })) as { deleted: boolean; revision: string };

    expect(first.deleted).toBe(true);
    // The replay reports the stored result rather than writing a second tombstone.
    expect(second.deleted).toBe(false);
    expect(second.revision).toBe(first.revision);

    const rows = await handle.database.db
      .select()
      .from(schema.measurements)
      .where(
        and(eq(schema.measurements.userId, userId), eq(schema.measurements.date, "2026-07-03")),
      );
    // FR-SYNC-005: a tombstone, not an erasure, so an offline copy cannot resurrect it.
    expect(rows).toHaveLength(1);
    expect(rows[0]!.deletedAt).not.toBeNull();
    expect(rows[0]!.revision).toBe(BigInt(first.revision));

    // And it is gone from the reads that do not ask for tombstones.
    const read = (await callOk(client, "mue.get_weight_measurement", { date: "2026-07-03" })) as {
      measurement: null;
    };
    expect(read.measurement).toBeNull();
  });

  test("delete_weight_measurement refuses a day that holds nothing, naming the record", async () => {
    const error = await callError(client, "mue.delete_weight_measurement", { date: "1998-03-03" });
    expect(error.code).toBe("http.not_found");
    expect(error.aggregateType).toBe("measurement");
  });

  // --- activities ------------------------------------------------------------------------

  test("list_activities and get_activity walk what create_activity wrote", async () => {
    const created = (await callOk(client, "mue.create_activity", {
      movement: "swimming",
      startedOn: "2026-06-02",
      durationMinutes: 40,
      startedAtTime: "07:15",
    })) as { activity: { id: string } };
    activityId = created.activity.id;

    const page = (await callOk(client, "mue.list_activities", {
      from: "2026-06-01",
      to: "2026-06-30",
      limit: 10,
    })) as {
      activities: { id: string; movement: string; durationSeconds: number }[];
      hasMore: boolean;
      lastAndroidSyncAt: string | null;
    };
    expect(page.activities.map((activity) => activity.id)).toContain(activityId);
    expect(page.hasMore).toBe(false);
    expect(page).toHaveProperty("lastAndroidSyncAt");

    const filtered = (await callOk(client, "mue.list_activities", {
      movement: "swimming",
      limit: 10,
    })) as { activities: { movement: string }[] };
    for (const activity of filtered.activities) expect(activity.movement).toBe("swimming");

    const one = (await callOk(client, "mue.get_activity", { id: activityId })) as {
      activity: { startedAtTime: string; durationSeconds: number; source: string };
    };
    expect(one.activity.startedAtTime).toBe("07:15");
    expect(one.activity.durationSeconds).toBe(2400);
    expect(one.activity.source).toBe("agent");

    const missing = (await callOk(client, "mue.get_activity", { id: crypto.randomUUID() })) as {
      activity: null;
    };
    expect(missing.activity).toBeNull();
  });

  test("list_activities pages by day and identifier, never repeating a session", async () => {
    // Two sessions on one day is the case a cursor on the date alone silently drops.
    for (const minutes of [20, 30, 45]) {
      await callOk(client, "mue.create_activity", {
        movement: "walking",
        startedOn: "2026-05-05",
        durationMinutes: minutes,
      });
    }

    const seen: string[] = [];
    let cursor: string | null = null;
    let pages = 0;
    do {
      const data: { activities: { id: string }[]; nextCursor: string | null } = (await callOk(
        client,
        "mue.list_activities",
        cursor === null
          ? { from: "2026-05-05", to: "2026-05-05", limit: 2 }
          : { from: "2026-05-05", to: "2026-05-05", limit: 2, cursor },
      )) as { activities: { id: string }[]; nextCursor: string | null };
      for (const activity of data.activities) seen.push(activity.id);
      cursor = data.nextCursor;
      pages += 1;
      expect(pages).toBeLessThan(10);
    } while (cursor !== null);

    expect(pages).toBeGreaterThan(1);
    expect(new Set(seen).size).toBe(seen.length);
    expect(seen).toHaveLength(3);
  });

  test("update_activity changes what was named and keeps what was not", async () => {
    const data = (await callOk(client, "mue.update_activity", {
      id: activityId,
      durationMinutes: 45,
      notes: "felt easy",
    })) as {
      activity: { durationSeconds: number; notes: string; startedAtTime: string; revision: string };
    };

    expect(data.activity.durationSeconds).toBe(2700);
    expect(data.activity.notes).toBe("felt easy");
    // Not mentioned, so untouched -- not nulled.
    expect(data.activity.startedAtTime).toBe("07:15");
    expect(data.activity.revision).toBe("2");

    const rows = await handle.database.db
      .select()
      .from(schema.activitySessions)
      .where(
        and(eq(schema.activitySessions.userId, userId), eq(schema.activitySessions.id, activityId)),
      );
    expect(rows[0]!.durationSeconds).toBe(2700);
    expect(rows[0]!.notes).toBe("felt easy");
    expect(rows[0]!.startedAtTime).toBe("07:15");

    // Removing a value is a separate, explicit act.
    const cleared = (await callOk(client, "mue.update_activity", {
      id: activityId,
      clearNotes: true,
    })) as { activity: { notes: string | null } };
    expect(cleared.activity.notes).toBeNull();
  });

  test("update_activity refuses an identifier this account does not hold", async () => {
    const error = await callError(client, "mue.update_activity", {
      id: crypto.randomUUID(),
      durationMinutes: 10,
    });
    expect(error.code).toBe("http.not_found");
    expect(error.aggregateType).toBe("activitySession");
  });

  test("get_activity_statistics counts what was recorded and derives no energy", async () => {
    const data = (await callOk(client, "mue.get_activity_statistics", {
      from: "2026-05-05",
      to: "2026-05-05",
    })) as {
      sessionCount: number;
      totalDurationSeconds: number;
      byMovement: { movement: string; sessionCount: number; totalDurationSeconds: number }[];
      method: string;
    };
    expect(data.sessionCount).toBe(3);
    expect(data.totalDurationSeconds).toBe((20 + 30 + 45) * 60);
    expect(data.byMovement).toEqual([
      { movement: "walking", sessionCount: 3, totalDurationSeconds: (20 + 30 + 45) * 60 },
    ]);
    // No energy total, because Mue records an energy only when someone entered one and a
    // figure derived from a duration would be an estimate nobody made.
    expect(data).not.toHaveProperty("totalEnergyKcal");
    expect(data.method).toContain("no energy");
  });

  test("delete_activity tombstones the session and the reads stop showing it", async () => {
    const data = (await callOk(client, "mue.delete_activity", { id: activityId })) as {
      deleted: boolean;
    };
    expect(data.deleted).toBe(true);

    const rows = await handle.database.db
      .select()
      .from(schema.activitySessions)
      .where(
        and(eq(schema.activitySessions.userId, userId), eq(schema.activitySessions.id, activityId)),
      );
    expect(rows).toHaveLength(1);
    expect(rows[0]!.deletedAt).not.toBeNull();

    const read = (await callOk(client, "mue.get_activity", { id: activityId })) as {
      activity: null;
    };
    expect(read.activity).toBeNull();

    const withTombstones = (await callOk(client, "mue.get_activity", {
      id: activityId,
      includeDeleted: true,
    })) as { activity: { deletedAt: string } };
    expect(withTombstones.activity.deletedAt).not.toBeNull();
  });

  // --- personal exercises ------------------------------------------------------------------

  test("create_custom_exercise stores a definition and refuses to invent its tracking mode", async () => {
    const missing = await callError(client, "mue.create_custom_exercise", { name: "Sled push" });
    expect(missing.code).toBe("sync.missing_required_field");
    expect(missing.field).toBe("trackingMode");

    const data = (await callOk(client, "mue.create_custom_exercise", {
      name: "Sled push",
      trackingMode: "weight_and_reps",
    })) as { exercise: { id: string; equipment: string | null } };
    exerciseId = data.exercise.id;
    // Absent stays absent: no equipment was mentioned, so none was chosen.
    expect(data.exercise.equipment).toBeNull();

    const rows = await handle.database.db
      .select()
      .from(schema.customExercises)
      .where(
        and(eq(schema.customExercises.userId, userId), eq(schema.customExercises.id, exerciseId)),
      );
    expect(rows[0]!.name).toBe("Sled push");
    expect(rows[0]!.nameFolded).toBe("sled push");
    expect(rows[0]!.trackingMode).toBe("weight_and_reps");
    expect(rows[0]!.equipment).toBeNull();
    expect(rows[0]!.originType).toBe("agent");
  });

  test("list_custom_exercises and get_custom_exercise read it back", async () => {
    const page = (await callOk(client, "mue.list_custom_exercises", { limit: 50 })) as {
      exercises: { id: string; name: string }[];
    };
    expect(page.exercises.map((exercise) => exercise.id)).toContain(exerciseId);

    const one = (await callOk(client, "mue.get_custom_exercise", { id: exerciseId })) as {
      exercise: { name: string; revision: string };
    };
    expect(one.exercise.name).toBe("Sled push");
    expect(one.exercise.revision).toBe("1");
  });

  test("update_custom_exercise renames it and moves the folded name with it", async () => {
    const data = (await callOk(client, "mue.update_custom_exercise", {
      id: exerciseId,
      name: "Heavy sled push",
    })) as { exercise: { name: string; trackingMode: string } };
    expect(data.exercise.name).toBe("Heavy sled push");
    // Untouched by a rename.
    expect(data.exercise.trackingMode).toBe("weight_and_reps");

    const rows = await handle.database.db
      .select()
      .from(schema.customExercises)
      .where(
        and(eq(schema.customExercises.userId, userId), eq(schema.customExercises.id, exerciseId)),
      );
    expect(rows[0]!.name).toBe("Heavy sled push");
    expect(rows[0]!.nameFolded).toBe("heavy sled push");
  });

  test("delete_custom_exercise is refused by the domain, and says why", async () => {
    // PRD_ACTIVITIES 9.2 keeps a personal definition for ever. Section 14.3 lists the tool, so
    // the tool exists; the rule that refuses it is the domain's, reached through the same path
    // a phone would reach, and the agent is told rather than left guessing.
    const error = await callError(client, "mue.delete_custom_exercise", { id: exerciseId });
    expect(error.code).toBe("sync.invalid_payload");
    expect(error.aggregateType).toBe("customExerciseDefinition");
    expect(error.message).toContain("kept for ever");

    const rows = await handle.database.db
      .select()
      .from(schema.customExercises)
      .where(
        and(eq(schema.customExercises.userId, userId), eq(schema.customExercises.id, exerciseId)),
      );
    expect(rows[0]!.deletedAt).toBeNull();
  });

  // --- foods --------------------------------------------------------------------------------

  test("create_food keeps an unknown nutrient absent instead of storing a zero", async () => {
    const data = (await callOk(client, "mue.create_food", {
      name: "Skyr nature",
      energyKcalPer100: 63,
      proteinGramsPer100: 10.5,
      // No carbohydrate, no fat, no fibre: the person did not say.
    })) as { food: { id: string; fatGramsPer100: number | null; energyKcalPer100: number } };
    foodId = data.food.id;
    expect(data.food.energyKcalPer100).toBe(63);
    expect(data.food.fatGramsPer100).toBeNull();

    const rows = await handle.database.db
      .select()
      .from(schema.foods)
      .where(and(eq(schema.foods.userId, userId), eq(schema.foods.id, foodId)));
    const row = rows[0]!;
    // The integers the domain stores: thousandths of a kilocalorie and milligrams.
    expect(row.energyMilliKcal).toBe(63_000);
    expect(row.proteinMilligrams).toBe(10_500);
    // PRD_FOOD 13.1: unknown is NULL, never 0. A zero here would be handed to the phone as a
    // fact nobody stated.
    expect(row.fatMilligrams).toBeNull();
    expect(row.carbsMilligrams).toBeNull();
    expect(row.fibreMilligrams).toBeNull();
    expect(row.source).toBe("custom");
    expect(row.referenceUnit).toBe("gram");
    expect(row.rawLabel).toBe("Raw");
    expect(row.originType).toBe("agent");
  });

  test("update_food changes one field, keeps the unknown ones unknown, and clears on request", async () => {
    await callOk(client, "mue.update_food", { id: foodId, brand: "Isey" });

    let rows = await handle.database.db
      .select()
      .from(schema.foods)
      .where(and(eq(schema.foods.userId, userId), eq(schema.foods.id, foodId)));
    expect(rows[0]!.brand).toBe("Isey");
    // Omitting a nutrient did not set it to zero.
    expect(rows[0]!.fatMilligrams).toBeNull();
    expect(rows[0]!.energyMilliKcal).toBe(63_000);

    await callOk(client, "mue.update_food", { id: foodId, clear: ["brand"] });
    rows = await handle.database.db
      .select()
      .from(schema.foods)
      .where(and(eq(schema.foods.userId, userId), eq(schema.foods.id, foodId)));
    expect(rows[0]!.brand).toBeNull();
    expect(rows[0]!.revision).toBe(3n);
  });

  // --- recipes -------------------------------------------------------------------------------

  test("create_recipe stores a whole recipe with its ingredients and their snapshots", async () => {
    const data = (await callOk(client, "mue.create_recipe", {
      name: "Skyr bowl",
      type: "breakfast",
      baseServings: 2,
      ingredients: [{ foodId, quantity: 250 }],
      steps: ["Spoon the skyr into a bowl.", "Add the fruit."],
      prepTimeMinutes: 5,
    })) as {
      recipe: {
        id: string;
        ingredients: { foodName: string; quantityGrams: number; unit: string }[];
      };
    };
    recipeId = data.recipe.id;
    expect(data.recipe.ingredients).toHaveLength(1);
    // The unit came from the food itself rather than from the caller.
    expect(data.recipe.ingredients[0]!.unit).toBe("gram");
    expect(data.recipe.ingredients[0]!.foodName).toBe("Skyr nature");
    expect(data.recipe.ingredients[0]!.quantityGrams).toBe(250);

    const rows = await handle.database.db
      .select()
      .from(schema.recipes)
      .where(and(eq(schema.recipes.userId, userId), eq(schema.recipes.id, recipeId)));
    const row = rows[0]!;
    expect(row.name).toBe("Skyr bowl");
    expect(row.baseServings).toBe(2);
    expect(row.isFavourite).toBe(false);
    expect(row.prepTimeMinutes).toBe(5);
    expect(row.steps).toEqual(["Spoon the skyr into a bowl.", "Add the fruit."]);
    const ingredients = row.ingredients as { quantityThousandths: number; foodName: string }[];
    // Thousandths of a gram, the integer the domain stores.
    expect(ingredients[0]!.quantityThousandths).toBe(250_000);
    expect(ingredients[0]!.foodName).toBe("Skyr nature");
  });

  test("create_recipe names the ingredient that is wrong, not just the call", async () => {
    const error = await callError(client, "mue.create_recipe", {
      name: "Nonsense",
      type: "main",
      baseServings: 1,
      ingredients: [
        { foodId, quantity: 100 },
        { foodId: crypto.randomUUID(), quantity: 50 },
      ],
    });
    expect(error.code).toBe("sync.invalid_payload");
    // The dotted path is what turns "something was wrong" into one fixable line.
    expect(error.field).toBe("ingredients.1.unit");
  });

  test("update_recipe replaces the ingredient list whole, as PRD_FOOD 21.3 requires", async () => {
    const data = (await callOk(client, "mue.update_recipe", {
      id: recipeId,
      baseServings: 4,
    })) as { recipe: { baseServings: number; ingredients: unknown[]; steps: string[] } };
    expect(data.recipe.baseServings).toBe(4);
    // The list was not mentioned, so it was carried through untouched.
    expect(data.recipe.ingredients).toHaveLength(1);
    expect(data.recipe.steps).toHaveLength(2);

    const rows = await handle.database.db
      .select()
      .from(schema.recipes)
      .where(and(eq(schema.recipes.userId, userId), eq(schema.recipes.id, recipeId)));
    expect(rows[0]!.baseServings).toBe(4);
  });

  // --- the food journal, and the moment of the day ---------------------------------------------

  test("create_food_log deduces the moment from the time, and never at random", async () => {
    // PRD_FOOD 22, in its own words: "une pomme à dix heures tombe en collation".
    const morning = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-06-10",
      consumedAt: "10:00",
      title: "Pomme",
      energyKcal: 72,
    })) as { entry: { id: string; slot: string; estimation: string }; slotWasDeduced: boolean };

    expect(morning.entry.slot).toBe("snack");
    expect(morning.slotWasDeduced).toBe(true);

    const evening = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-06-10",
      consumedAt: "20:15",
      title: "Soupe",
    })) as { entry: { slot: string } };
    expect(evening.entry.slot).toBe("dinner");

    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(
          eq(schema.foodLogEntries.userId, userId),
          eq(schema.foodLogEntries.id, morning.entry.id),
        ),
      );
    const row = rows[0]!;
    expect(row.slot).toBe("snack");
    expect(row.consumedAt).toBe("10:00");
    expect(row.energyMilliKcal).toBe(72_000);
    // Nothing else was said, so nothing else was written.
    expect(row.proteinMilligrams).toBeNull();
    expect(row.quantityThousandths).toBeNull();
    expect(row.quantityUnit).toBeNull();
    // PRD_FOOD 13.2: a portion described in words is an approximation, and Mue says so.
    expect(row.estimation).toBe("approximate");
    expect(row.weighedCooked).toBe(false);
    expect(row.kind).toBe("quick");
    expect(row.originType).toBe("agent");
  });

  test("create_food_log takes a stated moment and writes it at that moment's usual time", async () => {
    const data = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-06-11",
      slot: "lunch",
      title: "Salade",
      foodId,
      quantityGrams: 180,
    })) as {
      entry: { id: string; slot: string; consumedAt: string; kind: string };
      slotWasDeduced: boolean;
    };

    expect(data.entry.slot).toBe("lunch");
    // PRD_FOOD 10.3's default time of a retroactive entry, not an invented clock reading.
    expect(data.entry.consumedAt).toBe("13:00");
    expect(data.slotWasDeduced).toBe(false);
    // `kind` follows from what was referenced; there is no input to get it wrong.
    expect(data.entry.kind).toBe("food");

    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(eq(schema.foodLogEntries.userId, userId), eq(schema.foodLogEntries.id, data.entry.id)),
      );
    expect(rows[0]!.quantityThousandths).toBe(180_000);
    expect(rows[0]!.quantityUnit).toBe("gram");
    expect(rows[0]!.sourceRef).toBe(foodId);
  });

  test("create_food_log asks for the time when it has neither a time nor a moment", async () => {
    const error = await callError(client, "mue.create_food_log", {
      consumedOn: "2026-06-11",
      title: "Quelque chose",
    });
    expect(error.code).toBe("sync.missing_required_field");
    expect(error.field).toBe("consumedAt");
    expect(error.retryable).toBe(false);
  });

  test("create_food_log refuses a serving count off its quarter step, naming the field", async () => {
    const error = await callError(client, "mue.create_food_log", {
      consumedOn: "2026-06-11",
      consumedAt: "12:30",
      title: "Skyr bowl",
      recipeId,
      servings: 1.3,
    });
    expect(error.code).toBe("sync.invalid_payload");
    expect(error.field).toBe("servings");
  });

  test("create_food_log refuses a day in the future", async () => {
    const future = new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    const error = await callError(client, "mue.create_food_log", {
      consumedOn: future,
      consumedAt: "12:30",
      title: "Demain",
    });
    expect(error.code).toBe("sync.invalid_payload");
    expect(error.field).toBe("consumedOn");
  });

  test("replaying create_food_log with one key produces one row, not two", async () => {
    const key = crypto.randomUUID();
    const args = {
      consumedOn: "2026-06-12",
      consumedAt: "08:10",
      title: "Porridge",
      energyKcal: 310,
      idempotencyKey: key,
    };

    const first = (await callOk(client, "mue.create_food_log", args)) as {
      entry: { id: string };
      created: boolean;
    };
    const second = (await callOk(client, "mue.create_food_log", args)) as {
      entry: { id: string };
      created: boolean;
    };

    expect(first.created).toBe(true);
    expect(second.created).toBe(false);
    // The replay returns the row the *first* call wrote, not the one this call would have.
    expect(second.entry.id).toBe(first.entry.id);

    const rows = await handle.database.db
      .select({ id: schema.foodLogEntries.id })
      .from(schema.foodLogEntries)
      .where(
        and(
          eq(schema.foodLogEntries.userId, userId),
          eq(schema.foodLogEntries.consumedOn, "2026-06-12"),
        ),
      );
    expect(rows).toHaveLength(1);
  });

  test("update_food_log corrects a line and leaves its moment where it was recorded", async () => {
    const created = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-06-13",
      consumedAt: "19:00",
      title: "Pates",
      energyKcal: 500,
    })) as { entry: { id: string; slot: string } };
    expect(created.entry.slot).toBe("dinner");

    const updated = (await callOk(client, "mue.update_food_log", {
      id: created.entry.id,
      title: "Pates au pesto",
      consumedAt: "19:30",
    })) as { entry: { title: string; slot: string; consumedAt: string; energyKcal: number } };

    expect(updated.entry.title).toBe("Pates au pesto");
    expect(updated.entry.consumedAt).toBe("19:30");
    // Correcting the clock does not move a line to another moment: the moment may have been
    // chosen deliberately, and moving it silently would rewrite the person's day.
    expect(updated.entry.slot).toBe("dinner");
    // Not mentioned, so untouched.
    expect(updated.entry.energyKcal).toBe(500);

    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(
          eq(schema.foodLogEntries.userId, userId),
          eq(schema.foodLogEntries.id, created.entry.id),
        ),
      );
    expect(rows[0]!.title).toBe("Pates au pesto");
    expect(rows[0]!.consumedAt).toBe("19:30");
    expect(rows[0]!.energyMilliKcal).toBe(500_000);

    const cleared = (await callOk(client, "mue.update_food_log", {
      id: created.entry.id,
      clear: ["energyKcal"],
    })) as { entry: { energyKcal: number | null } };
    expect(cleared.entry.energyKcal).toBeNull();
  });

  test("delete_food_log, delete_recipe and delete_food all leave tombstones", async () => {
    const line = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-06-14",
      consumedAt: "12:00",
      title: "A supprimer",
    })) as { entry: { id: string } };

    await callOk(client, "mue.delete_food_log", { id: line.entry.id });
    await callOk(client, "mue.delete_recipe", { id: recipeId });
    await callOk(client, "mue.delete_food", { id: foodId });

    const lines = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(eq(schema.foodLogEntries.userId, userId), eq(schema.foodLogEntries.id, line.entry.id)),
      );
    expect(lines[0]!.deletedAt).not.toBeNull();

    const recipes = await handle.database.db
      .select()
      .from(schema.recipes)
      .where(and(eq(schema.recipes.userId, userId), eq(schema.recipes.id, recipeId)));
    expect(recipes[0]!.deletedAt).not.toBeNull();

    const foods = await handle.database.db
      .select()
      .from(schema.foods)
      .where(and(eq(schema.foods.userId, userId), eq(schema.foods.id, foodId)));
    expect(foods[0]!.deletedAt).not.toBeNull();
  });

  // --- section 14.7 ----------------------------------------------------------------------------

  test("every write is in the agent audit, with the eight fields and nothing else", async () => {
    const data = (await callOk(client, "mue.upsert_weight_measurement", {
      date: "2026-07-09",
      weightKg: 71,
    })) as { mutationId: string; measurement: { revision: string } };

    const audits = await handle.database.db
      .select()
      .from(schema.agentAudit)
      .where(eq(schema.agentAudit.mutationId, data.mutationId));

    const audit = audits[0]!;
    expect(audit.agentId).toBe(agent.clientId);
    expect(audit.toolName).toBe("mue.upsert_weight_measurement");
    expect(audit.occurredAt).toBeInstanceOf(Date);
    expect(audit.aggregates).toEqual([{ type: "measurement", id: "2026-07-09" }]);
    expect(audit.result).toBe("ok");
    expect(audit.revision).toBe(BigInt(data.measurement.revision));
    expect(audit.error).toBeNull();
    // Section 14.7 does not want prompts or conversations, and the row has nowhere to put one.
    expect(Object.keys(audit).sort()).toEqual([
      "agentId",
      "aggregates",
      "error",
      "id",
      "mutationId",
      "occurredAt",
      "result",
      "revision",
      "toolName",
    ]);
  });

  test("a refused write is audited too, with no aggregate and no revision", async () => {
    const before = await handle.database.db
      .select({ id: schema.agentAudit.id })
      .from(schema.agentAudit)
      .where(
        and(eq(schema.agentAudit.agentId, agent.clientId), eq(schema.agentAudit.result, "error")),
      );

    await callError(client, "mue.create_food", {});

    const after = await handle.database.db
      .select()
      .from(schema.agentAudit)
      .where(
        and(eq(schema.agentAudit.agentId, agent.clientId), eq(schema.agentAudit.result, "error")),
      )
      .orderBy(desc(schema.agentAudit.occurredAt));

    expect(after.length).toBeGreaterThan(before.length);
    const latest = after.find((row) => row.toolName === "mue.create_food")!;
    expect(latest.aggregates).toEqual([]);
    expect(latest.revision).toBeNull();
    expect((latest.error as { code: string; field: string }).code).toBe(
      "sync.missing_required_field",
    );
    expect((latest.error as { code: string; field: string }).field).toBe("name");
  });

  test("no error message this catalogue produces carries a value from the record", async () => {
    // Section 16: an error names the field, never its content. The weight, the height and the
    // title are the personal data these tools carry, and none of them belongs in a message a
    // client will log.
    const messages: string[] = [];
    for (const [name, args] of [
      ["mue.upsert_weight_measurement", { date: "2026-07-10", weightCg: 7013 }],
      [
        "mue.create_food_log",
        {
          consumedOn: "2026-06-11",
          consumedAt: "12:30",
          title: "Secret dish",
          recipeId,
          servings: 1.3,
        },
      ],
      ["mue.update_health_profile", {}],
    ] as const) {
      messages.push((await callError(client, name, args)).message);
    }
    for (const message of messages) {
      expect(message).not.toContain("7013");
      expect(message).not.toContain("Secret dish");
      expect(message).not.toContain("1.3");
    }
  });
});

describe("the deletion permission of section 15.2", () => {
  test(
    "a full write scope without `data:delete` reaches no delete tool",
    async () => {
      // Section 15.2 allows the deletion permission to be folded into a domain write scope or
      // kept explicit; `scopes.ts` keeps it explicit, so this is what that buys: an agent
      // trusted to write every domain still cannot remove anything.
      const writer = await newAgent(
        "Writer without deletion",
        CATALOGUE_SCOPES.split(" ")
          .filter((scope) => scope !== "data:delete")
          .join(" "),
      );
      const client = await connect(writer);

      const { tools } = await client.listTools();
      const names = tools.map((tool) => tool.name);
      expect(names).toContain("mue.upsert_weight_measurement");
      expect(names).toContain("mue.create_food_log");
      for (const name of names) expect(name).not.toContain(".delete_");

      await client.callTool({
        name: "mue.upsert_weight_measurement",
        arguments: { date: "2026-07-20", weightKg: 68 },
      });
      const refused = await client.callTool({
        name: "mue.delete_weight_measurement",
        arguments: { date: "2026-07-20" },
      });
      expect(refused.isError).toBe(true);
      expect(JSON.stringify(refused.content)).toContain("mue.delete_weight_measurement");

      // And the measurement is still there, untouched.
      const rows = await handle.database.db
        .select()
        .from(schema.measurements)
        .where(
          and(eq(schema.measurements.userId, userId), eq(schema.measurements.date, "2026-07-20")),
        );
      expect(rows[0]!.deletedAt).toBeNull();

      await client.close();
    },
    OAUTH_TIMEOUT_MS,
  );
});
