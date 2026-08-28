import type { StreamableHTTPTransport } from "@hono/mcp";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

/**
 * The Hono application. TanStack Start delegates /api/*, /mcp and /health/* to it,
 * so there is exactly one routing tree whatever the entry point.
 */
export {
  AGENTS_PATH,
  createAgentRoutes,
  toAgentResource,
  type AgentResource,
  type AgentRouteOptions,
} from "./agents";
export { createApiApp, type ApiOptions } from "./app";
export { mountAuthRoutes, requireSession, type AuthedEnv, type AuthVariables } from "./auth-routes";
export { createSyncRoutes, syncErrorHandler, type SyncRouteOptions } from "./sync";
export {
  createSyncEventRoutes,
  HEARTBEAT_INTERVAL_MS,
  MAX_STREAM_MS,
  POLL_INTERVAL_MS,
  type SyncEventRouteOptions,
} from "./sync-events";

/**
 * The MCP endpoint is mounted in a later phase. Both dependency surfaces are bound
 * here so a breaking change in either fails `typecheck` before it reaches a route.
 */
export type McpMount = (server: McpServer, transport: StreamableHTTPTransport) => void;
