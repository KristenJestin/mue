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
vite.config.ts     Vite+ task graph
```

## Android

`apps/android/` carries the full history of the application, rewritten under its
new path, so `git log` and `git blame` answer from it. Gradle remains the only
owner of the Android build.

```sh
cd apps/android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

`apps/android/local.properties` is not versioned. It must contain `sdk.dir`
pointing at the local Android SDK.

## Platform

Bun is the runtime, package manager and workspace manager. Dependency versions
are pinned once in the root `catalog` and referenced with `catalog:` from each
workspace. The catalog is populated in phase 1, after each dependency has been
verified to exist.

## Documents

`docs/` holds the PRD set. `docs/PRD_SERVER_SYNC_MCP.md` is the authority for
this module.
