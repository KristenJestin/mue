import type { MueError } from "@mue/contracts";
import type { DatabaseHandle } from "@mue/db";
import { SyncRequestError, readChanges, submitMutations } from "@mue/domain";
import { Hono } from "hono";
import type { AuthedEnv } from "./auth-routes";

/**
 * `/api/v1/sync/push` and `/api/v1/sync/pull` -- the Android contract of PRD
 * section 20.4.
 *
 * These handlers hold no rule. They read the account off the authenticated
 * request, hand the body to `@mue/domain` and serialise what comes back;
 * everything about what a mutation means lives in the one implementation an MCP
 * tool also calls (section 20.2).
 */

function errorBody(error: MueError): { error: MueError } {
  return { error };
}

async function readBody(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    throw new SyncRequestError(
      {
        code: "sync.invalid_payload",
        message: "The request body is not JSON.",
        retryable: false,
      },
      400,
    );
  }
}

export interface SyncRouteOptions {
  readonly database: DatabaseHandle;
}

export function createSyncRoutes(options: SyncRouteOptions): Hono<AuthedEnv> {
  const routes = new Hono<AuthedEnv>();

  /**
   * FR-SYNC-006 and FR-SYNC-007 both answer 200.
   *
   * A rejected mutation is a business result carried in `results[]`, not a
   * transport failure: Ktor's default `HttpClient` throws on a non-2xx before
   * the body is deserialised, so a 4xx here would hide the very `MueError`
   * FR-SYNC-007 requires the client to display.
   */
  routes.post("/push", async (c) => {
    const body = await readBody(c.req.raw);
    const mutations = (body as { mutations?: unknown } | null)?.mutations;
    if (!Array.isArray(mutations)) {
      return c.json(
        errorBody({
          code: "sync.invalid_payload",
          message: "A push carries a `mutations` array.",
          retryable: false,
        }),
        400,
      );
    }
    const response = await submitMutations(
      options.database,
      { userId: c.get("userId") },
      mutations,
    );
    return c.json(response, 200);
  });

  /** A page of changes, or `upgrade_required` -- also both 200, for the same reason. */
  routes.post("/pull", async (c) => {
    const body = await readBody(c.req.raw);
    const response = await readChanges(options.database, { userId: c.get("userId") }, body);
    return c.json(response, 200);
  });

  return routes;
}

/**
 * The one place a thrown `SyncRequestError` becomes a response, so every
 * non-2xx body on `/api/v1` is the single `{ error }` envelope the Kotlin DTO
 * parses. Anything else is a server fault: it is logged, and answered with a
 * retryable error carrying none of its detail (section 16).
 */
export function syncErrorHandler(error: unknown): Response {
  if (error instanceof SyncRequestError) {
    return Response.json(errorBody(error.mueError), {
      status: error.status,
      headers: { "content-type": "application/json" },
    });
  }
  console.error("[sync] unhandled failure", error);
  return Response.json(
    errorBody({
      code: "server.internal",
      message: "The server could not complete the request. Retry later.",
      retryable: true,
    }),
    { status: 500 },
  );
}
