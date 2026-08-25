import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { APP_SCHEMA, AUTH_SCHEMA, readDatabaseConfig, type DatabaseConfig } from "./config";
import * as schema from "./schema";

export type Sql = postgres.Sql;
export type Database = ReturnType<typeof createDatabase>["db"];

export interface DatabaseHandle {
  readonly db: ReturnType<typeof drizzle<typeof schema>>;
  readonly sql: Sql;
  readonly config: DatabaseConfig;
  close(): Promise<void>;
}

/**
 * `DATABASE_URL` always carries the limited `mue` role, in development and in
 * production alike. The role cannot create a database, a role, or anything in
 * `public`, so a mistake in this package fails instead of reaching another
 * application on the shared cluster (infra/README.md).
 *
 * `search_path` is pinned on the connection as well as on the role: the role
 * default only applies inside the database it was set for, and a pooler or a
 * different database must not let an unqualified statement land in `public`.
 * Drizzle qualifies everything it emits, so this is belt and braces.
 */
export function createDatabase(config: DatabaseConfig = readDatabaseConfig()): DatabaseHandle {
  const sql = postgres(config.url, {
    max: config.maxConnections,
    connection: { search_path: `${APP_SCHEMA}, ${AUTH_SCHEMA}` },
    // A payload is health data and nothing about it belongs in a log by
    // default (section 16), so notices are dropped unless MUE_DB_DEBUG is set.
    ...(process.env.MUE_DB_DEBUG === undefined ? { onnotice: () => {} } : {}),
  });
  return {
    db: drizzle(sql, { schema }),
    sql,
    config,
    close: () => sql.end({ timeout: 5 }),
  };
}
