/**
 * The standing check behind "the application creates no database, role or
 * schema". It reads every emitted migration and fails on any statement that
 * would step outside the two pre-authorised schemas.
 *
 * It also re-checks that every table the SQL creates is schema-qualified: an
 * unqualified CREATE TABLE would land wherever `search_path` happens to point,
 * which in a shared cluster is somebody else.
 */
import { defaultMigrationsFolder, readMigrationFiles } from "../src/migrate";

const files = await readMigrationFiles(defaultMigrationsFolder());
if (files.length === 0) {
  console.error("no migrations found -- run `bun run generate` first");
  process.exit(1);
}

const unqualified: string[] = [];
const qualified = /^\s*create table (?:if not exists )?"(mue_app|mue_auth)"\."/i;
for (const file of files) {
  for (const line of file.sql.split("\n")) {
    if (/^\s*create table/i.test(line) && !qualified.test(line)) {
      unqualified.push(`${file.tag}: ${line.trim()}`);
    }
  }
}

if (unqualified.length > 0) {
  console.error("Unqualified CREATE TABLE, which would land outside the Mue schemas:");
  for (const line of unqualified) console.error(`  ${line}`);
  process.exit(1);
}

// readMigrationFiles already threw on CREATE DATABASE / ROLE / SCHEMA.
console.log(
  `${files.length} migration(s) checked: no CREATE DATABASE, CREATE ROLE or CREATE SCHEMA, ` +
    "every table qualified with mue_app or mue_auth.",
);
