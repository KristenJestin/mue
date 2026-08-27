# Mue infrastructure

| File | Purpose |
| --- | --- |
| `compose.dev.yml` | Development PostgreSQL 18: persistent, healthchecked, loopback-only. |
| `initdb/01-mue-role.sql` | Creates the limited `mue` role. Development only. |
| `initdb/02-mue-schemas.sql` | Creates `mue_app` and `mue_auth`, owned by that role. Development only. |

The `initdb/` scripts are the development mirror of what a DBA prepares by hand
on the shared production cluster. They exist so that the two environments have
the same shape, not so that anything creates a database or a role at runtime:
PRD §20.3 forbids the application container from creating either, in
development and in production alike. The production procedure is at the bottom
of this file; it is the only supported way to provision Mue on a real cluster.

## Development

Requires Docker with Compose v2 or later. Everything is configured from the
repository-root `.env`, which is git-ignored:

```sh
cp .env.example .env
```

Compose resolves `${...}` from the *project* directory, which is the directory
of the file passed to `-f` — here `infra/`. Point it at the root file
explicitly, from the repository root:

```sh
docker compose --env-file .env -f infra/compose.dev.yml up -d
```

Without `--env-file` the required variables are reported missing by name
instead of silently defaulting.

```sh
# state and health
docker compose --env-file .env -f infra/compose.dev.yml ps

# a psql shell as the limited role
docker compose --env-file .env -f infra/compose.dev.yml exec postgres \
  psql -h 127.0.0.1 -U mue -d mue_dev

# stop; the named volume and its data stay
docker compose --env-file .env -f infra/compose.dev.yml down

# reset: drop the volume, then start again on an empty cluster. The initdb
# scripts re-run and the two schemas come back empty.
docker compose --env-file .env -f infra/compose.dev.yml down -v
docker compose --env-file .env -f infra/compose.dev.yml up -d
```

Only the volume carries state, so the reset is total: role, schemas, migration
history and data all go. Nothing in development reads or writes the production
cluster.

The port is published on `127.0.0.1` so the platform can run on the host with
Bun and keep fast reload (§20.3) while nothing listens on a public interface
(§22.5). `5433` is the default, leaving a host PostgreSQL on `5432` alone.

A Compose profile starting the whole platform for integration tests, also
described in §20.3, needs the multi-stage Bun image of §20.5. It lands with
that Dockerfile; this file only ships the database.

## Production — what the DBA must run

Mue connects to the PostgreSQL already administered on the personal server
through `DATABASE_URL`. The Mue deployment ships no PostgreSQL container and
creates no database, no role and no schema. Run the following once, as a
superuser, **before the first deploy**.

Pick a password with `openssl rand -base64 24` and keep it out of the
repository. Replace the password placeholder and nothing else, unless the
database, role and schema names change in `DATABASE_URL` and in the Drizzle
schema definitions too.

```sql
-- 1. The limited Mue role. No superuser, no database creation, no role
--    creation: the cluster is shared with other applications.
CREATE ROLE mue LOGIN PASSWORD 'REPLACE_WITH_A_GENERATED_PASSWORD'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- 2. A dedicated database. Skip this statement if Mue is to live in an
--    existing shared database; the dedicated schemas below are what actually
--    isolate it.
CREATE DATABASE mue;

-- 3. Everything below runs inside that database.
\connect mue

-- 4. Nothing is granted to PUBLIC on a database holding personal data.
REVOKE ALL ON DATABASE mue FROM PUBLIC;
GRANT CONNECT ON DATABASE mue TO mue;

-- 5. No application owns objects in `public`. Already the default since
--    PostgreSQL 15; harmless to repeat, required on an older cluster.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- 6. The two Mue schemas, owned by the Mue role. Ownership is what lets the
--    migrations create and alter tables inside them -- and only inside them.
CREATE SCHEMA mue_app AUTHORIZATION mue;
CREATE SCHEMA mue_auth AUTHORIZATION mue;

-- 7. An unqualified statement must not land in `public`.
ALTER ROLE mue IN DATABASE mue SET search_path = mue_app, mue_auth;
```

Verify the result before handing the credentials over:

```sql
-- Expect exactly: mue_app | mue and mue_auth | mue.
SELECT nspname, pg_get_userbyid(nspowner) AS owner
FROM pg_namespace WHERE nspname IN ('mue_app', 'mue_auth') ORDER BY 1;

-- Expect f in every column.
SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname = 'mue';
```

Then, connected as `mue`, all three of these must fail. If any succeeds, the
role is too wide and step 1 was not applied as written:

```sql
CREATE DATABASE mue_must_not_exist;      -- permission denied to create database
CREATE ROLE mue_must_not_exist LOGIN;    -- permission denied to create role
CREATE TABLE public.mue_must_not_exist (id int);  -- permission denied for schema public
```

The deployment then receives one value:

```sh
DATABASE_URL=postgres://mue:PASSWORD@db.internal:5432/mue
```

### What the application must never do

- Create a database, a role or a schema. Drizzle Kit emits `CREATE SCHEMA` for
  a multi-schema project: strip it from the generated migration, and keep
  `mue_app` and `mue_auth` as pre-authorised schemas the migrations only fill.
- Run migrations at process start. They run explicitly during deployment, as
  one step, never concurrently by each starting process (§20.3).
- Connect as the cluster owner. `DATABASE_URL` carries the limited role.

### Backups

A Docker volume is not a backup (§20.5). The production database, the identity
configuration and the secrets needed for a restore are backed up by the
server's own documented procedure, and a restore is exercised on a clean
install. The development volume is disposable by design and is backed up by
nothing.

## Voir les données à l'œil (développement)

`docker compose -f infra/compose.dev.yml up -d` démarre aussi **Adminer** sur
<http://127.0.0.1:8081>, en boucle locale comme la base qu'il lit.

Il se connecte avec le rôle limité `mue`, pas avec le propriétaire : ce que
l'écran montre est exactement ce que l'application peut voir. Le mot de passe
n'est pas pré-rempli — Adminer n'a pas de variable pour ça, et un mot de passe
dans un fichier de composition est un mot de passe dans l'historique du shell.

| Champ | Valeur |
|---|---|
| Système | PostgreSQL |
| Serveur | `postgres:5432` |
| Utilisateur | la valeur de `MUE_DB_ROLE` dans `.env` |
| Mot de passe | la valeur de `MUE_DB_PASSWORD` |
| Base | la valeur de `POSTGRES_DB` |

Les tables métier sont dans le schéma `mue_app`, l'authentification dans
`mue_auth`. Pour vérifier qu'une pesée est bien arrivée :

```sql
select date, weight_cg, revision, origin_type from mue_app.measurement order by date desc;
```

Ce service n'existe qu'en développement : le fichier de déploiement n'en porte
aucun équivalent.
