import { tanstackStart } from "@tanstack/react-start/plugin/vite";
import type { Plugin } from "vite";
import { defineConfig } from "vite-plus";
import { describeForbidden, findForbidden, leaksSecret } from "./src/bundle-guard";

/**
 * The Vite configuration TanStack Start needs to exist at all.
 *
 * Without it, `@tanstack/start-server-core` answers every non-delegated request with
 * `Cannot find package '#tanstack-router-entry'`: `createStartHandler` reaches the
 * route tree, the client manifest and the server-function table through four subpath
 * imports -- `#tanstack-router-entry`, `#tanstack-start-entry`,
 * `#tanstack-start-plugin-adapters` and `tanstack-start-manifest:v` -- that only this
 * plugin can resolve. Two of them have a stub in `start-server-core`'s own `imports`
 * field, which is why `bun test` imports `src/server.ts` happily and why the failure
 * only ever showed up as an HTTP body. `#tanstack-router-entry` has no stub.
 *
 * So the Web half of the platform is a build artefact, not a source tree that Bun can
 * run directly. `bun run build` produces both halves; `bun run start` runs the server
 * one. `bun run dev` skips the build and lets Vite own the process instead.
 *
 * `vite-plus` re-exports Vite's `defineConfig` unchanged. It is used here rather than
 * `vite` because PRD section 20.1 pins every dependency version in the root catalog
 * and Vite+ is the toolchain that already carries Vite; adding a second, separately
 * versioned `vite` to this package would put two Vites in one build.
 */
/**
 * What the server build must not swallow.
 *
 * The workspace packages are consumed as TypeScript source through a symlink, so Vite
 * resolves them to a real path outside `node_modules` and would inline them. Two things
 * break when it does.
 *
 * Their transitive dependencies come along -- `zod`, `drizzle-orm`, `postgres`,
 * `@modelcontextprotocol/sdk` -- and land in a bundle that Bun resolves from
 * `apps/platform/node_modules`, which under Bun's isolated linker does not contain
 * them: they live in `packages/*\/node_modules`.
 *
 * Worse, module scope merges. `packages/db/src/migrate.ts` ends in
 * `if (import.meta.main) { ... migrate(handle) ... }` -- correct for `bun run
 * src/migrate.ts`, and inlined into the server entry it becomes *that* module's
 * `import.meta.main`, which is true. The built server ran the migration CLI on every
 * boot, against a `migrations` folder that is not in the image. PRD section 20.3 says
 * migrations run explicitly at deploy and never at process start, precisely because
 * several starting processes race; a bundler is not allowed to overrule that.
 *
 * This list is an intention, so `bundleGuard` below checks the bundle instead of
 * trusting it.
 *
 * `hono` is here for a narrower reason: `src/edge.ts` imports it directly and
 * `@mue/api` imports it too. One of the two must not be inlined, or `createEdgeApp`
 * mounts a router built by a different Hono instance than the one it mounts it on.
 */
const SERVER_EXTERNALS = ["@mue/api", "@mue/auth", "@mue/contracts", "@mue/db", "hono"];

/**
 * The externals list, checked against what was actually emitted.
 *
 * It runs in `generateBundle` rather than in a test because a test can only assert on
 * a build somebody remembered to run, while this fails the build that creates the
 * hazard. `src/bundle-guard.ts` holds the predicates and `src/bundle-guard.test.ts`
 * exercises them offline; this hook is only the plumbing.
 */
function bundleGuard(): Plugin {
  return {
    name: "mue:bundle-guard",
    generateBundle(_options, bundle) {
      const server = this.environment.name === "ssr";
      const secret = process.env["BETTER_AUTH_SECRET"];
      const problems: string[] = [];

      for (const [fileName, output] of Object.entries(bundle)) {
        if (output.type !== "chunk") continue;

        if (server) {
          const found = findForbidden(output.code);
          if (found.length > 0) problems.push(describeForbidden(fileName, found));
        } else if (leaksSecret(output.code, secret)) {
          // Section 15.1: "Aucun client ne reçoit le secret maître Better Auth."
          problems.push(
            `${fileName} contains the value of BETTER_AUTH_SECRET. PRD section 15.1: ` +
              "no client ever receives the master secret. Something reachable from " +
              "src/router.tsx read it.",
          );
        }
      }

      if (problems.length > 0) this.error(problems.join("\n"));
    },
  };
}

export default defineConfig({
  plugins: [
    tanstackStart({
      /**
       * `src/serve.ts`, not the `src/server.ts` Start would find on its own.
       *
       * Start treats its server entry as the module whose default export answers
       * requests, and in this repository that module is the composition root's host:
       * `serve.ts` is the only importer of `runtime.ts`. Leaving the default in place
       * would make `src/server.ts` -- which deliberately exports an entry with *no*
       * Hono router and no database, so that `server.test.ts` can import it with
       * `DATABASE_URL` unset -- the thing that serves production, and `/api/*` would
       * 404 in the built image.
       *
       * `serve.ts` does not listen. `src/main.ts` does; see `input` below.
       */
      server: { entry: "serve" },

      router: {
        // `src/routes/*.test.tsx` sits beside the component it tests, which is where
        // the rest of the repository puts its tests. Without this the generator would
        // turn `consent.test.tsx` into a `/consent/test` route with no `Route` export.
        routeFileIgnorePattern: "\.(test|spec)\.",
      },
    }),
    bundleGuard(),
  ],

  environments: {
    ssr: {
      resolve: { external: SERVER_EXTERNALS },
      build: {
        rollupOptions: {
          // `resolve.external` alone is not enough here: this build bundles its SSR
          // dependencies by default, and the list has to reach Rolldown to be honoured.
          external: SERVER_EXTERNALS,

          /**
           * The emitted server is `dist/server/main.js`, not `dist/server/serve.js`.
           *
           * This `input` takes the place of the one the Start plugin declares, and it
           * has to: Start's server entry default-exports `{ fetch }`, and `bun run`
           * starts a server of its own for any file it runs whose default export looks
           * like one. Running the Start entry as the process entry bound the port
           * twice and died before answering anything --
           *
           *   Mue Platform listening on http://127.0.0.1:3111
           *   error: Failed to start server. Is port 3111 in use?  code: "EADDRINUSE"
           *
           * -- and adopting Bun's implicit server instead would have given up section
           * 22.5's loopback default, which it does not honour, and section 20.5's
           * graceful stop, which it cannot offer.
           *
           * Nothing is lost by replacing the entry: `src/main.ts` imports `./serve`, so
           * the Start entry and everything it reaches are in this bundle, once. Start
           * resolves it as a module (`#tanstack-start-entry`), never by file name --
           * nothing in `dist/` mentions `serve.js`.
           */
          input: { main: "src/main.ts" },
        },
      },
    },
  },
});
