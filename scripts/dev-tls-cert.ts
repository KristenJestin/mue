#!/usr/bin/env bun
/**
 * The development certificate for a Mue Platform reached over the home network.
 *
 * PRD section 16 lets nothing but HTTPS carry a synchronisation, and the Android client
 * enforces it before a request is ever made: `ServerAddresses.parse` refuses `http://`
 * by name, with no exception for loopback or for private ranges. That is the right rule
 * and it is not negotiable, so the only way a phone talks to the server on the owner's
 * WiFi is for that server to hold a certificate the phone believes.
 *
 * No public authority will issue one for `192.168.1.100`, so this script makes a small
 * certificate authority that exists only on this machine, and one leaf certificate
 * signed by it. The CA is what gets installed on the phone (see `docs/TESTS-MANUELS.md`);
 * the leaf is what `Bun.serve` presents.
 *
 * Nothing it writes is ever committed: the output directory is git-ignored, and the
 * private keys never leave the machine that generated them.
 *
 * Usage, from the repository root:
 *
 *   bun run scripts/dev-tls-cert.ts                     # 192.168.1.100 + loopback
 *   bun run scripts/dev-tls-cert.ts --host 192.168.1.42 # after the router moves the PC
 *   bun run scripts/dev-tls-cert.ts --host mue.home.arpa --out certs
 *   bun run scripts/dev-tls-cert.ts --new-ca            # start over, phone included
 *
 * `--host` may be repeated. An argument that parses as an IPv4 or IPv6 literal becomes
 * an `IP:` entry in the subject alternative names, anything else a `DNS:` entry --
 * getting that wrong is the classic cause of a certificate that a browser accepts and
 * a phone rejects, because Android matches an address against `IP:` only.
 *
 * ## The authority is kept, the leaf is not
 *
 * An existing CA is reused rather than replaced, and that is the difference between a
 * change of address costing one command and costing a walk through Android's settings
 * screens with a cable. The router moving the PC to another lease is the expected event
 * here, and the only thing it invalidates is the *name* in the leaf. Re-running this
 * mints a new leaf under the same authority, so the certificate the phone already trusts
 * keeps vouching for it and nothing has to be installed again.
 *
 * `--new-ca` opts back into replacing it, for the case where the CA key is believed to
 * have leaked. It is the one path that does require reinstalling on the phone.
 */
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = join(dirname(fileURLToPath(import.meta.url)), "..");

/** The address the owner's PC holds on the home network today. */
const DEFAULT_HOSTS = ["192.168.1.100"];

/**
 * Loopback is always in the list, whatever `--host` says.
 *
 * `curl https://localhost:3000/...` from the machine running the server is the first
 * check anyone makes, and a certificate that only covers the LAN address fails it for a
 * reason that has nothing to do with the phone.
 */
const ALWAYS = ["localhost", "127.0.0.1", "::1"];

/**
 * 397 days, not ten years.
 *
 * Conscrypt -- the TLS stack behind every Android app -- is not obliged to honour a
 * leaf that outlives the public 398-day ceiling, and a certificate rejected for its
 * lifetime fails with the same opaque handshake error as one rejected for its name.
 * The CA is allowed to be long-lived because it is installed by hand and its expiry is
 * visible in Android's own credential screen.
 */
const LEAF_DAYS = 397;
const CA_DAYS = 3650;

interface Options {
  readonly hosts: readonly string[];
  readonly outputDirectory: string;
  /** Replace the authority as well, which means installing it on the phone again. */
  readonly newCertificateAuthority: boolean;
}

function parseArguments(argv: readonly string[]): Options {
  const hosts: string[] = [];
  let outputDirectory = "certs";
  let newCertificateAuthority = false;

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (argument === "--host") {
      if (value === undefined) throw new Error("--host needs an address");
      hosts.push(value);
      index += 1;
    } else if (argument === "--out") {
      if (value === undefined) throw new Error("--out needs a directory");
      outputDirectory = value;
      index += 1;
    } else if (argument === "--new-ca") {
      newCertificateAuthority = true;
    } else {
      throw new Error(`unknown argument: ${argument}`);
    }
  }

  return {
    hosts: hosts.length > 0 ? hosts : DEFAULT_HOSTS,
    outputDirectory: resolve(repositoryRoot, outputDirectory),
    newCertificateAuthority,
  };
}

/**
 * Whether `host` is an address literal rather than a name.
 *
 * Deliberately narrow: only shapes that are unambiguously literals become `IP:` entries,
 * and everything else becomes `DNS:`. A name misfiled as an IP produces an openssl error
 * at generation time, which is loud; an IP misfiled as a name produces a certificate that
 * fails the handshake on the phone, which is not.
 */
export function isAddressLiteral(host: string): boolean {
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    return host.split(".").every((part) => Number(part) <= 255);
  }
  return host.includes(":");
}

/** The `subjectAltName` value for `hosts`, deduplicated, loopback always included. */
export function subjectAltNames(hosts: readonly string[]): string {
  const all = [...hosts, ...ALWAYS];
  const seen = new Set<string>();
  const entries: string[] = [];
  for (const host of all) {
    if (seen.has(host)) continue;
    seen.add(host);
    entries.push(`${isAddressLiteral(host) ? "IP" : "DNS"}:${host}`);
  }
  return entries.join(",");
}

async function openssl(args: readonly string[]): Promise<void> {
  const process_ = Bun.spawn(["openssl", ...args], { stdout: "pipe", stderr: "pipe" });
  const exitCode = await process_.exited;
  if (exitCode !== 0) {
    const stderr = await new Response(process_.stderr).text();
    throw new Error(`openssl ${args[0]} failed (${exitCode}):\n${stderr}`);
  }
}

async function main(): Promise<void> {
  const { hosts, outputDirectory, newCertificateAuthority } = parseArguments(Bun.argv.slice(2));
  mkdirSync(outputDirectory, { recursive: true });

  const caKey = join(outputDirectory, "mue-dev-ca.key");
  const caCertificate = join(outputDirectory, "mue-dev-ca.crt");
  const serverKey = join(outputDirectory, "mue-dev-server.key");
  const serverCertificate = join(outputDirectory, "mue-dev-server.crt");
  const request = join(outputDirectory, "mue-dev-server.csr");
  const extensions = join(outputDirectory, "mue-dev-server.ext");

  const names = subjectAltNames(hosts);

  // Both halves have to be there for a reuse to mean anything: a certificate without its
  // key cannot sign, and a key without its certificate is not an authority anybody trusts.
  const caExists = existsSync(caKey) && existsSync(caCertificate);
  const reuseCa = caExists && !newCertificateAuthority;

  if (reuseCa) {
    console.log(`Reusing the authority already in ${outputDirectory}.`);
    console.log("The phone keeps the certificate it has; only the server's leaf changes.");
    console.log("");
  } else {
    // The CA. `-nodes` because there is no operator to type a passphrase at boot, and a
    // passphrase on a key that lives next to the certificate protects nothing anyway.
    await openssl([
      "req",
      "-x509",
      "-newkey",
      "rsa:4096",
      "-sha256",
      "-days",
      String(CA_DAYS),
      "-nodes",
      "-keyout",
      caKey,
      "-out",
      caCertificate,
      // The name Android shows in Settings once it is installed, so it says what it is
      // and who it belongs to rather than "Unknown".
      "-subj",
      "/CN=Mue development CA/O=Mue/OU=local network",
      "-addext",
      "basicConstraints=critical,CA:TRUE,pathlen:0",
      "-addext",
      "keyUsage=critical,keyCertSign,cRLSign",
    ]);
  }

  // The leaf, named for the address the phone will type.
  await openssl([
    "req",
    "-newkey",
    "rsa:2048",
    "-sha256",
    "-nodes",
    "-keyout",
    serverKey,
    "-out",
    request,
    "-subj",
    `/CN=${hosts[0]}/O=Mue`,
  ]);

  /**
   * The extensions live in a file rather than in `-addext`, because `x509 -req` ignores
   * `-addext` and takes only `-extfile`. A leaf signed without them carries no SAN at
   * all, and every modern client -- Android included -- has stopped falling back to the
   * common name, so the handshake fails with a name mismatch on a certificate whose CN
   * is visibly correct.
   */
  writeFileSync(
    extensions,
    [
      "basicConstraints=critical,CA:FALSE",
      "keyUsage=critical,digitalSignature,keyEncipherment",
      "extendedKeyUsage=serverAuth",
      `subjectAltName=${names}`,
      "",
    ].join("\n"),
  );

  await openssl([
    "x509",
    "-req",
    "-in",
    request,
    "-CA",
    caCertificate,
    "-CAkey",
    caKey,
    "-CAcreateserial",
    "-days",
    String(LEAF_DAYS),
    "-sha256",
    "-extfile",
    extensions,
    "-out",
    serverCertificate,
  ]);

  console.log(`Subject alternative names: ${names}`);
  console.log("");
  console.log("Wrote:");
  console.log(
    `  ${caCertificate}   ${
      reuseCa ? "unchanged, already on the phone" : "install this one on the phone"
    }`,
  );
  console.log(`  ${caKey}${reuseCa ? "   unchanged" : ""}`);
  console.log(`  ${serverCertificate}`);
  console.log(`  ${serverKey}`);
  console.log("");
  console.log("Point the server at it, in .env:");
  console.log(`  MUE_TLS_CERT_FILE=${serverCertificate}`);
  console.log(`  MUE_TLS_KEY_FILE=${serverKey}`);
  console.log(`  HOST=0.0.0.0`);
}

if (import.meta.main) {
  await main();
}
