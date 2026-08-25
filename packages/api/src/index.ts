import type { StreamableHTTPTransport } from "@hono/mcp";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Hono } from "hono";

/**
 * The Hono application. TanStack Start delegates /api/*, /mcp and /health/* to it,
 * so there is exactly one routing tree whatever the entry point.
 */
export function createApiApp() {
  const app = new Hono();

  app.get("/health/live", (c) => c.json({ status: "ok" }));

  return app;
}

/**
 * The MCP endpoint is mounted in a later phase. Both dependency surfaces are bound
 * here so a breaking change in either fails `typecheck` before it reaches a route.
 */
export type McpMount = (server: McpServer, transport: StreamableHTTPTransport) => void;
