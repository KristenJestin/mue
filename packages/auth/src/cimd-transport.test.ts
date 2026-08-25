import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { readFile } from "node:fs/promises";
import {
  createPinnedMetadataFetch,
  MetadataFetchError,
  type PinnedFetchOptions,
} from "./cimd-transport";

/**
 * The transport is exercised against a real HTTPS server on a real socket.
 * The only thing stubbed is the address policy, and only where the server has
 * to be reachable: loopback is the one address a test can bind, and the policy
 * itself is checked exhaustively in ssrf.test.ts.
 */

const fixture = (name: string) => new URL(`../test/fixtures/${name}`, import.meta.url);

let server: ReturnType<typeof Bun.serve>;
let origin: string;
let ca: string;

beforeAll(async () => {
  const [cert, key] = await Promise.all([
    readFile(fixture("localhost-cert.pem"), "utf8"),
    readFile(fixture("localhost-key.pem"), "utf8"),
  ]);
  ca = cert;
  server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    tls: { cert, key },
    fetch(request) {
      const { pathname } = new URL(request.url);
      if (pathname === "/redirect") {
        return new Response(null, { status: 302, headers: { location: "https://example.com/" } });
      }
      if (pathname === "/permanent") {
        return new Response(null, { status: 301, headers: { location: "/metadata" } });
      }
      if (pathname === "/huge") {
        return new Response("x".repeat(4096));
      }
      return Response.json({ client_id: "https://agent.test/id", client_name: "probe" });
    },
  });
  origin = `https://localhost:${server.port}`;
});

afterAll(() => {
  server.stop(true);
});

/**
 * Loopback is allowed only so the server under test can be reached, and the
 * resolver is pinned to the address it binds: on this machine `localhost`
 * answers ::1 first, which is a different socket. Built per call because the
 * certificate is only read in `beforeAll`.
 */
const localFetch = (extra: Partial<PinnedFetchOptions> = {}) =>
  createPinnedMetadataFetch({
    isAddressAllowed: () => true,
    ca,
    resolve: async () => ["127.0.0.1"],
    ...extra,
  });

describe("the SSRF boundary", () => {
  test("rejects a hostname that resolves to a private address", async () => {
    // No policy override here, so the default RFC 6890 rule applies to a real
    // server that really is listening: the address is what stops it.
    const guarded = createPinnedMetadataFetch({ ca });
    await expect(guarded(`https://127.0.0.1:${server.port}/metadata`)).rejects.toThrow(
      /forbidden address: 127\.0\.0\.1 is loopback/,
    );
    // And by name, through the system resolver, whichever family it answers.
    await expect(guarded(`${origin}/metadata`)).rejects.toThrow(
      /localhost resolves to a forbidden address: .* is loopback/,
    );
  });

  test("rejects a cloud metadata address given as a literal", async () => {
    const guarded = createPinnedMetadataFetch();
    await expect(guarded("https://169.254.169.254/latest/meta-data/")).rejects.toThrow(
      /169\.254\.169\.254 is link-local/,
    );
  });

  test("rejects when only one of several DNS answers is private", async () => {
    // DNS rebinding: a name that answers publicly and privately is a target
    // whichever answer is used, so every answer has to pass.
    const guarded = createPinnedMetadataFetch({
      resolve: async () => ["93.184.215.14", "10.0.0.5"],
    });
    await expect(guarded("https://agent.test/id")).rejects.toThrow(/10\.0\.0\.5 is private-use/);
  });

  test("rejects a plaintext URL", async () => {
    const guarded = createPinnedMetadataFetch();
    await expect(guarded("http://agent.test/id")).rejects.toThrow(/must be https/);
  });

  test("rejects a method other than GET or HEAD", async () => {
    await expect(localFetch()(`${origin}/metadata`, { method: "POST" })).rejects.toThrow(
      /GET and HEAD/,
    );
  });
});

describe("redirects", () => {
  test("refuses a 302 instead of following it", async () => {
    await expect(localFetch()(`${origin}/redirect`)).rejects.toThrow(MetadataFetchError);
    await expect(localFetch()(`${origin}/redirect`)).rejects.toThrow(/refuses redirects.*302/);
  });

  test("refuses a 301 to a same-origin path just as firmly", async () => {
    await expect(localFetch()(`${origin}/permanent`)).rejects.toThrow(/refuses redirects.*301/);
  });
});

describe("what it does allow", () => {
  test("fetches a metadata document over a pinned connection", async () => {
    const response = await localFetch()(`${origin}/metadata`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      client_id: "https://agent.test/id",
      client_name: "probe",
    });
  });

  test("refuses a document larger than the cap", async () => {
    const capped = localFetch({ maxResponseBytes: 1024 });
    await expect(capped(`${origin}/huge`)).rejects.toThrow(/larger than 1024 bytes/);
  });
});
