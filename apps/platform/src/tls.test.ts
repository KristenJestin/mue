import { describe, expect, test } from "bun:test";
import { CERTIFICATE_VARIABLE, KEY_VARIABLE, readTlsFiles, schemeFor } from "./tls";

describe("reading the TLS configuration", () => {
  test("an environment that mentions neither variable serves plain HTTP", () => {
    // The deployment case of section 20.5: a proxy in front terminates TLS, and the
    // process must start exactly as it did before this module existed.
    expect(readTlsFiles({})).toBeUndefined();
    expect(readTlsFiles({ PORT: "3000", DATABASE_URL: "postgres://x" })).toBeUndefined();
    expect(schemeFor(undefined)).toBe("http");
  });

  test("both variables give the two paths", () => {
    const files = readTlsFiles({
      [CERTIFICATE_VARIABLE]: "certs/mue-dev-server.crt",
      [KEY_VARIABLE]: "certs/mue-dev-server.key",
    });
    expect(files).toEqual({
      certificateFile: "certs/mue-dev-server.crt",
      keyFile: "certs/mue-dev-server.key",
    });
    expect(schemeFor(files)).toBe("https");
  });

  test("surrounding whitespace is not part of a path", () => {
    // `.env` files are edited by hand and a trailing space is invisible.
    expect(readTlsFiles({ [CERTIFICATE_VARIABLE]: "  a.crt ", [KEY_VARIABLE]: "b.key  " })).toEqual(
      { certificateFile: "a.crt", keyFile: "b.key" },
    );
  });

  test("only the certificate refuses to start rather than falling back to HTTP", () => {
    // The point of the module: a server that looks configured and silently is not
    // would put a session cookie on the WiFi in clear.
    expect(() => readTlsFiles({ [CERTIFICATE_VARIABLE]: "a.crt" })).toThrow(KEY_VARIABLE);
  });

  test("only the key refuses too, and names the one that is missing", () => {
    expect(() => readTlsFiles({ [KEY_VARIABLE]: "b.key" })).toThrow(CERTIFICATE_VARIABLE);
  });

  test("an empty value counts as unset, on both sides", () => {
    // `MUE_TLS_CERT_FILE=` in a `.env` is how someone turns TLS off again.
    expect(readTlsFiles({ [CERTIFICATE_VARIABLE]: "", [KEY_VARIABLE]: "" })).toBeUndefined();
    expect(readTlsFiles({ [CERTIFICATE_VARIABLE]: "   ", [KEY_VARIABLE]: "" })).toBeUndefined();
    expect(() => readTlsFiles({ [CERTIFICATE_VARIABLE]: "a.crt", [KEY_VARIABLE]: " " })).toThrow(
      KEY_VARIABLE,
    );
  });
});
