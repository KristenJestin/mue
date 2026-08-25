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
 * `schemaFilter` keeps the diff inside the two pre-authorised schemas, so an
 * object that appeared in `public` cannot show up as a drop in a migration.
 */
export default defineConfig({
  dialect: "postgresql",
  schema: "./src/schema/index.ts",
  out: "./migrations",
  schemaFilter: ["mue_app", "mue_auth"],
  strict: true,
  verbose: true,
  // Present only because the config type asks for it. Nothing in the generate
  // path opens a connection; see the note above.
  dbCredentials: { url: process.env.DATABASE_URL ?? "postgres://unused" },
});
