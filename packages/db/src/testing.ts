import { createDatabase, type DatabaseHandle } from "./client";
import { APP_SCHEMA, AUTH_SCHEMA, readDatabaseConfig } from "./config";

/**
 * Helpers the integration tests use. They are deliberately in `src/` and not
 * in a test file: `resetSchemas` empties both schemas, and the guard that
 * stops it reaching anything but a loopback database has to be reviewable.
 */

function assertLoopback(url: string): void {
  const { hostname } = new URL(url);
  const loopback =
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "::1" ||
    hostname === "[::1]";
  if (!loopback) {
    throw new Error(
      `refusing to reset a non-loopback database (${hostname}). ` +
        "The development cluster is disposable; a remote one is not.",
    );
  }
}

export function createTestDatabase(): DatabaseHandle {
  const config = readDatabaseConfig();
  assertLoopback(config.url);
  return createDatabase(config);
}

/**
 * Drop every table the Mue role owns in both schemas, leaving the schemas
 * themselves alone -- the role could not recreate them (infra/README.md).
 * This is what makes "apply the migrations to an empty database" testable
 * without destroying the Docker volume.
 */
export async function resetSchemas(handle: DatabaseHandle): Promise<void> {
  assertLoopback(handle.config.url);
  const { sql } = handle;
  const tables = await sql<{ schemaname: string; tablename: string }[]>`
    select schemaname, tablename from pg_tables
    where schemaname in (${APP_SCHEMA}, ${AUTH_SCHEMA})
  `;
  for (const table of tables) {
    await sql`drop table if exists ${sql(table.schemaname)}.${sql(table.tablename)} cascade`;
  }
}

/** A user row, because every synchronised table is keyed by one. */
export async function seedUser(handle: DatabaseHandle, id: string): Promise<string> {
  await handle.sql`
    insert into ${handle.sql(AUTH_SCHEMA)}."user" ("id", "name", "email", "emailVerified")
    values (${id}, ${`test ${id}`}, ${`${id}@mue.test`}, false)
    on conflict ("id") do nothing
  `;
  return id;
}
