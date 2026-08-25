/**
 * Drizzle Kit emits `CREATE SCHEMA "mue_app";` for a multi-schema project.
 * The application must not create a schema (PRD section 20.3, infra/README.md)
 * and the limited `mue` role cannot: PostgreSQL checks CREATE on the database
 * before it checks whether the schema already exists, so even
 * `create schema if not exists mue_app` is refused for a schema the role owns.
 *
 * This runs straight after `drizzle-kit generate` and removes those statements
 * from the emitted SQL. It is the only edit made to generated files, and
 * `verify-migrations.ts` fails the build if one ever survives.
 */
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

const MIGRATIONS = new URL("../migrations/", import.meta.url).pathname.replace(
  /^\/([A-Za-z]:)/,
  "$1",
);
const CREATE_SCHEMA = /^\s*CREATE SCHEMA (?:IF NOT EXISTS )?"[^"]+";\s*$/gim;

let stripped = 0;
for (const name of (await readdir(MIGRATIONS)).filter((f) => f.endsWith(".sql"))) {
  const path = join(MIGRATIONS, name);
  const original = await readFile(path, "utf8");
  const cleaned = original
    .replace(CREATE_SCHEMA, "")
    // A stripped statement leaves its `--> statement-breakpoint` behind.
    .replace(/^(?:\s*-->\s*statement-breakpoint\s*\n)+/, "")
    .replace(/\n{3,}/g, "\n\n")
    .trimStart();
  if (cleaned !== original) {
    await writeFile(path, cleaned);
    stripped += 1;
    console.log(`stripped CREATE SCHEMA from ${name}`);
  }
}
console.log(stripped === 0 ? "no CREATE SCHEMA to strip" : `${stripped} migration(s) rewritten`);
