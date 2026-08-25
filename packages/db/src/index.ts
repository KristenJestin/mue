import { pgSchema } from "drizzle-orm/pg-core";

/**
 * The application never creates anything outside a pre-authorised schema, so
 * `mue_app` is declared here but provisioned as a documented DBA step in infra/.
 * Any CREATE SCHEMA that Drizzle Kit emits is stripped from the migration.
 */
export const mueApp = pgSchema("mue_app");
