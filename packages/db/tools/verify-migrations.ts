/**
 * Le contrôle qui tient debout à la place de l'ancien.
 *
 * ## Ce qu'il vérifiait, et pourquoi la règle ne pouvait plus s'appliquer
 *
 * Il refusait tout `CREATE TABLE` non qualifié, et la raison écrite était :
 * « sans schéma explicite, la table atterrit là où pointe `search_path`, ce qui
 * sur un cluster partagé est chez quelqu'un d'autre ». La règle supposait que
 * Mue possédait deux schémas à elle. Elle n'en possède plus aucun : le cluster
 * de production est celui du propriétaire, il n'y crée pour Mue ni schéma ni
 * rôle, et Drizzle émet donc du SQL non qualifié pour `pgTable`. Appliquée
 * telle quelle, la règle refuserait chacune des 25 tables.
 *
 * ## Ce qui la remplace
 *
 * Sa raison d'être — « une instruction ne doit pas atterrir chez quelqu'un
 * d'autre » — n'a pas disparu, elle a changé de mécanisme. Deux règles la
 * portent maintenant, et la seconde est la plus importante des deux.
 *
 * 1. **Aucune instruction ne nomme de schéma.** L'inverse exact de l'ancienne
 *    règle, et pour la même raison : le seul endroit qui décide est le
 *    `search_path` de la connexion, réglé par l'administrateur du cluster.
 *    Qu'une partie d'une migration nomme `public` et l'autre non, et les deux
 *    se retrouvent à deux endroits différents — voir `strip-default-schema.ts`,
 *    qui retire la qualification que le générateur pose sur les clés étrangères.
 *
 * 2. **Aucun `IF NOT EXISTS`.** C'est la propriété qui protège les autres
 *    applications du propriétaire, et elle vaut plus que l'ancienne isolation
 *    par schéma. Les tables de Mue ne portent pas de préfixe : `user`,
 *    `session`, `account`, `verification`, `jwks`, `measurements` sont des noms
 *    qu'une autre application peut déjà occuper dans le schéma partagé. Sans
 *    `IF NOT EXISTS`, une collision fait échouer la migration bruyamment, avant
 *    d'avoir rien écrit. Avec, Mue se grefferait en silence sur la table d'un
 *    autre — même nom, colonnes étrangères, et une suite de migrations qui
 *    croit avoir créé ce qu'elle n'a fait qu'emprunter. C'est le seul contrôle
 *    de ce fichier dont la disparition serait irrattrapable au moment où on
 *    s'en apercevrait.
 *
 * `readMigrationFiles` a déjà refusé les `CREATE DATABASE`, `CREATE ROLE` et
 * `CREATE SCHEMA` avant que ce fichier ne regarde quoi que ce soit.
 */
import { getTableName, is } from "drizzle-orm";
import { PgTable } from "drizzle-orm/pg-core";
import { defaultMigrationsFolder, readMigrationFiles } from "../src/migrate";
import * as schema from "../src/schema";

const files = await readMigrationFiles(defaultMigrationsFolder());
if (files.length === 0) {
  console.error("no migrations found -- run `bun run generate` first");
  process.exit(1);
}

/**
 * Les noms de table que le schéma Drizzle déclare.
 *
 * Ils servent à distinguer les deux formes `"a"."b"` que le SQL généré porte :
 * `CHECK ("agent_audit"."result" …)` qualifie une colonne par sa table, ce qui
 * ne nomme aucun schéma, tandis que `REFERENCES "public"."user"` qualifie une
 * table par un schéma. Un préfixe qui n'est pas une table de Mue est donc un
 * nom de schéma.
 */
const tableNames = new Set<string>();
for (const value of Object.values(schema) as unknown[]) {
  if (is(value, PgTable)) tableNames.add(getTableName(value));
}

const QUALIFIER = /"([^"]+)"\."[^"]+"/g;
const IF_NOT_EXISTS = /\bif\s+not\s+exists\b/i;

const namesASchema: string[] = [];
const conditional: string[] = [];

for (const file of files) {
  for (const line of file.sql.split("\n")) {
    for (const match of line.matchAll(QUALIFIER)) {
      const qualifier = match[1];
      if (qualifier !== undefined && !tableNames.has(qualifier)) {
        namesASchema.push(`${file.tag}: ${line.trim()}`);
        break;
      }
    }
    if (IF_NOT_EXISTS.test(line)) {
      conditional.push(`${file.tag}: ${line.trim()}`);
    }
  }
}

if (namesASchema.length > 0) {
  console.error(
    "A migration names a schema. Mue names none: where a statement lands is the " +
      "connection's search_path, and half a migration pinned to one schema would " +
      "separate the tables from their constraints.",
  );
  for (const line of namesASchema) console.error(`  ${line}`);
  console.error("Run `bun run generate`, which strips the qualifier the generator adds.");
  process.exit(1);
}

if (conditional.length > 0) {
  console.error(
    "A migration uses IF NOT EXISTS. Mue's tables carry no prefix and live in a schema " +
      "shared with the owner's other applications, so a name collision must fail the " +
      "migration rather than graft Mue onto somebody else's table.",
  );
  for (const line of conditional) console.error(`  ${line}`);
  process.exit(1);
}

console.log(
  `${files.length} migration(s) checked: no CREATE DATABASE, CREATE ROLE or CREATE SCHEMA, ` +
    "no schema named anywhere, no IF NOT EXISTS.",
);
