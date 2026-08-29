import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { readDatabaseConfig, type DatabaseConfig } from "./config";
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
 * `DATABASE_URL` porte le rôle Mue, en développement comme en production, et
 * jamais le propriétaire du cluster (infra/README.md).
 *
 * ## Pourquoi cette connexion ne pose plus de `search_path`
 *
 * Elle en posait un : `search_path = mue_app, mue_auth`, présenté comme une
 * ceinture par-dessus des bretelles, puisque Drizzle qualifiait alors tout ce
 * qu'il émettait. Les deux schémas n'existent plus — le cluster de production
 * est celui du propriétaire, partagé entre ses applications, et il n'en crée
 * aucun pour Mue — et Drizzle émet désormais du SQL **non qualifié**.
 *
 * La tentation était donc d'écrire `search_path = public` ici. C'est refusé, et
 * pour une raison précise : ce serait Mue qui déciderait, depuis son code, dans
 * quel schéma d'une base qui ne lui appartient pas ses tables atterrissent. Le
 * `search_path` d'un rôle est une décision de l'administrateur du cluster, il
 * se règle par `ALTER ROLE … SET search_path` ou par `DATABASE_URL`, et le
 * défaut de PostgreSQL — `"$user", public` — envoie déjà dans `public`. En ne
 * posant rien, Mue suit ce réglage au lieu de le contredire : déplacer Mue
 * ailleurs devient une commande SQL sur le rôle, pas une modification de ce
 * fichier.
 *
 * Conséquence à connaître avant de revenir en arrière : c'est ce `search_path`,
 * et lui seul, qui dit où lit et où crée chaque instruction non qualifiée. Un
 * rôle dont il pointerait ailleurs y emmènerait Mue en entier — tables,
 * migrations et suivi de migrations ensemble, jamais à moitié.
 */
export function createDatabase(config: DatabaseConfig = readDatabaseConfig()): DatabaseHandle {
  const sql = postgres(config.url, {
    max: config.maxConnections,
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
