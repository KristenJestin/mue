import { defineConfig } from 'vite-plus'

// Task graph for the Mue monorepo (PRD_SERVER_SYNC_MCP.md section 20.1).
//
// `run.tasks` is deliberately empty, and the graph lives in `package.json` instead.
// PLATFORM-CONTRACT decision 7 requires every task to be a plain `package.json`
// script runnable by `bun run` alone, while Vite+ 0.3.0 rejects any task whose name
// also exists as a script in the same package:
//
//   Task mue#typecheck conflicts with a package.json script of the same name.
//
// So the two cannot both hold for the same name, and package.json wins. Nothing is
// lost: `vp run -r <task>` already orders packages by the workspace dependency graph
// declared in each package.json, which is what `dependsOn` would have expressed.
// This is also what makes section 24's mandated fallback a one-line swap per script
// (`vp run -r X` becomes `bun run --filter '*' X`) with no task rewritten.
//
// Caching is off for scripts, which is the conservative half of a real trade-off.
// Android is a top-level deliverable and Gradle alone owns its module graph and its
// up-to-date checks; a Vite+ fingerprint over that tree could report a cache hit for
// a build Gradle never performed. Since every task here is a script, disabling script
// caching is what guarantees Android is never cached. The cost is that the TypeScript
// packages are not cached either. Per-package opt-in can restore that later, once the
// packages own their own `vite.config.ts`.
export default defineConfig({
  run: {
    cache: {
      scripts: false,
      tasks: true,
    },
    tasks: {},
  },
})
