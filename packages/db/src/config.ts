/**
 * Configuration read from the environment. Nothing here has a value that is
 * only correct in development: a missing `DATABASE_URL` is a startup failure,
 * not a silent fallback to localhost.
 */

/** The schema names the Drizzle definitions hardcode. */
export const APP_SCHEMA = "mue_app";
export const AUTH_SCHEMA = "mue_auth";

/**
 * PLATFORM-CONTRACT decision 6. Tombstones and `mutation_log` rows must outlive
 * the longest resume window a client can present, so this is a configuration
 * value and not a constant: a deployment that supports longer offline periods
 * raises it without a code change.
 */
export const DEFAULT_RETENTION_DAYS = 180;

export interface DatabaseConfig {
  readonly url: string;
  readonly retentionDays: number;
  /** Connection pool size. One process, one small pool. */
  readonly maxConnections: number;
}

export type Env = Readonly<Record<string, string | undefined>>;

function required(env: Env, name: string): string {
  const value = env[name];
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is not set. See .env.example and infra/README.md.`);
  }
  return value;
}

function positiveInteger(env: Env, name: string, fallback: number): number {
  const raw = env[name];
  if (raw === undefined || raw.trim() === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer, got ${JSON.stringify(raw)}.`);
  }
  return value;
}

/**
 * The Drizzle definitions carry the schema names as TypeScript literals while
 * infra/ drives the same names from `MUE_APP_SCHEMA` and `MUE_AUTH_SCHEMA`.
 * Whoever changes one must change the other, so a mismatch fails loudly here
 * rather than as a "relation does not exist" during the first migration.
 */
export function assertSchemaNamesMatchEnvironment(env: Env = process.env): void {
  const mismatches: string[] = [];
  const app = env.MUE_APP_SCHEMA;
  const auth = env.MUE_AUTH_SCHEMA;
  if (app !== undefined && app !== APP_SCHEMA) {
    mismatches.push(`MUE_APP_SCHEMA=${app} but the Drizzle schema declares ${APP_SCHEMA}`);
  }
  if (auth !== undefined && auth !== AUTH_SCHEMA) {
    mismatches.push(`MUE_AUTH_SCHEMA=${auth} but the Drizzle schema declares ${AUTH_SCHEMA}`);
  }
  if (mismatches.length > 0) {
    throw new Error(
      `Schema name mismatch between infra/ and packages/db. ${mismatches.join("; ")}. ` +
        "Change both, as infra/README.md says.",
    );
  }
}

export function readDatabaseConfig(env: Env = process.env): DatabaseConfig {
  assertSchemaNamesMatchEnvironment(env);
  return {
    url: required(env, "DATABASE_URL"),
    retentionDays: positiveInteger(env, "MUE_RETENTION_DAYS", DEFAULT_RETENTION_DAYS),
    maxConnections: positiveInteger(env, "MUE_DB_POOL_SIZE", 10),
  };
}
