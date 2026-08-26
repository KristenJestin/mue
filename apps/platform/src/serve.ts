import { createPlatformRuntime } from "./runtime";

/**
 * TanStack Start's server entry, and the composition root's host.
 *
 * `vite.config.ts` names this module as Start's *server entry*, so `vite dev` and the
 * SSR build both take `export default runtime.entry` as the thing that answers
 * requests. It has to be this module and not `src/server.ts`: the entry `server.ts`
 * exports carries no Hono router and no database on purpose, so `server.test.ts` can
 * import it offline -- shipping that one would 404 every `/api/*` call in the image.
 *
 * No test imports this file, which is what lets it construct a Postgres pool and a
 * Better Auth instance at module scope while `bun test` still runs with `DATABASE_URL`
 * unset. `runtime.ts` is imported from here and nowhere else.
 *
 * This module does not listen. `src/main.ts` does, and it is the only module in the
 * package that binds a port. The split is not stylistic: `bun run <file>` starts a
 * server of its own whenever that file's default export looks like one, and this
 * file's default export is exactly `{ fetch }`. Running *this* module as the process
 * entry therefore produced two servers on the same port and died before it could
 * answer anything:
 *
 *   Mue Platform listening on http://127.0.0.1:3111
 *   error: Failed to start server. Is port 3111 in use?  code: "EADDRINUSE"
 *
 * Bun's implicit server binds `0.0.0.0` and drains nothing on a signal, so adopting it
 * instead would have given up both section 22.5's loopback default and section 20.5's
 * graceful stop. A module with no default export cannot trigger it at all.
 *
 * Running the *source* of either file under bare Bun does not work and cannot be made
 * to: `createStartHandler` resolves the route tree through `#tanstack-router-entry`, a
 * subpath import that only the Vite plugin defines. `bun run build` first.
 */
export const runtime = createPlatformRuntime();

export default runtime.entry;
