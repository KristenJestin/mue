#!/usr/bin/env bun
/**
 * L'entrée du conteneur : appliquer les migrations, puis rendre la main au serveur.
 *
 * ## Pourquoi ce fichier existe, et ce qu'il change
 *
 * `AGENTS.md` §5 disait : « les migrations sont une étape explicite du déploiement,
 * jamais un effet de bord de `n` processus qui démarrent en même temps. » La règle
 * visait un vrai danger — plusieurs conteneurs migrant en concurrence — et ce danger
 * n'existe plus dans le code : `migrate()` prend un `pg_advisory_lock` depuis sa
 * première version, donc deux migrations simultanées se **sérialisent** au lieu de se
 * marcher dessus, et la seconde constate simplement qu'il n'y a rien à faire.
 *
 * Ce qui restait vrai de la règle est le refus de l'automatisme *implicite* : une
 * migration déclenchée par un effet de bord, sans que personne l'ait décidée, et qui
 * échoue dans le dos de tout le monde. La réponse n'est pas de la rendre manuelle
 * pour toujours — c'est de la rendre **décidée une fois** : elle est le premier acte
 * du conteneur, avant que le serveur n'écoute, et un échec empêche le conteneur de
 * servir au lieu de servir à moitié.
 *
 * Ce qui protège encore, et qui devient la garantie à lire : le lanceur refuse toute
 * instruction interdite (`verify-migrations.ts` : aucun schéma nommé, aucun
 * `IF NOT EXISTS`, aucun `CREATE DATABASE/ROLE/SCHEMA`), il est **additif et testé
 * depuis la version 1**, et il **n'émet aucun DDL de schéma**. Un conteneur qui migre
 * ne peut donc pas détruire de données.
 *
 * ## Ce qu'il ne fait pas
 *
 * Il ne remplace pas `bun run packages/db/src/migrate.ts` : cette commande reste le
 * chemin manuel, utile quand on veut migrer **sans** démarrer le serveur — par
 * exemple sur un déploiement arrêté, ou pour voir le résultat seul. Les deux appellent
 * exactement la même fonction.
 *
 * Il n'installe rien et ne crée ni base, ni rôle, ni schéma : c'est le travail de
 * l'administrateur du cluster (PRD 20.3), et `migrate.ts` le refuse à sa place.
 */

import { createDatabase, migrate, type DatabaseHandle } from "../packages/db/src/index";

/**
 * Combien de fois la connexion est retentée avant d'abandonner.
 *
 * PostgreSQL peut n'être pas encore prêt quand le conteneur démarre — c'est le cas
 * ordinaire d'un `docker compose up` qui relève la base et l'application ensemble, et
 * celui d'un cluster qui vient de redémarrer. Trente tentatives à une seconde couvrent
 * largement ce délai sans transformer une base durablement injoignable en conteneur
 * qui pend indéfiniment : au bout, il sort en erreur, et l'échec est visible.
 *
 * Le nombre est réglable par variable d'environnement parce qu'un déploiement qui
 * restaure une grosse base peut légitimement avoir besoin de plus.
 */
const CONNECT_ATTEMPTS = Number(process.env["MUE_DB_CONNECT_ATTEMPTS"] ?? 30);

/**
 * La connexion, une fois PostgreSQL joignable.
 *
 * `createDatabase()` est appelé **hors** de la boucle de retry, et c'est délibéré :
 * il lève immédiatement sur un `DATABASE_URL` absent ou illisible, ce qui est une
 * erreur de configuration, pas une indisponibilité passagère. Retenter trente fois une
 * variable manquante ne ferait qu'ajouter trente secondes avant le même message.
 */
async function connect(): Promise<DatabaseHandle> {
  const handle = createDatabase();

  for (let attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt += 1) {
    try {
      await handle.sql`select 1`;
      return handle;
    } catch (error) {
      if (attempt === CONNECT_ATTEMPTS) {
        await handle.close();
        throw error;
      }
      // La cause est imprimée **au premier essai seulement**, et la progression ensuite.
      // Constaté à l'exécution : sur une base durablement injoignable — un nom de base
      // qui n'existe pas, un mot de passe faux — les trente lignes « ne répond pas
      // encore » repoussent le vrai message hors de l'écran, et c'est le vrai message
      // qu'on vient lire dans les journaux d'un déploiement.
      const cause = attempt === 1 && error instanceof Error ? ` — ${error.message}` : "";
      console.log(
        `[mue] la base ne répond pas encore (tentative ${attempt}/${CONNECT_ATTEMPTS})${cause}`,
      );
      await Bun.sleep(1000);
    }
  }

  // Inatteignable : la boucle rend la main ou lève. Écrit pour que le type soit total
  // plutôt que pour dire quelque chose.
  await handle.close();
  throw new Error("unreachable");
}

async function main(): Promise<void> {
  const handle = await connect();
  try {
    const result = await migrate(handle);
    for (const tag of result.applied) console.log(`[mue] applied  ${tag}`);
    for (const tag of result.alreadyApplied) console.log(`[mue] current  ${tag}`);
    console.log(
      `[mue] ${result.applied.length} appliquée(s), ${result.alreadyApplied.length} déjà à jour.`,
    );
  } finally {
    await handle.close();
  }
}

try {
  await main();
} catch (error) {
  // Le message est imprimé sans la pile : cette ligne est lue par quelqu'un qui
  // regarde les journaux d'un déploiement, et une pile au-dessus de la phrase utile
  // ne fait que la repousser hors de l'écran. Le conteneur sort en erreur, donc le
  // serveur ne démarre pas : mieux vaut un déploiement rouge qu'une application qui
  // répond avec un schéma à moitié appliqué.
  console.error(
    `[mue] la migration a échoué : ${error instanceof Error ? error.message : String(error)}`,
  );
  process.exit(1);
}
