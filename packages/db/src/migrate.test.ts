import { describe, expect, test } from "bun:test";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { readdir } from "node:fs/promises";
import { assertNoForbiddenStatements, defaultMigrationsFolder, splitStatements } from "./migrate";

/**
 * The standing proof of "the application creates no database, role or schema".
 * It needs no database: it reads what was committed.
 */

describe("the committed migrations", () => {
  test("contain no forbidden statement", async () => {
    const folder = defaultMigrationsFolder();
    const files = (await readdir(folder)).filter((name) => name.endsWith(".sql"));
    expect(files.length).toBeGreaterThan(0);
    for (const name of files) {
      const sql = await readFile(join(folder, name), "utf8");
      expect(() => assertNoForbiddenStatements(name, sql)).not.toThrow();
      expect(sql).not.toMatch(/create\s+schema/i);
    }
  });

  /**
   * L'inverse exact de l'assertion qui était ici.
   *
   * Elle exigeait que chaque `CREATE TABLE` soit qualifié par `mue_app` ou
   * `mue_auth`. Ces schémas n'existent plus : le cluster de production est celui
   * du propriétaire, partagé entre ses applications, et il n'en crée aucun pour
   * Mue. Une migration qui nommerait encore un schéma le nommerait donc à côté
   * de tables créées ailleurs — voir `tools/verify-migrations.ts`, qui porte la
   * règle et son raisonnement.
   */
  test("ne nomment aucun schéma", async () => {
    const folder = defaultMigrationsFolder();
    for (const name of (await readdir(folder)).filter((file) => file.endsWith(".sql"))) {
      const sql = await readFile(join(folder, name), "utf8");
      for (const line of sql.split("\n")) {
        if (/^\s*create table/i.test(line)) {
          expect(line).toMatch(/^\s*create table "[^".]+" \(/i);
        }
        expect(line).not.toMatch(/references\s+"[^"]+"\."/i);
      }
    }
  });

  /**
   * La propriété qui protège les autres applications du propriétaire.
   *
   * Les tables de Mue ne portent pas de préfixe — `user`, `session`, `account`,
   * `verification`, `jwks`, `measurements` — et vivent dans un schéma partagé.
   * `CREATE TABLE` sans `IF NOT EXISTS` fait donc échouer bruyamment une
   * collision de nom, au lieu de greffer Mue sur la table d'une autre
   * application. Si un jour Drizzle Kit se met à émettre `IF NOT EXISTS`, c'est
   * ce test qui le dit, et c'est un changement de nature du risque : pas une
   * migration à réparer, une décision à reprendre.
   */
  test("ne portent aucun IF NOT EXISTS", async () => {
    const folder = defaultMigrationsFolder();
    for (const name of (await readdir(folder)).filter((file) => file.endsWith(".sql"))) {
      const sql = await readFile(join(folder, name), "utf8");
      expect(sql).not.toMatch(/\bif\s+not\s+exists\b/i);
    }
  });
});

describe("the guard itself", () => {
  const forbidden = [
    'CREATE SCHEMA "mue_app";',
    'CREATE SCHEMA IF NOT EXISTS "drizzle";',
    "CREATE DATABASE mue;",
    "CREATE ROLE mue LOGIN;",
    "create user someone;",
    "DROP SCHEMA mue_app CASCADE;",
  ];

  for (const statement of forbidden) {
    test(`rejects ${statement}`, () => {
      expect(() => assertNoForbiddenStatements("probe", statement)).toThrow(/forbidden statement/);
    });
  }

  test("allows the DDL the Mue role actually owns", () => {
    expect(() =>
      assertNoForbiddenStatements(
        "probe",
        'CREATE TABLE "x" ("a" text);\nALTER TABLE "x" ADD COLUMN "b" text;\nCREATE INDEX "i" ON "x" ("a");',
      ),
    ).not.toThrow();
  });

  test("splits on the generator's statement breakpoints", () => {
    const sql = 'CREATE TABLE "a" ();\n--> statement-breakpoint\nCREATE TABLE "b" ();';
    expect(splitStatements(sql)).toEqual(['CREATE TABLE "a" ();', 'CREATE TABLE "b" ();']);
  });
});
