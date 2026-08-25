// Task graph for the Mue monorepo.
//
// Vite+ reads `run.tasks` from this file (PRD_SERVER_SYNC_MCP.md section 20.1).
// Every task here is a thin reference to a plain `package.json` script, so the
// whole graph stays runnable without Vite+ via `bun --filter '*' run <script>`.
//
// Android is a top-level deliverable: Gradle owns its module graph, dependencies
// and cache. Its tasks are never cached here, so this graph cannot claim a
// rebuild that Gradle did not actually perform.
//
// The Vite+ package itself is installed and pinned in phase 1, once its
// existence and version have been verified. Until then this file exports a
// plain object and imports nothing, so it is valid on a repository with no
// node_modules.

export default {
  run: {
    tasks: {
      build: { dependsOn: ['^build'] },
      typecheck: { dependsOn: ['^build'] },
      test: { dependsOn: ['^build'] },
      lint: {},
      format: {},
      check: { dependsOn: ['format', 'lint', 'typecheck'] },
      'android:assemble': { cache: false },
      'android:test': { cache: false },
    },
  },
}
