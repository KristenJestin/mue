import { createPlatformRuntime } from "./runtime";
import { isDelegatedPath } from "./server";

/**
 * The composition root's host, and the only file in this package that reads the
 * environment. No test imports it, which is what lets it construct a Postgres pool and
 * a Better Auth instance at module scope while `bun test` still runs with
 * `DATABASE_URL` unset.
 *
 * It has two callers and answers to both from one module scope:
 *
 *  - `vite.config.ts` names it as TanStack Start's *server entry*, so `vite dev` and
 *    the SSR build both take `export default runtime.entry` as the thing that answers
 *    requests. It has to be this module and not `src/server.ts`: the entry `server.ts`
 *    exports carries no Hono router and no database on purpose, so `server.test.ts` can
 *    import it offline -- shipping that one would 404 every `/api/*` call in the image.
 *  - `bun run start` executes the built copy of this file directly. `import.meta.main`
 *    is true only then, so `Bun.serve` never runs inside Vite's module runner.
 *
 * Running the *source* of this file under bare Bun does not work and cannot be made to:
 * `createStartHandler` resolves the route tree through `#tanstack-router-entry`, a
 * subpath import that only the Vite plugin defines. `bun run build` first.
 */

const runtime = createPlatformRuntime();

export default runtime.entry;

if (import.meta.main) {
  const port = Number(process.env["PORT"] ?? 3000);

  // Loopback by default: no Mue service listens on a public interface unless it was
  // configured to (PRD section 22.5).
  const hostname = process.env["HOST"] ?? "127.0.0.1";

  /**
   * Section 20.5: "Les assets TanStack Start et le serveur Hono sont livrés dans la
   * même image." `vite build` writes the browser bundle to `dist/client` and this file
   * to `dist/server`, so the client directory is the sibling of the running module.
   *
   * Nothing here hard-codes where Vite puts the bundle. The emitted names carry a
   * content hash and the directory has already moved once (`/_build` to `/assets`)
   * between the plugin default and what Vite+ actually emits; a prefix written here
   * would be a second source of truth that only fails in production. Disk decides.
   */
  const clientDirectory = new URL(process.env["MUE_CLIENT_DIR"] ?? "../client/", import.meta.url);

  async function serveAsset(pathname: string): Promise<Response | null> {
    // `new URL(relative, base)` normalises `..` before we look, so a traversal attempt
    // leaves the client directory and is rejected here rather than opened.
    const candidate = new URL(pathname.slice(1), clientDirectory);
    if (!candidate.href.startsWith(clientDirectory.href)) return null;

    const file = Bun.file(candidate);
    if (!(await file.exists())) return null;
    return new Response(file, {
      // Every emitted name carries a content hash, so the bytes behind a URL never
      // change. Nothing here is personal: it is the same bundle for every visitor.
      headers: { "cache-control": "public, max-age=31536000, immutable" },
    });
  }

  const server = Bun.serve({
    port,
    hostname,
    fetch: async (request) => {
      const { pathname } = new URL(request.url);
      /**
       * The delegated prefixes are asked first and are never looked for on disk. That
       * is the boundary of section 20.2, restated where it can be violated: a file
       * dropped into the client bundle must not be able to answer for `/api/*`,
       * `/mcp`, `/health/*` or `/.well-known/*`.
       */
      if (!isDelegatedPath(pathname) && (request.method === "GET" || request.method === "HEAD")) {
        const asset = await serveAsset(pathname);
        if (asset !== null) return asset;
      }
      // Wrapped rather than passed by reference: Bun hands its `Server` as the second
      // argument, and Start reads a request-options object there.
      return runtime.entry.fetch(request);
    },
  });

  console.log(`Mue Platform listening on http://${server.hostname}:${server.port}`);

  /**
   * Section 20.5: "L'arrêt et le redémarrage ne perdent aucune mutation acquittée."
   * `stop(true)` lets in-flight requests finish before the pool goes away, so a push
   * that has already been answered is never cut off mid-commit.
   */
  let stopping = false;
  const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
    if (stopping) return;
    stopping = true;
    console.log(`${signal} received, draining.`);
    await server.stop(true);
    await runtime.close();
    process.exit(0);
  };

  process.on("SIGINT", (signal) => void shutdown(signal));
  process.on("SIGTERM", (signal) => void shutdown(signal));
}
