# Mue infrastructure

| File | Purpose |
| --- | --- |
| `compose.dev.yml` | Development PostgreSQL 18: persistent, healthchecked, loopback-only. |
| `initdb/01-mue-role.sql` | Creates the disposable `mue` role and grants it `CREATE` on `public`. Development only. |
| `initdb/02-mue-test-database.sql` | Creates the throwaway `mue_test` database the suites work on. Development only. |

The `initdb/` scripts exist so that the two environments have the same shape.
That principle has not changed; what it now asks for is the opposite of what it
used to.

**Mue creates no schema, and nobody creates one for it.** The production
PostgreSQL belongs to the owner and is shared with his other applications. He
creates no schema and no role for Mue: the deployment gets credentials that
already exist, and the migrations create their tables in the schema the
connection's `search_path` points at — `public`, by PostgreSQL's own default.
There is therefore no production provisioning procedure any more; the section
that held one is gone, and what replaced it is below.

Development keeps a dedicated role because a container is disposable and
`down -v` takes it back to nothing. It keeps no schema, because production has
none.

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
# scripts re-run and the role, the test database and an empty `public` come back.
docker compose --env-file .env -f infra/compose.dev.yml down -v
docker compose --env-file .env -f infra/compose.dev.yml up -d
```

Only the volume carries state, so the reset is total: role, tables, migration
history and data all go. Nothing in development reads or writes the production
cluster.

The port is published on `127.0.0.1` so the platform can run on the host with
Bun and keep fast reload (§20.3) while nothing listens on a public interface
(§22.5). `5433` is the default, leaving a host PostgreSQL on `5432` alone.

A Compose profile starting the whole platform for integration tests, also
described in §20.3, needs the multi-stage Bun image of §20.5. It lands with
that Dockerfile; this file only ships the database.

## Production — what has to be run, and why it is nothing

This section used to be called "what the DBA must run" and carried seven SQL
statements: a role, a database, two `CREATE SCHEMA … AUTHORIZATION mue`, and an
`ALTER ROLE … SET search_path`. None of it will ever be run.

The cluster is the owner's, shared between all his applications, and the
decision is his: **no new schema, no new role.** Mue lives in the schema his
credentials already reach. A procedure describing a provisioning nobody will
perform is worse than no procedure — it reads as a prerequisite, and the first
person to follow it discovers the role cannot create a schema anyway.

So the deployment receives one value, and that is the whole of it:

```sh
DATABASE_URL=postgres://<role>:<password>@db.internal:5432/<database>
```

The role needs exactly two things, and an existing application role already has
them:

- `CONNECT` on the database;
- `USAGE` and `CREATE` on the schema its `search_path` resolves to.

If the first migration fails with `permission denied for schema public`, that
second grant is what is missing. It is one statement, run by whoever
administers the cluster, and it is the only one this document asks for:

```sql
GRANT USAGE, CREATE ON SCHEMA public TO <role>;
```

### What lives in that schema, and what protects the neighbours

Mue creates 25 tables and none of them carries a prefix: `user`, `session`,
`account`, `verification`, `jwks`, `measurements`, `foods`, `recipes` and the
rest. In a shared `public` those names can collide with another application's.

The protection is not a naming convention, it is a property of the emitted SQL:
**`CREATE TABLE` is emitted without `IF NOT EXISTS`.** A collision therefore
fails the migration, loudly, before anything is written — rather than grafting
Mue onto a table that belongs to somebody else and leaving a migration history
that believes it created what it merely borrowed.
`packages/db/tools/verify-migrations.ts` fails the build if that ever stops
being true, and `packages/db/src/journal.test.ts` proves it against a real
PostgreSQL by applying a migration twice.

The one table that does carry a prefix is `__mue_migrations`, the runner's own
bookkeeping. It is created with `IF NOT EXISTS` precisely because the prefix
makes it unmistakably Mue's.

Before handing the credentials over, check that the role is not wider than it
needs to be. All three of these must fail, connected as that role:

```sql
CREATE DATABASE mue_must_not_exist;      -- permission denied to create database
CREATE ROLE mue_must_not_exist LOGIN;    -- permission denied to create role
CREATE SCHEMA mue_must_not_exist;        -- permission denied for database
```

And this must succeed, since it is what a migration does:

```sql
CREATE TABLE mue_probe_delete_me (id int);
DROP TABLE mue_probe_delete_me;
```

### What the application must never do

- Create a database, a role or a schema. `packages/db/src/migrate.ts` refuses
  such a statement before executing any of a migration, and the migration files
  contain none.
- Name a schema. Not in a migration, not on the connection, not in a query. The
  `search_path` of the role is the one place that decides, and it belongs to
  whoever administers the cluster. Half a migration pinned to `public` and half
  unqualified is how tables and their foreign keys end up in two different
  places.
- Run migrations at process start. They run explicitly during deployment, as
  one step, never concurrently by each starting process (§20.3).
- Connect as the cluster owner. `DATABASE_URL` carries an ordinary application
  role.


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

Les tables de Mue sont dans le schéma courant, celui vers lequel pointe le
`search_path` du rôle — `public` par défaut, et le même pour les tables métier
et pour celles de l'authentification. Pour vérifier qu'une pesée est bien arrivée :

```sql
select date, weight_cg, revision, origin_type from measurements order by date desc;
```

Ce service n'existe qu'en développement : le fichier de déploiement n'en porte
aucun équivalent.
