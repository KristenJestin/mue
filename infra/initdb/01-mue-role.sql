-- Development only. In production the DBA runs the equivalent by hand before
-- the first deploy (infra/README.md): PRD §20.3 forbids the application
-- container from creating a database or a role, so nothing here ever runs from
-- application code. It runs once, from the postgres entrypoint, against
-- POSTGRES_DB as POSTGRES_USER.

\set ON_ERROR_STOP on

\getenv mue_role MUE_DB_ROLE
\getenv mue_password MUE_DB_PASSWORD

-- Built through format() + \gexec because CREATE ROLE takes no parameters and
-- psql does not interpolate variables inside a DO block's quoted body.
SELECT format(
         'CREATE ROLE %I LOGIN PASSWORD %L'
         ' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
         :'mue_role', :'mue_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'mue_role')
\gexec

-- The production cluster is shared with other applications: PUBLIC gets
-- nothing on the Mue database, and the Mue role gets connection rights only.
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database())
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'mue_role')
\gexec

-- No object of any application belongs in `public`. Already the default since
-- PostgreSQL 15; restated so the same script is safe on an older cluster.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
