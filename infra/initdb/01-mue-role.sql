-- Development only. It runs once, from the postgres entrypoint, against
-- POSTGRES_DB as POSTGRES_USER. PRD §20.3 forbids the application container
-- from creating a database or a role, so nothing here ever runs from
-- application code.
--
-- Ce script n'a **pas** d'équivalent en production, et c'est un changement.
-- Il en avait un : la procédure « what the DBA must run » d'infra/README.md
-- créait le même rôle et les deux mêmes schémas à la main. Le PostgreSQL de
-- production est celui du propriétaire, partagé entre toutes ses applications,
-- et il ne crée pour Mue ni rôle ni schéma : `DATABASE_URL` y porte des
-- identifiants qui existent déjà, et les migrations créent leurs tables dans le
-- schéma vers lequel pointe le `search_path` de ce rôle.
--
-- Le développement garde un rôle dédié parce qu'il est jetable — c'est un
-- conteneur, `down -v` le reprend depuis rien. Ce qui devait disparaître des
-- deux côtés, ce sont les schémas : voir infra/README.md, « the two
-- environments have the same shape ».

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

-- Le pseudo-rôle PUBLIC ne crée rien nulle part. C'est déjà le défaut depuis
-- PostgreSQL 15 ; répété pour qu'un cluster plus ancien se comporte pareil.
-- Ce n'est pas ce qui suit : `PUBLIC` est « tout le monde », le rôle Mue est
-- nommé juste après.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Ce que le rôle Mue reçoit, et qui remplace la possession de deux schémas.
--
-- `public` appartient au propriétaire de la base (`pg_database_owner` depuis
-- PostgreSQL 15), c'est-à-dire ici à POSTGRES_USER et non à `mue`. Sans ce
-- GRANT, la première migration échoue sur `permission denied for schema
-- public` — c'est la contrepartie exacte de l'ancien `CREATE SCHEMA …
-- AUTHORIZATION mue` : le droit de créer ses tables, et rien de plus.
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'mue_role')
\gexec

-- Aucun `search_path` n'est posé ici, et c'est délibéré.
--
-- Le script en posait un — `ALTER ROLE mue … SET search_path = mue_app,
-- mue_auth` — parce que les deux schémas devaient être trouvés. Le défaut de
-- PostgreSQL, `"$user", public`, envoie déjà dans `public` tant qu'aucun schéma
-- ne porte le nom du rôle, et c'est ce défaut que le code suit : Mue ne nomme
-- aucun schéma, ni dans ses migrations ni sur sa connexion
-- (packages/db/src/client.ts). Le régler ici en développement le ferait diverger
-- de la production, où personne ne le règlera.
