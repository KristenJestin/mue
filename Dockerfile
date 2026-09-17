# Image de la plateforme Mue : les assets TanStack Start et le serveur Hono dans la même
# image (PRD_SERVER_SYNC_MCP 20.5).
#
# Le contexte de construction est la **racine du dépôt**, pas `apps/platform/`. Ce n'est pas
# un détail de confort : les espaces de travail Bun installent tout dans le `node_modules`
# racine et lient les paquets internes par lien symbolique. Un contexte réduit à
# `apps/platform/` ne verrait ni `packages/`, ni le fichier de verrouillage, ni le catalogue
# de versions — et `@mue/api`, `@mue/auth`, `@mue/db` et `@mue/contracts` sont tous consommés
# en TypeScript source.
#
# La version de Bun est celle de `devEngines` dans la `package.json` racine. La faire
# diverger d'ici est le genre d'écart qui ne se voit qu'à l'exécution.

# --- Dépendances -------------------------------------------------------------------------
#
# Étape séparée pour que le cache de couches survive à une modification de code : tant que
# `bun.lock` et les manifestes ne bougent pas, l'installation n'est pas rejouée.
FROM oven/bun:1.4.0-alpine AS deps
WORKDIR /repo

COPY package.json bun.lock ./
COPY apps/platform/package.json apps/platform/
# Le manifeste Android, et lui seul. `apps/*` est un espace de travail : sans ce fichier,
# Bun n'installe que les dépendances de la racine et ne pose aucun lien `node_modules/@mue`,
# en silence. L'image se construit alors très bien et meurt au démarrage sur
# `Cannot find module '@mue/api'`.
COPY apps/android/package.json apps/android/
COPY packages/api/package.json packages/api/
COPY packages/auth/package.json packages/auth/
COPY packages/ciqual/package.json packages/ciqual/
COPY packages/contracts/package.json packages/contracts/
COPY packages/db/package.json packages/db/
COPY packages/design-tokens/package.json packages/design-tokens/
COPY packages/domain/package.json packages/domain/
COPY packages/ui/package.json packages/ui/

# `--ignore-scripts` : rien de ce qui est installé ici n'a besoin d'exécuter du code arbitraire
# pour être utilisable, et une image de production n'est pas l'endroit où le découvrir.
RUN bun install --frozen-lockfile --ignore-scripts

# --- Construction ------------------------------------------------------------------------
FROM deps AS build
WORKDIR /repo

COPY tsconfig.base.json vite.config.ts ./
COPY packages/ packages/
COPY apps/platform/ apps/platform/

# Écrit `apps/platform/dist/client` (le bundle navigateur) et `apps/platform/dist/server`
# (le serveur). `main.js` retrouve le premier par `../client/` relatif à lui-même : les deux
# répertoires doivent rester frères dans l'image finale.
RUN bun run --filter @mue/platform build

# --- Exécution ---------------------------------------------------------------------------
FROM oven/bun:1.4.0-alpine AS runtime
WORKDIR /repo

ENV NODE_ENV=production

# `HOST` vaut `127.0.0.1` par défaut dans `main.ts`, et c'est le bon défaut pour un processus
# lancé à la main (PRD 22.5 : aucun service n'écoute sur une interface publique sans avoir été
# configuré pour). Dans un conteneur, ce défaut rend le serveur injoignable depuis l'extérieur
# du conteneur — il démarrerait, répondrait à son propre `/health/live`, et resterait invisible.
# La publication de port de Compose est ici la configuration explicite que le PRD demande.
ENV HOST=0.0.0.0
ENV PORT=3000

# **Bun 1.4 installe en mode isolé, pas hoisté.** Le `node_modules` de la racine ne porte que
# les dépendances de la racine et le magasin `.bun/` ; les liens `@mue/*` et les dépendances
# de chaque espace de travail vivent dans le `node_modules` de cet espace de travail —
# `apps/platform/node_modules/@mue`, `packages/api/node_modules/@mue`, et ainsi de suite.
#
# Copier le seul `node_modules` racine donne donc une image qui se construit sans un mot et
# meurt au démarrage : la résolution depuis `apps/platform/dist/server/main.js` remonte
# jusqu'à `apps/platform/node_modules`, et ne l'y trouve pas.
#
# Copiés depuis l'étape de construction et non réinstallés : l'image exécute exactement
# l'arbre contre lequel la construction a réussi.
COPY --from=build /repo/node_modules node_modules
COPY --from=build /repo/package.json /repo/tsconfig.base.json ./
COPY --from=build /repo/apps/platform/package.json apps/platform/
COPY --from=build /repo/apps/platform/node_modules apps/platform/node_modules
COPY --from=build /repo/apps/platform/dist apps/platform/dist

# `packages/` voyage **en entier**, et ce n'est pas une précaution : le bundle serveur
# n'est pas autonome. Il externalise les paquets internes — `@mue/api`, `@mue/auth`,
# `@mue/contracts`, `@mue/domain`, `@mue/db` — qui sont consommés en TypeScript source, leur
# `exports` pointant sur `src/index.ts`. Les entrées correspondantes du `node_modules` copié
# ci-dessus sont des liens vers `/repo/packages/*` : sans les cibles, ils pendent et le
# processus meurt au démarrage sur `Cannot find module '@mue/api'`.
#
# `packages/db` a en plus une raison propre : la migration est un point d'entrée à part
# (`src/migrate.ts`) qui lit les fichiers SQL de `migrations/` à côté de lui. Voir le service
# `migrate` de `infra/compose.yml`.
COPY --from=build /repo/packages packages

# Le CLI d'administration voyage avec l'image, et pas seulement le serveur.
#
# `scripts/admin.ts` est ce qui reste possible quand l'interface Web ne l'est plus : la
# section 15.3 l'exige comme chemin de révocation documenté, et depuis que les agents se
# branchent par une clé d'API, c'est **aussi** la seule manière de fabriquer une clé en
# production — elle a besoin du `DATABASE_URL` du déploiement, qui ne vit que dans ce
# conteneur. Sans cette ligne, l'image est complète et la commande est introuvable.
#
# Copié depuis le contexte et non depuis `build` : c'est du TypeScript source, il n'est pas
# compilé, et l'étape de construction ne le reçoit pas. Il s'exécute contre `packages/`,
# qui est juste au-dessus — même raison que `packages/db/src/migrate.ts` deux paragraphes
# plus haut.
COPY scripts scripts

EXPOSE 3000

# Le conteneur n'exécute rien en tant que root. L'image `oven/bun` fournit déjà cet
# utilisateur ; aucun chemin écrit par le processus n'est dans l'image, tout l'état est en
# base.
USER bun

# Le premier acte du conteneur est la migration, et le serveur ne démarre que si elle a
# réussi. C'était une étape manuelle du déploiement, et c'en était une parce que plusieurs
# conteneurs qui migrent en concurrence se marchent dessus — ce que `migrate()` ne risque
# plus : il prend un `pg_advisory_lock` (`packages/db/src/migrate.ts`), donc deux migrations
# simultanées se sérialisent et la seconde ne trouve rien à faire.
#
# Ce qui reste vrai, et que la ligne ci-dessous respecte : la migration est **décidée**, pas
# subie. Elle précède l'écoute du port, son échec sort en erreur, et le conteneur ne sert
# jamais avec un schéma à moitié appliqué. Le chemin manuel
# (`bun run packages/db/src/migrate.ts`) reste disponible pour migrer sans démarrer le
# serveur. `scripts/entrypoint.ts` porte le raisonnement en entier.
CMD ["sh", "-c", "bun run scripts/entrypoint.ts && exec bun run apps/platform/dist/server/main.js"]
