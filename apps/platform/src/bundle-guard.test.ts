import { describe, expect, test } from "bun:test";
import {
  MINIMUM_SECRET_LENGTH,
  SERVER_BUNDLE_FORBIDDEN,
  describeForbidden,
  findForbidden,
  leaksSecret,
} from "./bundle-guard";

describe("the server bundle guard", () => {
  test("catches the CLI guard that made the built server migrate at boot", () => {
    // The tail of packages/db/src/migrate.ts, as a bundler would have inlined it.
    const inlined = `
      var runtime = createPlatformRuntime();
      if (import.meta.main) {
        const handle = createDatabase();
        await migrate(handle);
      }
    `;
    const found = findForbidden(inlined);
    expect(found.map((marker) => marker.name)).toContain("import.meta.main");

    // The failure has to say where to look and what to do, because it fires during a
    // build and the reader has no stack trace to follow.
    const message = describeForbidden("dist/server/main.js", found);
    expect(message).toContain("dist/server/main.js");
    expect(message).toContain("SERVER_EXTERNALS");
  });

  test("names the migrator when its own statements come along", () => {
    const inlined = `create table if not exists mue_app.__mue_migrations (tag text primary key)`;
    const found = findForbidden(inlined);
    expect(found.map((marker) => marker.name)).toEqual(["the @mue/db migration runner"]);
    expect(describeForbidden("dist/server/main.js", found)).toContain("PRD section 20.3");
  });

  test("passes a bundle that only delegates and renders", () => {
    const clean = `
      import { createApiApp } from "@mue/api";
      import { Hono } from "hono";
      const server = Bun.serve({ port, hostname, fetch });
    `;
    expect(findForbidden(clean)).toEqual([]);
  });

  test("every marker explains itself, so a failing build is actionable", () => {
    for (const marker of SERVER_BUNDLE_FORBIDDEN) {
      expect(marker.why.length).toBeGreaterThan(0);
      expect(marker.name.length).toBeGreaterThan(0);
    }
  });
});

describe("the client bundle guard", () => {
  const secret = "a-real-master-secret-of-at-least-32-characters";

  test("catches the master secret verbatim (section 15.1)", () => {
    expect(secret.length).toBeGreaterThanOrEqual(MINIMUM_SECRET_LENGTH);
    expect(leaksSecret(`const s=${JSON.stringify(secret)};`, secret)).toBe(true);
  });

  test("passes a bundle that never saw it", () => {
    expect(leaksSecret('fetch("/api/auth/sign-in/email")', secret)).toBe(false);
  });

  test("says nothing when the build environment holds no secret to leak", () => {
    expect(leaksSecret("anything at all", undefined)).toBe(false);
    expect(leaksSecret("anything at all", "")).toBe(false);
  });

  test("ignores a value too short to be a secret rather than flagging ordinary words", () => {
    // `readAuthConfig` refuses anything under 32 characters, so a shorter value is a
    // placeholder. Matching on it would fail every build over the word it happens to be.
    expect(leaksSecret("const secret = 'dev';", "dev")).toBe(false);
  });
});
