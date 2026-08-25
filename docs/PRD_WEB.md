# PRD — Mue Web

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Module | Application Web privée |
| Version | V1 Web — cadrage initial |
| Statut | Fondation validée, expérience produit à prototyper |
| Date | 25 août 2026 |
| Langue | Anglais uniquement |
| Thème | Sombre uniquement |
| Document parent | [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) |
| Documents métier | [`PRD.md`](./PRD.md), [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md), [`PRD_ACTIVITY_TIMER.md`](./PRD_ACTIVITY_TIMER.md), [`PRD_FOOD.md`](./PRD_FOOD.md) |

## 2. Objet du document

Mue Web sera une véritable application Web privée connectée à Mue Platform, et non une simple documentation d'API ou une page d'administration technique.

Le présent document sépare volontairement deux niveaux :

1. **la fondation Web**, construite avec la plateforme serveur afin de fournir TanStack Start et les écrans indispensables à l'authentification ;
2. **le produit Web complet**, dont les écrans, parcours et prototypes seront cadrés avant leur développement.

Cette séparation permet de développer immédiatement la synchronisation, MCP, l'authentification et le stockage sans décider trop tôt de toute l'expérience Web.

## 3. Vision

Mue Web doit offrir une lecture confortable et une gestion complète des données Mue depuis un navigateur connecté au réseau privé.

À terme, l'utilisateur doit pouvoir :

- consulter sa progression de poids ;
- consulter et gérer ses activités ;
- accéder aux fonctions alimentaires lorsque leur PRD sera validé ;
- modifier les données synchronisées ;
- administrer les sessions Android et les autorisations agents ;
- comprendre l'état et la fraîcheur de la synchronisation ;
- utiliser une interface visuellement cohérente avec Android sans reproduire littéralement sa navigation portrait.

## 4. Principes produit

1. **Le Web utilise les données serveur.** Il ne lit jamais Room et n'accède jamais directement à Android.
2. **Le Web est privé.** Il n'est pas publié automatiquement sur Internet et exige une authentification.
3. **Le Web peut écrire.** Une création ou modification Web est finale, auditée et synchronisée vers Android comme une écriture serveur normale.
4. **Le Web et MCP partagent les mêmes règles métier.** Aucun canal ne contourne les validations du domaine.
5. **Le Web n'est pas local-first en V1.** Le fonctionnement hors connexion et une PWA synchronisée sont hors périmètre initial.
6. **Le Web n'est pas une copie agrandie d'Android.** Il exploite l'espace disponible tout en partageant les mêmes couleurs, typographies et principes d'interaction.
7. **Le design évite l'apparence Material générique et le rendu shadcn par défaut.** Les primitives sont personnalisées par les tokens Mue.
8. **Les données de santé restent lisibles et prudentes.** Les indicateurs dérivés ne sont jamais présentés comme un diagnostic.

## 5. Périmètre livré avec la plateforme serveur

La première implémentation de [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) prépare uniquement :

- l'application TanStack Start compilable et déployable ;
- son point d'entrée `fetch` capable de déléguer les routes techniques à Hono ;
- le chargement de session Better Auth pendant le rendu serveur ;
- le middleware d'autorisation des server functions privées ;
- une page `Sign in` fonctionnelle ;
- une page de consentement OAuth MCP fonctionnelle ;
- une page d'autorisation par code d'appareil si le flux retenu la nécessite ;
- une page minimale après connexion confirmant que la plateforme fonctionne ;
- Tailwind CSS V4 ;
- les packages `ui` et `design-tokens` ;
- le socle shadcn/ui sur Base UI ;
- React Doctor et TanStack Intent.

Ces pages minimales doivent être propres et utilisables, mais leur composition finale ne constitue pas encore la V1 produit de Mue Web.

## 6. Périmètre candidat de la V1 Web complète

Les capacités suivantes appartiennent au produit Web à prototyper. Leur présence dans cette section fixe l'intention, mais pas encore leur hiérarchie d'écran.

### 6.1 Accueil et état général

- poids actuel et évolution récente ;
- activité récente ;
- état de synchronisation Android ;
- date de dernière synchronisation connue ;
- raccourcis vers les principales actions ;
- signalement discret des erreurs nécessitant une intervention.

### 6.2 Poids

- historique complet ;
- graphique de progression ;
- filtres temporels ;
- création d'une mesure ;
- modification ou suppression d'une mesure existante ;
- respect de la règle d'une mesure par date et du remplacement sans avertissement ;
- statistiques et IMC selon les règles de [`PRD.md`](./PRD.md).

La balance gestuelle d'Android n'est pas imposée au Web. Une interaction adaptée au pointeur, au clavier et au tactile devra être prototypée.

### 6.3 Activités

- historique des séances finalisées ;
- filtres par date et type ;
- détail d'une séance et de ses métriques ;
- création et modification des activités selon [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md) ;
- gestion des exercices personnalisés ;
- suppression avec confirmation explicite.

Le lancement d'un minuteur d'activité depuis le Web n'est pas inclus par défaut. Il devra être validé séparément, car [`PRD_ACTIVITY_TIMER.md`](./PRD_ACTIVITY_TIMER.md) définit aujourd'hui un état opérationnel local au téléphone.

### 6.4 Alimentation

Le domaine est défini par [`PRD_FOOD.md`](./PRD_FOOD.md), dont la V1 est Android. Le Web ne l'ouvre qu'après la livraison de ce module et de sa synchronisation.

Son périmètre Web candidat se limite alors à la consultation du journal, à la lecture des recettes et à la correction d'une entrée depuis un clavier confortable. La création par scan reste propre au téléphone.

Le produit Web doit pouvoir accueillir ce domaine sans modifier sa navigation fondamentale ni son système d'autorisation.

### 6.5 Profil et administration

- profil santé synchronisé ;
- sessions Web actives ;
- appareils Android associés ;
- autorisations MCP et scopes accordés ;
- dernière utilisation de chaque client ;
- révocation d'une session ou d'un agent ;
- état technique de la synchronisation ;
- erreurs de mutations nécessitant une action ;
- accès aux procédures de sauvegarde et restauration sans exposer les secrets.

## 7. Hors périmètre de la V1 Web

- accès public à Mue Web ;
- hébergement SaaS géré par Mue ;
- mode multi-utilisateur ou familial ;
- collaboration en temps réel ;
- PWA hors ligne et synchronisation locale du navigateur ;
- remplacement de l'application Android ;
- contrôle direct d'un minuteur Android actif ;
- accès SQL ou console d'administration brute depuis le navigateur ;
- exposition de secrets Better Auth, PostgreSQL ou MCP ;
- recommandations médicales autonomes ;
- finalisation du domaine alimentaire avant son PRD ;
- thème clair ;
- traduction autre que l'anglais.

## 8. Architecture Web

### 8.1 Répartition des responsabilités

```text
Navigateur
  └─ TanStack Start
       ├─ SSR, routing, loaders et server functions
       ├─ session Better Auth
       └─ composants Mue
                │
                ▼
Services métier partagés
  ├─ poids
  ├─ activités
  ├─ synchronisation
  └─ alimentation future
                │
                ▼
Drizzle ── PostgreSQL

Android ── API Hono /api/v1 ──┘
Agents  ── MCP /mcp ──────────┘
```

### 8.2 TanStack Start et Hono

- TanStack Start gère le rendu, la navigation et les server functions réservées au Web.
- Hono gère `/api/v1/*`, `/api/auth/*`, `/mcp` et `/health/*`.
- Une server function Web appelle directement un service métier côté serveur ; elle ne fait pas une requête HTTP vers la propre API de Mue.
- Android ne consomme jamais une server function TanStack Start.
- Les outils MCP ne dépendent jamais d'un composant React ou d'une route de page.

### 8.3 Authentification

- Better Auth est l'unique autorité d'identité.
- Le navigateur utilise un cookie de session `HttpOnly`, `Secure` et `SameSite`.
- Le rendu serveur charge l'utilisateur et la session dans le contexte TanStack Start.
- Toute server function privée contrôle la session sur le serveur.
- Les guards de navigation améliorent l'expérience mais ne constituent jamais la frontière d'autorisation.
- Les actions destructives exigent une confirmation d'interface en plus de l'autorisation serveur.

## 9. Direction visuelle arrêtée

### 9.1 Fondations

| Élément | Décision |
|---|---|
| Runtime d'interface | React 19 |
| Bibliothèque de distribution | shadcn/ui |
| Primitives headless | Base UI |
| Style | Code Mue personnalisé, pas le thème shadcn laissé par défaut |
| CSS | Tailwind CSS V4 et variables sémantiques |
| Thème | Sombre uniquement |
| Accent | Ambre, référence actuelle `#EFB45F` |
| Typographie | Sora |
| Icônes | Lucide |
| Mouvement | Motion pour les transitions orchestrées, CSS pour les micro-états |
| Graphiques | Composants Chart shadcn sur Recharts V3 |

Base UI est choisie pour toute la V1. Mélanger Base UI et React Aria dans le même package `ui` est interdit afin de conserver des comportements de focus, d'état et d'animation cohérents.

### 9.2 Tokens Mue

Le package `design-tokens` est la source de vérité pour :

- fonds et surfaces ;
- textes principaux et secondaires ;
- accent ambre et ses états ;
- bordures et focus ;
- succès, avertissement et erreur ;
- couleurs des graphiques ;
- échelle typographique Sora ;
- rayons ;
- ombres ;
- durées et courbes d'animation.

Les tokens produisent des variables CSS pour le Web et peuvent produire des constantes Kotlin pour Compose. Une divergence spécifique à une plateforme reste possible lorsqu'elle est documentée.

### 9.3 Valeurs numériques

- Les poids, durées, distances et calories utilisent des chiffres tabulaires.
- Une valeur importante reste lisible sans dépendre uniquement de sa couleur.
- Les unités sont toujours visibles.
- Les animations de compteur ne retardent jamais la compréhension ni l'action.

## 10. Graphiques

- Les graphiques utilisent les tokens Mue, jamais des couleurs codées isolément dans un écran.
- La courbe de poids distingue clairement les mesures réelles, les statistiques dérivées et un éventuel objectif.
- Les grilles restent discrètes sur le fond sombre.
- Le survol, le focus clavier et le tactile donnent accès aux mêmes informations.
- Les animations s'appliquent à l'entrée ou au changement de période, pas à chaque rafraîchissement mineur.
- `prefers-reduced-motion` remplace les mouvements importants par des fondus courts.
- Une synthèse textuelle ou tabulaire accessible accompagne les informations qui ne peuvent être comprises autrement qu'en observant le tracé.
- Les axes et tooltips n'impliquent jamais une précision supérieure à celle des données.

## 11. Composants et registre interne

Le package `ui` possède les composants shadcn copiés et adaptés par Mue. Les composants génériques sont partagés ; les composants métier restent nommés selon leur rôle.

Exemples de primitives :

- `Button` ;
- `Field` ;
- `Input` et `NumberField` ;
- `DatePicker` ;
- `Dialog` et `Drawer` ;
- `Select` et `Combobox` ;
- `Tabs` ;
- `Toast` ;
- `Tooltip` ;
- `Chart`.

Exemples de composants métier futurs :

- `WeightTrendChart` ;
- `WeightEntryForm` ;
- `ActivitySummary` ;
- `SyncStatus` ;
- `AgentPermissionCard`.

Un registre shadcn interne pourra distribuer les tokens, composants et conventions Mue dans le monorepo. L'ajout d'un composant externe exige une revue de son accessibilité, de ses dépendances et de sa cohérence avec Base UI.

## 12. Mouvement et accessibilité

- Toutes les fonctions sont utilisables au clavier.
- Les focus sont visibles et utilisent le token `ring` Mue.
- Les composants Base UI conservent leurs comportements ARIA et leur gestion du focus.
- Les zones interactives tactiles restent adaptées à un écran de téléphone.
- Les contrastes sont vérifiés sur toutes les surfaces sombres.
- Aucun statut n'est transmis uniquement par la couleur.
- Les transitions de page restent brèves, interruptibles et sans rebond systématique.
- `prefers-reduced-motion` est respecté dans Motion, les graphiques et les composants CSS.
- Les dialogs rendent le focus à leur déclencheur après fermeture.
- Les erreurs de formulaire sont associées au champ et annoncées aux technologies d'assistance.

## 13. États et erreurs

Le produit Web devra concevoir au minimum :

- chargement SSR initial ;
- attente d'une mutation ;
- état vide ;
- serveur indisponible ;
- session expirée ;
- permission insuffisante ;
- erreur de validation métier ;
- conflit ou révision obsolète ;
- mutation enregistrée mais Android pas encore synchronisé ;
- suppression réussie ;
- révocation de la session courante.

Une erreur technique ne doit jamais être affichée sous la forme brute d'une stack trace ou d'un message PostgreSQL.

## 14. Performance et qualité

- Le HTML utile est rendu côté serveur lorsque cela améliore le premier affichage.
- Les composants lourds, notamment les graphiques, sont chargés seulement lorsqu'ils sont nécessaires.
- Les server functions n'exposent aucun secret dans le bundle navigateur.
- Le budget JavaScript et les dépendances de chaque composant sont surveillés.
- React Doctor analyse le projet localement et en CI.
- Les tests d'accessibilité couvrent les flux critiques.
- TanStack Intent utilise uniquement les skills des packages autorisés et correspondant aux versions installées.
- Les erreurs serveur possèdent un identifiant de corrélation affichable à l'utilisateur sans révéler de donnée sensible.

## 15. Critères d'acceptation de la fondation

- [ ] TanStack Start démarre avec Bun dans le monorepo orchestré par Vite+.
- [ ] Le point d'entrée délègue correctement les routes Hono et rend les routes Web restantes.
- [ ] Une page privée rendue côté serveur redirige un utilisateur sans session.
- [ ] La connexion Better Auth crée une session Web par cookie sécurisé.
- [ ] La déconnexion invalide la session et rend les pages privées inaccessibles.
- [ ] Le consentement MCP affiche le client et les scopes demandés puis accepte ou refuse l'autorisation.
- [ ] Aucune route privée ne s'appuie uniquement sur un guard navigateur.
- [ ] Les tokens sombres, l'ambre et Sora sont chargés depuis `design-tokens`.
- [ ] shadcn utilise exclusivement la base Base UI configurée.
- [ ] React Doctor peut analyser uniquement `apps/platform` depuis la racine.
- [ ] TanStack Intent découvre seulement les skills autorisés.
- [ ] Le build Web est inclus dans l'image Docker de la plateforme.

## 16. Critères à préciser avant la V1 Web complète

Une session de cadrage et des prototypes doivent encore fixer :

- l'architecture exacte de navigation ;
- le contenu de la page d'accueil ;
- la priorité entre consultation, saisie et administration ;
- les écrans de poids et leurs périodes graphiques ;
- le parcours de création et d'édition d'activité ;
- le comportement Web du sélecteur de poids ;
- la place future de l'alimentation ;
- la densité desktop et le comportement aux faibles largeurs ;
- les transitions entre pages ;
- le niveau de détail visible dans l'administration des agents ;
- la présence ou non d'un minuteur Web ;
- les prototypes visuels finaux.

Le développement des écrans métier Web ne commence pas avant cette validation. Cette règle ne bloque ni le serveur, ni la synchronisation, ni MCP, ni les pages minimales d'authentification.

## 17. Décisions arrêtées

| Question | Décision |
|---|---|
| Le Web est-il une simple API ? | Non, une véritable application Web est prévue. |
| Faut-il développer tout le Web avec le serveur ? | Non. Seule la fondation et l'authentification minimale sont incluses dans le module serveur. |
| Framework de rendu | TanStack Start. |
| Couche HTTP externe | Hono. |
| Authentification | Better Auth. |
| Le Web utilise-t-il l'API Android ? | Non. Il appelle les services métier côté serveur ; l'API Hono reste le contrat Android. |
| Le Web fonctionne-t-il hors ligne en V1 ? | Non. |
| Design system | shadcn/ui personnalisé par les tokens Mue. |
| Primitives | Base UI uniquement. |
| Thème | Sombre uniquement, accent ambre, Sora. |
| Graphiques | shadcn Chart et Recharts V3. |
| Animations | Motion et transitions CSS, avec réduction des animations. |
| Produit Web complet | Prototypage et validation dans une phase distincte. |
