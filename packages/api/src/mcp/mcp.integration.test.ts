import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createAuth, OAUTH_SCOPES, revokeAgent, type AuthHandle } from "@mue/auth";
import { schema } from "@mue/db";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { OAuthClientProvider } from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type {
  OAuthClientInformation,
  OAuthClientMetadata,
  OAuthTokens,
} from "@modelcontextprotocol/sdk/shared/auth.js";
import { and, eq } from "drizzle-orm";
import { Hono } from "hono";
import { createMcpApp, createOAuthDiscoveryApp } from "./index";
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
  });

  // A signing key encrypted under a different secret cannot be decrypted, and the
  // development database is shared with every other suite that boots Better Auth.
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
});

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
  });

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
    expect(names).toEqual(["mue.create_activity", "mue.list_weight_measurements"]);

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
  test("a read-only scope neither sees nor reaches a write tool", async () => {
    // Section 22.5: "Verification qu'un scope MCP de lecture ne peut appeler aucun
    // outil d'ecriture."
    const reader = await newAgent("Read-only agent", "weight:read");
    const client = await connect(reader);

    const { tools } = await client.listTools();
    expect(tools.map((tool) => tool.name)).toEqual(["mue.list_weight_measurements"]);

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
  });

  test("a revoked identity is refused everything, and told nothing", async () => {
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
  });

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
