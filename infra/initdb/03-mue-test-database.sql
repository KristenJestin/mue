-- A throwaway database for the integration tests, so they never reach the one
-- a phone pairs with.
--
-- `resetSchemas` drops every table in `mue_app` and `mue_auth`. Its only guard
-- used to be "is this loopback", which the development cluster satisfies by
-- construction — so a bare `bun test` at the repository root emptied the
-- development database on 27 August, accounts and sessions included, with no
-- warning, because from that guard's point of view nothing was wrong.
--
-- `createTestDatabase` now rewrites the database name to `mue_test` rather than
-- refusing `mue_dev`: a refusal would only have turned a silent wipe into a red
-- test, and the next person would have reached for the override.

\set ON_ERROR_STOP on

\getenv mue_role MUE_DB_ROLE
\getenv app_schema MUE_APP_SCHEMA
\getenv auth_schema MUE_AUTH_SCHEMA

SELECT format('CREATE DATABASE mue_test OWNER %I', :'mue_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'mue_test')
\gexec

\connect mue_test

-- The same two schemas, owned by the same limited role, so a test exercises the
-- permissions the application actually has rather than a laxer copy.
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', schema_name, :'mue_role')
FROM unnest(ARRAY[:'app_schema', :'auth_schema']) AS schema_name
\gexec

SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, %I',
              :'mue_role', current_database(), :'app_schema', :'auth_schema')
\gexec
