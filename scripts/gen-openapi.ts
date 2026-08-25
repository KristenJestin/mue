#!/usr/bin/env bun
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
// A relative path, not the `@mue/contracts` specifier: the repository root is a workspace
// root with no dependencies of its own, and adding one there is a change to a file this
// script has no business owning.
import { buildOpenApiDocument, canonicalJson } from "../packages/contracts/src/index";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const outputPath = join(repoRoot, "packages", "contracts", "openapi.json");

// Generated twice and compared, because "deterministic" is a property of the generator
// and not of a single run: an unstable key order only shows when two runs disagree.
const first = canonicalJson(buildOpenApiDocument());
const second = canonicalJson(buildOpenApiDocument());

if (first !== second) {
  console.error("openapi generation is not deterministic: two runs produced different bytes");
  process.exit(1);
}

const checkOnly = process.argv.includes("--check");
const existing = await Bun.file(outputPath)
  .text()
  .catch(() => null);

if (checkOnly) {
  if (existing !== first) {
    console.error(
      `${outputPath} is stale. Run \`bun run --filter @mue/contracts openapi\` and commit the result.`,
    );
    process.exit(1);
  }
  console.log("openapi.json is up to date");
} else {
  if (existing !== first) {
    await Bun.write(outputPath, first);
  }
  console.log(`wrote ${outputPath}`);
}
