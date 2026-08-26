// The integrity gate in front of every build.
//
// The ANSES tables are 71 MB and are not committed (PRD_FOOD 9.1 wants the subset
// regenerable, not the source vendored). What *is* committed is this manifest of
// where they came from and what they must hash to, so that "regenerable" means
// reproducible rather than "download something with a similar name and hope".
//
// A silent source swap is the failure this guards against. Ciqual reuses `alim_code`
// across releases and changes values under them; a 2020 file dropped into `.source`
// parses perfectly and yields a catalogue that is wrong food by food, with nothing on
// screen to show for it. So the build refuses to run on a hash mismatch rather than
// warning.

import { createHash } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";

import manifest from "../ciqual.source.json" with { type: "json" };

export interface SourceFileManifest {
  readonly fileName: string;
  readonly required: boolean;
  readonly recordTag: string;
  readonly bytes: number;
  readonly sha256: string;
  readonly publisherMd5: string;
  readonly downloadUrl: string;
  readonly readByBuild?: boolean;
}

export interface SourceManifest {
  readonly release: string;
  readonly version: string;
  readonly publishedOn: string;
  readonly landingPage: string;
  readonly archiveSha256: string;
  readonly sourceDirectory: string;
  readonly files: readonly SourceFileManifest[];
  readonly constituents: Readonly<Record<string, string>>;
}

export const sourceManifest = manifest as unknown as SourceManifest;

/** `packages/ciqual`, wherever the repository was cloned. */
export const packageRoot = resolve(import.meta.dir, "..");

export function sourceDirectory(): string {
  const override = process.env["CIQUAL_SOURCE_DIR"];
  return override !== undefined && override !== ""
    ? resolve(override)
    : join(packageRoot, sourceManifest.sourceDirectory);
}

export function sha256(bytes: Uint8Array | string): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * One hash for the release, derived from the per-file hashes.
 *
 * ANSES stopped shipping the 2025 tables as a single archive — the Ciqual landing page
 * links only the methodological PDF, and the data lives in the Recherche Data Gouv
 * repository as eight separate files. A repository-generated zip is not byte-stable
 * (it embeds a manifest and timestamps), so hashing one would produce a number that
 * differs on every download and gate nothing. Folding the file hashes instead gives a
 * single value that is stable, reproducible by hand with `sha256sum`, and unchanged by
 * however the files were transported.
 */
export function archiveDigest(hashes: ReadonlyMap<string, string>): string {
  const lines = [...hashes.keys()]
    .sort()
    .map((fileName) => `${hashes.get(fileName)}  ${fileName}\n`)
    .join("");
  return sha256(lines);
}

export interface SourceFileCheck {
  readonly fileName: string;
  readonly path: string;
  readonly present: boolean;
  readonly expectedSha256: string;
  readonly actualSha256: string | null;
  readonly expectedBytes: number;
  readonly actualBytes: number | null;
  readonly ok: boolean;
}

export interface SourceCheck {
  readonly ok: boolean;
  readonly directory: string;
  readonly files: readonly SourceFileCheck[];
  readonly expectedArchiveSha256: string;
  readonly actualArchiveSha256: string | null;
}

export class SourceMismatchError extends Error {
  constructor(readonly check: SourceCheck) {
    super(formatSourceCheck(check));
    this.name = "SourceMismatchError";
  }
}

export async function checkSource(): Promise<SourceCheck> {
  const directory = sourceDirectory();
  const hashes = new Map<string, string>();
  const files: SourceFileCheck[] = [];

  for (const file of sourceManifest.files) {
    const path = join(directory, file.fileName);
    let bytes: Buffer | null = null;
    let size: number | null = null;
    try {
      size = (await stat(path)).size;
      bytes = await readFile(path);
    } catch {
      bytes = null;
    }
    const actual = bytes === null ? null : sha256(bytes);
    if (actual !== null) hashes.set(file.fileName, actual);
    files.push({
      fileName: file.fileName,
      path,
      present: bytes !== null,
      expectedSha256: file.sha256,
      actualSha256: actual,
      expectedBytes: file.bytes,
      actualBytes: size,
      ok: actual === file.sha256,
    });
  }

  const complete = files.every((file) => file.ok);
  return {
    ok: complete,
    directory,
    files,
    expectedArchiveSha256: sourceManifest.archiveSha256,
    actualArchiveSha256: complete ? archiveDigest(hashes) : null,
  };
}

export function formatSourceCheck(check: SourceCheck): string {
  const lines = [
    `Ciqual source check failed in ${check.directory}`,
    `  expected archive sha256 ${check.expectedArchiveSha256}`,
    `  actual   archive sha256 ${check.actualArchiveSha256 ?? "(not computed: a file is missing or altered)"}`,
  ];
  for (const file of check.files) {
    if (file.ok) continue;
    lines.push(
      file.present
        ? `  ${file.fileName}: sha256 ${file.actualSha256} (${file.actualBytes} bytes), expected ${file.expectedSha256} (${file.expectedBytes} bytes)`
        : `  ${file.fileName}: missing`,
    );
  }
  lines.push("", "Download the files listed in SOURCES.md into that directory, then retry.");
  return lines.join("\n");
}

/** The build's first statement. Throws rather than returning a flag. */
export async function requireVerifiedSource(): Promise<SourceCheck> {
  const check = await checkSource();
  if (!check.ok || check.actualArchiveSha256 !== check.expectedArchiveSha256) {
    throw new SourceMismatchError(check);
  }
  return check;
}

export async function readSourceFile(fileName: string): Promise<string> {
  const raw = await readFile(join(sourceDirectory(), fileName), "utf8");
  // The tables are UTF-8 with a BOM; leaving it in makes the first element name of
  // every file a different string from the same name in every other file.
  return raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
}
