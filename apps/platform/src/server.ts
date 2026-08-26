import { createStartHandler, defaultStreamHandler } from "@tanstack/react-start/server";
import type { ServerEntry } from "@tanstack/react-start/server-entry";
import { type EdgeOptions, createEdgeApp } from "./edge";

/**
 * The prefixes PRD section 20.2 hands to Hono. A prefix matches the path itself and
 * anything under it, and nothing else: `/mcp` and `/mcp/session` are delegated,
 * `/mcpanel` is not.
 *
 * `/.well-known` is not in section 20.2's list and has to be here anyway. RFC 9728
 * puts the protected-resource metadata at a fixed origin-root path, and an MCP client
 * fetches it *before* it has any credential — it is the document that tells the client
 * which authorization server to talk to. Left with TanStack Start, discovery answers
 * 404 and the OAuth flow of section 15.1 never begins, so no client reaches `/mcp` at
 * all. `packages/api/src/mcp/route.ts` (`createOAuthDiscoveryApp`) is what answers it.
 */
export const DELEGATED_PREFIXES = ["/api", "/mcp", "/health", "/.well-known"] as const;

export function isDelegatedPath(pathname: string): boolean {
  return DELEGATED_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}

/**
 * The server entry TanStack Start's Vite plugin picks up from `src/server.ts`.
 *
 * Start's handler is an ordinary `(Request) => Response` function, so the direction
 * the PRD assumes works as written: this function owns the routing decision and calls
 * whichever half applies. Inverting it — Hono outer, Start as a catch-all route — is
 * equally possible, and was rejected because it would move the deployment entry point
 * out of the file Start's build looks for.
 *
 * The delegated half never touches Start's handler, which is what lets `/health/*`
 * and `/api/*` run under bare Bun before any Vite build of the Web shell exists.
 */
export function createServerEntry(options: EdgeOptions = {}): ServerEntry {
  const start = createStartHandler(defaultStreamHandler);
  const edge = createEdgeApp(options);

  return {
    fetch(request, requestOptions) {
      if (isDelegatedPath(new URL(request.url).pathname)) {
        return edge.fetch(request);
      }
      return start(request, requestOptions);
    },
  };
}

export default createServerEntry();
