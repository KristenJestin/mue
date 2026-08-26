# Mue

A single repository holding the Mue Android application and the Mue Platform
server.

## Layout

```text
apps/
  platform/        TanStack Start + Hono
  android/         Kotlin + Compose + Gradle
packages/
  api/             Hono routes and MCP
  auth/            Better Auth
  contracts/       HTTP schemas and OpenAPI
  db/              Drizzle and migrations
  domain/          server business services
  design-tokens/   future Web/Android foundation
  ui/              future shadcn foundation
infra/             Dockerfile and Compose files
docs/              PRD and scoping documents
proto/             manipulable HTML prototypes
scripts/           shared build utilities
package.json       workspaces and the version catalog
tsconfig.base.json shared TypeScript settings, strict
vite.config.ts     Vite+ run configuration
```

## Toolchain

Verified on Windows 11 with Bun 1.3.13.

| Tool | Version | Role |
|---|---|---|
| Bun | 1.3.13 | runtime, package manager, workspaces |
| Vite+ (`vite-plus`) | 0.3.0 | task runner, linter (oxlint), formatter (oxfmt) |
| TypeScript | 7.0.2 | `typecheck`, strict |

```sh
bun install
bun run check        # format:check, then lint, then typecheck
bun run typecheck
bun run lint
bun run format       # rewrites in place
```

Dependency versions are pinned once in the root `catalog` and referenced with
`catalog:` from each workspace. Every version in the catalog was installed and
resolved before it was written down.

Internal packages are consumed as TypeScript source: each points `exports` and
`types` at `src/index.ts`. There is no build step to run before `typecheck`, and
no project references to keep in sync.

### Tasks are plain scripts, on purpose

Every task is a `package.json` script that runs on its own:

```sh
cd packages/contracts && bun run typecheck
```

`vite.config.ts` only configures how Vite+ runs them; it defines no tasks of its
own. Vite+ 0.3.0 rejects a task whose name also exists as a script in the same
package, so the graph lives in `package.json` and nowhere else. Ordering is not
lost: `vp run` already sequences packages by the workspace dependency graph.

If Vite+ has to go, swap the orchestrator in the five root scripts and rewrite
nothing else:

```sh
vp run --filter '!android' typecheck      # becomes
bun run --filter '!android' typecheck
```

Note the argument order. `bun run --filter <pattern> <script>` works;
`bun --filter <pattern> run <script>` matches no packages.

`lint` and `format` are the one place Vite+ is more than an orchestrator: they
call `vp lint` and `vp fmt`, because the `oxlint` and `oxfmt` binaries that
`vite-plus` installs are IDE wrappers that refuse to run from a terminal.
Dropping Vite+ means adding the standalone `oxlint` and `oxfmt` packages and
changing those two script lines.

Task caching is off. Gradle alone decides what an Android build needs to redo,
and a Vite+ fingerprint over that tree could claim a cache hit for a build that
never happened. Since every task here is a script, disabling script caching is
what guarantees Android is never cached; the TypeScript packages lose caching as
the price.

## Android

`apps/android/` carries the full history of the application, rewritten under its
new path, so `git log` and `git blame` answer from it. Gradle remains the only
owner of the Android build; `apps/android/package.json` does nothing but pick the
right wrapper and hand over.

```sh
bun run android:assemble        # assembleDebug
bun run android:test            # testDebugUnitTest
bun run android:lint            # lintDebug
bun run android:clean
```

Android is excluded from the workspace-wide `typecheck`, `lint` and `format`
sweeps, so those stay fast and need no JDK or Android SDK.

Requirements:

- `apps/android/local.properties` is not versioned. It must contain `sdk.dir`
  pointing at the local Android SDK.
- `JAVA_HOME` must point at a JDK. Gradle provisions the Java 17 toolchain the
  build compiles against, but it still needs a JVM to start.

Running Gradle directly works exactly as before:

```sh
cd apps/android
./gradlew assembleDebug     # gradlew.bat on Windows
```

## Documents

Les PRD vivent **hors du dépôt**, à la racine du projet, un niveau au-dessus :
`../PRD.md`, `../PRD_SERVER_SYNC_MCP.md`, `../PRD_FOOD.md` et les autres. Ils
partagent cet espace avec `../proto/`, auquel ils renvoient, et avec les APKs.

C'est délibéré : la racine est l'espace de travail, le dépôt ne porte que ce qui
se construit. Conséquence à connaître : `git log` et `git blame` ne répondent
plus sur les spécifications au-delà du commit qui les a sorties.
