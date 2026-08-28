/**
 * Whether the process serves TLS itself, read from the environment.
 *
 * In a deployment it does not: section 20.5 puts the platform behind a reverse proxy
 * that terminates TLS, and giving the Bun process a certificate there would be a second
 * place to rotate it. So this is absent by default and nothing changes for anyone who
 * does not set the two variables.
 *
 * It exists for the one case the deployment does not cover: the owner's own server, on
 * his own WiFi, reached from his own phone. Section 16 admits nothing but HTTPS, and
 * `ServerAddresses.parse` on the Android side refuses `http://` by name with no
 * loopback exception, so there is no reverse proxy in front of a laptop and no way to
 * pair without one. `scripts/dev-tls-cert.ts` makes the certificate; this reads it.
 *
 * The function is pure and takes the environment as an argument so `tls.test.ts` can
 * exercise every branch without touching `process.env` or the disk.
 */

/** Where the certificate chain lives, in PEM. Set with {@link KEY_VARIABLE} or not at all. */
export const CERTIFICATE_VARIABLE = "MUE_TLS_CERT_FILE";

/** Where the matching private key lives, in PEM. */
export const KEY_VARIABLE = "MUE_TLS_KEY_FILE";

export interface TlsFiles {
  readonly certificateFile: string;
  readonly keyFile: string;
}

/**
 * The two paths, or `undefined` when neither variable is set.
 *
 * Setting exactly one throws rather than falling back to plaintext, and that is the
 * whole safety property of this module. A half-configured server that quietly listens
 * on `http://` is the failure that cannot be seen from the outside: the process starts,
 * the health check answers, and the first thing to notice is a phone refusing to pair
 * -- or, far worse, not refusing. Better Auth cookies and a sync token would have gone
 * over the network in clear before anyone read a log line.
 */
export function readTlsFiles(
  environment: Readonly<Record<string, string | undefined>>,
): TlsFiles | undefined {
  const certificateFile = environment[CERTIFICATE_VARIABLE]?.trim();
  const keyFile = environment[KEY_VARIABLE]?.trim();

  const hasCertificate = certificateFile !== undefined && certificateFile.length > 0;
  const hasKey = keyFile !== undefined && keyFile.length > 0;

  if (!hasCertificate && !hasKey) return undefined;
  if (!hasCertificate || !hasKey) {
    throw new Error(
      `${CERTIFICATE_VARIABLE} and ${KEY_VARIABLE} go together: ${
        hasCertificate ? KEY_VARIABLE : CERTIFICATE_VARIABLE
      } is missing. ` +
        "Set both to serve HTTPS, or neither to serve plain HTTP behind a proxy that " +
        "terminates TLS. Serving HTTP with one of them set would be a server that looks " +
        "configured and is not (PRD section 16).",
    );
  }

  return { certificateFile, keyFile };
}

/** The scheme the process actually listens on, for the line it prints at boot. */
export function schemeFor(tls: TlsFiles | undefined): "http" | "https" {
  return tls === undefined ? "http" : "https";
}
