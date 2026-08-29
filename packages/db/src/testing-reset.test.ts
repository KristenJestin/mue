import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import type { DatabaseHandle } from "./client";
import { migrate } from "./migrate";
import { createTestDatabase, resetSchemas } from "./testing";

/**
 * La couche du garde-fou de `resetSchemas` que seul un vrai PostgreSQL peut
 * prouver : **elle ne supprime que les tables que le schéma Drizzle déclare.**
 *
 * `testing-guard.test.ts`, à côté, éprouve les couches qui refusent d'ouvrir la
 * connexion — l'hôte et le nom de la base. Celles-là ont une échappatoire.
 * Celle-ci n'en a pas, et c'est la raison d'être de ce fichier : depuis que Mue
 * vit dans un schéma partagé avec les autres applications du propriétaire,
 * « toutes les tables du schéma courant » n'est plus une description de Mue.
 * Une table qui n'est pas dans `schema/` doit survivre, quoi qu'on pose dans
 * l'environnement.
 */

let handle: DatabaseHandle;
const DECOY = "une_autre_application";

beforeAll(async () => {
  handle = createTestDatabase();
  await resetSchemas(handle);
  await migrate(handle);
});

afterAll(async () => {
  await handle.sql`drop table if exists ${handle.sql(DECOY)}`;
  await handle.close();
});

describe("resetSchemas", () => {
  test("supprime les tables de Mue et laisse celles qui ne le sont pas", async () => {
    const { sql } = handle;

    // Le rôle de test possède cette table comme il possède celles de Mue, donc
    // ni le propriétaire ni le schéma ne la distinguent : seul son nom le fait.
    await sql`drop table if exists ${sql(DECOY)}`;
    await sql`create table ${sql(DECOY)} ("id" text primary key)`;
    await sql`insert into ${sql(DECOY)} ("id") values ('des données que Mue n''a pas écrites')`;

    await resetSchemas(handle);

    const survivors = await sql<{ id: string }[]>`select "id" from ${sql(DECOY)}`;
    expect(survivors).toHaveLength(1);

    // Et Mue, elle, est bien partie : sans quoi le test ci-dessus serait vrai
    // d'une fonction qui ne supprime rien du tout.
    const mueTables = await sql<{ tablename: string }[]>`
      select tablename from pg_tables
      where schemaname = current_schema() and tablename in ('measurements', 'user')
    `;
    expect(mueTables.map((row) => row.tablename)).toEqual([]);

    // Remis en état pour la suite : la base de test est partagée par tous les
    // fichiers de cette suite, qui s'exécutent l'un après l'autre.
    await migrate(handle);
  });
});
