-- A throwaway database for the integration tests, so they never reach the one
-- a phone pairs with.
--
-- `resetSchemas` drops Mue's tables. Its only guard used to be "is this
-- loopback", which the development cluster satisfies by construction — so a
-- bare `bun test` at the repository root emptied the development database on
-- 27 August, accounts and sessions included, with no warning, because from that
-- guard's point of view nothing was wrong.
--
-- `createTestDatabase` now rewrites the database name to `mue_test` rather than
-- refusing `mue_dev`: a refusal would only have turned a silent wipe into a red
-- test, and the next person would have reached for the override.
--
-- Ce fichier s'appelait `03-mue-test-database.sql`, derrière un
-- `02-mue-schemas.sql` qui créait `mue_app` et `mue_auth`. Ce dernier a disparu
-- avec les schémas : Mue vit là où pointe le `search_path`, et le
-- développement doit avoir la même forme que la production, où le propriétaire
-- ne créera aucun schéma.

\set ON_ERROR_STOP on

\getenv mue_role MUE_DB_ROLE

SELECT format('CREATE DATABASE mue_test OWNER %I', :'mue_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'mue_test')
\gexec

\connect mue_test

-- Le même droit qu'en `01-mue-role.sql`, pour la même raison : un test doit
-- exercer les permissions que l'application a vraiment, pas une copie plus
-- large. Le rôle possède cette base, donc il possède déjà son `public` par
-- `pg_database_owner` ; le GRANT est écrit quand même pour que les deux bases
-- se lisent pareil et qu'aucune des deux ne dépende de qui l'a créée.
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'mue_role')
\gexec
