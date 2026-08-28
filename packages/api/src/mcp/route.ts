import { requireMcpAuth } from "@better-auth/mcp";
import { StreamableHTTPTransport } from "@hono/mcp";
import { oauthIssuer, type AuthHandle } from "@mue/auth";
import type { MueError } from "@mue/contracts";
import { Hono } from "hono";
import { unauthenticated } from "./errors";
import { IdentityError, isAgentRevoked, readAgentIdentity } from "./identity";
import { buildMcpServer } from "./server";
import { MUE_TOOLS } from "./tools";
import type { MueMcpServices } from "./services";
import { createMueMcpServices } from "./store";

export interface McpRouteOptions {
  readonly auth: AuthHandle;
  /** Injected in tests. Defaults to the Drizzle-backed implementation. */
  readonly services?: MueMcpServices;
  /** Where an unexpected tool failure is reported. Section 16 governs the sink. */
  readonly onInternalError?: (toolName: string, error: unknown) => void;
  /** Overrides the scopes named in the 401 challenge. See `challengeScopes` below. */
  readonly challengeScopes?: readonly string[];
}

/** The one path section 8.3 defines. No SSE endpoint, and no `stdio` adapter in V1. */
export const MCP_PATH = "/mcp";

function jsonRpcError(code: number, error: MueError, status: number): Response {
  // A JSON-RPC envelope, because a client at this point is speaking JSON-RPC and would
  // otherwise have to guess at a bare body. `data` carries the Mue error verbatim so an
  // agent reads the same structure here as inside a tool result.
  return Response.json(
    { jsonrpc: "2.0", id: null, error: { code, message: error.message, data: error } },
    { status },
  );
}

/**
 * Section 16: "Le serveur valide les hotes et origines HTTP."
 *
 * Only a *present* Origin is checked. A browser always sends one, which is what makes
 * this a DNS-rebinding defence; a native MCP client sends none at all, and refusing
 * those would lock out every client section 8.2 describes. `@hono/mcp`'s own
 * `allowedOrigins` cannot express the distinction -- it rejects an absent header --
 * which is why the check is here and its `allowedHosts` is used for the Host header.
 */
function isOriginAllowed(request: Request, trusted: readonly string[]): boolean {
  const origin = request.headers.get("origin");
  if (origin === null) return true;
  return trusted.includes(origin);
}

/**
 * What the 401 challenge asks a client to obtain.
 *
 * It matters more than it looks. A spec-following MCP client picks its scopes in the
 * order SEP-835 defines: the `scope` field of the `WWW-Authenticate` challenge first,
 * the protected-resource metadata second, its own configuration last. The metadata
 * `@better-auth/mcp` publishes is the *whole* Mue vocabulary minus the OAuth machinery,
 * so with no challenge every agent asks for every scope and never for `offline_access`
 * -- which means no refresh token, and section 22.4's refresh test cannot pass.
 *
 * The union of what the tools actually declare is both narrower and truer. Narrowing
 * further is the human's job, on the consent page, which is what section 15.2's "la
 * configuration personnelle peut accorder toutes les portees a un agent de confiance"
 * describes: the server offers, the owner decides.
 */
function challengeScopes(options: McpRouteOptions): readonly string[] {
  if (options.challengeScopes !== undefined) return options.challengeScopes;
  const declared = new Set<string>();
  for (const tool of MUE_TOOLS) for (const scope of tool.scopes) declared.add(scope);
  return ["offline_access", ...[...declared].sort()];
}

/**
 * `/mcp`, Streamable HTTP, as section 8.3 defines it.
 *
 * Every request is authorised before a tool catalogue is even built:
 *
 *  1. `requireMcpAuth` verifies the access token against the JWKS -- signature, issuer,
 *     audience and expiry -- and answers an unauthenticated request with the RFC 9728
 *     `WWW-Authenticate` header an MCP client needs to start the OAuth flow;
 *  2. the agent identity is read from the verified claims;
 *  3. revocation is checked against the database, because a signed token proves it was
 *     issued and not that it is still wanted (section 15.3);
 *  4. `buildMcpServer` registers only the tools the granted scopes allow.
 */
export function createMcpApp(options: McpRouteOptions): Hono {
  const { auth, database, config } = options.auth;
  const services = options.services ?? createMueMcpServices(database);
  const allowedHost = new URL(config.baseUrl).host;

  const app = new Hono();

  // A stateless server has no stream to resume and sends nothing unsolicited, so the
  // standalone GET stream and the DELETE session teardown have nothing to do. 405 is
  // what the specification defines for exactly that, and what an MCP client reads as
  // "this server has no server-initiated messages".
  const methodNotAllowed = (c: { text: (body: string, status: 405) => Response }) =>
    c.text("Method Not Allowed", 405);
  app.get(MCP_PATH, methodNotAllowed);
  app.delete(MCP_PATH, methodNotAllowed);

  app.post(MCP_PATH, async (c) => {
    if (!isOriginAllowed(c.req.raw, config.trustedOrigins)) {
      return jsonRpcError(
        -32600,
        { code: "auth.forbidden", message: "Origin not allowed.", retryable: false },
        403,
      );
    }

    const guarded = requireMcpAuth(
      auth,
      async (_request, claims) => {
        let identity;
        try {
          identity = readAgentIdentity(claims as Record<string, unknown>);
        } catch (error) {
          if (!(error instanceof IdentityError)) throw error;
          return jsonRpcError(-32001, unauthenticated(error.message), 401);
        }

        if (await isAgentRevoked(database, identity)) {
          // Section 15.3: "Une tentative ulterieure retourne une erreur
          // d'authentification sans reveler de donnee." No tool is registered, no
          // catalogue is built, and the message says nothing about the account.
          return jsonRpcError(
            -32001,
            unauthenticated("This authorization is no longer valid."),
            401,
          );
        }

        const server = buildMcpServer({
          identity,
          services,
          ...(options.onInternalError === undefined
            ? {}
            : { onInternalError: options.onInternalError }),
        });

        const transport = new StreamableHTTPTransport({
          // `sessionIdGenerator` is deliberately absent, not `undefined`: absent is
          // what the SDK reads as stateless, and `exactOptionalPropertyTypes` refuses
          // to let the two be written the same way. Stateless means nothing survives
          // the request, so no client can resume into another agent's authorization.
          //
          // One JSON response per POST. V1 has no server-initiated message, so an SSE
          // body would be a stream that never carries a second frame -- and it is what
          // lets the server be closed as soon as the response is built.
          enableJsonResponse: true,
          enableDnsRebindingProtection: true,
          allowedHosts: [allowedHost],
        });

        try {
          await server.connect(transport);
          const response = await transport.handleRequest(c);
          return response ?? new Response(null, { status: 202 });
        } finally {
          await server.close();
        }
      },
      {
        resource: config.mcpResource,
        challengeScopes: challengeScopes(options),
        // `requireMcpAuth` defaults the expected issuer to Better Auth's base
        // URL, `<origin>/api/auth`. `@mue/auth` moved the issuer to the origin so
        // OAuth discovery lands where clients look for it, so the tokens this
        // very server signs carry `iss: <origin>` and the default would reject
        // every one of them -- as a bare 401 with no server-side reason, because
        // an issuer mismatch is indistinguishable from a forged token. The two
        // values have one source: `oauthIssuer`.
        issuer: oauthIssuer(config.baseUrl),
      },
    );

    return guarded(c.req.raw);
  });

  return app;
}

/**
 * The OAuth discovery documents, at the origin root where a client looks for them.
 *
 * They are Better Auth's own responses; this only puts them where RFC 8414 and RFC
 * 9728 say they live. Better Auth serves everything under its base path, so
 * `/.well-known/oauth-protected-resource/mcp` and the path-aware
 * `/.well-known/oauth-authorization-server/api/auth` are 404 without this passthrough
 * and no MCP client can discover the authorization server.
 *
 * NOTE: `apps/platform/src/server.ts` delegates only `/api`, `/mcp` and `/health` to
 * Hono. `/.well-known` has to join `DELEGATED_PREFIXES` or this router never sees the
 * request. That file belongs to another chunk, so the change is reported rather than
 * made here.
 */
export function createOAuthDiscoveryApp(handle: AuthHandle): Hono {
  const app = new Hono();
  app.all("/.well-known/*", (c) => handle.auth.handler(c.req.raw));
  return app;
}
