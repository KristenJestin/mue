import { createApiApp } from "@mue/api";
import {
  createClientRegistrationApp,
  createMcpApp,
  createOAuthDiscoveryApp,
  createPairingWindow,
} from "@mue/api/mcp";
import { type AuthHandle, createAuth } from "@mue/auth";
import { Hono } from "hono";
import type { ServerEntry } from "@tanstack/react-start/server-entry";
import type { ReadinessCheck } from "./edge";
import { createServerEntry } from "./server";

/**
 * The composition root: the one place that reads the environment, opens the
 * PostgreSQL pool and assembles every delegated router into the entry point.
 *
 * It lives beside `server.ts` rather than inside it on purpose. `server.ts` ends in
 * `export default createServerEntry()` at module scope and `server.test.ts` imports
 * that default, so anything env-dependent placed there runs at import time in the
 * test process: `createAuth()` calls `readAuthConfig()`, which throws when
 * `BETTER_AUTH_SECRET` is unset, and `createDatabase()` calls `readDatabaseConfig()`,
 * which throws when `DATABASE_URL` is unset and otherwise opens a Postgres pool
 * immediately. Wiring there would turn the whole offline platform suite into a suite
 * that needs a live PostgreSQL to import a module.
 *
 * Nothing imports this file except `serve.ts`, and nothing imports `serve.ts` except
 * `main.ts` and TanStack Start itself -- so this module is evaluated only under
 * `bun run start` and `bun run dev`. That is what keeps `bun test` offline.
 */
export interface PlatformRuntime {
  readonly entry: ServerEntry;
  /** Releases the PostgreSQL pool. Called on shutdown, never per request. */
  close(): Promise<void>;
}

export interface PlatformRuntimeOptions {
  /**
   * Injected by an integration harness that already owns an auth instance and its
   * database. Absent in production, where the environment decides.
   */
  readonly auth?: AuthHandle;
}

/**
 * Everything TanStack Start delegates, in one Hono tree.
 *
 * Order is by specificity: discovery owns `/.well-known/*`, MCP owns `/mcp`, client
 * registration owns `POST /api/auth/oauth2/register` and the pairing switch, and the
 * API router owns the rest of `/api/auth/*` and the guarded `/api/v1/*`.
 *
 * The first three claim disjoint paths, so for them this is documentation rather than
 * precedence. The fourth is not: client registration deliberately claims two paths out
 * from under `createApiApp`, and being registered first is what makes that work.
 *
 * Health is deliberately not here: `createEdgeApp` registers `/health/live` and
 * `/health/ready` before this router so an operational probe can never be shadowed
 * by a business route.
 */
function createDelegatedRouter(handle: AuthHandle): Hono {
  const app = new Hono();
  app.route("/", createOAuthDiscoveryApp(handle));
  app.route("/", createMcpApp({ auth: handle }));
  // Order is precedence here, and the one place in this file where it is.
  // `createApiApp` hands every `/api/auth/*` request to Better Auth, which has
  // been told to accept an unauthenticated RFC 7591 registration; this router
  // claims `POST /api/auth/oauth2/register` first and refuses it unless the
  // owner has opened a pairing window. Moving it below `createApiApp` opens
  // client registration to the whole network -- see `mcp/registration.ts`.
  //
  // The window is created here, once per process, because that is what makes a
  // restart close it.
  app.route(
    "/",
    createClientRegistrationApp({ auth: handle.auth, pairing: createPairingWindow() }),
  );
  app.route("/", createApiApp({ auth: handle.auth, database: handle.database }));
  return app;
}

/**
 * Section 20.5: the image exposes `ready` with no personal data. A `select 1` on the
 * pool is the whole check — it proves the limited role can still reach the cluster,
 * and it reports nothing but the name. The driver's own error text never leaves
 * `createEdgeApp`, which drops it precisely because a DSN with a password is the
 * usual way one leaks out of an unauthenticated endpoint.
 *
 * Readiness deliberately does not verify that migrations ran. Section 20.3 runs them
 * explicitly at deploy, never per process, so a schema check here would report on
 * something this process is not allowed to fix.
 */
function databaseReadiness(handle: AuthHandle): ReadinessCheck {
  return {
    name: "database",
    probe: async () => {
      await handle.database.sql`select 1`;
      return true;
    },
  };
}

export function createPlatformRuntime(options: PlatformRuntimeOptions = {}): PlatformRuntime {
  // `createAuth()` with no options reads `BETTER_AUTH_*` and opens the pool itself,
  // then hands both back. Building the database separately would open a second pool
  // for the same process and give Better Auth a different connection from the one the
  // sync routes write through.
  const handle = options.auth ?? createAuth();

  return {
    entry: createServerEntry({
      api: createDelegatedRouter(handle),
      readinessChecks: [databaseReadiness(handle)],
    }),
    // Only closes what it opened: an injected handle belongs to its caller.
    close: options.auth === undefined ? handle.close : async () => {},
  };
}
