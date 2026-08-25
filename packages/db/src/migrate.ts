import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createDatabase, type DatabaseHandle } from "./client";
import { APP_SCHEMA } from "./config";

/**
 * The migration runner.
 *
 * Drizzle ships one, and it cannot be used: `PgDialect.migrate` issues
 * `create schema if not exists <migrationsSchema>` unconditionally, and
 * PostgreSQL checks CREATE on the database before it checks whether the schema
 * exists. So the limited `mue` role is refused even for a schema it already
 * owns:
 *
 *   ERROR:  permission denied for database mue_dev
 *
 * That is not a misconfiguration to work around, it is section 20.3 working as
 * intended. This runner therefore keeps its own bookkeeping table inside
 * `mue_app`, a schema the DBA already granted, and issues no DDL outside it.
 *
 * Migrations run explicitly, as a deployment step. Nothing here is called at
 * process start: several starting processes would race, which section 20.3
 * forbids in as many words.
 */

const MIGRATIONS_TABLE = "__mue_migrations";
const STATEMENT_SEPARATOR = "--> statement-breakpoint";
const ADVISORY_LOCK_KEY = 4_073_411_207;

/** Statements the application must never issue, whatever a generator emits. */
const FORBIDDEN = [
  /\bcreate\s+database\b/i,
  /\bcreate\s+(unique\s+)?role\b/i,
  /\bcreate\s+user\b/i,
  /\bcreate\s+schema\b/i,
  /\bdrop\s+database\b/i,
  /\bdrop\s+schema\b/i,
] as const;

export interface MigrationFile {
  readonly tag: string;
  readonly path: string;
  readonly sql: string;
  readonly hash: string;
}

export interface MigrationResult {
  readonly applied: readonly string[];
  readonly alreadyApplied: readonly string[];
}

export function defaultMigrationsFolder(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "..", "migrations");
}

/**
 * Reject any forbidden statement before a single one is executed. The limited
 * role would refuse them anyway; failing here names the file and the statement
 * instead of leaving half a migration applied.
 */
export function assertNoForbiddenStatements(tag: string, sql: string): void {
  for (const pattern of FORBIDDEN) {
    const match = pattern.exec(sql);
    if (match !== null) {
      throw new Error(
        `Migration ${tag} contains a forbidden statement: ${JSON.stringify(match[0])}. ` +
          "The application creates no database, role or schema (PRD section 20.3); " +
          "strip it from the generated SQL.",
      );
    }
  }
}

export async function readMigrationFiles(folder: string): Promise<MigrationFile[]> {
  const entries = (await readdir(folder)).filter((name) => name.endsWith(".sql")).sort();
  const files: MigrationFile[] = [];
  for (const name of entries) {
    const path = join(folder, name);
    const sql = await readFile(path, "utf8");
    const tag = name.slice(0, -".sql".length);
    assertNoForbiddenStatements(tag, sql);
    files.push({
      tag,
      path,
      sql,
      hash: createHash("sha256").update(sql).digest("hex"),
    });
  }
  return files;
}

export function splitStatements(sql: string): string[] {
  return sql
    .split(STATEMENT_SEPARATOR)
    .map((statement) => statement.trim())
    .filter((statement) => statement.length > 0);
}

export async function migrate(
  handle: DatabaseHandle,
  folder: string = defaultMigrationsFolder(),
): Promise<MigrationResult> {
  const files = await readMigrationFiles(folder);
  const { sql } = handle;

  // CREATE TABLE inside a schema the role owns, which is all the role can do.
  await sql`
    create table if not exists ${sql(APP_SCHEMA)}.${sql(MIGRATIONS_TABLE)} (
      tag text primary key,
      hash text not null,
      applied_at timestamptz not null default now()
    )
  `;

  // A deploy runs this once, but a retried deploy or a second operator must not
  // apply the same file twice. The lock is session-scoped and released on exit.
  await sql`select pg_advisory_lock(${ADVISORY_LOCK_KEY})`;
  try {
    const recorded = await sql<{ tag: string; hash: string }[]>`
      select tag, hash from ${sql(APP_SCHEMA)}.${sql(MIGRATIONS_TABLE)}
    `;
    const known = new Map(recorded.map((row) => [row.tag, row.hash]));
    const applied: string[] = [];
    const alreadyApplied: string[] = [];

    for (const file of files) {
      const previous = known.get(file.tag);
      if (previous !== undefined) {
        if (previous !== file.hash) {
          throw new Error(
            `Migration ${file.tag} was already applied with a different content. ` +
              "An applied migration is immutable: add a new one instead.",
          );
        }
        alreadyApplied.push(file.tag);
        continue;
      }

      // One transaction per file: a failure leaves the database on the last
      // complete migration, never halfway through one.
      await sql.begin(async (tx) => {
        for (const statement of splitStatements(file.sql)) {
          await tx.unsafe(statement);
        }
        await tx`
          insert into ${sql(APP_SCHEMA)}.${sql(MIGRATIONS_TABLE)} (tag, hash)
          values (${file.tag}, ${file.hash})
        `;
      });
      applied.push(file.tag);
    }

    return { applied, alreadyApplied };
  } finally {
    await sql`select pg_advisory_unlock(${ADVISORY_LOCK_KEY})`;
  }
}

/** `bun run src/migrate.ts` -- the deployment step, and nothing else. */
if (import.meta.main) {
  const handle = createDatabase();
  try {
    const result = await migrate(handle);
    for (const tag of result.applied) console.log(`applied  ${tag}`);
    for (const tag of result.alreadyApplied) console.log(`current  ${tag}`);
    console.log(
      `${result.applied.length} applied, ${result.alreadyApplied.length} already up to date.`,
    );
  } finally {
    await handle.close();
  }
}
