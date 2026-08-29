# AGENTS.md

Ce fichier s'adresse aux agents de code qui travaillent sur Mue. Il complète
`README.md` : celui-ci décrit l'outillage à un lecteur humain, celui-ci décrit
les décisions qu'un agent peut défaire sans le savoir. Chaque règle est écrite
avec sa raison, parce qu'une règle sans raison est une règle que le prochain
passage supprime en la trouvant superflue.

`CLAUDE.md` renvoie ici ; il n'y a qu'une source.

---

## 1. Le projet

Mue est une application de suivi de poids, d'activité et d'alimentation, écrite
pour une seule personne — son propriétaire — et hébergée sur son propre serveur.
Ce n'est pas un produit multi-locataire, et beaucoup de choix ci-dessous n'ont de
sens qu'à cette échelle : pas de CI, pas de dépôt distant, une base PostgreSQL
partagée avec d'autres applications sur un serveur personnel, un téléphone
physique branché en permanence à la machine de développement.

Cette dernière donnée est celle qui coûte le plus cher quand on l'oublie. Voir
§7.

Un dépôt, deux moitiés :

- **`apps/android/`** — l'application Kotlin/Compose, construite par Gradle.
  C'est elle que le propriétaire utilise tous les jours.
- **`packages/` et `apps/platform/`** — le serveur TypeScript (Bun, Hono,
  Drizzle, PostgreSQL) qui synchronise l'application et expose un serveur MCP.

Les deux moitiés se parlent à travers `packages/contracts`, qui est la source de
vérité du fil (§8.7).

### Les spécifications ne sont pas dans le dépôt

Les PRD vivent **un niveau au-dessus de la racine** : `../PRD.md`,
`../PRD_SERVER_SYNC_MCP.md`, `../PRD_FOOD.md`, `../PRD_SCALE.md` et les autres,
à côté de `../proto/` et des APK. `docs/` et `proto/` sont explicitement
`.gitignore`-és.

Conséquence pratique : le code renvoie constamment aux PRD par numéro de section
(« PRD 16.3 », « PRD_FOOD 9.2 », « sync PRD 19 ») — 325 des 353 fichiers Kotlin
de `src/main` contiennent au moins une de ces références — mais `git log` et
`git blame` ne répondent pas sur ces documents. Si une référence semble fausse,
lire le PRD à la racine du projet ; ne pas la corriger au jugé.

---

## 2. Structure

```text
apps/
  platform/          TanStack Start + Hono, point d'entrée du serveur
  android/           Kotlin + Compose + Gradle (module :app et :benchmark)
packages/
  api/               routes Hono, transport et outils MCP
  auth/              Better Auth, OAuth, appairage d'agents
  ciqual/            générateur du catalogue alimentaire ANSES embarqué
  contracts/         schémas Zod partagés, openapi.json, fixtures
  db/                schéma Drizzle et migrations versionnées
  design-tokens/     socle Web/Android (embryonnaire)
  domain/            services métier serveur, implémentation unique des règles
  ui/                primitives shadcn (embryonnaire)
infra/               compose.dev.yml, scripts initdb, procédure de déploiement
scripts/             gen-openapi.ts, admin.ts, dev-tls-cert.ts, mue-server.ps1
```

`packages/design-tokens` et `packages/ui` ne portent aujourd'hui qu'un
`typecheck` : ils existent pour la suite, ne pas s'étonner de les trouver vides.

Les paquets internes sont consommés **en TypeScript source** : chacun pointe
`exports` et `types` sur `src/index.ts`. Il n'y a donc aucune étape de build
avant `typecheck`, et aucune *project reference* à tenir à jour.

---

## 3. Outillage et versions

Les versions ne sont pas des suggestions : elles sont épinglées une fois dans le
`catalog` de la `package.json` racine, puis référencées par `catalog:` depuis
chaque espace de travail. Côté Android, `gradle/libs.versions.toml` joue le même
rôle. Ne pas écrire un numéro de version dans une `package.json` de paquet.

| Outil | Version | Où c'est écrit |
|---|---|---|
| Bun | `1.4.0` (`devEngines`, `onFail: download`) | `package.json` |
| Vite+ (`vite-plus`) | `0.3.0` | `catalog` |
| TypeScript | `7.0.2` | `catalog` |
| Gradle | `9.1.0` (wrapper, SHA-256 vérifié) | `gradle-wrapper.properties` |
| Android Gradle Plugin | `9.0.1` | `libs.versions.toml` |
| Kotlin / KSP | `2.3.20` / `2.3.11` | `libs.versions.toml` |
| Compose BOM | `2026.03.01` | `libs.versions.toml` |
| Room | `2.8.4` | `libs.versions.toml` |
| Ktor | `3.2.4` — **volontairement pas la plus récente** | `libs.versions.toml` |
| compileSdk / targetSdk / minSdk | `36` / `36` / `26` | `app/build.gradle.kts` |
| Toolchain Java | 17 | `app/build.gradle.kts` |

> `README.md` annonce « Verified on Windows 11 with Bun 1.3.13 » alors que
> `devEngines` demande `1.4.0`. C'est le `package.json` qui fait foi ; le tableau
> du README a vieilli.

**Ktor reste en 3.2.4 par nécessité, pas par retard.** Un bloc
`resolutionStrategy.force` en fin de `app/build.gradle.kts` réaligne les trois
artefacts `kotlinx-serialization` sur 1.8.1, la version contre laquelle
`androidx.room:room-migration` est construit. 3.2.4 est la dernière version de
Ktor dont `ktor-serialization-kotlinx` demande exactement 1.8.1 ; à partir de
3.3.0 elle demande 1.9.0, ce qui remonterait le `force` et remettrait
`MigrationTestHelper` sur un runtime pour lequel il n'est pas compilé — symptôme :
`AbstractMethodError` sur `GeneratedSerializer.typeParametersSerializers`, à
l'intérieur de sérialiseurs générés, sans rapport apparent avec Ktor. Monter
Ktor au-delà de 3.2.x, c'est re-dériver ce `force` et relancer la suite
instrumentée, pas changer un chiffre.

### Il n'y a ni CI ni dépôt distant

`git remote -v` ne répond rien. Il n'existe aucun `.github/`, aucun pipeline.
Tout se passe en local : pas de *pull request*, pas de contrôle automatique qui
rattraperait un `bun run check` oublié. **C'est à l'agent de lancer les
vérifications avant de considérer un travail terminé.**

---

## 4. Commandes

Toutes les commandes se lancent depuis la racine du dépôt sauf mention
contraire.

### 4.1 Installation

```sh
bun install
```

### 4.2 La vérification qui compte

```sh
bun run check          # format:check, puis lint, puis typecheck
```

`check` **ne lance aucun test** : c'est un contrôle statique. Les tests se
lancent paquet par paquet (§4.4) parce qu'ils n'ont pas tous les mêmes besoins —
certains veulent une base PostgreSQL, d'autres non, et une commande unique
transformerait « PostgreSQL n'est pas démarré » en « la suite est rouge ».

Les tâches individuelles existent aussi :

```sh
bun run typecheck      # vp run --filter '!android' typecheck
bun run lint
bun run format         # réécrit sur place
bun run format:check
```

Android est **exclu** de ces balayages (`--filter '!android'`) : ils restent
rapides et n'exigent ni JDK ni SDK Android.

### 4.3 Ordre des arguments de `bun run --filter`

```sh
bun run --filter <motif> <script>     # fonctionne
bun --filter <motif> run <script>     # ne correspond à aucun paquet
```

L'inversion échoue en silence, sans erreur : elle rapporte simplement qu'aucun
paquet ne correspond. Deuxième forme équivalente, souvent plus lisible, puisque
chaque tâche est un script autonome :

```sh
cd packages/contracts && bun run typecheck
```

### 4.4 Tests TypeScript, par paquet

| Commande | PostgreSQL requis |
|---|---|
| `bun run --filter @mue/contracts test` | non |
| `bun run --filter @mue/ciqual test` | non |
| `bun run --filter @mue/db test` | **oui** |
| `bun run --filter @mue/domain test` | **oui** |
| `bun run --filter @mue/api test` | **oui** |
| `bun run --filter @mue/auth test` | **oui** |
| `bun run --filter @mue/platform test` | **oui** |

Les suites marquées « oui » appellent `createTestDatabase()` et parlent à un
vrai PostgreSQL en boucle locale. Aucune n'est simulée : le sujet du test est
précisément ce que la base fait — contraintes, transactions, `search_path`,
permissions du rôle limité. Voir §5 pour le démarrage du conteneur et §9.2 pour
le piège du parallélisme.

Les scripts de test portent `--env-file=../../.env` (ou `--env-file=.env` pour
la racine) : sans `.env`, ils échouent au démarrage sur `DATABASE_URL is not
set`. C'est voulu — `packages/db/src/config.ts` refuse de deviner.

### 4.5 Android

```sh
bun run android:assemble           # :app:assembleDebug
bun run android:assemble:release   # :app:assembleRelease
bun run android:test               # :app:testDebugUnitTest  (JVM, hors ligne)
bun run android:lint               # :app:lintDebug
bun run android:clean
```

`apps/android/package.json` ne fait que choisir le bon lanceur (`gradlew.bat`
sous Windows, `gradlew` ailleurs) et passer la main. Gradle reste le seul
propriétaire de la construction Android. La forme directe marche à l'identique :

```sh
cd apps/android
./gradlew :app:testDebugUnitTest      # gradlew.bat sous Windows
```

Toute tâche Gradle est atteignable par le délégué, sans passer par un `cd` :

```sh
bun run --filter android gradle :app:testDebugUnitTest
```

Prérequis, tous deux non versionnés :

- `apps/android/local.properties` doit contenir `sdk.dir` pointant sur le SDK ;
- `JAVA_HOME` doit désigner un JDK. Gradle provisionne lui-même la toolchain
  Java 17 contre laquelle le code est compilé, mais il lui faut une JVM pour
  démarrer.

Le même `local.properties` accepte trois clés **facultatives** :

```properties
mue.beta.server=http://192.168.1.100:3000
mue.beta.email=kris@example.org
mue.beta.password=<un mot de passe jetable, et rien d'autre — lire ci-dessous>
```

`mue.beta.server` est l'adresse, `mue.beta.email` le compte et
`mue.beta.password` son mot de passe, dont la variante `beta` pré-remplit les
champs de `Server settings`, pour que le propriétaire n'ait rien à retaper au
clavier d'un téléphone à chaque réinstallation de la bêta — le client Android
n'ayant pas de parcours d'inscription, le compte lui-même se sème avec
`admin.ts accounts create` (§4.9). Les trois voyagent par `resValue("string", …)`
comme `app_name` (§7), dans `default_server_address`, `default_account_email` et
`default_account_password`, ne sont lues par aucune autre variante, et
n'écrasent jamais ce qu'un téléphone déjà appairé porte ni une saisie en cours.
Elles sont lues indépendamment : en configurer une sans les autres est un état
ordinaire. **Absentes, rien ne change** : la bêta se construit et se comporte
exactement comme avant, champs vides et aucun message — il n'y a donc rien à
configurer pour qu'un dépôt fraîchement cloné compile.

**La troisième clé descend un mot de passe dans l'APK, en clair, et c'est
assumé.** Ce paragraphe disait l'inverse et le refusait ; la moitié factuelle de
ce refus reste vraie mot pour mot. Une `resValue` finit dans
`res/values/values.xml` à l'intérieur de l'APK, un APK se copie sur un
téléphone, se garde à côté des PRD et s'envoie pour être installé, et n'importe
qui le tenant relit cette table :

```sh
aapt2 dump resources app-beta.apk     # affiche default_account_password en clair
```

C'est la commande qui l'a prouvé sur l'artefact, pas une supposition : R8 renomme
des classes et ne touche pas à `res/values`, et `shrinkResources` retire les
ressources inutilisées au lieu de masquer celles qui servent.

Ce qui a été rejugé, c'est le coût de cette divulgation. Le compte visé est
**jetable** : il vit sur `mue_dev`, une base que `docker compose down -v` détruit
et que `admin.ts accounts create` regarnit, et le serveur qui le sert tourne sur
la machine du propriétaire, sur son réseau domestique, sans hébergement, sans nom
public et sans port ouvert. Qui extrait cette chaîne d'un APK a appris les
identifiants d'un compte qu'il ne peut pas joindre, gardant des données qu'une
commande recrée. Le secret ne protège rien qu'un attaquant puisse atteindre.

**Et c'est là toute la condition : cette clé ne reçoit qu'un mot de passe
jetable.** Jamais celui du propriétaire, jamais un qui ouvre aussi le serveur de
production, jamais un réutilisé quelque part où une personne ou un service
l'accepterait. L'argument n'est pas qu'un mot de passe dans un APK soit
acceptable ; c'est que *celui-là* ne coûte rien quand il fuit. Le jour où
`mue.beta.password` porte un mot de passe qui vaut quelque chose ailleurs — ou
le jour où le serveur de développement devient joignable depuis l'extérieur de
ce réseau — chaque ligne ci-dessus est fausse, et ce qui doit partir est la clé,
pas ce paragraphe.

La borne, elle, ne se lit pas dans une intention : `release` et `local` sont
l'application que le propriétaire porte, `debug` le bac à sable des
instrumentations, et les trois ressources y sont la chaîne vide. C'est vérifié
**sur le binaire** — `verifyReleaseCarriesNoBetaDefaults`, dans
`app/build.gradle.kts`, relit la table de ressources de l'APK `release` avec
`aapt2` et fait échouer `assembleRelease` si l'une des trois y arrive avec une
valeur. L'historique de ce fichier justifie cette méfiance : un
`applicationIdSuffix` posé depuis `buildTypes.configureEach` compilait, se
configurait, s'exécutait et ne faisait rien, et il a fallu `aapt dump badging`
sur l'APK pour s'en apercevoir (§7).

La commande de semis côté serveur continue de refuser un mot de passe en
argument, et c'est une règle distincte, pas une version affaiblie de celle-ci :
`admin.ts accounts create` le prend dans `MUE_ACCOUNT_PASSWORD` ou à une invite
parce qu'un argument part dans l'historique du shell et dans `argv` — donc dans
`ps` et le gestionnaire des tâches. Ce sont des fichiers et des tables de
processus sur la *machine de développement*, dont l'arbitrage ci-dessus ne dit
rien (§4.9).

Le cache de tâches Vite+ est **désactivé**. Gradle seul décide de ce qu'une
construction Android doit refaire, et une empreinte Vite+ sur cette arborescence
pourrait annoncer un *cache hit* pour une construction qui n'a jamais eu lieu.
Les paquets TypeScript perdent le cache au passage : c'est le prix.

### 4.6 Tests instrumentés

```sh
cd apps/android
./gradlew :app:connectedDebugAndroidTest
```

**Lire le §7 avant de lancer cette commande.** Elle installe puis désinstalle
une application sur *chaque appareil branché*, et l'appareil branché à cette
machine, à côté de l'émulateur, est le téléphone du propriétaire.

Une poignée de tests instrumentés (`Live*Test`) parlent à un vrai serveur et
s'auto-excluent tant qu'on ne leur donne pas d'adresse — sans quoi une suite
lancée sur une machine sans serveur serait rouge en permanence. On les lance
délibérément :

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=fr.kristenjestin.mue.ui.sync.LiveServerPairingTest \
  -Pandroid.testInstrumentationRunnerArguments.mueLiveServer=https://192.168.1.100:3000 \
  -Pandroid.testInstrumentationRunnerArguments.mueLiveEmail=… \
  -Pandroid.testInstrumentationRunnerArguments.mueLivePassword=…
```

Le compte doit préexister : le client Android n'a pas de parcours d'inscription.
`admin.ts accounts create` (§4.9) est ce qui le crée sur une base de
développement.

### 4.7 Régénérations

Aucune de ces sorties ne se modifie à la main. Chacune a un contrôle qui échoue
si elle a divergé.

```sh
# Fixtures de contrat lues par le test JVM ContractDriftTest.
# Écrit 33 fichiers dans apps/android/app/src/test/resources/contract/.
bun run --filter @mue/contracts fixtures

# openapi.json, généré deux fois et comparé — « déterministe » est une propriété
# du générateur, pas d'une exécution.
bun run --filter @mue/contracts openapi
bun run --filter @mue/contracts openapi:check    # échoue si le fichier est périmé

# Migrations Drizzle : génère, retire la qualification de schéma, puis vérifie.
bun run --filter @mue/db generate
bun run --filter @mue/db verify:sql              # le contrôle seul
bun run --filter @mue/db migrate                 # applique (jamais au démarrage)

# Catalogue alimentaire Ciqual (asset Android) et ses fixtures de test.
bun run --filter @mue/ciqual source:verify
bun run --filter @mue/ciqual catalogue:report    # à blanc
bun run --filter @mue/ciqual catalogue:build
bun run --filter @mue/ciqual fixtures:rebuild
```

`generate` enchaîne `drizzle-kit generate`, `tools/strip-default-schema.ts` et
`tools/verify-migrations.ts`. **Mue ne nomme aucun schéma** : les tables sont
déclarées avec `pgTable`, la migration émet `CREATE TABLE "measurements"` non
qualifié, et l'endroit où cela atterrit est celui vers lequel pointe le
`search_path` du rôle que porte `DATABASE_URL` — une décision de
l'administrateur du cluster, pas du code. Le PostgreSQL de production est celui
du propriétaire, partagé entre toutes ses applications ; il n'y crée pour Mue ni
schéma ni rôle.

`strip-default-schema.ts` **est la seule retouche faite à un fichier généré**.
Drizzle Kit émet bien les `CREATE TABLE` sans schéma, mais il matérialise
`public` dans les clés étrangères (`REFERENCES "public"."user"`) alors que son
propre instantané écrit `"schema": ""`. La laisser serait garder le seul endroit
où Mue nomme un schéma, et le pire : la table créée là où pointe `search_path`,
la contrainte cherchée dans `public`. Le tout est retiré, donc les deux moitiés
suivent le même `search_path`.

`verify-migrations.ts` porte les deux règles qui remplacent l'ancienne
« tout `CREATE TABLE` doit être qualifié » :

1. **aucune instruction ne nomme de schéma** — l'inverse exact, pour la même
   raison ;
2. **aucun `IF NOT EXISTS`** — c'est la règle qui compte. Les tables de Mue ne
   portent pas de préfixe (`user`, `session`, `account`, `verification`, `jwks`,
   `measurements`) et vivent dans un schéma partagé avec les autres applications
   du propriétaire. Sans `IF NOT EXISTS`, une collision de nom fait échouer la
   migration bruyamment ; avec, Mue se grefferait en silence sur la table de
   quelqu'un d'autre. Si Drizzle Kit se met un jour à en émettre, ce n'est pas
   une migration à réparer, c'est une décision à reprendre.

La seule table de Mue qui porte un préfixe est `__mue_migrations`, la
comptabilité du lanceur, et c'est ce préfixe qui autorise son
`create table if not exists`.

### 4.8 Faire tourner le serveur

```sh
bun run --filter @mue/platform dev      # vp dev, rechargement rapide
bun run --filter @mue/platform build    # vp build -> apps/platform/dist/
bun run --filter @mue/platform start    # bun run dist/server/main.js
```

Le serveur se démarre **depuis la racine du dépôt**, parce que c'est de là que
Bun charge `.env`, qui porte `BETTER_AUTH_SECRET` et le reste. Pour un serveur
qui survive à la session qui l'a lancé, et pour que `NODE_EXTRA_CA_CERTS` soit
posé avant le démarrage de Bun, passer par `scripts/mue-server.ps1` (§4.9).

### 4.9 Scripts d'exploitation locale

```sh
bun --env-file=.env run scripts/admin.ts sessions list
bun --env-file=.env run scripts/admin.ts agents revoke <clientId>
bun --env-file=.env run scripts/admin.ts accounts create <email> [nom]
bun run scripts/dev-tls-cert.ts            # autorité + certificat de dev
./scripts/mue-server.ps1 start|stop|restart|status|logs
```

`accounts create` sème un compte de développement, parce que le client Android
n'a pas de parcours d'inscription (§4.6) et que le propriétaire recrée `mue_dev`
avec `down -v`. Quatre choses à en savoir :

- **Elle passe par Better Auth**, jamais par un `INSERT`. Un compte utilisable
  est deux lignes : `user` et `account`, cette dernière portant le mot de passe
  haché par la fonction de Better Auth, dans le format que sa propre
  vérification relit. Une ligne écrite à la main donne un compte qu'Adminer
  montre et qui répond `INVALID_EMAIL_OR_PASSWORD` à l'appairage, exactement
  comme un mot de passe faux.
- **Le mot de passe n'est jamais un argument.** Il vient de
  `MUE_ACCOUNT_PASSWORD`, ou d'une invite sans écho quand l'entrée standard est
  un terminal. Un argument, lui, part dans l'historique du shell, dans `argv` —
  donc dans `ps` et le gestionnaire des tâches — et à l'écran. Minimum 12
  caractères (`minPasswordLength`, `packages/auth/src/auth.ts`), refusés ici
  plutôt que par une `APIError` de Better Auth.
- **Elle refuse toute base qui n'est pas une base de développement**, sur deux
  couches conjointes : boucle locale *et* nom dans `{mue_dev, mue_test}`. La
  production se joint par un nom et s'appelle `mue`, donc elle échoue aux deux.
  Il n'y a pas d'échappatoire par variable, à la différence de
  `MUE_ALLOW_DESTRUCTIVE_TESTS` (§5) : une base jetable peut légitimement
  s'appeler autrement, « semer un compte de test sur la production » non.
- **Elle est rejouable.** Un compte déjà présent n'est pas une erreur : elle le
  dit, n'écrit rien, ne remplace pas le mot de passe et sort en 0.

`mue-server.ps1` existe pour deux raisons constatées : `NODE_EXTRA_CA_CERTS`
doit être dans l'environnement **avant** que Bun démarre (Bun initialise son
magasin TLS au lancement, une variable posée dans `.env` est lue trop tard), et
un serveur lancé depuis une session d'agent meurt avec elle — le processus est
donc détaché.

---

## 5. PostgreSQL de développement

```sh
cp .env.example .env
docker compose --env-file .env -f infra/compose.dev.yml up -d
```

`--env-file .env` n'est pas facultatif : Compose résout `${...}` depuis le
répertoire du fichier passé à `-f`, c'est-à-dire `infra/`. Sans lui, les
variables requises sont signalées manquantes par leur nom plutôt que remplacées
en silence par un défaut.

| Service | Conteneur | Port (boucle locale) |
|---|---|---|
| PostgreSQL 18 | `mue-dev-postgres` | `127.0.0.1:5433` |
| Adminer | `mue-dev-adminer` | `127.0.0.1:8081` |

`5433` laisse tranquille un PostgreSQL déjà installé sur l'hôte en `5432`. La
publication est en boucle locale : aucun service Mue n'écoute sur une interface
publique sans avoir été configuré pour.

Trois bases entrent en jeu :

- **`mue_dev`** — la base de développement, celle que le téléphone appaire et
  qu'Adminer montre. Elle contient de vraies données. `DATABASE_URL` y pointe.
- **`mue_test`** — jetable, créée par `infra/initdb/02-mue-test-database.sql`.
  C'est là que toutes les suites de test travaillent.
- La production, sur le serveur personnel, jamais atteinte depuis ici.

`createTestDatabase()` (dans `packages/db/src/testing.ts`) **réécrit** le nom de
la base dans `DATABASE_URL` vers `mue_test` en conservant hôte, port, rôle et mot
de passe. Il réécrit plutôt qu'il ne refuse, et la raison est écrite dans le
code : un refus n'aurait transformé un effacement silencieux qu'en test rouge, et
la personne suivante aurait cherché l'échappatoire.

### `resetSchemas()`, et pourquoi son garde-fou a été refait

Il ne reste **plus de schéma à Mue**. Les tables sont créées là où pointe le
`search_path` du rôle — `public` sur le cluster partagé du propriétaire, à côté
de celles de ses autres applications (§4.7). « Toutes les tables du schéma
courant » n'est donc plus une description de Mue, et la même fonction qui
nettoyait deux schémas dont elle était seule occupante détruirait aujourd'hui
les données d'applications qui n'ont jamais entendu parler d'elle.

Le garde-fou est en quatre couches, et la dernière est celle qui compte :

1. **l'hôte** doit être la boucle locale ;
2. **le nom de la base** doit être `mue_test` (ou `MUE_TEST_DATABASE`).
   `postgres` était sur cette liste et en a été retiré : elle y était sans
   danger quand seules les tables de `mue_app` et `mue_auth` étaient
   supprimées — ces schémas n'existent pas dans la base d'administration d'un
   cluster ordinaire — et elle y serait dangereuse maintenant ;
3. **le schéma visé est `current_schema()`**, demandé à la connexion, jamais un
   littéral ;
4. **seules les tables que le schéma Drizzle déclare** peuvent être supprimées,
   la liste étant dérivée de `schema/` et non recopiée. Une table absente de ce
   fichier n'est atteignable par aucun chemin.

`MUE_ALLOW_DESTRUCTIVE_TESTS=yes-destroy-it` reste l'échappatoire, écrite en
toutes lettres pour ne pas être posée par accident, mais **elle ne relâche que
la couche 2**. Ni la boucle locale ni la liste des tables ne s'ouvrent, quoi
qu'on pose dans l'environnement : la conséquence la plus grave de ce changement
ne doit pas dépendre d'une variable.

Le garde-fou d'origine était « est-ce du loopback », et c'était la mauvaise
question : le cluster de développement l'est par construction, et c'est
exactement ainsi qu'un `bun test` nu à la racine a vidé `mue_dev`, comptes et
sessions compris, le 27 août.

**Toute nouvelle suite qui construit son propre `createAuth` doit lui passer
`database: createTestDatabase()`.** Sans cet argument, `createAuth` retombe sur
`DATABASE_URL` — donc sur `mue_dev` — et la suite y inscrit des comptes puis
supprime la clé de signature JWKS. Le téléphone ne s'authentifie alors plus : une
clé chiffrée sous un autre secret ne peut pas être déchiffrée, et le symptôme est
un `401` nu, sans trace dans aucun journal.

Ni la production ni `mue_dev` ne sont jamais migrées automatiquement au
démarrage d'un processus : les migrations sont une étape explicite du
déploiement, jamais un effet de bord de `n` processus qui démarrent en même
temps.

---

## 6. Flux de branches

C'est une décision du propriétaire du dépôt, écrite ici telle quelle.

```
main     ──●─────────────●───────────────●──   application quotidienne, production
            ╲           ╱ ╲             ╱
develop  ────●───●───●─●   ●───●───●───●───    version bêta
              ╲ ╱ ╲ ╱           ╲ ╱
fonctionnalités                worktrees d'agents
```

- **`main`** porte l'application quotidienne, celle qui tourne sur le téléphone
  du propriétaire. C'est de la production.
- **`develop`** porte la version bêta.
- **Il n'y a pas de branche de release.** C'est explicite, pas un oubli : à une
  seule personne et sans CI, une branche de stabilisation n'ajoute qu'un endroit
  de plus où un correctif peut manquer. Ne pas en créer une « pour bien faire ».
- Une **fonctionnalité** part de `develop` et y revient.
- `develop` **va dans `main`** quand une version est jugée bonne.
- Un **correctif urgent** part de `main`, et retombe dans `main` *et* dans
  `develop`. Les deux, systématiquement : un correctif qui n'existe que sur
  `main` ressort à la fusion suivante de `develop`.

### Worktrees d'agents

Les worktrees d'agents se greffent sur `develop`. Le dépôt en utilise beaucoup —
`git worktree list` en montre une douzaine — et c'est le mécanisme par lequel
plusieurs agents travaillent en parallèle sans se marcher dessus : chacun a sa
copie du travail et sa branche, un seul index par worktree, aucune bascule de
branche partagée.

Ils vivent dans `.claude/worktrees/<nom>/`, répertoire `.gitignore`-é à la
racine comme dans `apps/android/`.

Règles d'un agent qui travaille dans un worktree :

1. **Ne toucher qu'à son périmètre.** Un autre agent édite peut-être le même
   fichier dans un autre worktree ; le conflit ne se verra qu'à la fusion.
2. `git add` et `git commit` n'appartiennent pas à l'agent sauf demande
   explicite : c'est l'orchestrateur qui décide de ce qui devient un commit.
3. Voir §9.4 : un worktree n'emporte pas les fichiers non versionnés.

---

## 7. Variantes d'application, et pourquoi elles doivent rester étanches

### La règle

| Branche | Variante | `applicationId` | Nom affiché |
|---|---|---|---|
| `main` | `release` / `local` | `fr.kristenjestin.mue` | Mue |
| `develop` | `beta` | `fr.kristenjestin.mue.beta` | Mue Beta |
| tests et développement | `debug` | `fr.kristenjestin.mue.debug` | Mue Debug |

Les trois peuvent donc être installées **en même temps** sur le même téléphone,
avec chacune sa base Room, son DataStore, son appairage serveur et ses
notifications. C'est le but.

Le nom affiché et le suffixe de `versionName` sont portés par la variante, dans
`build.gradle.kts`, via `applicationIdSuffix`, `versionNameSuffix` et
`resValue("string", "app_name", …)` — le nom de production étant posé une fois
dans `defaultConfig`. `main/res/values/strings.xml` ne déclare donc plus
`app_name` du tout : le laisser aux deux endroits ne casserait pas la
construction — AGP tranche en faveur de la valeur générée sans un mot — mais
laisserait dans le fichier une chaîne morte qui se lit comme la vraie.

### L'invariant qui rend ça sûr

**Le suffixe appartient à la variante, jamais à la branche.**

`apps/android/app/build.gradle.kts` est **identique** sur `main` et sur
`develop`. Une fusion de `develop` vers `main` ne modifie aucun identifiant, ne
produit aucun conflit sur ce fichier, et ne demande à personne de « se souvenir
de remettre la bonne valeur ». Ce qui distingue une bêta d'une production, c'est
la variante qu'on assemble, pas la branche depuis laquelle on l'assemble.

La tentation inverse — mettre le suffixe dans une propriété de branche, ou le
patcher à la fusion — est celle qui a produit le problème d'origine. Ne pas y
revenir.

### Pourquoi cette séparation existe

`connectedAndroidTest` **installe puis désinstalle l'application qu'il teste**,
sur *tous* les appareils branchés. Tant que toutes les variantes partageaient
`fr.kristenjestin.mue`, l'application désinstallée était l'application de
production — et le téléphone du propriétaire est branché à cette machine en
débogage sans fil, à côté de l'émulateur. Ses données de production ont été
détruites de cette façon. Il n'y a pas de sauvegarde dans le nuage : ce qui est
parti est parti.

Avec `.debug` sur la variante de test, l'instrumentation installe et désinstalle
`fr.kristenjestin.mue.debug`, et ne peut plus atteindre l'application
quotidienne, quel que soit l'appareil visé.

Ce paragraphe existe pour que la règle ne soit pas retirée par quelqu'un qui la
trouve verbeuse. **Elle a un coût de zéro et elle a déjà servi une fois.**

### Corollaires

- Ne jamais viser l'identifiant de production dans une commande de test, un
  `adb shell am instrument`, ou un script.
- `generateBaselineProfile` et toute tâche `connected…AndroidTest` non épinglée
  sont interdites pour la régénération du profil de référence :
  `automaticGenerationDuringBuild` est à `false` dans `build.gradle.kts`
  précisément pour ça. La procédure — assemblage, `adb -s <série>` explicite,
  `am instrument` — est écrite en entier dans le KDoc de
  `apps/android/benchmark/src/main/java/fr/kristenjestin/mue/benchmark/BaselineProfileGenerator.kt`.

  **Le plugin de profil de référence dérive une paire de types de construction de
  chaque type non debuggable** : il y en a dix, pas quatre. `nonMinifiedRelease`,
  `benchmarkRelease`, `nonMinifiedLocal` et `benchmarkLocal` sont des cibles de
  macrobenchmark que `:benchmark` installe par-dessus ce qui répond à leur nom, et
  `connectedNonMinifiedReleaseAndroidTest` n'exige aucun numéro de série. Ces six
  types portent donc eux aussi le suffixe `.debug`, et la règle ci-dessus n'est
  plus une discipline mais une propriété des binaires. Le suffixe leur est appliqué
  depuis `androidComponents.finalizeDsl` et **non** depuis `configureEach` : Gradle
  déclenche les règles de conteneur à l'ajout, avant que le plugin n'appelle
  `initWith`, qui écrase ensuite le suffixe — la version `configureEach` ne fait
  rien, sans erreur, et `aapt` continue d'afficher l'identifiant de production.
- La variante **`local`** est un cas à part et n'est pas un accident : elle
  hérite de `release` (minifiée, R8, profil de référence) et n'ajoute que le
  `res/xml` et la surcharge de manifeste du jeu de sources `debug`, ceux qui font
  confiance à une autorité installée par l'utilisateur. C'est la construction que
  le propriétaire porte : la vitesse de `release` avec le magasin de confiance de
  `debug`, parce qu'un `release` pur ne peut pas atteindre son serveur domestique.
  Elle pointe sur `src/debug/` plutôt que d'en copier les fichiers, pour que
  `debug` et `local` ne puissent pas diverger sur ce à quoi ils font confiance.

---

## 8. Conventions

### 8.1 Commits

Angular / Conventional Commits, et le dépôt les suit déjà : sur 300 commits,
les seuls sujets non conformes sont des `Merge branch …` produits par git.

```
<type>(<portée>): <description à l'impératif, en français, sans majuscule>
```

Types observés : `feat`, `fix`, `test`, `refactor`, `docs`, `perf`, `build`,
`chore`, `wip`, `merge`.

Portées réellement utilisées : `food`, `sync`, `activity`, `components`, `scale`,
`platform`, `domain`, `timer`, `entry`, `data`, `android`, `mcp`, `profile`,
`progress`, `icons`, `contracts`, `auth`, `ui`, `dev`, `prd`. Une portée absente
de cette liste doit décrire un module qui existe ; le type sans portée est
accepté (`chore:`, `build:`, `test:`).

**Les descriptions sont en français**, et ce ne sont pas des étiquettes : elles
disent ce que le commit change dans le comportement observable, souvent sous
forme de phrase.

```
fix(profile): l'aperçu de la date de naissance invalide cessait de l'être en 2030
feat(scale): ouvrir la composition aux agents sans leur laisser l'effacer
refactor(scale): laisser la pastille porter seule la provenance
```

Pas :

```
fix(profile): correction bug date
```

### 8.2 Langues

- **L'interface est en anglais.** Toutes les chaînes affichées par l'application
  Android sont en anglais.
- **Les commentaires et le KDoc sont en français** pour tout ce qui s'écrit
  aujourd'hui. Le dépôt est de fait bilingue — les modules les plus anciens
  (Room, migrations, conteneur d'injection, `build.gradle.kts`) sont commentés en
  anglais, les plus récents (`scale`, `food`, `sync`, la plateforme, l'infra) en
  français, et certains fichiers mélangent les deux. **Ne pas traduire en masse
  l'existant** : un diff de traduction noie le diff utile et détruit `git blame`.
  Écrire le nouveau en français, laisser l'ancien.
- Les messages de commit sont en français (§8.1).

### 8.3 Le style de commentaire attendu

C'est la convention la plus importante de ce fichier et la moins mécanisable.
Les commentaires de ce dépôt sont **denses, argumentés, et expliquent *pourquoi*
plutôt que *quoi***. Ils citent une section de PRD, nomment le symptôme observé
quand la décision inverse a été prise, et disent ce qu'il faudrait re-dériver
pour en changer.

Un commentaire du dépôt ressemble à ça (`app/build.gradle.kts`, à propos de
`camera-compose`) :

> `camera-view` exists to give a `PreviewView` to a `View` hierarchy, and it
> declares `androidx.appcompat` and `androidx.camera:camera-video` to do it — an
> `AppCompatActivity` theme stack and a video recorder, in an app that has
> neither a `View` layout nor a `Recorder` anywhere in it.

Pas à ça :

```kotlin
// Utilise camera-compose
```

Le test d'un bon commentaire ici : **est-ce qu'il empêche quelqu'un de défaire la
décision sans le savoir ?** Si la réponse est non, il ne mérite pas ses lignes.

Corollaire : quand une mesure justifie un choix, écrire les chiffres. Le bloc
`local` du `build.gradle.kts` dit « 13–29 images au-delà de 64 ms contre 0–1 »,
pas « c'est plus rapide ».

### 8.4 Les chaînes d'interface sont des constantes Kotlin

`app/src/main/res/values/strings.xml` ne déclare **aucune chaîne** : même
`app_name` en est sorti, parce que le libellé du lanceur appartient à la variante
et vit désormais en `resValue` dans `build.gradle.kts` (§7). Il n'y a par
ailleurs aucun appel à `stringResource` dans `src/main` — vérifié, zéro
occurrence.

Les textes vivent en `const val` à côté de l'écran qui les affiche, souvent
`internal` pour que le test instrumenté du même écran les cible par la constante
plutôt que par une copie du littéral :

```kotlin
internal const val EDIT_SHEET_TITLE = "Edit measurement"
internal const val DELETE_CONFIRMATION_TITLE = "Delete this measurement?"
```

Fichiers de regroupement quand un écran en a beaucoup : `FoodAddMessages.kt`,
`ScaleMessages.kt`, `SyncMessages.kt`, `FoodDayMessages.kt`,
`FoodCatalogueMessages.kt`.

L'application n'est pas localisée et n'a pas vocation à l'être : un seul
utilisateur, une seule langue d'interface. `strings.xml` reviendra le jour où une
seconde locale sera un besoin réel, pas avant.

### 8.5 Ni bibliothèque de navigation, ni cadriciel d'injection

**Aucune bibliothèque de navigation.** Le commentaire dans `build.gradle.kts` est
explicite : la coquille est faite d'onglets frères sans pile arrière à modéliser,
donc `MueNavigationHost` est un entier sauvegardé, et le seul onglet qui a une
pile — Activity — la modélise comme une liste de routes sauvegardée. Les deux
utilisent `AnimatedContent` de Compose. Ne pas introduire `navigation-compose`
« pour faire propre ».

**Aucun cadriciel d'injection.** L'injection est manuelle, dans
`app/src/main/java/fr/kristenjestin/mue/di/`, découpée par domaine :
`AppContainer.kt`, `FoodContainer.kt`, `ScaleContainer.kt`, `SyncContainer.kt`,
`TimerContainer.kt`. Tout est `by lazy` — ouvrir la base à la première lecture la
garde hors du chemin de démarrage. Les fabriques de ViewModel lisent le
conteneur. Pas de Hilt, pas de Koin.

Ces deux absences sont des **décisions**, pas des retards. Les défaire est un
travail à part entière qui se discute, pas un effet de bord d'une fonctionnalité.

### 8.6 Room

Base `mue.db`, version **7**, schémas exportés dans `apps/android/app/schemas/`
(`1.json` … `7.json`) et embarqués comme asset du test instrumenté — c'est de là
que `MigrationTestHelper` les lit.

- **`fallbackToDestructiveMigration` est interdite sous toutes ses formes.**
  L'utilisateur n'a aucune sauvegarde dans le nuage. Une base que Room ne sait
  pas migrer doit échouer bruyamment, pas repartir vide en silence.
- **Les migrations sont additives et testées depuis la version 1.**
  `MueMigrations.kt` garde un chemin depuis chaque version jamais livrée. Une
  migration ne supprime jamais une mesure qu'elle ne peut pas d'abord convertir.
  Le passage 1 → 2 (dixièmes vers centièmes de kilogramme) recopie les lignes
  dans une table neuve plutôt que d'altérer sur place, parce que
  `ALTER TABLE … RENAME COLUMN` exige SQLite 3.25 (Android 11) et que Mue
  supporte Android 8.
- Les instructions d'une migration sont **celles que Room exporte** pour la
  version cible, gardées identiques : une base migrée et une base fraîchement
  créée doivent être la même base, et `MigrationTestHelper` les compare colonne
  par colonne, index par index.
- **Aucun `@TypeConverter`.** Les enums sont persistés par leur nom stable dans
  une colonne `TEXT` nue, et la conversion vit dans des fonctions d'extension en
  fin de fichier d'entité. Raison : le schéma exporté reste lisible et la
  migration reste vérifiable à l'œil ; un convertisseur déplace la vérité dans du
  code généré.
- `Callback.onCreate` ne se déclenche qu'à l'installation neuve. Une migration
  qui introduit une table à semer doit semer elle-même, sinon les appareils qui
  se mettent à jour ouvrent un écran vide.

### 8.7 `packages/contracts` est la source de vérité du fil

Les schémas Zod de `packages/contracts` définissent ce qui passe sur le réseau.
`openapi.json` en est dérivé. Les DTO Kotlin de
`app/src/main/java/fr/kristenjestin/mue/data/remote/sync/` en sont un **miroir
écrit à la main** — il n'y a pas de génération de code vers Kotlin.

Ce qui garde le miroir honnête est `ContractDriftTest`
(`app/src/test/java/…/data/remote/sync/ContractDriftTest.kt`), qui tourne sur la
JVM, hors ligne, à chaque `testDebugUnitTest`. Il lit les 33 fixtures émises par
`bun run --filter @mue/contracts fixtures` et pose trois assertions séparées :

1. toute fixture sur le disque est au manifeste — un fichier ne peut pas
   atterrir sans être vu ;
2. tout schéma du manifeste a un consommateur Kotlin — un schéma ne peut pas
   atterrir sans lecteur, ce qui est exactement l'état dans lequel ces fixtures
   ont été trouvées ;
3. toute fixture fait l'aller-retour DTO → JSON sans différence — la détection de
   dérive proprement dite.

Le nombre 33 est écrit en dur, délibérément : le dériver de la liste du
répertoire rendrait l'assertion vraie de n'importe quel répertoire, y compris
d'un qu'une émission ratée aurait laissé à moitié écrit. **Changer le contrat,
c'est donc : modifier le schéma Zod, ré-émettre les fixtures, ajuster le DTO
Kotlin, ajuster ce nombre.** Dans cet ordre.

Le test tourne hors ligne exprès. Un détecteur de dérive qui ne s'exécute qu'en
intégration continue « les bons jours » est un détecteur que personne ne voit
échouer — et ici, il n'y a pas d'intégration continue du tout.

### 8.8 Tests

**Côté Android**, les doubles de test sont écrits à la main. Il n'y a ni
Robolectric, ni MockK, ni Turbine, ni JUnit 5 dans le catalogue de versions, et
les seules occurrences de « Robolectric » dans le code sont des commentaires qui
expliquent qu'on s'en passe :

> La machine à états de mesure, éprouvée **sans Bluetooth, sans Android, sans
> Robolectric**

La répartition est nette :

- `src/test/` — 153 fichiers, JVM, hors ligne, aucun émulateur. Logique métier,
  ViewModels, mappers, protocole de balance, dérive de contrat. C'est là que doit
  aller tout ce qui *peut* y aller.
- `src/androidTest/` — une centaine de fichiers, exige un appareil. Room et ses
  migrations, DataStore, Compose UI, CameraX, notifications, et les `Live*Test`
  qui parlent au vrai serveur.

Les faux vivent à côté de ce qu'ils servent (`FoodDayFakes.kt`,
`RecipeFakes.kt`, `FakeScaleDriver.kt`, `testing/Fixtures.kt`,
`testing/LocaleRule.kt`), avec un commentaire qui dit pourquoi : « pas
d'émulateur, pas de Robolectric, pas de base de données, et un bug dans un DAO ne
peut pas rendre l'un d'eux rouge ». C'est l'argument — un test de ViewModel qui
échoue parce qu'une requête SQL a changé n'apprend rien à personne.

**Côté TypeScript**, `bun:test`. Les suites qui touchent la base ne la simulent
pas : elles migrent `mue_test` et travaillent dessus.

### 8.9 TypeScript

`tsconfig.base.json` est strict et le reste : `strict`,
`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`,
`noFallthroughCasesInSwitch`, `noUnusedLocals`, `noUnusedParameters`,
`verbatimModuleSyntax`, `isolatedModules`, `noEmit`. Un paquet n'ajoute que son
`include` (et `lib`/`jsx` quand le DOM est concerné). Ne pas relâcher un de ces
drapeaux dans un `tsconfig.json` de paquet.

`verbatimModuleSyntax` impose `import type` sur tout import de type. Ce n'est pas
cosmétique : c'est ce qui permet à Bun et Vite+ de retirer les types sans
aller-retour de résolution.

Le formatage et le *lint* passent par `vp fmt` et `vp lint` (oxfmt / oxlint).
C'est le seul endroit où Vite+ est plus qu'un orchestrateur : les binaires
`oxlint` et `oxfmt` qu'il installe sont des enveloppes pour l'IDE et refusent de
tourner depuis un terminal.

---

## 9. Pièges connus

Ceux qui coûtent une heure quand on les découvre seul.

### 9.1 Les tests instrumentés exigent un appareil, et savent le désinstaller

Voir §7 en entier. Résumé : un appareil (émulateur ou téléphone) doit être
connecté, `connectedAndroidTest` installe puis désinstalle sur *tous* les
appareils branchés, et **la commande ne doit jamais viser l'identifiant de
production**. Épingler la série (`adb -s <série>`) quand on sort du chemin
Gradle.

### 9.2 Deux suites TypeScript adossées à la base ne se lancent pas en parallèle

`@mue/db`, `@mue/domain`, `@mue/api`, `@mue/auth` et `@mue/platform` travaillent
toutes sur **la même base `mue_test`**. Plusieurs d'entre elles, en `beforeAll` :

- suppriment et resèment les mêmes utilisateurs (`seedUser`, `delete from
  "user" where "email" = …`) ;
- vident `jwks`, parce qu'une clé de signature chiffrée sous le secret d'une
  autre suite ne peut pas être déchiffrée ;
- appellent `resetSchemas()`, qui supprime toutes les tables de Mue (§5).

Lancées en parallèle, elles se marchent dessus et échouent de manière non
reproductible — typiquement un `401` nu au milieu d'une suite qui n'a rien
demandé. **Les lancer en série, un paquet à la fois.** Il n'y a pas de commande
racine qui les enchaîne, et c'est volontaire.

### 9.3 Une base de développement n'est pas une base jetable

Le garde-fou de `resetSchemas` protège `mue_dev`, mais il ne protège que ce qui
passe par lui. Une suite qui construit `createAuth` sans lui passer
`database: createTestDatabase()` écrit dans `mue_dev` sans jamais toucher
`resetSchemas`. Le symptôme est un téléphone qui ne s'authentifie plus, sans
message clair nulle part. Voir §5.

Depuis que Mue vit dans le schéma partagé, la même remarque vaut pour les
**autres applications du propriétaire**, et elle est pire : `mue_dev` est au
moins une base qu'il sait pouvoir recréer. Le garde-fou de `resetSchemas` a été
refait pour ça (§5), mais aucun garde-fou ne surveille ce qu'une suite écrit
elle-même. Une requête écrite à la main dans un test — un `delete from "user"`
sans schéma sur la mauvaise connexion — atteint maintenant la table `user` de
n'importe quelle application du cluster. **Aucune suite ne doit ouvrir de
connexion autrement que par `createTestDatabase()`**, qui réécrit le nom de la
base vers `mue_test` quoi que porte `DATABASE_URL`.

### 9.4 Un worktree git n'emporte pas les fichiers non versionnés

Créer un worktree ne copie que ce qui est suivi par git. Restent à remettre à la
main dans le nouveau répertoire :

| Fichier | Pourquoi il manque | Conséquence |
|---|---|---|
| `.env` | `.gitignore` | tout script `--env-file` échoue sur `DATABASE_URL is not set` |
| `apps/android/local.properties` | `.gitignore` | Gradle ne trouve pas le SDK Android ; et sans `mue.beta.server`, `mue.beta.email` ni `mue.beta.password` (§4.5), la bêta s'assemble avec les trois champs d'appairage vides |
| `node_modules/` | `.gitignore` | `vp`, `tsc` et les tests n'existent pas — relancer `bun install` |
| `certs/` | `.gitignore` | le serveur TLS local ne démarre pas |

Le plus traître est `node_modules/` : `bun run check` échoue alors sur « commande
introuvable » et ressemble à un problème d'outillage plutôt qu'à un worktree
neuf.

### 9.5 Autres

- **`bun run check` ne lance pas les tests.** Un travail « vert » au sens de
  `check` peut casser une suite. Lancer les tests des paquets touchés.
- **`bun --filter <motif> run <script>` ne correspond à aucun paquet** et n'émet
  pas d'erreur. L'ordre est `bun run --filter <motif> <script>`.
- **`openapi.json` et les fixtures de contrat périment en silence** jusqu'à ce
  qu'on lance `openapi:check` ou `testDebugUnitTest`. Après toute modification
  d'un schéma Zod, régénérer les deux (§4.7).
- **Un `.bat` doit rester en CRLF.** `.gitattributes` force `eol=crlf` sur
  `*.bat` : `gradlew.bat` a besoin de CRLF pour `cmd.exe`, `gradlew` a besoin de
  LF pour `sh`. Les deux points d'entrée doivent continuer à fonctionner.
- **Le démon Gradle rend sa mémoire au bout de 10 minutes**
  (`org.gradle.daemon.idletimeout=600000` dans `gradle.properties`), pas au bout
  de trois heures. Un émulateur à 4 Go plus un démon oublié à 2 Go, c'est le
  cumul qui a fait tomber l'hôte.
- **La clé de signature de production n'est pas dans le dépôt et n'y entrera
  pas.** `-PmueDebugSigning` signe un `release` avec la clé de débogage, pour
  pouvoir *exercer* une construction minifiée sur un appareil — rien d'autre.

---

## 10. Avant de considérer un travail terminé

Il n'y a pas de CI pour rattraper un oubli.

1. `bun run check` — si des fichiers TypeScript ont bougé.
2. `bun run --filter <paquet> test` — pour chaque paquet touché, **en série**
   s'ils touchent la base (§9.2).
3. `bun run android:test` — si du Kotlin a bougé. Hors ligne, aucun appareil
   requis, et c'est là que `ContractDriftTest` se prononce.
4. `bun run android:lint` — si des ressources ou du manifeste ont bougé.
5. `bun run --filter @mue/contracts openapi:check` — si un schéma Zod a bougé.
6. Les tests instrumentés seulement si le changement les concerne, et en ayant
   lu le §7.

Ne pas commiter sans que l'orchestrateur ou l'utilisateur l'ait demandé.
