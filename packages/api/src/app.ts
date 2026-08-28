import type { MueAuth } from "@mue/auth";
import type { DatabaseHandle } from "@mue/db";
import { Hono } from "hono";
import { AGENTS_PATH, createAgentRoutes } from "./agents";
import { type AuthedEnv, mountAuthRoutes, requireSession } from "./auth-routes";
import { createSyncRoutes, syncErrorHandler } from "./sync";
import { createSyncEventRoutes } from "./sync-events";

/**
 * The Hono application `apps/platform` mounts.
 *
 * The entry point takes it as `options.api` and is never edited again: routes
 * land here, and the seam between TanStack Start and Hono stays proven by the
 * tests that already exist for it.
 *
 * Health is deliberately absent. `apps/platform/src/edge.ts` registers
 * `/health/live` and `/health/ready` *before* this router, so an operational
 * probe can never be shadowed by a business route and answers even when the
 * API package fails to build.
 */
export interface ApiOptions {
  readonly auth: MueAuth;
  readonly database: DatabaseHandle;
}

export function createApiApp(options: ApiOptions): Hono<AuthedEnv> {
  const app = new Hono<AuthedEnv>();

  // Every failure inside the tree answers in the one wire error shape.
  app.onError((error) => syncErrorHandler(error));

  mountAuthRoutes(app, options.auth);

  // The guard is registered on the prefix rather than inside each route, so a
  // route added later is authenticated by default instead of by remembering.
  app.use("/api/v1/*", requireSession(options.auth));
  app.route("/api/v1/sync", createSyncRoutes({ database: options.database }));

  // The live channel of PRD 9.4, on the same prefix and therefore behind the same
  // guard. It is mounted separately from `createSyncRoutes` only because it owns
  // no request/response pair: it streams, and `syncErrorHandler` has nothing to
  // say about a connection that ends.
  app.route("/api/v1/sync", createSyncEventRoutes({ database: options.database }));

  // Section 15.3's listing and revocation, for the Settings -> Agents page. Behind
  // the guard above like everything else on the prefix. `/api/v1/agents/pairing` is
  // *not* served from here: `createClientRegistrationApp` owns it and is mounted
  // ahead of this router, because it holds the one in-memory pairing window.
  app.route(AGENTS_PATH, createAgentRoutes({ database: options.database }));

  return app;
}
