import { type AgentSummary, listAgents, revokeAgent } from "@mue/auth";
import type { MueError } from "@mue/contracts";
import type { DatabaseHandle } from "@mue/db";
import { Hono } from "hono";
import type { AuthedEnv } from "./auth-routes";

/**
 * The two routes behind `Settings -> Agents`, and nothing else.
 *
 * Section 15.3 assigns the listing and the revocation to the Web administration:
 * *"L'administration Web prévue par PRD_WEB.md listera les sessions, appareils et
 * agents associés. Elle affiche leur dernière utilisation et leurs portées. Une
 * identité peut être révoquée immédiatement."* It then requires the same revocations
 * to stay possible from a documented local command until that product ships, which is
 * `scripts/admin.ts`.
 *
 * Both callers answer the same two functions in `packages/auth/src/administration.ts`.
 * This module is a translation into HTTP -- a session guard it inherits, a JSON shape,
 * and dates as ISO strings -- and holds no rule of its own. A second query against
 * `oauthClient` written here would be the beginning of two behaviours that drift.
 *
 * ## Why the page reaches these over HTTP
 *
 * PRD_WEB section 8.2 says a Web server function calls a business service directly
 * rather than making an HTTP request to Mue's own API. That is the shape of the Web
 * *product*; the shell of section 5 has deliberately not opened that door yet, and
 * `apps/platform/src/routes/index.tsx` says why: a server function is a module the
 * compiler splits across both bundles, and until something needs it, section 15.1's
 * boundary is one that no code has to be trusted to respect. `sign-in.tsx` and
 * `consent.tsx` already talk to `/api/auth/*` with `credentials: "same-origin"`; the
 * agents page talks to `/api/v1/agents` the same way, and the composition root keeps
 * its single PostgreSQL pool.
 *
 * The pairing window is not here. It is owned by `mcp/registration.ts`, which holds
 * the one in-memory window the registration gate consults, and already serves
 * `GET`/`POST`/`DELETE /api/v1/agents/pairing` behind the same guard.
 */

/** Where this router is mounted. The pairing window lives one segment below it. */
export const AGENTS_PATH = "/api/v1/agents";

/**
 * Path segments under {@link AGENTS_PATH} that are never a client id.
 *
 * `createClientRegistrationApp` owns `/api/v1/agents/pairing` and is mounted *ahead*
 * of `createApiApp`, so in the assembled server this router never sees that path. In
 * a tree where it did -- a reordering, a test that mounts only this half -- `DELETE
 * /api/v1/agents/pairing` would arrive here as "revoke the agent called pairing".
 * Refusing the segment outright means the mount order is a performance detail rather
 * than the only thing standing between closing a window and revoking an identity.
 */
const RESERVED_SEGMENTS: ReadonlySet<string> = new Set(["pairing"]);

export interface AgentRouteOptions {
  readonly database: DatabaseHandle;
}

/** One agent, on the wire. Dates are ISO instants so the browser can format them. */
export interface AgentResource {
  readonly clientId: string;
  readonly name: string | null;
  readonly scopes: readonly string[];
  /**
   * `disabled` in the database, `revoked` here. The column records the mechanism; the
   * owner performed a revocation, and section 15.3 is the word the page has to use.
   */
  readonly revoked: boolean;
  /** Discovered through a Client ID Metadata Document rather than registered. */
  readonly discovered: boolean;
  readonly registeredAt: string | null;
  readonly lastUsedAt: string | null;
}

export function toAgentResource(agent: AgentSummary): AgentResource {
  return {
    clientId: agent.clientId,
    name: agent.name,
    scopes: agent.scopes,
    revoked: agent.disabled,
    discovered: agent.discovered,
    registeredAt: agent.registeredAt?.toISOString() ?? null,
    lastUsedAt: agent.lastUsedAt?.toISOString() ?? null,
  };
}

function notFound(message: string, code: MueError["code"]): MueError {
  return { code, message, retryable: false };
}

/**
 * Mounted at {@link AGENTS_PATH} by `createApiApp`, which has already put
 * `requireSession` in front of `/api/v1/*`. Nothing here re-reads the session: the
 * guard is on the prefix so a route added later is authenticated by default rather
 * than by remembering, and duplicating it would invite the two copies to disagree.
 */
export function createAgentRoutes(options: AgentRouteOptions): Hono<AuthedEnv> {
  const routes = new Hono<AuthedEnv>();

  routes.get("/", async (c) => {
    const agents = await listAgents(options.database);
    return c.json({ agents: agents.map(toAgentResource) });
  });

  routes.delete("/:clientId", async (c) => {
    const clientId = c.req.param("clientId");

    if (RESERVED_SEGMENTS.has(clientId)) {
      // Answered as if the route did not exist, because for this router it does not.
      return c.json(
        {
          error: notFound(
            `No route matches DELETE ${AGENTS_PATH}/${clientId}. ` +
              "The pairing window is closed with the same verb on that path, and it " +
              "is served by the registration gate, not by this router.",
            "http.not_found",
          ),
        },
        404,
      );
    }

    const result = await revokeAgent(options.database, clientId);
    if (!result.found) {
      // The client id came from the caller. Echoing it back says nothing the caller
      // did not already know, and says nothing about any agent that does exist.
      return c.json(
        { error: notFound("No agent is registered under that client id.", "agent.not_found") },
        404,
      );
    }

    return c.json({
      clientId,
      accessTokensRevoked: result.accessTokensRevoked,
      refreshTokensRevoked: result.refreshTokensRevoked,
      consentsRemoved: result.consentsRemoved,
    });
  });

  return routes;
}
