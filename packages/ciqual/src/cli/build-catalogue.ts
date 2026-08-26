// `bun run catalogue:build` - regenerate the committed Android asset.
// `bun run catalogue:report` - the same pipeline, printing the report and writing nothing.
//
// Both are plain package.json scripts runnable by `bun run` alone, which PLATFORM-CONTRACT
// decision 7 requires of every task in this monorepo.

import { mkdir, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";

import { generateFromSource } from "../pipeline";
import { packageRoot, SourceMismatchError, sourceManifest } from "../source";

const dryRun = process.argv.includes("--dry-run");

const assetDirectory = resolve(packageRoot, "../../apps/android/app/src/main/assets/ciqual");

try {
  const generated = await generateFromSource();
  console.log(generated.report);

  if (dryRun) {
    console.log();
    console.log("--dry-run: nothing written.");
    process.exit(0);
  }

  await mkdir(assetDirectory, { recursive: true });
  const name = `catalogue-${sourceManifest.version}.json`;
  await writeFile(join(assetDirectory, name), generated.json, "utf8");
  // The checksum names the file it covers, so `sha256sum -c` works unmodified and a
  // reviewer can tell which catalogue a hash belongs to without opening it.
  await writeFile(
    join(assetDirectory, "catalogue.sha256"),
    `${generated.sha256}  ${name}\n`,
    "utf8",
  );

  console.log();
  console.log(`wrote ${join(assetDirectory, name)}`);
  console.log(`wrote ${join(assetDirectory, "catalogue.sha256")}`);
} catch (error) {
  if (error instanceof SourceMismatchError) {
    console.error(error.message);
    process.exit(1);
  }
  throw error;
}
