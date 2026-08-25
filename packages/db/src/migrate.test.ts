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

  test("qualify every table with a pre-authorised schema", async () => {
    const folder = defaultMigrationsFolder();
    for (const name of (await readdir(folder)).filter((file) => file.endsWith(".sql"))) {
      const sql = await readFile(join(folder, name), "utf8");
      for (const line of sql.split("\n")) {
        if (!/^\s*create table/i.test(line)) continue;
        expect(line).toMatch(/create table (?:if not exists )?"(mue_app|mue_auth)"\./i);
      }
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
        'CREATE TABLE "mue_app"."x" ("a" text);\nALTER TABLE "mue_app"."x" ADD COLUMN "b" text;\nCREATE INDEX "i" ON "mue_app"."x" ("a");',
      ),
    ).not.toThrow();
  });

  test("splits on the generator's statement breakpoints", () => {
    const sql =
      'CREATE TABLE "mue_app"."a" ();\n--> statement-breakpoint\nCREATE TABLE "mue_app"."b" ();';
    expect(splitStatements(sql)).toEqual([
      'CREATE TABLE "mue_app"."a" ();',
      'CREATE TABLE "mue_app"."b" ();',
    ]);
  });
});
