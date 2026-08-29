/**
 * Configuration read from the environment. Nothing here has a value that is
 * only correct in development: a missing `DATABASE_URL` is a startup failure,
 * not a silent fallback to localhost.
 *
 * ## Il n'y a plus de nom de schéma ici, et c'est délibéré
 *
 * Ce fichier portait `APP_SCHEMA = "mue_app"` et `AUTH_SCHEMA = "mue_auth"`,
 * plus une fonction qui vérifiait que ces littéraux s'accordaient avec les
 * variables `MUE_APP_SCHEMA` / `MUE_AUTH_SCHEMA` d'`infra/`. Les trois ont
 * disparu ensemble.
 *
 * Le PostgreSQL de production appartient au propriétaire et il est partagé
 * entre toutes ses applications ; il n'y crée pour Mue ni schéma ni rôle. Mue
 * n'a donc plus de schéma à elle — mais remplacer `"mue_app"` par `"public"`
 * n'aurait rien gagné : c'est le même littéral écrit ailleurs, avec la même
 * façon de devenir faux. **Mue n'exprime aucun schéma.** Les définitions
 * Drizzle utilisent `pgTable`, la migration émet `CREATE TABLE "measurements"`
 * non qualifié, et l'endroit où cela atterrit est celui vers lequel pointe le
 * `search_path` de la connexion — celui du rôle que porte `DATABASE_URL`,
 * c'est-à-dire une décision de l'administrateur du cluster et pas du code.
 *
 * Ce qu'il reste à écrire quand un nom est vraiment nécessaire — le ménage des
 * tests dans `testing.ts` en a besoin pour interroger `pg_tables` — se résout à
 * l'exécution avec `current_schema()`, sur la connexion elle-même. Une
 * constante aurait pu mentir ; `current_schema()` ne le peut pas.
 */

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
    throw new Error(`${name} is not set. DATABASE_URL carries the Mue role; see infra/README.md.`);
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

export function readDatabaseConfig(env: Env = process.env): DatabaseConfig {
  return {
    url: required(env, "DATABASE_URL"),
    retentionDays: positiveInteger(env, "MUE_RETENTION_DAYS", DEFAULT_RETENTION_DAYS),
    maxConnections: positiveInteger(env, "MUE_DB_POOL_SIZE", 10),
  };
}
