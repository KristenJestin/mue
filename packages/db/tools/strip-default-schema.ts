/**
 * La seule retouche faite à un fichier généré, et elle a changé d'objet.
 *
 * Ce fichier s'appelait `strip-create-schema.ts` et retirait les
 * `CREATE SCHEMA "mue_app";` que Drizzle Kit émet pour un projet multi-schémas.
 * Mue n'a plus de schéma à elle : les définitions utilisent `pgTable`, le
 * générateur n'émet plus aucun `CREATE SCHEMA`, et cette retouche-là n'avait
 * plus rien à retirer — constaté, pas supposé, sur la régénération complète.
 *
 * Il en reste une autre, et elle est moins visible. Drizzle Kit émet bien
 * `CREATE TABLE "user"` sans schéma, mais il **matérialise `public` dans les
 * clés étrangères** :
 *
 *   ALTER TABLE "session" ADD CONSTRAINT … REFERENCES "public"."user"("id")
 *
 * Son propre instantané, lui, écrit `"schema": ""` pour les 25 tables : le
 * modèle ne porte pas de schéma, c'est l'émetteur SQL qui remplit un défaut.
 * Laisser ce défaut serait garder le seul endroit où Mue nomme un schéma, et
 * ce serait le pire endroit possible — la table est créée là où pointe
 * `search_path`, la contrainte irait chercher `public`. Les deux d'accord, rien
 * ne se voit ; les deux en désaccord, la migration échoue... ou pire, si une
 * autre application du cluster partagé possède déjà `public.user`, la clé
 * étrangère de Mue se pose **sur la table de quelqu'un d'autre**.
 *
 * Retirer la qualification rend les deux moitiés solidaires : tables et
 * contraintes suivent le même `search_path`, quel qu'il soit. Elles ne peuvent
 * plus atterrir à deux endroits différents.
 *
 * `verify-migrations.ts` fait échouer la construction si une qualification
 * survit.
 */
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

const MIGRATIONS = new URL("../migrations/", import.meta.url).pathname.replace(
  /^\/([A-Za-z]:)/,
  "$1",
);

/**
 * `REFERENCES "public"."user"` -> `REFERENCES "user"`.
 *
 * Ancré sur `REFERENCES` plutôt que sur `"public".` seul : une contrainte
 * `CHECK ("agent_audit"."result" in …)` porte la même forme `"x"."y"` sans
 * qu'aucun schéma soit nommé, et un remplacement aveugle la casserait.
 */
const QUALIFIED_REFERENCE = /\bREFERENCES\s+"[^"]+"\.("[^"]+")/gi;

let rewritten = 0;
for (const name of (await readdir(MIGRATIONS)).filter((f) => f.endsWith(".sql"))) {
  const path = join(MIGRATIONS, name);
  const original = await readFile(path, "utf8");
  const cleaned = original.replace(QUALIFIED_REFERENCE, "REFERENCES $1");
  if (cleaned !== original) {
    await writeFile(path, cleaned);
    rewritten += 1;
    console.log(`stripped the default schema from the foreign keys of ${name}`);
  }
}
console.log(
  rewritten === 0
    ? "no schema-qualified reference to strip"
    : `${rewritten} migration(s) rewritten`,
);
