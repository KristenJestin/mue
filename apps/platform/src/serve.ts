import { createPlatformRuntime } from "./runtime";

/**
 * The process entry point, and the only file in this package that reads the
 * environment. `bun run start` and `bun run dev` execute it; no test imports it,
 * which is what lets it construct a Postgres pool and a Better Auth instance at
 * module scope while `bun test` still runs with `DATABASE_URL` unset.
 */

const port = Number(process.env["PORT"] ?? 3000);

// Loopback by default: no Mue service listens on a public interface unless it was
// configured to (PRD section 22.5).
const hostname = process.env["HOST"] ?? "127.0.0.1";

const runtime = createPlatformRuntime();

// Wrapped rather than passed by reference: Bun hands its `Server` as the second
// argument, and Start reads a request-options object there.
const server = Bun.serve({
  port,
  hostname,
  fetch: (request) => runtime.entry.fetch(request),
});

console.log(`Mue Platform listening on http://${server.hostname}:${server.port}`);

/**
 * Section 20.5: "L'arrêt et le redémarrage ne perdent aucune mutation acquittée."
 * `stop(true)` lets in-flight requests finish before the pool goes away, so a push
 * that has already been answered is never cut off mid-commit.
 */
let stopping = false;
async function shutdown(signal: NodeJS.Signals): Promise<void> {
  if (stopping) return;
  stopping = true;
  console.log(`${signal} received, draining.`);
  await server.stop(true);
  await runtime.close();
  process.exit(0);
}

process.on("SIGINT", (signal) => void shutdown(signal));
process.on("SIGTERM", (signal) => void shutdown(signal));
