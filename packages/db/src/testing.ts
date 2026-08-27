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

/**
 * Databases these helpers may empty.
 *
 * Loopback was the only guard, and it is the wrong question. The development
 * cluster *is* on loopback: it is the one the owner's phone pairs with and the
 * one Adminer reads. A bare `bun test` at the repository root therefore dropped
 * every table in `mue_app` and `mue_auth` — accounts, sessions and all — which
 * is exactly what happened on 27 August. Nothing warned, because from
 * `assertLoopback`'s point of view nothing was wrong.
 *
 * What has to be asked is not *where* the database is but *whether anyone is
 * using it*. So a name is required, and the development database's name is
 * deliberately not on the list. A test run that wants a clean cluster points
 * `DATABASE_URL` at one of these, or sets the escape hatch below and owns the
 * consequence.
 */
const DISPOSABLE_DATABASES: readonly string[] = ["mue_test", "postgres"];

/** The escape hatch, spelled out so it cannot be set by accident. */
const OVERRIDE = "MUE_ALLOW_DESTRUCTIVE_TESTS";

function assertDisposable(url: string): void {
  assertLoopback(url);

  const name = new URL(url).pathname.replace(/^\//, "");
  if (DISPOSABLE_DATABASES.includes(name)) return;
  if (process.env[OVERRIDE] === "yes-destroy-it") return;

  throw new Error(
    `refusing to drop every table in "${name}": it is not a disposable database.\n` +
      `Disposable names are ${DISPOSABLE_DATABASES.join(", ")}. ` +
      `"${name}" is likely the development cluster a phone pairs with.\n` +
      `Point DATABASE_URL at a throwaway database, or set ${OVERRIDE}=yes-destroy-it ` +
      "if you truly mean to empty this one.",
  );
}

/**
 * A handle on the throwaway database, whatever `DATABASE_URL` happens to name.
 *
 * It *redirects* rather than validates. Refusing the development database would
 * only have turned a silent wipe into a red test, and the next person would
 * have reached for the override. Tests want an empty cluster; the development
 * one is never it, so the name is replaced and the rest of the URL — host,
 * port, role, password — is kept.
 *
 * `mue_test` is created by `infra/initdb`. Set `MUE_TEST_DATABASE` to point
 * somewhere else.
 */
export function createTestDatabase(): DatabaseHandle {
  const config = readDatabaseConfig();
  assertLoopback(config.url);

  const url = new URL(config.url);
  url.pathname = `/${process.env["MUE_TEST_DATABASE"] ?? "mue_test"}`;

  return createDatabase({ ...config, url: url.toString() });
}

/**
 * Drop every table the Mue role owns in both schemas, leaving the schemas
 * themselves alone -- the role could not recreate them (infra/README.md).
 * This is what makes "apply the migrations to an empty database" testable
 * without destroying the Docker volume.
 */
export async function resetSchemas(handle: DatabaseHandle): Promise<void> {
  assertDisposable(handle.config.url);
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
