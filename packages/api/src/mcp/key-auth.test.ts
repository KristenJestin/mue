import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import {
  createApiKey,
  createAuth,
  revokeApiKey,
  type AuthHandle,
  type CreatedApiKey,
} from "@mue/auth";
import { createTestDatabase, migrate, schema } from "@mue/db";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { desc, eq } from "drizzle-orm";
import { Hono } from "hono";
import { createMcpApp, MUE_TOOLS } from "./index";
import { MUE_MCP_PROTOCOL_VERSION } from "./protocol";

/**
 * La clé d'API sur `/mcp`, éprouvée par un vrai client MCP, contre un vrai
 * serveur HTTP et une vraie base.
 *
 * ## Ce que cette suite prouve, et que les tests de `@mue/auth` ne peuvent pas
 *
 * `api-keys.test.ts` prouve le trousseau : le jeton n'est pas stocké, la
 * révocation est immédiate. Il ne prouve pas qu'une clé **ouvre le MCP**, et
 * c'est la seule chose qui compte pour le propriétaire. Ici, un `Client` du SDK
 * se connecte avec un `Authorization: Bearer *** et rien d'autre — pas de
 * navigation, pas de consentement, pas de fenêtre d'appairage — et lit le
 * catalogue.
 *
 * ## Les deux refus, distingués exprès
 *
 * Une clé révoquée et un jeton étranger ne se répondent pas de la même façon, et
 * les deux cas sont testés :
 *
 *  - une clé de Mue qui ne résout pas est refusée **sans** `WWW-Authenticate` :
 *    un client dont la clé vient d'être révoquée ne doit pas être envoyé vers
 *    une autorisation OAuth qu'il n'a jamais demandée ;
 *  - un jeton qui n'est pas une clé de Mue laisse le challenge RFC 9728 intact,
 *    ce qui est la preuve que le chemin OAuth n'a pas été cassé en ajoutant
 *    celui-ci.
 *
 * ## L'écriture est signée par la clé
 *
 * Le troisième test n'est pas décoratif : `originId` vaut `identity.clientId`,
 * donc une ligne du journal écrite avec une clé doit porter l'identifiant de
 * cette clé. C'est ce qui rend une révocation exploitable après coup — on sait ce
 * que chaque clé a écrit — et c'est exactement ce qu'une identité mal construite
 * casserait en silence.
 */

let handle: AuthHandle;
let server: ReturnType<typeof Bun.serve>;
let base = "";
let userId = "";
let key: CreatedApiKey;
let app: Hono | undefined;

/** Le SDK se contredit sur son propre type de transport (voir la suite d'intégration). */
function asTransport(transport: StreamableHTTPClientTransport): Parameters<Client["connect"]>[0] {
  return transport as unknown as Parameters<Client["connect"]>[0];
}

async function json(response: Response): Promise<Record<string, unknown>> {
  return (await response.json()) as Record<string, unknown>;
}

/** Un client MCP qui n'apporte que l'en-tête, comme Hermes ou Cursor le font. */
async function connectWith(token: string): Promise<Client> {
  const transport = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), {
    requestInit: { headers: { authorization: `Bearer ${token}` } },
  });
  const client = new Client({ name: "mue-key-auth", version: "0.0.0" });
  await client.connect(asTransport(transport));
  return client;
}

/**
 * Un `initialize` en JSON-RPC brut, pour lire ce qu'un client MCP complet
 * masque : le statut et les en-têtes de la réponse.
 */
async function probe(headers: Record<string, string>): Promise<Response> {
  return fetch(`${base}/mcp`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: MUE_MCP_PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: { name: "probe", version: "0" },
      },
    }),
  });
}

beforeAll(async () => {
  server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: (request) => app!.fetch(request) });
  base = `http://127.0.0.1:${server.port}`;

  handle = createAuth({
    config: {
      secret: "key-auth-test-secret-at-least-32-characters",
      baseUrl: base,
      trustedOrigins: [base],
      mcpResource: `${base}/mcp`,
      loginPage: "/sign-in",
      consentPage: "/consent",
      secureCookies: false,
    },
    // Sans cet argument, `createAuth` retombe sur `DATABASE_URL`, c'est-à-dire la
    // base de développement que le téléphone appaire (AGENTS.md §9.2 et §5).
    database: createTestDatabase(),
  });
  await migrate(handle.database);

  app = new Hono();
  app.all("/api/auth/*", (c) => handle.auth.handler(c.req.raw));
  app.route("/", createMcpApp({ auth: handle }));

  userId = `key-auth-${Date.now()}`;
  await handle.database.db.insert(schema.user).values({
    id: userId,
    name: "Clé d'API",
    email: `key-auth-${Date.now()}@mue.test`,
    emailVerified: true,
  });
  key = await createApiKey(handle.database, { userId, label: "coach" });
});

afterAll(async () => {
  // La cascade emporte les clés du compte.
  await handle.database.db.delete(schema.user).where(eq(schema.user.id, userId));
  await handle.database.close();
  server.stop(true);
});

describe("une clé ouvre /mcp", () => {
  test("le catalogue entier, sans qu'aucune portée ne manque", async () => {
    const client = await connectWith(key.token);
    const listed = await client.listTools();
    await client.close();

    // Une clé vaut le jeu complet : il n'y a donc pas un outil de moins que le
    // catalogue, et l'assertion est une égalité d'ensembles plutôt qu'un compte,
    // qui passerait encore si deux outils s'échangeaient.
    expect(listed.tools.map((tool) => tool.name).sort()).toEqual(
      MUE_TOOLS.map((tool) => tool.name).sort(),
    );
    expect(listed.tools.length).toBe(MUE_TOOLS.length);
  });

  test("un appel de lecture répond, comme le ferait un agent OAuth", async () => {
    const client = await connectWith(key.token);
    const result = await client.callTool({ name: "mue.get_sync_status", arguments: {} });
    await client.close();

    const envelope = result.structuredContent as { status?: string } | undefined;
    expect(result.isError ?? false).toBe(false);
    expect(envelope?.status).toBe("ok");
  });

  test("une écriture est signée par l'identifiant de la clé", async () => {
    const client = await connectWith(key.token);
    const result = await client.callTool({
      name: "mue.upsert_weight_measurement",
      arguments: { date: "2026-03-01", weightKg: 82.4 },
    });
    await client.close();
    expect(result.isError ?? false).toBe(false);

    const rows = await handle.database.db
      .select({
        originType: schema.syncJournal.originType,
        originId: schema.syncJournal.originId,
        aggregateType: schema.syncJournal.aggregateType,
      })
      .from(schema.syncJournal)
      .where(eq(schema.syncJournal.userId, userId))
      .orderBy(desc(schema.syncJournal.sequence))
      .limit(1);

    expect(rows[0]?.aggregateType).toBe("measurement");
    expect(rows[0]?.originType).toBe("agent");
    expect(rows[0]?.originId).toBe(key.key.id);
  });
});

describe("les refus", () => {
  test("une clé révoquée est refusée, et sans challenge OAuth", async () => {
    const doomed = await createApiKey(handle.database, { userId, label: "à révoquer" });
    // Elle fonctionne avant, sans quoi le test ne prouverait rien sur la révocation.
    const before = await connectWith(doomed.token);
    await before.close();

    expect(await revokeApiKey(handle.database, userId, doomed.key.id)).toBe(true);

    const response = await probe({ authorization: `Bearer ${doomed.token}` });
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBeNull();

    const body = await json(response);
    const error = body["error"] as { code?: number; data?: { code?: string } } | undefined;
    expect(error?.code).toBe(-32001);
    expect(error?.data?.code).toBe("auth.unauthenticated");

    // Et un client MCP complet n'y arrive pas non plus.
    await expect(connectWith(doomed.token)).rejects.toThrow();
  });

  test("une clé inconnue est refusée", async () => {
    const response = await probe({ authorization: `Bearer mue_${"A".repeat(43)}` });
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBeNull();
  });

  test("un jeton qui n'est pas une clé de Mue laisse l'OAuth intact", async () => {
    // Le préfixe est ce qui décide : ce jeton-ci n'en a pas, donc le garde ne
    // touche pas la base et rend la main à `requireMcpAuth`.
    const response = await probe({ authorization: "Bearer pas-une-cle-de-mue" });
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toContain("resource_metadata");
  });

  test("sans en-tête, le challenge RFC 9728 est toujours publié", async () => {
    const response = await probe({});
    expect(response.status).toBe(401);
    const challenge = response.headers.get("www-authenticate") ?? "";
    expect(challenge).toContain("resource_metadata");
    expect(challenge).toContain(`${base}/.well-known/oauth-protected-resource/mcp`);
  });
});
