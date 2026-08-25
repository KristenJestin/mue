-- Development only; the production counterpart is in infra/README.md.
--
-- The Mue role owns its two schemas and nothing else. Migrations therefore
-- create and alter objects inside pre-authorised schemas only (§20.3), and a
-- runaway migration cannot reach another application on the shared cluster.

\set ON_ERROR_STOP on

\getenv mue_role MUE_DB_ROLE
\getenv app_schema MUE_APP_SCHEMA
\getenv auth_schema MUE_AUTH_SCHEMA

SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', schema_name, :'mue_role')
FROM unnest(ARRAY[:'app_schema', :'auth_schema']) AS schema_name
\gexec

-- Drizzle qualifies its tables, but an unqualified statement from psql or from
-- a tool must not land in `public`.
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, %I',
              :'mue_role', current_database(), :'app_schema', :'auth_schema')
\gexec
