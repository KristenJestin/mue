import { type MueError, livenessReportSchema, readinessReportSchema } from "@mue/contracts";
import { Hono } from "hono";

/**
 * A dependency the process needs before it can serve traffic. The name is a fixed
 * identifier, never a URL or a credential: section 20.5 forbids personal data in the
 * checks, and a driver's own error text is the usual way a DSN leaks into an
 * unauthenticated endpoint.
 */
export interface ReadinessCheck {
  readonly name: string;
  readonly probe: () => Promise<boolean> | boolean;
}

export interface EdgeOptions {
  /**
   * The `@mue/api` router, mounted at the root. It stays injected so this seam can be
   * proven — and shipped — before the API package has a single route, and so the
   * entry point never has to be edited again when routes land.
   */
  readonly api?: Hono;
  readonly readinessChecks?: readonly ReadinessCheck[];
}

/**
 * The Hono half of the entry point: everything TanStack Start delegates.
 *
 * Health is registered before the API router so an operational probe can never be
 * shadowed by a business route, and so `/health/*` answers even when the API package
 * is absent.
 */
export function createEdgeApp(options: EdgeOptions = {}): Hono {
  const app = new Hono();

  app.get("/health/live", (c) => c.json(livenessReportSchema.parse({ status: "ok" })));

  app.get("/health/ready", async (c) => {
    const checks = await Promise.all(
      (options.readinessChecks ?? []).map(async (check) => ({
        name: check.name,
        status: (await runProbe(check)) ? ("ok" as const) : ("failed" as const),
      })),
    );
    const ready = checks.every((check) => check.status === "ok");
    const report = readinessReportSchema.parse({
      status: ready ? "ready" : "not_ready",
      checks,
    });
    return c.json(report, ready ? 200 : 503);
  });

  if (options.api) {
    app.route("/", options.api);
  }

  // Delegated prefixes that nothing has claimed answer in the wire error shape rather
  // than in Hono's HTML default, so a client only ever parses one error type.
  app.notFound((c) =>
    c.json(
      {
        error: {
          code: "http.not_found",
          message: `No route matches ${c.req.method} ${new URL(c.req.url).pathname}.`,
          retryable: false,
        } satisfies MueError,
      },
      404,
    ),
  );

  return app;
}

async function runProbe(check: ReadinessCheck): Promise<boolean> {
  try {
    return await check.probe();
  } catch {
    // The reason is deliberately dropped: it is the driver's message, and this
    // endpoint is unauthenticated.
    return false;
  }
}
