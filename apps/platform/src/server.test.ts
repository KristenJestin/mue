import { describe, expect, test } from "bun:test";
import { createEdgeApp } from "./edge";
import entry, { DELEGATED_PREFIXES, isDelegatedPath } from "./server";

describe("delegation", () => {
  test("claims each prefix and everything under it", () => {
    for (const prefix of DELEGATED_PREFIXES) {
      expect(isDelegatedPath(prefix)).toBe(true);
      expect(isDelegatedPath(`${prefix}/anything/deep`)).toBe(true);
    }
  });

  test("claims nothing that merely starts with the same characters", () => {
    for (const path of ["/", "/apidocs", "/mcpanel", "/healthz", "/login", "/consent"]) {
      expect(isDelegatedPath(path)).toBe(false);
    }
  });

  test("answers a delegated path without reaching TanStack Start", async () => {
    const response = await entry.fetch(new Request("http://localhost/health/live"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ok" });
  });

  test("hands everything else to TanStack Start", async () => {
    const response = await entry.fetch(new Request("http://localhost/"));
    // Start's own failure — there is no Vite build in a unit test — rather than Hono's
    // structured 404, which is the proof the request left the delegated half.
    expect(await response.text()).not.toContain("http.not_found");
  });
});

describe("health", () => {
  test("liveness never consults a dependency", async () => {
    const app = createEdgeApp({
      readinessChecks: [{ name: "database", probe: () => false }],
    });
    const response = await app.fetch(new Request("http://localhost/health/live"));
    expect(response.status).toBe(200);
  });

  test("readiness is ready when nothing has declared a dependency yet", async () => {
    const response = await createEdgeApp().fetch(new Request("http://localhost/health/ready"));
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "ready", checks: [] });
  });

  test("readiness reports each dependency by name and nothing else", async () => {
    const app = createEdgeApp({
      readinessChecks: [
        { name: "database", probe: () => true },
        {
          name: "migrations",
          probe: () => {
            throw new Error("postgres://mue:hunter2@127.0.0.1:5433/mue_dev unreachable");
          },
        },
      ],
    });
    const response = await app.fetch(new Request("http://localhost/health/ready"));
    expect(response.status).toBe(503);

    const body = await response.text();
    expect(JSON.parse(body)).toEqual({
      status: "not_ready",
      checks: [
        { name: "database", status: "ok" },
        { name: "migrations", status: "failed" },
      ],
    });
    // Section 20.5: no personal data, and no credentials the driver put in its message.
    expect(body).not.toContain("hunter2");
  });
});

describe("unmounted delegated routes", () => {
  test("answer in the wire error shape rather than Hono's HTML default", async () => {
    const response = await createEdgeApp().fetch(new Request("http://localhost/api/v1/sync/push"));
    expect(response.status).toBe(404);
    expect(await response.json()).toMatchObject({
      error: { code: "http.not_found", retryable: false },
    });
  });
});
