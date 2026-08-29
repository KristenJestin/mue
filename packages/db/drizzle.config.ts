import { defineConfig } from "drizzle-kit";

/**
 * Drizzle Kit is used for one thing: turning the schema into versioned SQL.
 *
 * It is never used to reach the database. `drizzle-kit push` and `pull` are
 * not wired to a script and must not be: push diffs against a live cluster and
 * would happily drop what it does not know about, and neither respects the
 * "migrations run explicitly at deploy" rule of section 20.3. Applying is
 * src/migrate.ts, which is also the only thing that holds credentials.
 *
 * `push` et `pull` sont d'ailleurs devenus bien plus dangereux qu'ils ne
 * l'étaient : Mue est maintenant dans `public`, sur un cluster que le
 * propriétaire partage entre toutes ses applications, et un diff contre une
 * base vivante y verrait les tables des autres comme des objets à supprimer.
 * `generate` ne se connecte à rien — il compare le schéma TypeScript aux
 * instantanés de `migrations/meta/` — et c'est la seule commande utilisée ici.
 */
export default defineConfig({
  dialect: "postgresql",
  schema: "./src/schema/index.ts",
  out: "./migrations",
  schemaFilter: ["public"],
  strict: true,
  verbose: true,
  // Present only because the config type asks for it. Nothing in the generate
  // path opens a connection; see the note above.
  dbCredentials: { url: process.env.DATABASE_URL ?? "postgres://unused" },
});
