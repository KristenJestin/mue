import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import {
  createAuth,
  MUE_SCOPES,
  OAUTH_SCOPES,
  oauthIssuer,
  revokeAgent,
  type AuthHandle,
} from "@mue/auth";
import { createTestDatabase, schema } from "@mue/db";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  discoverAuthorizationServerMetadata,
  discoverOAuthProtectedResourceMetadata,
  type OAuthClientProvider,
} from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type {
  OAuthClientInformation,
  OAuthClientMetadata,
  OAuthTokens,
} from "@modelcontextprotocol/sdk/shared/auth.js";
import { and, desc, eq, isNull } from "drizzle-orm";
import { Hono } from "hono";
import {
  createClientRegistrationApp,
  createMcpApp,
  createOAuthDiscoveryApp,
  createPairingWindow,
  MAX_PAIRING_MINUTES,
  MUE_TOOLS,
  PAIRING_PATH,
  type PairingWindow,
} from "./index";
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
/**
 * The pairing window RFC 7591 registration sits behind. Held here rather than
 * driven only over HTTP so a test can assert the *closed* default, which is the
 * state that matters: it is what stands between an open registration endpoint
 * and a network the owner does not control.
 */
const pairing: PairingWindow = createPairingWindow();

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
  // The consent page of `apps/platform/src/routes/consent.tsx`, mounted here at the path
  // this suite configured for it.
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
 * Every member of `MUE_SCOPES` now appears here, including `nutrition:read`. It used not to:
 * PRD_FOOD 21.5's six food *read* tools were unshipped, nothing declared the scope, and so
 * nothing could ask for it — a scope that was not merely unused but unreachable. The six
 * declare it, and this constant picked them up on its own the moment they landed.
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
  // Ahead of the `/api/auth/*` passthrough, exactly as `apps/platform/src/runtime.ts`
  // mounts it: Better Auth has been told to accept an unauthenticated RFC 7591
  // registration, and this router is the thing that refuses one outside a pairing
  // window. Mounted after the passthrough it would never see a request.
  app.route("/", createClientRegistrationApp({ auth: handle.auth, pairing }));
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
    // The origin, not `${base}/api/auth`. The endpoints did not move -- the *issuer*
    // did, and this field carries the issuer. See `oauthIssuer` in `@mue/auth`.
    expect(metadata["authorization_servers"]).toEqual([base]);
  });

  test("publishes authorization-server metadata with PKCE and the Mue scopes", async () => {
    const response = await fetch(`${base}/.well-known/oauth-authorization-server`);
    expect(response.status).toBe(200);

    const metadata = await json(response);
    expect(metadata["code_challenge_methods_supported"]).toContain("S256");
    expect(metadata["scopes_supported"]).toContain("activity:write");
    // `@better-auth/cimd` is composed into the same plugin set, and this flag is how a
    // client learns it may present a Client ID Metadata Document URL as its client_id.
    expect(metadata["client_id_metadata_document_supported"]).toBe(true);
  });

  /**
   * The two documents live at the origin root, and say so.
   *
   * A real client sent its human to `https://<origin>/authorize` and got a 404. It had
   * not failed to read the metadata; it had failed to *find* it. The issuer carried
   * Better Auth's base path, so RFC 8414 put the document at
   * `/.well-known/oauth-authorization-server/api/auth` and OpenID Connect Discovery at
   * `/api/auth/.well-known/openid-configuration`. The client looked at the origin, saw
   * two 404s, and fell back to the origin defaults that RFC 8414 never promised.
   *
   * Serving the same document at the origin *as well* was the cheap repair and is the
   * wrong one: RFC 8414 section 3.3 makes a client verify that `issuer` equals the
   * identifier it inserted into the well-known path, so a root document announcing
   * `issuer: <origin>/api/auth` is one a strict client rejects -- later, and with a
   * worse message, than the 404 it replaced. So the issuer moved instead, and these
   * assertions are the pair that has to hold together: the document is at the root,
   * *and* it names the root.
   */
  test("serves both discovery documents at the origin, naming the origin as issuer", async () => {
    for (const path of [
      "/.well-known/oauth-authorization-server",
      "/.well-known/openid-configuration",
    ]) {
      const response = await fetch(`${base}${path}`);
      const metadata = await json(response);
      expect({ path, status: response.status, issuer: metadata["issuer"] }).toEqual({
        path,
        status: 200,
        issuer: base,
      });
    }
  });

  /**
   * An issuer identifies a server; it does not locate its endpoints. Moving the issuer
   * to the origin must not have moved anything, and this is the assertion that would
   * catch it if a later change tried to "tidy" the endpoints to match the issuer.
   */
  test("keeps the endpoints under the Better Auth base path, where they are", async () => {
    const metadata = await json(await fetch(`${base}/.well-known/oauth-authorization-server`));
    expect(metadata["authorization_endpoint"]).toBe(`${base}/api/auth/oauth2/authorize`);
    expect(metadata["token_endpoint"]).toBe(`${base}/api/auth/oauth2/token`);
    expect(metadata["jwks_uri"]).toBe(`${base}/api/auth/jwks`);
    expect(metadata["registration_endpoint"]).toBe(`${base}/api/auth/oauth2/register`);

    // And the advertised authorization endpoint is the one that answers.
    const probe = await fetch(`${metadata["authorization_endpoint"] as string}`, {
      redirect: "manual",
    });
    expect(probe.status).not.toBe(404);
  });

  /**
   * The client's own discovery code, not a hand-written URL.
   *
   * `discoverOAuthProtectedResourceMetadata` and `discoverAuthorizationServerMetadata`
   * are what the MCP SDK runs inside `auth()`; driving them directly is the difference
   * between asserting that a path answers and asserting that a client finds it.
   */
  test("a real MCP client's discovery reaches the authorization endpoint from /mcp alone", async () => {
    const resource = await discoverOAuthProtectedResourceMetadata(`${base}/mcp`);
    expect(resource.authorization_servers).toEqual([base]);

    const metadata = await discoverAuthorizationServerMetadata(resource.authorization_servers![0]!);
    expect(metadata?.issuer).toBe(base);
    expect(metadata?.authorization_endpoint).toBe(`${base}/api/auth/oauth2/authorize`);
  });

  /**
   * The failure that started this, reproduced from the other end.
   *
   * When a client has no protected-resource metadata -- it is optional, and plenty of
   * clients skip it -- the SDK treats the server's own origin as the authorization
   * server and discovers from there. That path used to end in `undefined`, and
   * `startAuthorization` then builds `new URL('/authorize', authorizationServerUrl)`:
   * the exact 404 the owner was sent to. With the issuer at the origin it resolves.
   */
  test("a client that knows only the origin still finds the authorization endpoint", async () => {
    const metadata = await discoverAuthorizationServerMetadata(base);
    expect(metadata).toBeDefined();
    expect(metadata?.authorization_endpoint).toBe(`${base}/api/auth/oauth2/authorize`);
  });

  test("refuses the historical SSE stream: section 8.3 implements it nowhere", async () => {
    const response = await fetch(`${base}/mcp`, {
      method: "GET",
      headers: { accept: "text/event-stream" },
    });
    expect(response.status).toBe(405);
  });
});

/**
 * The issuer, byte for byte.
 *
 * Five things name the issuer, and a disagreement between any two of them is a 401 with
 * nothing in any log -- the failure this repository has already been bitten by:
 *
 *  - `authorization_servers` in the RFC 9728 protected-resource metadata, which is where
 *    a client learns the identifier at all;
 *  - `issuer` in the RFC 8414 authorization-server metadata, which section 3.3 of that
 *    RFC makes a client compare against the identifier before it uses anything else in
 *    the document;
 *  - `issuer` in the OpenID Connect Discovery document, for the clients that ask for that
 *    one instead;
 *  - `iss` on an access token this server actually minted;
 *  - `oauthIssuer(baseUrl)`, which is what `route.ts` hands `requireMcpAuth` and therefore
 *    what every one of those tokens is verified against.
 *
 * They are asserted as a single object so a failure prints all five and names the odd one
 * out, instead of stopping at whichever pair happened to be compared first.
 */
describe("the issuer, byte for byte", () => {
  let accessToken = "";

  beforeAll(async () => {
    accessToken = (await newAgent("Issuer probe", "weight:read offline_access")).tokens
      .access_token;
  }, OAUTH_TIMEOUT_MS);

  /** The `iss` claim as a resource server reads it: off the token, not off a document. */
  function issuerClaim(token: string): unknown {
    const payload = token.split(".")[1];
    return (
      JSON.parse(Buffer.from(payload ?? "", "base64url").toString("utf8")) as Record<
        string,
        unknown
      >
    )["iss"];
  }

  async function advertisedIssuer(): Promise<string> {
    const resource = await json(await fetch(`${base}/.well-known/oauth-protected-resource/mcp`));
    return (resource["authorization_servers"] as string[])[0]!;
  }

  test("is one string in every document that names it, and on the token", async () => {
    const authServer = await json(await fetch(`${base}/.well-known/oauth-authorization-server`));
    const openId = await json(await fetch(`${base}/.well-known/openid-configuration`));

    expect({
      authorizationServers: await advertisedIssuer(),
      authorizationServerMetadata: authServer["issuer"],
      openIdConfiguration: openId["issuer"],
      accessTokenIssClaim: issuerClaim(accessToken),
      requireMcpAuthExpects: oauthIssuer(base),
    }).toEqual({
      authorizationServers: base,
      authorizationServerMetadata: base,
      openIdConfiguration: base,
      accessTokenIssClaim: base,
      requireMcpAuthExpects: base,
    });
  });

  /**
   * F-04, stated as the property it is actually about.
   *
   * Codex warns `rejecting authorization server metadata URL https://<origin>/` at every
   * initialisation, and the trailing slash in it is not ours: the test above is what says
   * no document we write carries one. It is how Rust's `url::Url` prints itself. An issuer
   * whose path component is empty is the one shape a URL parser rewrites -- RFC 3986
   * section 6.2.3, "a URI that uses the generic syntax for authority with an empty path
   * should be normalized to a path of '/'" -- so every client that holds the issuer in a
   * URL type rather than in a string hands back the slashed form.
   *
   * That matters because RFC 8414 section 3.3 then compares byte for byte: "The 'issuer'
   * value returned MUST be identical to the authorization server's issuer identifier value
   * into which the well-known URI string was inserted to create the URL used to retrieve
   * the metadata." A client that kept the advertised string compares `<origin>` against
   * `<origin>` and passes. A client that re-derives it from a URL compares `<origin>/`
   * against `<origin>` and needs the root-slash leniency that rmcp's
   * `issuer_identifiers_match` spends its whole body on.
   *
   * This assertion exists so that a change moving the issuer has to come through here and
   * read that paragraph first.
   */
  test("publishes the issuer in the one shape a URL parser rewrites", async () => {
    const advertised = await advertisedIssuer();

    expect(advertised).toBe(base);
    expect(advertised.endsWith("/")).toBe(false);
    // The divergence, pinned. Not a defect -- the reason the warning prints a slash.
    expect(new URL(advertised).href).toBe(`${advertised}/`);
  });

  /**
   * The slashed form has to find a document too.
   *
   * Both derivations start from the issuer after a URL parser has had it, which is the
   * only form a client like Codex ever holds. RFC 8414 section 3.1 is the first: "If the
   * issuer identifier value contains a path component, any terminating '/' MUST be removed
   * before inserting '/.well-known/' and the well-known URI suffix between the host
   * component and the path component." The second is what a client that resolves a
   * relative reference produces instead of building a string.
   */
  test("answers at the metadata URLs derivable from the slashed issuer", async () => {
    const parsed = new URL(await advertisedIssuer()).href;
    expect(parsed).toBe(`${base}/`);

    const derivations: Record<string, string> = {
      "rfc 8414 section 3.1 insertion": `${parsed.replace(/\/$/, "")}/.well-known/oauth-authorization-server`,
      "relative resolution": new URL(".well-known/oauth-authorization-server", parsed).href,
      "openid connect discovery": new URL(".well-known/openid-configuration", parsed).href,
    };

    for (const [derivation, url] of Object.entries(derivations)) {
      const response = await fetch(url);
      const metadata = response.ok ? await json(response) : {};
      expect({ derivation, status: response.status, issuer: metadata["issuer"] }).toEqual({
        derivation,
        status: 200,
        issuer: base,
      });
    }
  });

  /**
   * The ladder Codex actually walks, and the rung this server has to hold.
   *
   * rmcp -- the Rust MCP SDK Codex embeds -- refuses to fetch an `authorization_servers`
   * entry whose host is a private address. `is_allowed_authorization_server_metadata_url`
   * passes a host only if `is_disallowed_metadata_host` says no, and that consults
   * `Ipv4Addr::is_private()`; the one exemption needs *both* the MCP server and the
   * candidate to be loopback, which `192.168.1.100` is not. So on the owner's LAN our
   * `authorization_servers` entry is skipped with that warning, every time, and always
   * has been: the entry named `<origin>/api/auth` before the issuer moved and was skipped
   * just the same. This is `modelcontextprotocol/rust-sdk` PR 935, an SSRF fix.
   *
   * What is left is `try_discover_oauth_server(base_url, None)`, which is not gated, and
   * whose `generate_discovery_urls` derives these four from the MCP endpoint URL in this
   * order. The fourth is the only one that can answer, and it is the entire reason Codex
   * works here at all. It began answering the day the issuer became the origin: before
   * that the root was a 404, discovery fell through to the legacy `<origin>/authorize`,
   * and that 404 is the report `oauthIssuer` was written for.
   *
   * So this is not a nicety. It is the single rung holding a shipping client up, reached
   * only after the documented path has been refused, and nothing else in this file would
   * notice if it went. The first three must stay 404: `fetch_authorization_metadata` reads
   * a non-200 as "try the next" but a 200 as final, and a 200 there would be checked
   * against an expected issuer of `<origin>/mcp` and fail the whole discovery rather than
   * falling through.
   */
  test("holds the fourth rung of the rmcp discovery ladder, which is the only one", async () => {
    const ladder = [
      { rung: "/.well-known/oauth-authorization-server/mcp", answers: false },
      { rung: "/.well-known/openid-configuration/mcp", answers: false },
      { rung: "/mcp/.well-known/openid-configuration", answers: false },
      { rung: "/.well-known/oauth-authorization-server", answers: true },
    ];

    for (const { rung, answers } of ladder) {
      const response = await fetch(`${base}${rung}`);
      const metadata = response.status === 200 ? await json(response) : {};
      expect({
        rung,
        answers: response.status === 200,
        issuer: metadata["issuer"] ?? null,
      }).toEqual({ rung, answers, issuer: answers ? base : null });
    }
  });

  /**
   * And the comparison that rung is then put through.
   *
   * Having refused the advertised string, the client has no issuer left to compare against
   * but the one it reverses out of the URL it just fetched -- `<origin>/`, because that is
   * what a URL type prints. rmcp accepts it through `issuer_identifiers_match`, which
   * strips a trailing slash from either side when what remains has an empty or root path.
   * A client that normalised nothing at all would reject this document, and MCP's
   * 2026-07-28 authorization revision does tell clients to compare issuers by simple
   * string comparison.
   *
   * The assertion is therefore the honest one: not that the two strings are equal, but
   * that they differ by exactly the root slash and by nothing else. If a later change
   * makes them differ by more, that leniency stops covering it.
   */
  test("differs from the reversed-out issuer by the root slash and nothing else", async () => {
    const discoveryUrl = `${base}/.well-known/oauth-authorization-server`;
    const metadata = await json(await fetch(discoveryUrl));

    // What a client reverses out of the URL it fetched: that URL minus the well-known
    // suffix, printed by a URL type.
    const reversed = new URL(discoveryUrl.replace("/.well-known/oauth-authorization-server", "/"))
      .href;
    const trimRootSlash = (issuer: string) =>
      new URL(issuer).pathname === "/" ? issuer.replace(/\/$/, "") : issuer;

    expect(reversed).toBe(`${base}/`);
    expect(trimRootSlash(reversed)).toBe(metadata["issuer"] as string);
  });
});

/**
 * The redirect URI the owner's client actually asked for, and why one registration
 * cannot cover it.
 *
 * The suffix is minted per session. `UW4qsoeKLHI2` is the one that appeared in his
 * logs; the next run invents another. Every assertion in this block is about that.
 */
const SESSION_REDIRECT = "http://127.0.0.1:33418/callback/UW4qsoeKLHI2";

async function openPairingWindow(): Promise<Response> {
  return fetch(`${base}${PAIRING_PATH}`, { method: "POST", headers: ownerHeaders() });
}

async function closePairingWindow(): Promise<Response> {
  return fetch(`${base}${PAIRING_PATH}`, { method: "DELETE", headers: ownerHeaders() });
}

/** An RFC 7591 registration exactly as the MCP SDK sends one: JSON, no credential. */
async function registerDynamically(metadata: Record<string, unknown>): Promise<Response> {
  return fetch(`${base}/api/auth/oauth2/register`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(metadata),
  });
}

function nativeClientMetadata(redirectUri: string, name: string): Record<string, unknown> {
  return {
    client_name: name,
    redirect_uris: [redirectUri],
    grant_types: ["authorization_code", "refresh_token"],
    response_types: ["code"],
    // Deliberately no `application_type`. No shipping MCP client sends one, and the
    // server has to cope with that rather than the other way round.
    token_endpoint_auth_method: "none",
  };
}

describe("the redirect URI a client actually asks for", () => {
  /**
   * Better Auth's matcher, proven rather than assumed.
   *
   * `findRegisteredRedirectUri` relaxes the *port* of a loopback redirect and nothing
   * else -- `reg.pathname === req.pathname` is in the condition. That is RFC 8252
   * section 7.3 read correctly: a native client cannot reserve a port, so the port is
   * unpredictable and the path is not. These two tests pin both halves, because the
   * first is the reason a hand-registered client fails and the second is the reason
   * the server must not be "fixed" to match paths loosely.
   */
  test("relaxes the port of a loopback redirect, as RFC 8252 section 7.3 allows", async () => {
    const clientId = await createOAuthClient("Port-varying client");
    const url = new URL(`${base}/api/auth/oauth2/authorize`);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", clientId);
    url.searchParams.set("redirect_uri", "http://127.0.0.1:55555/callback");
    url.searchParams.set("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("scope", "openid weight:read");

    const response = await fetch(url, { headers: { cookie }, redirect: "manual" });
    expect(response.status).toBe(302);
    expect(new URL(response.headers.get("location")!, base).pathname).toBe("/oauth-consent");
  });

  test("refuses a path the registration did not declare, which is what breaks the client", async () => {
    // Registered `/callback`, as `MCP.md` told the owner to. The client asks for
    // `/callback/UW4qsoeKLHI2`.
    const clientId = await createOAuthClient("Path-varying client");
    const url = new URL(`${base}/api/auth/oauth2/authorize`);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", clientId);
    url.searchParams.set("redirect_uri", SESSION_REDIRECT);
    url.searchParams.set("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("scope", "openid weight:read");

    const response = await fetch(url, { headers: { cookie }, redirect: "manual" });
    expect(response.status).toBe(302);
    // Not the consent page: the server error page, because an unregistered redirect
    // must never receive the error either (RFC 6749 section 4.1.2.1).
    const location = new URL(response.headers.get("location")!, base);
    expect(location.pathname).not.toBe("/oauth-consent");
    expect(location.searchParams.get("error")).toBe("invalid_redirect");
  });
});

describe("dynamic client registration behind a pairing window", () => {
  test("is closed by default, and refuses in the shape an OAuth client reads", async () => {
    expect(pairing.state().open).toBe(false);

    const response = await registerDynamically(
      nativeClientMetadata(SESSION_REDIRECT, "Uninvited client"),
    );
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toContain("Bearer");

    const body = await json(response);
    expect(body["error"]).toBe("invalid_token");
    // The refusal has to be actionable: the owner reading his client's log needs to
    // learn that a window exists, not that registration is "disabled".
    expect(body["error_description"]).toContain(PAIRING_PATH);
  });

  test("cannot be opened by anyone but the owner", async () => {
    const response = await fetch(`${base}${PAIRING_PATH}`, {
      method: "POST",
      headers: { "content-type": "application/json", origin: base },
    });
    expect(response.status).toBe(401);
    expect(pairing.state().open).toBe(false);
  });

  test("accepts the per-session loopback redirect once the owner opens it", async () => {
    const opened = await openPairingWindow();
    expect(opened.status).toBe(200);
    expect((await json(opened))["open"]).toBe(true);

    try {
      const response = await registerDynamically(
        nativeClientMetadata(SESSION_REDIRECT, "Dynamically registered client"),
      );
      expect(response.status).toBe(201);

      const registered = await json(response);
      expect(typeof registered["client_id"]).toBe("string");
      expect(registered["redirect_uris"]).toEqual([SESSION_REDIRECT]);

      // The row Better Auth stored, not just what it echoed back.
      const rows = await handle.database.db
        .select({ redirectUris: schema.oauthClient.redirectUris })
        .from(schema.oauthClient)
        .where(eq(schema.oauthClient.clientId, registered["client_id"] as string));
      expect(rows[0]?.redirectUris).toEqual([SESSION_REDIRECT]);
    } finally {
      await closePairingWindow();
    }
  });

  /**
   * Without the inference in `registration.ts` this is a 400 and every MCP client on
   * the planet stops here: Better Auth defaults a dynamic registration to
   * `application_type: "web"`, and a web client may not use an http loopback redirect.
   */
  test("reads an http loopback redirect as the native client it is", async () => {
    await openPairingWindow();
    try {
      const response = await registerDynamically(
        nativeClientMetadata("http://localhost:41234/oauth/callback/aBc123", "Loopback client"),
      );
      expect({ status: response.status, error: (await json(response))["error"] ?? null }).toEqual({
        status: 201,
        error: null,
      });
    } finally {
      await closePairingWindow();
    }
  });

  test("does not invent an application type for a redirect that is not loopback", async () => {
    await openPairingWindow();
    try {
      const response = await registerDynamically(
        nativeClientMetadata("http://192.168.1.50:8080/callback", "Off-host client"),
      );
      expect(response.status).toBe(400);
      expect((await json(response))["error"]).toBe("invalid_redirect_uri");
    } finally {
      await closePairingWindow();
    }
  });

  test("closes on DELETE, and the next registration is refused again", async () => {
    await openPairingWindow();
    const closed = await closePairingWindow();
    expect(closed.status).toBe(200);
    expect((await json(closed))["open"]).toBe(false);

    const response = await registerDynamically(
      nativeClientMetadata(SESSION_REDIRECT, "Late client"),
    );
    expect(response.status).toBe(401);
  });

  test("closes by itself, so a window left open does not stay open", () => {
    let now = 1_000_000;
    const window = createPairingWindow(() => now);

    expect(window.state().open).toBe(false);
    window.open(10);
    expect(window.state().open).toBe(true);

    now += 9 * 60_000;
    expect(window.state().open).toBe(true);

    now += 2 * 60_000;
    expect(window.state().open).toBe(false);
  });

  test("caps how long the owner can hold it open", () => {
    let now = 0;
    const window = createPairingWindow(() => now);
    window.open(60 * 24);

    now = MAX_PAIRING_MINUTES * 60_000 + 1;
    expect(window.state().open).toBe(false);
  });
});

/**
 * The whole thing, end to end, as the owner's client would do it: no configured
 * `client_id`, a redirect path minted for this session, and nothing driven by hand
 * except the two steps a human performs in a browser.
 */
describe("a client that registers itself", () => {
  test(
    "discovers, registers, authorizes and calls a tool",
    async () => {
      await openPairingWindow();

      let authorizationUrl: URL | undefined;
      let tokens: OAuthTokens | undefined;
      let clientInformation: OAuthClientInformation | undefined;
      let verifier = "";
      const sessionRedirect = `http://127.0.0.1:33418/callback/${crypto.randomUUID().slice(0, 12)}`;

      const provider: OAuthClientProvider = {
        get redirectUrl() {
          return sessionRedirect;
        },
        get clientMetadata(): OAuthClientMetadata {
          return {
            client_name: "Self-registering client",
            redirect_uris: [sessionRedirect],
            grant_types: ["authorization_code", "refresh_token"],
            response_types: ["code"],
            token_endpoint_auth_method: "none",
          };
        },
        clientInformation() {
          return clientInformation;
        },
        saveClientInformation(next) {
          clientInformation = next;
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
      const client = new Client({ name: "mue-self-registering", version: "0.0.0" });

      try {
        // The 401 on `/mcp` is what starts discovery, registration and authorization.
        await expect(client.connect(asTransport(transport))).rejects.toThrow();

        // Registration happened, unprompted, against the endpoint that did not exist.
        expect(clientInformation?.client_id).toBeDefined();

        // And the client built the URL the owner's client got a 404 on -- this time
        // pointing at the endpoint that answers.
        expect(authorizationUrl).toBeDefined();
        expect(`${authorizationUrl!.origin}${authorizationUrl!.pathname}`).toBe(
          `${base}/api/auth/oauth2/authorize`,
        );
        expect(authorizationUrl!.searchParams.get("redirect_uri")).toBe(sessionRedirect);
        expect(authorizationUrl!.searchParams.get("code_challenge_method")).toBe("S256");
        expect(authorizationUrl!.searchParams.get("resource")).toBe(`${base}/mcp`);

        const authorizeResponse = await fetch(authorizationUrl!.toString(), {
          headers: { cookie },
          redirect: "manual",
        });
        expect(authorizeResponse.status).toBe(302);

        const consentUrl = new URL(authorizeResponse.headers.get("location")!, base);
        expect(consentUrl.pathname).toBe("/oauth-consent");

        /**
         * The signed query repeats a parameter, and that is load-bearing.
         *
         * `signParams` calls `setSignedOAuthQueryParameterNames`, which *appends*
         * `ba_param` once per signed parameter name. `verifyOAuthQueryParams` re-signs
         * what comes back after sorting it, so ordering and percent-encoding are free --
         * both sides finish at `URLSearchParams.toString()` -- but the number of times a
         * key appears is part of the message.
         *
         * Which means anything standing between this 302 and the POST below that cannot
         * carry a repeated key breaks the authorization. Something did: TanStack Router
         * hands a page `stringifySearch(parseSearch(search))`, whose `encode` writes each
         * key once, and the consent page read its query from there. A real client got
         * `invalid_signature` under the Allow button and could not authorize at all.
         *
         * Asserted here, against the real server, because this file owns the server's
         * half of that contract; that the browser's router really does collapse the key
         * is proven with the router itself in `apps/platform/src/routes/consent.test.tsx`,
         * which `packages/api` cannot import.
         */
        const signedParameterNames = new URLSearchParams(consentUrl.search).getAll("ba_param");
        expect(signedParameterNames.length).toBeGreaterThan(1);

        const collapsed = new URLSearchParams(consentUrl.search);
        collapsed.set("ba_param", JSON.stringify(signedParameterNames));
        const refused = await fetch(`${base}/api/auth/oauth2/consent`, {
          method: "POST",
          headers: ownerHeaders(),
          body: JSON.stringify({
            accept: true,
            scope: "offline_access weight:read",
            oauth_query: collapsed.toString(),
          }),
        });
        expect(refused.status).toBe(400);
        expect(await json(refused)).toEqual({ error: "invalid_signature" });

        // And what the consent page hands back: the address bar, byte for byte.
        const consent = await fetch(`${base}/api/auth/oauth2/consent`, {
          method: "POST",
          headers: ownerHeaders(),
          body: JSON.stringify({
            accept: true,
            scope: "offline_access weight:read",
            oauth_query: consentUrl.search.replace(/^\?/, ""),
          }),
        });
        expect(consent.status).toBe(200);

        const redirect = new URL((await json(consent))["url"] as string);
        // The code comes back on the exact per-session path the client minted.
        expect(`${redirect.origin}${redirect.pathname}`).toBe(sessionRedirect);
        const code = redirect.searchParams.get("code");
        expect(code).not.toBeNull();

        await transport.finishAuth(code!);
        expect(tokens?.access_token).toBeDefined();
        expect(tokens?.refresh_token).toBeDefined();
      } finally {
        await transport.close();
        await closePairingWindow();
      }

      // The point of all of it: the token works on `/mcp`.
      const connected = await connect({ clientId: clientInformation!.client_id, tokens: tokens! });
      try {
        const listed = await connected.listTools();
        expect(listed.tools.map((tool) => tool.name)).toContain("mue.list_weight_measurements");
      } finally {
        await connected.close();
      }
    },
    OAUTH_TIMEOUT_MS,
  );
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
    // Twenty-eight from section 14.3 and its food list, plus PRD_FOOD 21.5's six reads and
    // its `plan_meal`/`unplan_meal` pair.
    expect(tools).toHaveLength(36);

    // Every tool PRD_FOOD 21.5 names, checked against the section rather than against a
    // count: a number can be made to match by deleting the wrong tool.
    for (const name of [
      "mue.list_food_logs",
      "mue.get_daily_nutrition",
      "mue.search_foods",
      "mue.get_recipe",
      "mue.list_recipes",
      "mue.list_meal_plan",
      "mue.plan_meal",
      "mue.unplan_meal",
    ]) {
      expect(tools.map((tool) => tool.name)).toContain(name);
    }

    // Section 14.1's four annotations, on all thirty-six, and section 14.6's rule that a
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

    // `unplan_meal` is a deletion whose name does not say so, which is exactly why the rule
    // above cannot be left to a name pattern.
    const unplan = tools.find((tool) => tool.name === "mue.unplan_meal");
    expect(unplan?.annotations?.destructiveHint).toBe(true);
  });

  test("declares every section 15.2 scope, including the one the food reads finally reach", async () => {
    // Section 15.2 lists nine scopes and `scopes.ts` declares all nine. Until PRD_FOOD 21.5's
    // read tools landed, eight were reachable and `nutrition:read` was not: nothing declared
    // it, so the 401 challenge never offered it, Better Auth refused to grant a scope that
    // had not been requested, and nobody could consent to an agent reading their food. This
    // test asserted that gap; it now asserts that there is none.
    const declared = new Set(MUE_TOOLS.flatMap((tool) => tool.scopes));
    const unused = MUE_SCOPES.filter((scope) => !declared.has(scope));
    expect(unused).toEqual([]);

    // And the scope is genuinely granted, not merely declared: `CATALOGUE_SCOPES` is derived
    // from the tools, so this agent asked for `nutrition:read` and the server issued it.
    expect(agent.tokens.scope).toContain("nutrition:read");
    expect(agent.tokens.scope).toContain("data:delete");
    expect(agent.tokens.scope).toContain("nutrition:write");

    // Every food read declares it, and no food read asks for a write.
    for (const tool of MUE_TOOLS.filter((candidate) =>
      [
        "mue.list_food_logs",
        "mue.get_daily_nutrition",
        "mue.search_foods",
        "mue.get_recipe",
        "mue.list_recipes",
        "mue.list_meal_plan",
      ].includes(candidate.name),
    )) {
      expect({ name: tool.name, scopes: [...tool.scopes] }).toEqual({
        name: tool.name,
        scopes: ["nutrition:read"],
      });
    }
  });

  test("a name no tool has is refused, which is what each of these looked like yesterday", async () => {
    // Every tool in this file failed exactly like this before it existed, and the eight named
    // above were seen to fail exactly like this on `main` before they were written -- the same
    // assertion, run against the same client, over the same nine names.
    //
    // Only the name that will never exist is left here. The second slot used to hold
    // `mue.list_food_logs`, asserting the read gap; the gap is closed, and asserting it now
    // would mean deleting a tool to keep a test green.
    for (const name of ["mue.definitely_not_a_tool"]) {
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
    // PRD_FOOD 22, in its own words: "une pomme à dix heures tombe en collation" — and with six
    // moments the collation it falls in is the one that follows breakfast, not the catch-all.
    const morning = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-06-10",
      consumedAt: "10:00",
      title: "Pomme",
      energyKcal: 72,
    })) as { entry: { id: string; slot: string; estimation: string }; slotWasDeduced: boolean };

    expect(morning.entry.slot).toBe("morning_snack");
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
    expect(row.slot).toBe("morning_snack");
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

  // --- PRD_FOOD 21.5, the six reads and the meal plan -------------------------------------------

  /**
   * The day these tests total, kept apart from the dates the write tests use, so that adding
   * a line over there cannot silently change a total asserted over here.
   */
  const READ_DAY = "2026-05-20";
  /** A day nothing was ever written on. */
  const EMPTY_DAY = "2026-05-25";

  /** The lines `get_daily_nutrition` is asserted against, by identifier. */
  let breakfastLineId = "";
  let unmeasuredLineId = "";
  let dinnerLineId = "";

  /**
   * A local calendar day, offset from today, computed as the server computes its own.
   *
   * `plan_meal`'s window moves with the clock, so these dates cannot be literals the way the
   * journal's can: a test that hardcoded a plannable date would pass until sixty days after
   * it was written and then fail for a reason having nothing to do with the code.
   */
  function localDatePlus(days: number): string {
    const now = new Date();
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + days);
    const month = String(day.getMonth() + 1).padStart(2, "0");
    return `${day.getFullYear()}-${month}-${String(day.getDate()).padStart(2, "0")}`;
  }

  /** One computed nutrient, as `nutrition-view.ts` puts it on the wire. */
  interface ComputedEnergy {
    known: boolean;
    milliKcal: number | null;
    kcal: number | null;
    display: string;
    unknownFrom: string[];
  }
  interface ComputedMacro {
    known: boolean;
    milligrams: number | null;
    grams: number | null;
    display: string;
    unknownFrom: string[];
  }
  interface ComputedNutrients {
    energy: ComputedEnergy;
    protein: ComputedMacro;
    carbs: ComputedMacro;
    fat: ComputedMacro;
    fibre: ComputedMacro;
  }

  test("list_food_logs reads back what was written, and filters by moment", async () => {
    const breakfast = (await callOk(client, "mue.create_food_log", {
      consumedOn: READ_DAY,
      consumedAt: "08:00",
      title: "Porridge",
      energyKcal: 310,
      proteinGrams: 9,
    })) as { entry: { id: string } };
    breakfastLineId = breakfast.entry.id;

    const lunch = (await callOk(client, "mue.create_food_log", {
      consumedOn: READ_DAY,
      consumedAt: "12:30",
      title: "Soupe du traiteur",
      energyKcal: 180,
      // No protein: the traiteur does not say and the person did not weigh it. This is the
      // line the whole "unknown is not zero" case below turns on.
    })) as { entry: { id: string } };
    unmeasuredLineId = lunch.entry.id;

    const dinner = (await callOk(client, "mue.create_food_log", {
      consumedOn: READ_DAY,
      consumedAt: "19:00",
      title: "Riz et poulet",
      energyKcal: 400,
      proteinGrams: 8,
    })) as { entry: { id: string } };
    dinnerLineId = dinner.entry.id;

    const day = (await callOk(client, "mue.list_food_logs", {
      from: READ_DAY,
      to: READ_DAY,
    })) as { entries: { id: string }[]; hasMore: boolean };

    // Newest first, which is the order the tool advertises.
    expect(day.entries.map((entry) => entry.id)).toEqual([
      dinnerLineId,
      unmeasuredLineId,
      breakfastLineId,
    ]);
    expect(day.hasMore).toBe(false);

    // Asserted against the rows and not against the envelope: the tool is only correct if
    // what it returned is what PostgreSQL holds.
    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(
          eq(schema.foodLogEntries.userId, userId),
          eq(schema.foodLogEntries.consumedOn, READ_DAY),
        ),
      );
    expect(rows).toHaveLength(3);
    const stored = new Map(rows.map((row) => [row.id, row]));
    expect(stored.get(breakfastLineId)!.energyMilliKcal).toBe(310_000);
    expect(stored.get(breakfastLineId)!.proteinMilligrams).toBe(9_000);
    // PRD_FOOD 13.1: the protein nobody stated is NULL in the column, not 0.
    expect(stored.get(unmeasuredLineId)!.proteinMilligrams).toBeNull();
    expect(stored.get(unmeasuredLineId)!.energyMilliKcal).toBe(180_000);

    // And the moment filter selects on the moment the server deduced from the clock.
    const lunchOnly = (await callOk(client, "mue.list_food_logs", {
      from: READ_DAY,
      to: READ_DAY,
      slot: "lunch",
    })) as { entries: { id: string }[] };
    expect(lunchOnly.entries.map((entry) => entry.id)).toEqual([unmeasuredLineId]);
  });

  test("list_food_logs pages by day, clock and identifier without repeating a line", async () => {
    const seen: string[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < 5; page += 1) {
      const data = (await callOk(client, "mue.list_food_logs", {
        from: READ_DAY,
        to: READ_DAY,
        limit: 1,
        ...(cursor === undefined ? {} : { cursor }),
      })) as { entries: { id: string }[]; nextCursor: string | null; hasMore: boolean };
      seen.push(...data.entries.map((entry) => entry.id));
      if (!data.hasMore) break;
      cursor = data.nextCursor as string;
    }
    // Three lines, one per page, each once. A keyset on the day alone would have returned the
    // same line three times over.
    expect(seen).toEqual([dinnerLineId, unmeasuredLineId, breakfastLineId]);
    expect(new Set(seen).size).toBe(3);
  });

  test("get_daily_nutrition reads a day whose protein nobody measured as unknown, not as zero", async () => {
    const data = (await callOk(client, "mue.get_daily_nutrition", { date: READ_DAY })) as {
      isRecorded: boolean;
      entryCount: number;
      totals: ComputedNutrients;
      slots: { slot: string; entryCount: number; totals: ComputedNutrients }[];
      entries: { id: string }[];
      provenance: {
        computedBy: string;
        method: string;
        rule: string;
        approximate: boolean;
        contributionCount: number;
      };
    };

    expect(data.isRecorded).toBe(true);
    expect(data.entryCount).toBe(3);

    // The energy is known, because all three lines carry one: 310 + 180 + 400.
    expect(data.totals.energy.known).toBe(true);
    expect(data.totals.energy.milliKcal).toBe(890_000);
    expect(data.totals.energy.display).toBe("≈ 890 kcal");
    expect(data.totals.energy.unknownFrom).toEqual([]);

    // The protein is not. One line of three has none, so PRD_FOOD 13.1 makes the day's
    // protein unknown -- and this is the assertion the whole module rests on: the value is
    // null, and it is *not* 17 000, which dropping the unknown term would have given.
    expect(data.totals.protein.known).toBe(false);
    expect(data.totals.protein.milligrams).toBeNull();
    expect(data.totals.protein.grams).toBeNull();
    expect(data.totals.protein.milligrams).not.toBe(0);
    expect(data.totals.protein.milligrams).not.toBe(17_000);
    // PRD_FOOD 13.2: an unknown value is a dash, never a zero.
    expect(data.totals.protein.display).toBe("—");
    // And the day says which line left it unknown, so a person can be told what to complete.
    expect(data.totals.protein.unknownFrom).toEqual([unmeasuredLineId]);

    // Metric by metric: a known energy coexists with unknown protein, carbs, fat and fibre.
    for (const metric of ["carbs", "fat", "fibre"] as const) {
      expect({ metric, known: data.totals[metric].known }).toEqual({ metric, known: false });
      expect({ metric, value: data.totals[metric].milligrams }).toEqual({ metric, value: null });
      expect({ metric, display: data.totals[metric].display }).toEqual({ metric, display: "—" });
    }

    // Only the moments that hold a line, in the contract's own order.
    expect(data.slots.map((slot) => slot.slot)).toEqual(["breakfast", "lunch", "dinner"]);
    const lunch = data.slots.find((slot) => slot.slot === "lunch")!;
    expect(lunch.totals.energy.milliKcal).toBe(180_000);
    expect(lunch.totals.protein.known).toBe(false);
    const breakfast = data.slots.find((slot) => slot.slot === "breakfast")!;
    // A moment whose lines are all measured keeps a known total, unaffected by the lunch.
    expect(breakfast.totals.protein.known).toBe(true);
    expect(breakfast.totals.protein.milligrams).toBe(9_000);
    expect(breakfast.totals.protein.display).toBe("≈ 9.0 g");

    // Every line it added up, ordered by the clock (PRD_FOOD 22).
    expect(data.entries.map((entry) => entry.id)).toEqual([
      breakfastLineId,
      unmeasuredLineId,
      dinnerLineId,
    ]);

    // PRD_FOOD 21.5: a computed value keeps its provenance and its method.
    expect(data.provenance.computedBy).toBe("server");
    expect(data.provenance.method).toBe("strictSum");
    expect(data.provenance.rule).toBe("PRD_FOOD 13.1");
    expect(data.provenance.approximate).toBe(true);
    expect(data.provenance.contributionCount).toBe(3);
  });

  test("get_daily_nutrition on a day nobody wrote on says so rather than reporting a zero", async () => {
    const data = (await callOk(client, "mue.get_daily_nutrition", { date: EMPTY_DAY })) as {
      isRecorded: boolean;
      entryCount: number;
      totals: ComputedNutrients;
      slots: unknown[];
      provenance: { contributionCount: number };
    };

    // The strict sum of no lines is a *known* zero -- `Nutrients.ZERO`, exactly what Android
    // computes. What stops it being read as "they ate nothing" is not the total: it is these
    // three fields, and PRD_FOOD 10.4 is why they are here.
    expect(data.isRecorded).toBe(false);
    expect(data.entryCount).toBe(0);
    expect(data.provenance.contributionCount).toBe(0);
    expect(data.slots).toEqual([]);
    expect(data.totals.energy.known).toBe(true);
    expect(data.totals.energy.milliKcal).toBe(0);

    // The tool's own description carries the instruction, because this distinction is one an
    // agent has to be told in words rather than left to infer from a boolean.
    const { tools } = await client.listTools();
    const daily = tools.find((tool) => tool.name === "mue.get_daily_nutrition")!;
    expect(daily.description).toContain("nothing was written down, not that nothing was eaten");
  });

  test("get_daily_nutrition refuses to assume today, and names the field", async () => {
    const error = await callError(client, "mue.get_daily_nutrition", {});
    expect(error.code).toBe("sync.missing_required_field");
    expect(error.field).toBe("date");
  });

  test("search_foods finds a food by name, brand and barcode, and says what it did not search", async () => {
    const created = (await callOk(client, "mue.create_food", {
      name: "Galettes de sarrasin",
      brand: "Paysan Breton",
      barcode: "3256540000117",
      energyKcalPer100: 0,
    })) as { food: { id: string } };
    const galetteId = created.food.id;

    const byName = (await callOk(client, "mue.search_foods", { search: "SARRASIN" })) as {
      foods: { id: string; fatGramsPer100: number | null; energyKcalPer100: number | null }[];
      catalogue: { ciqualSearchable: boolean; searched: string[] };
    };
    // Case is folded; the accent question is answered by `matchedOn` rather than pretended at.
    expect(byName.foods.map((food) => food.id)).toContain(galetteId);
    // PRD_FOOD 21.1: the Ciqual catalogue is not on this server, and the tool says so rather
    // than letting an empty result be read as "the person has no such food".
    expect(byName.catalogue.ciqualSearchable).toBe(false);
    expect(byName.catalogue.searched).toEqual(["custom", "open_food_facts"]);

    const byBrand = (await callOk(client, "mue.search_foods", { search: "paysan" })) as {
      foods: { id: string }[];
    };
    expect(byBrand.foods.map((food) => food.id)).toContain(galetteId);

    const byBarcode = (await callOk(client, "mue.search_foods", {
      barcode: "3256540000117",
    })) as { foods: { id: string }[] };
    expect(byBarcode.foods).toHaveLength(1);
    expect(byBarcode.foods[0]!.id).toBe(galetteId);

    // The stored row is what the search returned, and its unknown nutrients are still NULL.
    const rows = await handle.database.db
      .select()
      .from(schema.foods)
      .where(and(eq(schema.foods.userId, userId), eq(schema.foods.id, galetteId)));
    expect(rows[0]!.barcode).toBe("3256540000117");
    expect(rows[0]!.brand).toBe("Paysan Breton");
    // A *stated* zero energy is stored as a zero and a never-stated fat as NULL. The read
    // hands back the same distinction: 0 and null, and never both as 0.
    expect(rows[0]!.energyMilliKcal).toBe(0);
    expect(rows[0]!.fatMilligrams).toBeNull();
    const found = byName.foods.find((food) => food.id === galetteId)!;
    expect(found.energyKcalPer100).toBe(0);
    expect(found.fatGramsPer100).toBeNull();
  });

  test("search_foods with a source filter searches only that source", async () => {
    const off = (await callOk(client, "mue.search_foods", { source: "open_food_facts" })) as {
      foods: unknown[];
      catalogue: { searched: string[] };
    };
    expect(off.catalogue.searched).toEqual(["open_food_facts"]);
    // Every food an agent can create is `custom`, so this genuinely holds nothing.
    expect(off.foods).toEqual([]);
  });

  test("search_foods refuses a blank search rather than matching everything", async () => {
    const error = await callError(client, "mue.search_foods", { search: "   " });
    expect(error.code).toBe("sync.invalid_payload");
    expect(error.field).toBe("search");
  });

  test("get_recipe computes per-serving values from the ingredients, with their provenance", async () => {
    const data = (await callOk(client, "mue.get_recipe", { id: recipeId })) as {
      recipe: { id: string; name: string; baseServings: number };
      nutrition: {
        perServing: ComputedNutrients;
        wholeRecipe: ComputedNutrients;
        unresolvedIngredientIds: string[];
        provenance: {
          method: string;
          rule: string;
          approximate: boolean;
          contributionCount: number;
        };
      };
    };

    expect(data.recipe.id).toBe(recipeId);
    expect(data.recipe.name).toBe("Skyr bowl");
    expect(data.recipe.baseServings).toBe(4);

    // PRD_FOOD 13.1, worked through by hand: 250 g of a food at 63 kcal and 10.5 g of protein
    // per 100 g. Whole recipe: 63.000 x 2.5 = 157.500 kcal, 10.500 x 2.5 = 26.250 g.
    expect(data.nutrition.wholeRecipe.energy.milliKcal).toBe(157_500);
    expect(data.nutrition.wholeRecipe.protein.milligrams).toBe(26_250);
    // Per serving, for four: 157 500 / 4 = 39 375, and 26 250 / 4 = 6 562.5 rounded half-up.
    expect(data.nutrition.perServing.energy.milliKcal).toBe(39_375);
    expect(data.nutrition.perServing.protein.milligrams).toBe(6_563);
    // PRD_FOOD 13.2's rounding and its marker, in one string.
    expect(data.nutrition.perServing.energy.display).toBe("≈ 39 kcal");
    expect(data.nutrition.perServing.protein.display).toBe("≈ 6.6 g");

    // The food states no carbohydrate, so the recipe's is unknown -- not zero, and not left
    // out of the answer.
    expect(data.nutrition.perServing.carbs.known).toBe(false);
    expect(data.nutrition.perServing.carbs.milligrams).toBeNull();
    expect(data.nutrition.perServing.carbs.display).toBe("—");

    expect(data.nutrition.unresolvedIngredientIds).toEqual([]);
    expect(data.nutrition.provenance.method).toBe("strictSum");
    expect(data.nutrition.provenance.rule).toBe("PRD_FOOD 13.1");
    expect(data.nutrition.provenance.approximate).toBe(true);
    expect(data.nutrition.provenance.contributionCount).toBe(1);

    // Nothing was stored: PRD_FOOD 13.1 derives a recipe's values every time it is read.
    const rows = await handle.database.db
      .select()
      .from(schema.recipes)
      .where(and(eq(schema.recipes.userId, userId), eq(schema.recipes.id, recipeId)));
    expect(Object.keys(rows[0]!)).not.toContain("energyMilliKcal");
  });

  test("get_recipe reports a recipe whose food is gone as unknown, never as lighter", async () => {
    const doomed = (await callOk(client, "mue.create_food", {
      name: "Farine de chataigne",
      energyKcalPer100: 370,
    })) as { food: { id: string } };
    const orphan = (await callOk(client, "mue.create_recipe", {
      name: "Galette de chataigne",
      type: "snack",
      baseServings: 2,
      ingredients: [{ foodId: doomed.food.id, quantity: 100 }],
    })) as { recipe: { id: string; ingredients: { id: string }[] } };

    const before = (await callOk(client, "mue.get_recipe", { id: orphan.recipe.id })) as {
      nutrition: { wholeRecipe: ComputedNutrients };
    };
    expect(before.nutrition.wholeRecipe.energy.milliKcal).toBe(370_000);

    await callOk(client, "mue.delete_food", { id: doomed.food.id });

    const after = (await callOk(client, "mue.get_recipe", { id: orphan.recipe.id })) as {
      nutrition: {
        wholeRecipe: ComputedNutrients;
        perServing: ComputedNutrients;
        unresolvedIngredientIds: string[];
      };
    };
    // The worst available answer would be 0; the second worst would be to drop the ingredient
    // and report a lighter recipe. It is neither: it is unknown, and the ingredient
    // responsible is named.
    expect(after.nutrition.wholeRecipe.energy.known).toBe(false);
    expect(after.nutrition.wholeRecipe.energy.milliKcal).toBeNull();
    expect(after.nutrition.wholeRecipe.energy.display).toBe("—");
    expect(after.nutrition.perServing.energy.known).toBe(false);
    expect(after.nutrition.unresolvedIngredientIds).toEqual([orphan.recipe.ingredients[0]!.id]);
    expect(after.nutrition.wholeRecipe.energy.unknownFrom).toEqual([
      orphan.recipe.ingredients[0]!.id,
    ]);
  });

  test("get_recipe refuses an identifier this account does not hold", async () => {
    const missing = crypto.randomUUID();
    const error = await callError(client, "mue.get_recipe", { id: missing });
    expect(error.code).toBe("http.not_found");
    expect(error.aggregateType).toBe("recipe");
    expect(error.aggregateId).toBe(missing);
  });

  test("list_recipes filters by type and by favourite, and carries the computed values", async () => {
    const breakfasts = (await callOk(client, "mue.list_recipes", { type: "breakfast" })) as {
      recipes: {
        recipe: { id: string; type: string };
        nutrition: { perServing: ComputedNutrients };
      }[];
    };
    expect(breakfasts.recipes.map((entry) => entry.recipe.id)).toContain(recipeId);
    for (const entry of breakfasts.recipes) expect(entry.recipe.type).toBe("breakfast");
    const skyr = breakfasts.recipes.find((entry) => entry.recipe.id === recipeId)!;
    // The same arithmetic as `get_recipe`, because it is the same function.
    expect(skyr.nutrition.perServing.energy.milliKcal).toBe(39_375);

    const favourites = (await callOk(client, "mue.list_recipes", { favouritesOnly: true })) as {
      recipes: unknown[];
    };
    expect(favourites.recipes).toEqual([]);

    await callOk(client, "mue.update_recipe", { id: recipeId, isFavourite: true });
    const nowFavourite = (await callOk(client, "mue.list_recipes", { favouritesOnly: true })) as {
      recipes: { recipe: { id: string } }[];
    };
    expect(nowFavourite.recipes.map((entry) => entry.recipe.id)).toEqual([recipeId]);

    // And the flag really moved in the row, not only in the answer.
    const rows = await handle.database.db
      .select({ isFavourite: schema.recipes.isFavourite })
      .from(schema.recipes)
      .where(and(eq(schema.recipes.userId, userId), eq(schema.recipes.id, recipeId)));
    expect(rows[0]!.isFavourite).toBe(true);
  });

  test("plan_meal writes a proposal at its (date, moment) identity", async () => {
    const plannedOn = localDatePlus(3);
    const data = (await callOk(client, "mue.plan_meal", {
      plannedOn,
      slot: "dinner",
      recipeId,
      servings: 1.5,
    })) as {
      entry: { aggregateId: string; recipeName: string; plannedServingsThousandths: number };
      created: boolean;
      replaced: boolean;
    };

    expect(data.created).toBe(true);
    expect(data.replaced).toBe(false);
    // The separator is a colon: `aggregateIdSchema`'s alphabet has no `/`.
    expect(data.entry.aggregateId).toBe(`${plannedOn}:dinner`);
    expect(data.entry.recipeName).toBe("Skyr bowl");
    expect(data.entry.plannedServingsThousandths).toBe(1_500);

    const rows = await handle.database.db
      .select()
      .from(schema.mealPlanEntries)
      .where(
        and(
          eq(schema.mealPlanEntries.userId, userId),
          eq(schema.mealPlanEntries.plannedOn, plannedOn),
        ),
      );
    expect(rows).toHaveLength(1);
    expect(rows[0]!.slot).toBe("dinner");
    expect(rows[0]!.recipeId).toBe(recipeId);
    // Thousandths of a serving, the integer the domain stores. Never a float.
    expect(rows[0]!.plannedServingsThousandths).toBe(1_500);
    expect(rows[0]!.consumedLogEntryId).toBeNull();
    expect(rows[0]!.originType).toBe("agent");
  });

  test("replaying plan_meal with one key produces one row and one journal entry, not two", async () => {
    const plannedOn = localDatePlus(5);
    const aggregateId = `${plannedOn}:lunch`;
    const args = {
      plannedOn,
      slot: "lunch",
      recipeId,
      servings: 1,
      idempotencyKey: crypto.randomUUID(),
    };

    const first = (await callOk(client, "mue.plan_meal", args)) as {
      created: boolean;
      mutationId: string;
      entry: { revision: string };
    };
    const second = (await callOk(client, "mue.plan_meal", args)) as {
      created: boolean;
      mutationId: string;
      entry: { revision: string };
    };

    expect(first.created).toBe(true);
    // The replay is recognised, not repeated.
    expect(second.created).toBe(false);
    expect(second.mutationId).toBe(first.mutationId);
    // The revision did not move, which is the part a row count cannot prove: this aggregate
    // is keyed by (date, moment), so a second *distinct* mutation would also have left one
    // row -- it would simply have overwritten it, and taken a revision doing so.
    expect(second.entry.revision).toBe(first.entry.revision);

    const rows = await handle.database.db
      .select()
      .from(schema.mealPlanEntries)
      .where(
        and(
          eq(schema.mealPlanEntries.userId, userId),
          eq(schema.mealPlanEntries.plannedOn, plannedOn),
        ),
      );
    expect(rows).toHaveLength(1);
    expect(rows[0]!.lastMutationId).toBe(first.mutationId);

    // One change reached the journal, so one change reaches the phone.
    const journal = await handle.database.db
      .select({ sequence: schema.syncJournal.sequence })
      .from(schema.syncJournal)
      .where(
        and(
          eq(schema.syncJournal.userId, userId),
          eq(schema.syncJournal.aggregateType, "mealPlanEntry"),
          eq(schema.syncJournal.aggregateId, aggregateId),
        ),
      );
    expect(journal).toHaveLength(1);

    // Both calls are nevertheless audited: section 14.7 records what an agent asked for, and
    // a replay is something it asked for.
    const audit = await handle.database.db
      .select()
      .from(schema.agentAudit)
      .where(
        and(
          eq(schema.agentAudit.agentId, agent.clientId),
          eq(schema.agentAudit.toolName, "mue.plan_meal"),
          eq(schema.agentAudit.mutationId, first.mutationId),
        ),
      );
    expect(audit).toHaveLength(2);
    for (const row of audit) expect(row.result).toBe("ok");
  });

  test("plan_meal refuses a date in the past and one beyond sixty days, naming the field", async () => {
    const past = await callError(client, "mue.plan_meal", {
      plannedOn: localDatePlus(-1),
      slot: "dinner",
      recipeId,
      servings: 1,
    });
    expect(past.code).toBe("sync.invalid_payload");
    expect(past.field).toBe("plannedOn");

    // PRD_FOOD 15: "aujourd'hui ou dans le futur, dans les 60 jours". Sixty is inside.
    const edge = (await callOk(client, "mue.plan_meal", {
      plannedOn: localDatePlus(60),
      slot: "breakfast",
      recipeId,
      servings: 1,
    })) as { created: boolean };
    expect(edge.created).toBe(true);

    const beyond = await callError(client, "mue.plan_meal", {
      plannedOn: localDatePlus(61),
      slot: "breakfast",
      recipeId,
      servings: 1,
    });
    expect(beyond.code).toBe("sync.invalid_payload");
    expect(beyond.field).toBe("plannedOn");
    // Section 16: the message names the field, never the value.
    expect(beyond.message).not.toContain(localDatePlus(61));

    // And nothing was written for the day that was refused.
    const rows = await handle.database.db
      .select()
      .from(schema.mealPlanEntries)
      .where(
        and(
          eq(schema.mealPlanEntries.userId, userId),
          eq(schema.mealPlanEntries.plannedOn, localDatePlus(61)),
        ),
      );
    expect(rows).toEqual([]);
  });

  test("plan_meal refuses a serving count off its quarter step and an unknown recipe", async () => {
    const stepped = await callError(client, "mue.plan_meal", {
      plannedOn: localDatePlus(7),
      slot: "dinner",
      recipeId,
      servings: 1.3,
    });
    expect(stepped.field).toBe("servings");

    const missing = await callError(client, "mue.plan_meal", {
      plannedOn: localDatePlus(7),
      slot: "dinner",
      recipeId: crypto.randomUUID(),
      servings: 1,
    });
    expect(missing.code).toBe("http.not_found");
    expect(missing.aggregateType).toBe("recipe");

    // A refusal is audited too, with no aggregate and no revision.
    const audit = await handle.database.db
      .select()
      .from(schema.agentAudit)
      .where(
        and(
          eq(schema.agentAudit.agentId, agent.clientId),
          eq(schema.agentAudit.toolName, "mue.plan_meal"),
          eq(schema.agentAudit.result, "error"),
        ),
      );
    expect(audit.length).toBeGreaterThanOrEqual(2);
    for (const row of audit) {
      expect(row.aggregates).toEqual([]);
      expect(row.revision).toBeNull();
    }
  });

  test("plan_meal a second time on one moment replaces the proposal rather than duplicating it", async () => {
    const plannedOn = localDatePlus(9);
    const other = (await callOk(client, "mue.create_recipe", {
      name: "Soupe de courge",
      type: "main",
      baseServings: 2,
      ingredients: [{ foodId, quantity: 200 }],
    })) as { recipe: { id: string } };

    await callOk(client, "mue.plan_meal", { plannedOn, slot: "dinner", recipeId, servings: 1 });
    const swapped = (await callOk(client, "mue.plan_meal", {
      plannedOn,
      slot: "dinner",
      recipeId: other.recipe.id,
      servings: 2,
    })) as { replaced: boolean; replacedRecipeId: string | null; entry: { recipeId: string } };

    // PRD_FOOD 21.3: "la precedente est remplacee, jamais dupliquee".
    expect(swapped.replaced).toBe(true);
    expect(swapped.replacedRecipeId).toBe(recipeId);
    expect(swapped.entry.recipeId).toBe(other.recipe.id);

    const rows = await handle.database.db
      .select()
      .from(schema.mealPlanEntries)
      .where(
        and(
          eq(schema.mealPlanEntries.userId, userId),
          eq(schema.mealPlanEntries.plannedOn, plannedOn),
        ),
      );
    expect(rows).toHaveLength(1);
    expect(rows[0]!.recipeId).toBe(other.recipe.id);
    expect(rows[0]!.plannedServingsThousandths).toBe(2_000);
  });

  test("list_meal_plan reads the proposals back over a period, with their recipe names", async () => {
    const data = (await callOk(client, "mue.list_meal_plan", {
      from: localDatePlus(0),
      to: localDatePlus(60),
    })) as {
      entries: {
        aggregateId: string;
        plannedOn: string;
        recipeName: string | null;
        plannedServings: number;
        isConsumed: boolean;
      }[];
    };

    const byId = new Map(data.entries.map((entry) => [entry.aggregateId, entry]));
    const dinner = byId.get(`${localDatePlus(3)}:dinner`)!;
    expect(dinner.recipeName).toBe("Skyr bowl");
    expect(dinner.plannedServings).toBe(1.5);
    // PRD_FOOD 12: a proposal enters no total until it has been confirmed.
    expect(dinner.isConsumed).toBe(false);

    // Earliest first, which is how a plan is read.
    const dates = data.entries.map((entry) => entry.plannedOn);
    expect([...dates].sort()).toEqual(dates);

    // And the answer is exactly the live rows PostgreSQL holds.
    const rows = await handle.database.db
      .select()
      .from(schema.mealPlanEntries)
      .where(
        and(eq(schema.mealPlanEntries.userId, userId), isNull(schema.mealPlanEntries.deletedAt)),
      );
    expect(data.entries).toHaveLength(rows.length);
  });

  test("unplan_meal tombstones the proposal and touches neither the recipe nor the journal", async () => {
    const plannedOn = localDatePlus(3);
    const linesBefore = await handle.database.db
      .select({ id: schema.foodLogEntries.id })
      .from(schema.foodLogEntries)
      .where(eq(schema.foodLogEntries.userId, userId));

    const data = (await callOk(client, "mue.unplan_meal", { plannedOn, slot: "dinner" })) as {
      aggregateId: string;
      deleted: boolean;
    };
    expect(data.aggregateId).toBe(`${plannedOn}:dinner`);
    expect(data.deleted).toBe(true);

    const rows = await handle.database.db
      .select()
      .from(schema.mealPlanEntries)
      .where(
        and(
          eq(schema.mealPlanEntries.userId, userId),
          eq(schema.mealPlanEntries.plannedOn, plannedOn),
        ),
      );
    // A tombstone, not an erasure: FR-SYNC-005 needs the row to stay so the deletion travels.
    expect(rows).toHaveLength(1);
    expect(rows[0]!.deletedAt).not.toBeNull();

    // And it is gone from the read.
    const listed = (await callOk(client, "mue.list_meal_plan", {
      from: plannedOn,
      to: plannedOn,
    })) as { entries: unknown[] };
    expect(listed.entries).toEqual([]);

    // PRD_FOOD 12: "ne touche ni la recette ni le journal".
    const recipeRows = await handle.database.db
      .select({ deletedAt: schema.recipes.deletedAt })
      .from(schema.recipes)
      .where(and(eq(schema.recipes.userId, userId), eq(schema.recipes.id, recipeId)));
    expect(recipeRows[0]!.deletedAt).toBeNull();
    const linesAfter = await handle.database.db
      .select({ id: schema.foodLogEntries.id })
      .from(schema.foodLogEntries)
      .where(eq(schema.foodLogEntries.userId, userId));
    expect(linesAfter).toHaveLength(linesBefore.length);
  });

  test("unplan_meal refuses a moment that holds no proposal, naming the record", async () => {
    const error = await callError(client, "mue.unplan_meal", {
      plannedOn: localDatePlus(11),
      slot: "snack",
    });
    expect(error.code).toBe("http.not_found");
    expect(error.aggregateType).toBe("mealPlanEntry");
    expect(error.aggregateId).toBe(`${localDatePlus(11)}:snack`);
  });

  // --- F-01: a serving of a recipe carries its nutrition into the day -------------------------

  /**
   * The whole of the reported defect, driven exactly as the agent that found it drove it:
   * three foods, a two-serving recipe worth 969 kcal, one serving logged, then read back.
   *
   * PRD_FOOD 10.2 makes a `recipe` line one of the three forms a journal line takes, and
   * PRD_FOOD 21.2 makes that line self-contained -- *it* carries the numbers, not the recipe.
   * Which leaves one question the tool has to answer and used not to: where do the numbers
   * come from when the caller states a recipe and a serving count and nothing else? An agent
   * cannot compute them, because PRD_FOOD 13.1 is the server's rule and the agent does not
   * hold the foods. So the server resolves the recipe itself, through `@mue/domain`, or the
   * line is written empty and the day's totals are unknown for ever.
   *
   * The arithmetic is stated here in whole integers on purpose. One serving of two is a
   * *half*, and 484.5 kcal is the value that a serving count handled as an integer would
   * round away.
   */
  test("create_food_log resolves a recipe serving into the line's own snapshot (F-01)", async () => {
    const F01_DAY = "2026-05-27";

    // 350 kcal/100 g, and 180 g of it: 630 kcal.
    const rice = (await callOk(client, "mue.create_food", {
      name: "Riz basmati cru",
      energyKcalPer100: 350,
      proteinGramsPer100: 7.5,
      carbsGramsPer100: 78,
      fatGramsPer100: 0.9,
      fibreGramsPer100: 1.4,
    })) as { food: { id: string } };
    // 165 kcal/100 g, and 150 g of it: 247.5 kcal.
    const chicken = (await callOk(client, "mue.create_food", {
      name: "Blanc de poulet",
      energyKcalPer100: 165,
      proteinGramsPer100: 31,
      carbsGramsPer100: 0,
      fatGramsPer100: 3.6,
      fibreGramsPer100: 0,
    })) as { food: { id: string } };
    // 61 kcal/100 g, and 150 g of it: 91.5 kcal.
    const yoghurt = (await callOk(client, "mue.create_food", {
      name: "Yaourt nature",
      energyKcalPer100: 61,
      proteinGramsPer100: 3.5,
      carbsGramsPer100: 4.7,
      fatGramsPer100: 3.3,
      fibreGramsPer100: 0,
    })) as { food: { id: string } };

    const created = (await callOk(client, "mue.create_recipe", {
      name: "Poulet riz yaourt",
      type: "main",
      baseServings: 2,
      ingredients: [
        { foodId: rice.food.id, quantity: 180 },
        { foodId: chicken.food.id, quantity: 150 },
        { foodId: yoghurt.food.id, quantity: 150 },
      ],
    })) as { recipe: { id: string } };
    const dishId = created.recipe.id;

    // Step 3 of the report: the recipe knows what it is worth. 630 + 247.5 + 91.5 = 969.
    const dish = (await callOk(client, "mue.get_recipe", { id: dishId })) as {
      nutrition: { perServing: ComputedNutrients; wholeRecipe: ComputedNutrients };
    };
    expect(dish.nutrition.wholeRecipe.energy.milliKcal).toBe(969_000);
    expect(dish.nutrition.perServing.energy.milliKcal).toBe(484_500);
    expect(dish.nutrition.perServing.protein.milligrams).toBe(32_625);
    expect(dish.nutrition.perServing.carbs.milligrams).toBe(73_725);
    expect(dish.nutrition.perServing.fat.milligrams).toBe(5_985);
    expect(dish.nutrition.perServing.fibre.milligrams).toBe(1_260);

    // Step 4: one serving, stated as the person would state it -- the recipe and how much of
    // it. No nutrient is given, because the caller has none to give.
    const logged = (await callOk(client, "mue.create_food_log", {
      consumedOn: F01_DAY,
      consumedAt: "19:30",
      title: "Poulet riz yaourt",
      recipeId: dishId,
      servings: 1,
    })) as {
      entry: {
        id: string;
        kind: string;
        slot: string;
        energyKcal: number | null;
        proteinGrams: number | null;
        carbsGrams: number | null;
        fatGrams: number | null;
        fibreGrams: number | null;
      };
    };
    const lineId = logged.entry.id;
    expect(logged.entry.kind).toBe("recipe");
    expect(logged.entry.slot).toBe("dinner");
    expect(logged.entry.energyKcal).toBe(484.5);
    expect(logged.entry.proteinGrams).toBe(32.625);
    expect(logged.entry.carbsGrams).toBe(73.725);
    expect(logged.entry.fatGrams).toBe(5.985);
    expect(logged.entry.fibreGrams).toBe(1.26);

    // The stored row, not the answer that was rendered from it: PRD_FOOD 8.6 stores whole
    // counts of the canonical unit, and a half serving has to survive as an exact integer.
    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(and(eq(schema.foodLogEntries.userId, userId), eq(schema.foodLogEntries.id, lineId)));
    const row = rows[0]!;
    expect(row.energyMilliKcal).toBe(484_500);
    expect(row.proteinMilligrams).toBe(32_625);
    expect(row.carbsMilligrams).toBe(73_725);
    expect(row.fatMilligrams).toBe(5_985);
    expect(row.fibreMilligrams).toBe(1_260);
    expect(row.kind).toBe("recipe");
    expect(row.sourceRef).toBe(dishId);
    expect(row.quantityThousandths).toBe(1_000);
    expect(row.quantityUnit).toBe("serving");

    // Step 5, first read.
    const day = (await callOk(client, "mue.list_food_logs", { date: F01_DAY })) as {
      entries: { id: string; energyKcal: number | null; proteinGrams: number | null }[];
    };
    const line = day.entries.find((entry) => entry.id === lineId)!;
    expect(line.energyKcal).toBe(484.5);
    expect(line.proteinGrams).toBe(32.625);

    // Step 5, second read: the totals of the moment and of the day, which is what the report
    // says are wrong. The dinner and the day are each one line, so each is exactly a serving.
    const totals = (await callOk(client, "mue.get_daily_nutrition", { date: F01_DAY })) as {
      isRecorded: boolean;
      entryCount: number;
      totals: ComputedNutrients;
      slots: { slot: string; totals: ComputedNutrients }[];
    };
    expect(totals.isRecorded).toBe(true);
    expect(totals.entryCount).toBe(1);
    expect(totals.totals.energy.known).toBe(true);
    expect(totals.totals.energy.milliKcal).toBe(484_500);
    expect(totals.totals.energy.kcal).toBe(484.5);
    expect(totals.totals.energy.unknownFrom).toEqual([]);
    expect(totals.totals.protein.milligrams).toBe(32_625);
    expect(totals.totals.carbs.milligrams).toBe(73_725);
    expect(totals.totals.fat.milligrams).toBe(5_985);
    expect(totals.totals.fibre.milligrams).toBe(1_260);

    const dinner = totals.slots.find((slot) => slot.slot === "dinner")!;
    expect(dinner.totals.energy.milliKcal).toBe(484_500);
    expect(dinner.totals.protein.milligrams).toBe(32_625);
  });

  test("create_food_log resolves a food's own per-100 values for the amount eaten", async () => {
    // The reporter only drove the recipe path. The food path had the identical hole -- the
    // tool wrote `foodId` into `sourceRef` and nothing else -- so it is asserted here rather
    // than assumed fixed alongside.
    const FOOD_DAY = "2026-04-06";
    const skyr = (await callOk(client, "mue.create_food", {
      name: "Skyr F-01",
      energyKcalPer100: 63,
      proteinGramsPer100: 10.5,
      // No carbohydrate: an unknown per-100 value stays unknown through the contribution
      // rather than being read as a zero.
    })) as { food: { id: string } };

    const data = (await callOk(client, "mue.create_food_log", {
      consumedOn: FOOD_DAY,
      consumedAt: "08:15",
      title: "Skyr",
      foodId: skyr.food.id,
      quantityGrams: 150,
    })) as {
      entry: {
        id: string;
        kind: string;
        energyKcal: number | null;
        proteinGrams: number | null;
        carbsGrams: number | null;
      };
      nutritionFrom: string;
    };
    expect(data.entry.kind).toBe("food");
    expect(data.nutritionFrom).toBe("food");
    // 63 kcal per 100 g, and 150 g of it: 94.5 kcal, and 15.75 g of protein.
    expect(data.entry.energyKcal).toBe(94.5);
    expect(data.entry.proteinGrams).toBe(15.75);
    expect(data.entry.carbsGrams).toBeNull();

    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(eq(schema.foodLogEntries.userId, userId), eq(schema.foodLogEntries.id, data.entry.id)),
      );
    expect(rows[0]!.energyMilliKcal).toBe(94_500);
    expect(rows[0]!.proteinMilligrams).toBe(15_750);
    // PRD_FOOD 13.1: what the food does not state, the line does not state either. A zero
    // here would be a figure nobody measured, handed back as a fact.
    expect(rows[0]!.carbsMilligrams).toBeNull();

    const totals = (await callOk(client, "mue.get_daily_nutrition", { date: FOOD_DAY })) as {
      totals: ComputedNutrients;
    };
    expect(totals.totals.energy.milliKcal).toBe(94_500);
    expect(totals.totals.carbs.known).toBe(false);
  });

  test("a value the caller weighed itself beats the computed one, metric by metric", async () => {
    const OVERRIDE_DAY = "2026-04-07";
    const skyr = (await callOk(client, "mue.create_food", {
      name: "Skyr weighed",
      energyKcalPer100: 63,
      proteinGramsPer100: 10.5,
    })) as { food: { id: string } };

    const data = (await callOk(client, "mue.create_food_log", {
      consumedOn: OVERRIDE_DAY,
      consumedAt: "08:15",
      title: "Skyr",
      foodId: skyr.food.id,
      quantityGrams: 150,
      // The person read the pot rather than trusting the card. Their figure stands.
      energyKcal: 100,
    })) as { entry: { energyKcal: number | null; proteinGrams: number | null } };
    expect(data.entry.energyKcal).toBe(100);
    // And the metric they said nothing about is still worked out for them.
    expect(data.entry.proteinGrams).toBe(15.75);
  });

  test("changing the recipe afterwards does not move a line already written", async () => {
    // PRD_FOOD 8.4: editing or deleting the food or the recipe afterwards never changes a
    // line already journalled, and PRD_FOOD 13.1 adds that correcting a food later never
    // retroactively completes a value that was unknown. The snapshot exists for exactly this
    // property, so computing it at write time has to preserve it: a read that re-derived
    // would be a second defect wearing the first one costume.
    const FROZEN_DAY = "2026-04-08";
    const flour = (await callOk(client, "mue.create_food", {
      name: "Farine F-01",
      energyKcalPer100: 350,
    })) as { food: { id: string } };
    const cake = (await callOk(client, "mue.create_recipe", {
      name: "Gateau F-01",
      type: "snack",
      baseServings: 2,
      ingredients: [{ foodId: flour.food.id, quantity: 200 }],
    })) as { recipe: { id: string } };

    // 350 x 2 = 700 kcal for the whole cake, so 350 a serving.
    const line = (await callOk(client, "mue.create_food_log", {
      consumedOn: FROZEN_DAY,
      consumedAt: "16:00",
      title: "Gateau",
      recipeId: cake.recipe.id,
      servings: 1,
    })) as { entry: { id: string; energyKcal: number | null } };
    expect(line.entry.energyKcal).toBe(350);

    // The recipe is corrected afterwards: twice the flour, and written for four servings.
    await callOk(client, "mue.update_recipe", {
      id: cake.recipe.id,
      baseServings: 4,
      ingredients: [{ foodId: flour.food.id, quantity: 400 }],
    });
    // And so is the food it names.
    await callOk(client, "mue.update_food", { id: flour.food.id, energyKcalPer100: 400 });

    // The recipe now reads differently: 400 x 4 = 1600 kcal whole, and 400 a serving.
    const recipeNow = (await callOk(client, "mue.get_recipe", { id: cake.recipe.id })) as {
      nutrition: { perServing: ComputedNutrients; wholeRecipe: ComputedNutrients };
    };
    expect(recipeNow.nutrition.wholeRecipe.energy.milliKcal).toBe(1_600_000);
    expect(recipeNow.nutrition.perServing.energy.milliKcal).toBe(400_000);

    // The line does not. It records what was eaten, not what the recipe says today.
    const rows = await handle.database.db
      .select()
      .from(schema.foodLogEntries)
      .where(
        and(eq(schema.foodLogEntries.userId, userId), eq(schema.foodLogEntries.id, line.entry.id)),
      );
    expect(rows[0]!.energyMilliKcal).toBe(350_000);

    const totals = (await callOk(client, "mue.get_daily_nutrition", { date: FROZEN_DAY })) as {
      totals: ComputedNutrients;
    };
    expect(totals.totals.energy.milliKcal).toBe(350_000);
  });

  test("update_food_log rescales the frozen snapshot instead of reopening the recipe", async () => {
    // `FoodAddDraft.recipeNutrientsOrNull` is this same rescale on the phone, and the comment
    // beside it says why it is a rescale rather than a recomputation: the recipe may have
    // changed since the meal was eaten, and the line records the meal.
    const CORRECT_DAY = "2026-04-09";
    const oats = (await callOk(client, "mue.create_food", {
      name: "Flocons F-01",
      energyKcalPer100: 380,
      proteinGramsPer100: 13,
    })) as { food: { id: string } };
    const porridge = (await callOk(client, "mue.create_recipe", {
      name: "Porridge F-01",
      type: "breakfast",
      baseServings: 2,
      ingredients: [{ foodId: oats.food.id, quantity: 100 }],
    })) as { recipe: { id: string } };

    // 380 kcal for the pot, so 190 a serving.
    const line = (await callOk(client, "mue.create_food_log", {
      consumedOn: CORRECT_DAY,
      consumedAt: "08:00",
      title: "Porridge",
      recipeId: porridge.recipe.id,
      servings: 1,
    })) as { entry: { id: string; energyKcal: number | null; proteinGrams: number | null } };
    expect(line.entry.energyKcal).toBe(190);
    expect(line.entry.proteinGrams).toBe(6.5);

    // The recipe is rewritten between the meal and the correction. The correction must not
    // pick that up: "actually I had two servings" is a statement about the meal.
    await callOk(client, "mue.update_recipe", {
      id: porridge.recipe.id,
      ingredients: [{ foodId: oats.food.id, quantity: 1000 }],
    });

    const corrected = (await callOk(client, "mue.update_food_log", {
      id: line.entry.id,
      servings: 2,
    })) as {
      entry: { energyKcal: number | null; proteinGrams: number | null; quantity: number | null };
      nutritionFrom: string;
    };
    expect(corrected.nutritionFrom).toBe("carried");
    // Twice the line that was written, and not two servings of the recipe as it reads now.
    expect(corrected.entry.energyKcal).toBe(380);
    expect(corrected.entry.proteinGrams).toBe(13);
    expect(corrected.entry.quantity).toBe(2);

    const totals = (await callOk(client, "mue.get_daily_nutrition", { date: CORRECT_DAY })) as {
      totals: ComputedNutrients;
    };
    expect(totals.totals.energy.milliKcal).toBe(380_000);
  });

  test("create_food_log refuses what it cannot resolve rather than writing a line with no nutrition", async () => {
    // The failure belongs in the turn that caused it. Accepting the call would produce exactly
    // the F-01 symptom -- a successful write whose day totals are unknown -- and the agent
    // would have no way to tell that from a meal nobody could measure.
    const missing = await callError(client, "mue.create_food_log", {
      consumedOn: "2026-04-10",
      consumedAt: "12:00",
      title: "Un plat",
      recipeId: crypto.randomUUID(),
      servings: 1,
    });
    expect(missing.code).toBe("http.not_found");
    expect(missing.aggregateType).toBe("recipe");

    const dish = (await callOk(client, "mue.create_recipe", {
      name: "Plat sans portions",
      type: "main",
      baseServings: 2,
      ingredients: [{ foodId, quantity: 100 }],
    })) as { recipe: { id: string } };
    const noServings = await callError(client, "mue.create_food_log", {
      consumedOn: "2026-04-10",
      consumedAt: "12:00",
      title: "Un plat",
      recipeId: dish.recipe.id,
    });
    expect(noServings.code).toBe("sync.missing_required_field");
    expect(noServings.field).toBe("servings");

    // A caller carrying all five values itself is untouched by any of this: PRD_FOOD 21.2
    // makes such a line self-contained, and its `sourceRef` is provenance that resolves
    // nowhere and harms nothing.
    const selfContained = (await callOk(client, "mue.create_food_log", {
      consumedOn: "2026-04-10",
      consumedAt: "12:00",
      title: "Un plat rapporte",
      recipeId: crypto.randomUUID(),
      servings: 1,
      energyKcal: 400,
      proteinGrams: 20,
      carbsGrams: 40,
      fatGrams: 15,
      fibreGrams: 5,
    })) as { entry: { energyKcal: number | null }; nutritionFrom: string };
    expect(selfContained.nutritionFrom).toBe("stated");
    expect(selfContained.entry.energyKcal).toBe(400);
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
      expect(names).toContain("mue.plan_meal");
      for (const name of names) expect(name).not.toContain(".delete_");

      // `mue.unplan_meal` is the case a name pattern would have missed. It removes a
      // proposal, so PRD_FOOD 21.5 makes it destructive and it declares `data:delete` --
      // and its name contains no `delete_` at all. Checked by name, not by pattern.
      expect(names).not.toContain("mue.unplan_meal");
      // Every read this agent was granted is still there: the deletion permission narrows
      // the writes, not the reads.
      expect(names).toContain("mue.list_meal_plan");
      expect(names).toContain("mue.get_daily_nutrition");

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

      // The same, for a proposal: this agent can create one and cannot remove it. Calling
      // the tool it cannot see is refused by the server rather than by the catalogue, which
      // is the half that matters -- a client may hold a catalogue from a wider grant.
      // A day inside `plan_meal`'s own window, computed rather than written: unlike a journal
      // date, a plannable one moves with the clock, and a literal here would have started
      // failing sixty days after it was typed.
      const soon = new Date();
      soon.setDate(soon.getDate() + 15);
      const plannedOn = `${soon.getFullYear()}-${String(soon.getMonth() + 1).padStart(2, "0")}-${String(soon.getDate()).padStart(2, "0")}`;
      const recipes = await handle.database.db
        .select({ id: schema.recipes.id })
        .from(schema.recipes)
        .where(and(eq(schema.recipes.userId, userId), isNull(schema.recipes.deletedAt)));
      const planned = await client.callTool({
        name: "mue.plan_meal",
        arguments: { plannedOn, slot: "dinner", recipeId: recipes[0]!.id, servings: 1 },
      });
      expect(planned.isError).not.toBe(true);

      const refusedPlan = await client.callTool({
        name: "mue.unplan_meal",
        arguments: { plannedOn, slot: "dinner" },
      });
      expect(refusedPlan.isError).toBe(true);
      expect(JSON.stringify(refusedPlan.content)).toContain("mue.unplan_meal");

      const planRows = await handle.database.db
        .select()
        .from(schema.mealPlanEntries)
        .where(
          and(
            eq(schema.mealPlanEntries.userId, userId),
            eq(schema.mealPlanEntries.plannedOn, plannedOn),
          ),
        );
      expect(planRows[0]!.deletedAt).toBeNull();

      await client.close();
    },
    OAUTH_TIMEOUT_MS,
  );
});
