import { getTableName, is } from "drizzle-orm";
import { PgTable } from "drizzle-orm/pg-core";
import { createDatabase, type DatabaseHandle } from "./client";
import { readDatabaseConfig } from "./config";
import { MIGRATIONS_TABLE } from "./migrate";
import * as schema from "./schema";

/**
 * Helpers the integration tests use. They are deliberately in `src/` and not
 * in a test file: `resetSchemas` drops tables, and what stops it dropping the
 * wrong ones has to be reviewable.
 *
 * ## Ce que le passage à un schéma partagé a changé ici
 *
 * Cette fonction supprimait toutes les tables de `mue_app` et de `mue_auth`,
 * deux schémas dont Mue était le seul occupant. Le rayon d'explosion était donc
 * borné par la provision : ce que Mue n'avait pas créé n'était pas dans ces
 * schémas, et une erreur de cible ne pouvait pas atteindre autre chose.
 *
 * Cette borne a disparu. Mue vit maintenant là où pointe le `search_path` de la
 * connexion, sur un cluster que le propriétaire partage entre toutes ses
 * applications, et « toutes les tables du schéma courant » y désigne aussi
 * celles des autres. La même fonction, au même endroit, avec la même faute de
 * frappe dans un `DATABASE_URL`, ne détruit plus les données de Mue mais celles
 * d'applications qui n'ont jamais entendu parler d'elle.
 *
 * Deux destructions ont déjà eu lieu sur cette machine cette semaine (AGENTS.md
 * §5 et §7). Le garde-fou est donc reconstruit en couches, et la dernière est
 * celle qui compte : **`resetSchemas` ne peut supprimer que les tables que le
 * schéma Drizzle déclare**, quel que soit l'environnement, quelle que soit
 * l'URL, et sans échappatoire. Une table absente de `schema/` n'est atteignable
 * par aucun chemin.
 */

/** Couche 1 : l'hôte. */
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
 * La base jetable, résolue une fois et lue par les deux fonctions publiques.
 *
 * `createTestDatabase` y redirige et `resetSchemas` n'accepte qu'elle : les
 * deux ne peuvent donc pas diverger, et pointer les tests ailleurs se fait en
 * une variable plutôt qu'en deux.
 */
function disposableDatabaseName(): string {
  return process.env["MUE_TEST_DATABASE"] ?? "mue_test";
}

/** The escape hatch, spelled out so it cannot be set by accident. */
const OVERRIDE = "MUE_ALLOW_DESTRUCTIVE_TESTS";

/**
 * Couche 2 : le *nom* de la base, et lui seul.
 *
 * Le loopback était l'unique garde-fou et c'était la mauvaise question : le
 * cluster de développement *est* sur la boucle locale, c'est celui que le
 * téléphone du propriétaire appaire et qu'Adminer lit. Un `bun test` nu à la
 * racine a donc vidé `mue_dev` le 27 août, comptes et sessions compris, sans
 * que rien n'avertisse. La question est *qui s'en sert*, pas *où c'est*.
 *
 * **`postgres` a été retiré de la liste des bases jetables.** Elle y figurait
 * du temps où seules les tables de `mue_app` et `mue_auth` étaient supprimées :
 * dans la base `postgres` d'un cluster ordinaire ces schémas n'existent pas, et
 * la fonction n'y trouvait rien. Elle viderait aujourd'hui le schéma courant de
 * la base d'administration d'un cluster partagé — sur le serveur personnel du
 * propriétaire, ce n'est pas une base vide. Il ne reste donc qu'un seul nom,
 * celui d'une base créée pour être détruite.
 */
function assertDisposableDatabase(url: string): void {
  assertLoopback(url);

  const disposable = disposableDatabaseName();
  const name = new URL(url).pathname.replace(/^\//, "");
  if (name === disposable) return;
  if (process.env[OVERRIDE] === "yes-destroy-it") return;

  throw new Error(
    `refusing to drop Mue's tables in "${name}": it is not a disposable database.\n` +
      `The disposable database is "${disposable}". ` +
      `"${name}" is likely the development cluster a phone pairs with, or a shared database ` +
      "holding another application's data.\n" +
      `Point DATABASE_URL at the throwaway database, or set ${OVERRIDE}=yes-destroy-it ` +
      "if you truly mean to empty this one.",
  );
}

/**
 * Couche 4, et la seule qu'aucune variable d'environnement ne relâche : la
 * liste close des tables que Mue déclare.
 *
 * Elle est **dérivée du schéma Drizzle** et non écrite à la main. Une liste
 * recopiée serait vraie le jour où on l'écrit et fausse à la table suivante —
 * soit en laissant une table derrière elle, ce qui rend une suite rouge et se
 * corrige, soit, si on la corrigeait en l'élargissant, en autorisant une
 * suppression que personne n'a relue. Ici, une table absente de `schema/`
 * n'existe pas pour cette fonction.
 *
 * `__mue_migrations` s'y ajoute parce que c'est la seule table de Mue que
 * Drizzle ne déclare pas : `migrate.ts` la crée lui-même, et la laisser
 * derrière ferait croire à la migration suivante qu'elle a déjà tourné.
 */
function mueTableNames(): ReadonlySet<string> {
  const names = new Set<string>([MIGRATIONS_TABLE]);
  for (const value of Object.values(schema) as unknown[]) {
    if (is(value, PgTable)) names.add(getTableName(value));
  }
  return names;
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
  url.pathname = `/${disposableDatabaseName()}`;

  return createDatabase({ ...config, url: url.toString() });
}

/**
 * Supprime les tables de Mue, et rien d'autre, pour que « appliquer les
 * migrations sur une base vide » reste testable sans détruire le volume Docker.
 *
 * Le nom garde son pluriel d'origine — il supprimait les tables de deux
 * schémas — mais il n'y a plus de schéma à réinitialiser : il y a les tables de
 * Mue, là où la connexion les a mises.
 *
 * Couche 3 : le schéma visé est `current_schema()`, demandé à la connexion
 * elle-même. Aucun littéral n'est écrit ici, donc aucun ne peut mentir, et le
 * ménage suit exactement le `search_path` sous lequel les migrations ont créé
 * les tables. Un `search_path` qui ne désigne aucun schéma existant n'est pas
 * une raison de deviner : c'est une erreur.
 */
export async function resetSchemas(handle: DatabaseHandle): Promise<void> {
  assertDisposableDatabase(handle.config.url);
  const { sql } = handle;

  const [current] = await sql<{ schema: string | null }[]>`select current_schema() as schema`;
  const schemaName = current?.schema;
  if (schemaName === undefined || schemaName === null) {
    throw new Error(
      "current_schema() is null: the connection's search_path names no existing schema, " +
        "so there is nothing this could safely empty. Fix search_path on the role or in " +
        "DATABASE_URL (packages/db/src/client.ts).",
    );
  }

  const own = mueTableNames();
  // `tableowner = current_user` est une précaution de plus et non la
  // principale : elle ne coûte rien, elle ne peut rien filtrer à tort dans une
  // base jetable où le rôle a tout créé, et elle écarte l'homonyme d'une autre
  // application le jour où quelqu'un pose l'échappatoire sur la mauvaise base.
  const tables = await sql<{ tablename: string }[]>`
    select tablename from pg_tables
    where schemaname = ${schemaName} and tableowner = current_user
  `;

  for (const table of tables) {
    if (!own.has(table.tablename)) continue;
    await sql`drop table if exists ${sql(schemaName)}.${sql(table.tablename)} cascade`;
  }
}

/** A user row, because every synchronised table is keyed by one. */
export async function seedUser(handle: DatabaseHandle, id: string): Promise<string> {
  await handle.sql`
    insert into "user" ("id", "name", "email", "emailVerified")
    values (${id}, ${`test ${id}`}, ${`${id}@mue.test`}, false)
    on conflict ("id") do nothing
  `;
  return id;
}
