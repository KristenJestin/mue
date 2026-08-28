/**
 * F-02, reproduced against a *running* Mue Platform over HTTPS and the real MCP transport.
 *
 * Not a test: the suite already pins this. This is the acceptance check the report asked for --
 * the same three tools, the same `2099-12-01`, driven through a real SDK `Client`, a real OAuth
 * 2.1 + PKCE authorization and a real PostgreSQL, against a server started from a build.
 *
 * It signs up an account of its own, and it must never be pointed at the owner's database.
 * Start a throwaway instance beside the real one rather than restarting it:
 *
 *   NODE_EXTRA_CA_CERTS=certs/mue-dev-ca.crt  *   PORT=3100 BETTER_AUTH_URL=https://192.168.1.100:3100  *   DATABASE_URL=postgres://mue:...@127.0.0.1:5433/mue_test  *     bun run apps/platform/dist/server/main.js
 *
 *   cd packages/api && NODE_EXTRA_CA_CERTS=../../certs/mue-dev-ca.crt  *     VERIFY_BASE_URL=https://192.168.1.100:3100 bun run verify:f02
 *
 * `NODE_EXTRA_CA_CERTS` has to be in the environment before Bun starts, on both sides, for the
 * reason `scripts/mue-server.ps1` documents: Bun fixes its TLS trust store at launch.
 *
 * It lives outside `src`, so `tsc` does not see it and `vp lint` does not either. That is the
 * price of a script that needs the package's dependencies without being part of its build; the
 * rules it exercises are pinned properly in `src/mcp/mcp.integration.test.ts`, and this is the
 * acceptance check against a *running* server that a test on an ephemeral port cannot be.
 */

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { OAuthClientProvider } from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type {
  OAuthClientInformation,
  OAuthClientMetadata,
  OAuthTokens,
} from "@modelcontextprotocol/sdk/shared/auth.js";
import { MUE_TOOLS } from "@mue/api/mcp";
import { OAUTH_SCOPES } from "@mue/auth";

const base = (process.env["VERIFY_BASE_URL"] ?? "https://192.168.1.100:3100").replace(/\/$/, "");
const REDIRECT_URI = "http://127.0.0.1:9876/callback";
const email = `f02-verify+${Date.now()}@mue.test`;
const password = "correct horse battery staple";
let cookie = "";

function asTransport(t: StreamableHTTPClientTransport): Parameters<Client["connect"]>[0] {
  return t as unknown as Parameters<Client["connect"]>[0];
}

function ownerHeaders(): Record<string, string> {
  return { "content-type": "application/json", origin: base, cookie };
}

function must(condition: unknown, message: string): void {
  if (!condition) throw new Error(message);
}

async function signUp(): Promise<void> {
  const response = await fetch(`${base}/api/auth/sign-up/email`, {
    method: "POST",
    headers: { "content-type": "application/json", origin: base },
    body: JSON.stringify({ email, password, name: "F-02 verifier" }),
  });
  must(response.status === 200, `sign-up answered ${response.status}`);
  cookie = response.headers
    .getSetCookie()
    .map((value) => value.split(";")[0])
    .join("; ");
}

async function createClient(): Promise<string> {
  const response = await fetch(`${base}/api/auth/oauth2/create-client`, {
    method: "POST",
    headers: ownerHeaders(),
    body: JSON.stringify({
      client_name: "F-02 verifier",
      redirect_uris: [REDIRECT_URI],
      token_endpoint_auth_method: "none",
      scope: OAUTH_SCOPES.join(" "),
      application_type: "native",
    }),
  });
  must(response.status === 201, `create-client answered ${response.status}`);
  return ((await response.json()) as Record<string, unknown>)["client_id"] as string;
}

async function authorize(clientId: string, granted: string): Promise<OAuthTokens> {
  let authorizationUrl: URL | undefined;
  let tokens: OAuthTokens | undefined;
  let verifier = "";

  const provider: OAuthClientProvider = {
    get redirectUrl() {
      return REDIRECT_URI;
    },
    get clientMetadata(): OAuthClientMetadata {
      return {
        client_name: "F-02 verifier",
        redirect_uris: [REDIRECT_URI],
        grant_types: ["authorization_code", "refresh_token"],
        response_types: ["code"],
        token_endpoint_auth_method: "none",
      };
    },
    clientInformation: (): OAuthClientInformation => ({ client_id: clientId }),
    tokens: () => tokens,
    saveTokens: (next) => {
      tokens = next;
    },
    redirectToAuthorization: (url) => {
      authorizationUrl = url;
    },
    saveCodeVerifier: (next) => {
      verifier = next;
    },
    codeVerifier: () => verifier,
  };

  const transport = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), {
    authProvider: provider,
  });
  const client = new Client({ name: "f02-verify", version: "0.0.0" });
  // Expected to fail: it is what triggers discovery and the authorization request.
  await client.connect(asTransport(transport)).then(
    () => {
      throw new Error("the unauthenticated connect should not have succeeded");
    },
    () => undefined,
  );
  must(authorizationUrl !== undefined, "no authorization URL was produced");

  const authorizeResponse = await fetch(authorizationUrl!.toString(), {
    headers: { cookie },
    redirect: "manual",
  });
  must(authorizeResponse.status === 302, `authorize answered ${authorizeResponse.status}`);
  const consentUrl = new URL(authorizeResponse.headers.get("location")!, base);

  const consent = await fetch(`${base}/api/auth/oauth2/consent`, {
    method: "POST",
    headers: ownerHeaders(),
    body: JSON.stringify({
      accept: true,
      scope: granted,
      oauth_query: consentUrl.search.replace(/^\?/, ""),
    }),
  });
  must(consent.status === 200, `consent answered ${consent.status}`);

  const redirect = ((await consent.json()) as Record<string, unknown>)["url"] as string;
  const code = new URL(redirect).searchParams.get("code");
  must(code !== null, "the consent redirect carried no code");
  await transport.finishAuth(code!);
  await transport.close();
  must(tokens !== undefined, "no tokens were saved");
  return tokens!;
}

interface Envelope {
  status: string;
  data: unknown;
  error: { code: string; message: string; field?: string } | null;
}

async function main(): Promise<void> {
  console.log(`base                ${base}`);
  await signUp();
  console.log(`account             ${email}`);

  const scopes = [
    "offline_access",
    ...[...new Set(MUE_TOOLS.flatMap((tool) => tool.scopes))].sort(),
  ].join(" ");
  const tokens = await authorize(await createClient(), scopes);

  const transport = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), {
    requestInit: { headers: { authorization: `Bearer ${tokens.access_token}` } },
  });
  const client = new Client({ name: "f02-verify", version: "0.0.0" });
  await client.connect(asTransport(transport));

  const cases: { tool: string; args: Record<string, unknown> }[] = [
    {
      tool: "mue.create_activity",
      args: { movement: "running", startedOn: "2099-12-01", durationMinutes: 35 },
    },
    { tool: "mue.upsert_weight_measurement", args: { date: "2099-12-01", weightKg: 70.15 } },
    {
      tool: "mue.create_food_log",
      args: { consumedOn: "2099-12-01", consumedAt: "12:30", title: "Soupe", energyKcal: 200 },
    },
    {
      tool: "mue.plan_meal",
      args: { plannedOn: "2099-12-01", slot: "dinner", recipeId: crypto.randomUUID(), servings: 1 },
    },
    { tool: "mue.update_health_profile", args: { birthDate: "2099-12-01" } },
  ];

  let failures = 0;
  for (const { tool, args } of cases) {
    const result = await client.callTool({ name: tool, arguments: args });
    const envelope = result.structuredContent as Envelope | undefined;
    const refused = envelope?.status === "error" && result.isError === true;
    if (!refused) failures += 1;
    console.log(
      `\n${refused ? "REFUSED " : "ACCEPTED"}  ${tool}` +
        `\n  field   ${envelope?.error?.field ?? "-"}` +
        `\n  code    ${envelope?.error?.code ?? "-"}` +
        `\n  message ${envelope?.error?.message ?? JSON.stringify(result.content)}`,
    );
  }

  await client.close();
  console.log(`\n${failures === 0 ? "OK" : `FAILED: ${failures} tool(s) accepted 2099-12-01`}`);
  if (failures > 0) process.exit(1);
}

await main();
