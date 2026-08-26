# PRD — Mue Server, Sync & MCP

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Module | Plateforme serveur privée, synchronisation local-first, authentification et accès agent MCP |
| Version | V1 de la plateforme |
| Statut | Prêt pour développement |
| Date | 25 août 2026 |
| Application cliente | Android natif, téléphone, portrait |
| Langue de l'application | Anglais uniquement |
| Documents liés | [`PRD.md`](./PRD.md), [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md), [`PRD_ACTIVITY_TIMER.md`](./PRD_ACTIVITY_TIMER.md), [`PRD_FOOD.md`](./PRD_FOOD.md), [`PRD_WEB.md`](./PRD_WEB.md) |

## 2. Résumé

Mue évolue d'une application strictement locale vers une application **local-first synchronisée avec un serveur personnel privé**.

L'application Android continue d'enregistrer, lire et modifier toutes ses données localement. Elle ne dépend jamais du serveur pour fonctionner. Lorsqu'elle peut joindre le réseau domestique, elle synchronise dans les deux sens ses changements avec Mue Server.

Le serveur conserve une copie synchronisée des données dans PostgreSQL et expose un serveur MCP standard en lecture et en écriture. Un agent compatible MCP peut ainsi consulter l'historique complet de Mue et créer, modifier ou supprimer des données métier, notamment des mesures, des activités et, lorsque leur modèle sera défini, des aliments, repas et recettes.

La plateforme inclut également le socle d'une application Web TanStack Start. Dans cette version, le Web fournit seulement les écrans indispensables à l'authentification et à l'autorisation MCP ; le produit Web complet relève de [`PRD_WEB.md`](./PRD_WEB.md).

Le serveur n'est jamais exposé sur Internet. Aucun tunnel, SDK, protocole ou service propre à OpenAI ou à un autre fournisseur d'IA ne fait partie de ce module.

## 3. Décisions fondamentales

Les décisions suivantes gouvernent l'ensemble du module :

1. **Mue reste local-first.** Room et les DataStores Android demeurent les sources utilisées par l'interface.
2. **Le serveur est facultatif pour utiliser l'application.** Son indisponibilité ne bloque aucune fonction locale.
3. **La synchronisation est bidirectionnelle.** Le téléphone envoie ses changements et récupère ceux créés sur le serveur.
4. **Le téléphone initie toujours la synchronisation.** Le serveur ne se connecte jamais directement à Room.
5. **La cohérence est éventuelle.** Hors du domicile, l'application et les agents peuvent temporairement voir des états différents.
6. **MCP est en lecture et en écriture dès la V1 du module.** Il n'est pas limité à la consultation.
7. **Les agents peuvent créer des données finales.** Une activité ou une recette créée par un agent n'est pas obligatoirement un brouillon.
8. **L'historique complet reste accessible aux agents.** Les résumés ne remplacent pas les données originales.
9. **MCP reste indépendant du fournisseur d'IA.** Seuls les transports et schémas MCP standards sont utilisés.
10. **Le serveur reste strictement privé.** Aucun endpoint public, transfert de port automatique ou pont propre à un fournisseur n'est prévu.
11. **L'alimentation est un domaine extensible.** Son modèle est fixé par [`PRD_FOOD.md`](./PRD_FOOD.md) et s'ajoute sans reconstruire le moteur de synchronisation.
12. **Aucun export manuel de contexte vers un agent n'est prévu.** Les agents utilisent MCP.
13. **L'identité est commune.** Better Auth protège le Web, Android et MCP avec un mécanisme adapté à chaque client.
14. **La plateforme vit dans le même dépôt que l'application Android.** Vite+ est l'entrée unique de l'outillage et orchestre le monorepo sur des workspaces Bun, tandis que Gradle reste responsable d'Android.
15. **Le contrat Android est une API HTTP versionnée.** Une spécification OpenAPI documente ce contrat ; Android ne dépend jamais des server functions TanStack Start.

## 4. Problème à résoudre

Les données de Mue sont actuellement enfermées dans une seule installation Android. Cette architecture suffit pour le suivi local, mais ne permet pas :

- à un agent d'observer l'évolution du poids, les activités et l'alimentation ;
- à un agent d'enregistrer une activité décrite en langage naturel ;
- à un agent de créer une recette complète à partir d'ingrédients ou d'une image analysée ;
- de retrouver sur le téléphone une donnée créée ailleurs ;
- de faire évoluer Mue vers plusieurs clients sans abandonner son fonctionnement hors ligne.

Le module doit ajouter ces possibilités sans transformer une panne du serveur ou du réseau en panne de l'application.

## 5. Objectifs

### 5.1 Objectifs produit

- Conserver l'utilisation complète de Mue sans réseau et sans serveur joignable.
- Synchroniser les données locales vers un serveur personnel lorsqu'il est accessible.
- Rapporter sur le téléphone les données créées ou modifiées sur le serveur.
- Fournir aux agents un accès structuré à l'ensemble des données Mue par MCP.
- Autoriser des écritures agent complètes et immédiatement valides dans le domaine.
- Préserver les données pendant les reprises, doublons de requêtes et interruptions réseau.
- Rendre chaque écriture traçable sans alourdir l'interface principale.
- Permettre l'ajout du domaine alimentaire et d'autres domaines futurs.

### 5.2 Critères de réussite qualitatifs

- Une indisponibilité de plusieurs jours du serveur n'altère aucune fonction locale.
- Une modification locale hors ligne finit par apparaître sur le serveur au retour à domicile.
- Une activité créée par un agent apparaît dans Mue après la prochaine synchronisation.
- Rejouer une requête de synchronisation ne crée aucun doublon.
- Un agent peut obtenir l'historique complet sans connaître le schéma de Room ou de la base serveur.
- Un agent compatible MCP peut utiliser Mue sans dépendance à un fournisseur de modèle précis.
- L'utilisateur comprend si ses données sont synchronisées, en attente ou en erreur.

## 6. Hors périmètre

- Exposition publique de Mue Server sur Internet.
- Hébergement cloud géré par Mue.
- Tunnel OpenAI ou intégration réseau propre à un fournisseur d'IA.
- Garantie d'accès depuis un agent cloud dépourvu d'accès au réseau domestique.
- Synchronisation en temps réel lorsque le téléphone se trouve hors du réseau autorisé.
- Mode multi-utilisateur et partage familial.
- Collaboration simultanée entre plusieurs personnes.
- Interface Web complète de consultation ou d'administration des données.
- Réplication directe de la base Room ou copie de fichier de base de données.
- Requête SQL libre exposée aux agents.
- Modèle alimentaire détaillé, défini dans un PRD séparé.
- Recommandation médicale autonome ou modification silencieuse d'un objectif de santé.

Un VPN administré par l'utilisateur peut donner à un appareil ou à un agent un accès au réseau domestique, mais sa configuration ne fait pas partie de Mue.

## 7. Principes d'architecture

### 7.1 Séparation des responsabilités

```text
Mue Android
  ├─ Room et DataStore : fonctionnement local
  ├─ journal de changements : envoi différé
  ├─ session Better Auth : accès au serveur
  └─ moteur de synchronisation : push puis pull
                         │
                         │ réseau domestique autorisé
                         ▼
Mue Platform — Bun
  ├─ TanStack Start : rendu Web minimal et flux d'authentification
  ├─ Hono : API Android, synchronisation, auth, MCP et healthchecks
  ├─ Better Auth : utilisateurs, sessions, Bearer Android et OAuth MCP
  ├─ services métier partagés
  ├─ Drizzle : schéma, requêtes et migrations
  └─ PostgreSQL : état matérialisé, journal ordonné et audit
                         │
                         │ MCP standard sur le réseau privé
                         ▼
Agent compatible MCP
```

### 7.2 Source de vérité opérationnelle

- Sur Android, l'interface observe uniquement les repositories locaux.
- Une action utilisateur réussit dès que sa transaction locale réussit.
- Une action agent réussit dès que sa transaction serveur réussit.
- Le moteur de synchronisation réconcilie ensuite les deux copies.
- L'interface ne lit jamais directement une réponse réseau pour remplacer temporairement son état local.

### 7.3 Cohérence éventuelle assumée

- Hors du domicile, les changements Android restent en attente.
- Un agent connecté au serveur voit le dernier état synchronisé, qui peut être ancien.
- Les écritures de l'agent restent sur le serveur jusqu'à ce que le téléphone le rejoigne.
- À la prochaine synchronisation, les deux côtés convergent selon les règles de conflit du présent document.

## 8. Connectivité privée et compatibilité agent

### 8.1 Serveur privé

- Mue Server écoute uniquement sur une interface du réseau privé configuré.
- Aucun port n'est automatiquement ouvert sur le routeur.
- Aucun service public de relais ou de tunnel n'est requis ou intégré.
- Le serveur doit fonctionner sans compte OpenAI, Anthropic, Google ou autre fournisseur de modèle.

### 8.2 Condition d'accès d'un agent

Un agent peut utiliser Mue lorsque les deux conditions suivantes sont réunies :

1. son client implémente MCP ;
2. son environnement d'exécution dispose d'un chemin réseau vers l'endpoint HTTPS privé de Mue Server.

Un agent hébergé uniquement dans le cloud et sans accès au réseau domestique ne peut pas joindre le serveur. Cette limite relève de la connectivité réseau et non du protocole MCP.

### 8.3 Transports MCP

La V1 expose un endpoint unique **Streamable HTTP** à `/mcp` pour les clients capables de joindre le serveur sur le réseau privé.

- La révision cible est MCP `2025-11-25`.
- Le SDK TypeScript MCP assure la négociation avec les clients encore limités à `2025-06-18` ou `2025-03-26`.
- Le transport SSE historique n'est pas implémenté.
- Un adaptateur `stdio` n'est pas requis en V1 ; il pourra être ajouté plus tard comme simple pont vers l'endpoint HTTP sans dupliquer les outils métier.
- L'implémentation n'utilise aucune extension Anthropic, OpenAI ou propre à un autre fournisseur comme condition de fonctionnement.

Référence : [spécification MCP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25).

## 9. Intégration dans l'application Android

### 9.1 Section Data & sync

Ajouter dans `Profile` une section `Data & sync` contenant :

- l'état `Not connected`, `Synced`, `Changes pending` ou `Sync issue` ;
- le nom du serveur associé ;
- la date et l'heure de la dernière synchronisation réussie ;
- le nombre de changements locaux en attente lorsqu'il est non nul ;
- l'action `Sync now` ;
- l'action `Server settings`.

L'absence de serveur associé n'affiche aucune alerte sur les écrans principaux.

### 9.2 Association du serveur

- Associer un serveur en scannant un QR code produit par Mue Server.
- Proposer en solution de repli la saisie manuelle de son adresse HTTPS privée.
- Le QR code contient uniquement l'URL du serveur et les informations publiques nécessaires à sa vérification ; il ne contient ni mot de passe ni secret permanent.
- Après découverte du serveur, l'utilisateur s'authentifie avec son compte Mue par les endpoints Better Auth.
- Android obtient une session Bearer propre à l'appareil et révocable ; le mot de passe n'est jamais conservé sur le téléphone.
- Le token de session est protégé par Android Keystore.
- Une association réussie déclenche la synchronisation initiale.

### 9.3 Déconnexion

- `Disconnect server` demande une confirmation.
- Déconnecter révoque la session Android si le serveur est joignable et supprime localement le token de connexion.
- Aucune donnée métier locale n'est supprimée.
- Si le serveur est indisponible, la révocation distante reste possible depuis la future interface Web ou une autre session autorisée.
- Se reconnecter au même compte reprend la synchronisation ; connecter un autre compte ne fusionne jamais silencieusement ses données avec le stockage Room existant.

### 9.4 Déclenchement de la synchronisation

Tenter une synchronisation :

- après l'association initiale ;
- au démarrage de l'application ;
- au retour au premier plan ;
- après détection d'un réseau adapté, avec temporisation ;
- après `Sync now` ;
- périodiquement par le mécanisme Android approprié, sans promesse d'heure exacte.

Les échecs utilisent un backoff et ne déclenchent jamais une boucle agressive ni une notification répétitive.

## 10. Domaines synchronisés

### 10.1 Matrice de la V1

| Domaine | Synchronisé | Accessible par MCP | Écriture agent | Règle |
|---|---:|---:|---:|---|
| Mesures de poids | Oui | Oui | Oui | Une mesure par date, remplacement sans avertissement. |
| Profil santé : taille et date de naissance | Oui | Oui | Oui | Utilisé pour les calculs autorisés par les PRD existants. |
| Nom d'affichage | Non | Non | Non | Reste local conformément au PRD Weight. |
| Préférences d'interface et haptiques | Non | Non | Non | Spécifiques à l'appareil. |
| Séances d'activité finalisées | Oui | Oui | Oui | L'agrégat complet est synchronisé atomiquement. |
| Métriques, équipements, exercices et séries | Oui | Oui | Oui | Inclus dans l'agrégat de leur séance. |
| Exercices personnalisés | Oui | Oui | Oui | Identifiants stables et noms personnalisés conservés. |
| Catalogue d'exercices fourni par Mue | Non | Oui | Non | Référence versionnée, pas une donnée personnelle synchronisée. |
| Minuteur actif ou en pause | Non | Non | Non | État opérationnel propre au téléphone. |
| Brouillons locaux de révision du minuteur | Non | Non | Non | Restent locaux jusqu'à `Save activity`. |
| Aliments personnalisés et produits copiés | Oui | Oui | Oui | Agrégats autonomes définis par [`PRD_FOOD.md`](./PRD_FOOD.md). |
| Recettes | Oui | Oui | Oui | Agrégat complet avec ses ingrédients. |
| Lignes de journal alimentaire | Oui | Oui | Oui | Une ligne par consommation, avec son instantané nutritionnel. |
| Propositions de repas | Oui | Oui | Oui | Une proposition maximum par date et moment. |
| Catalogue d'aliments Ciqual embarqué | Non | Oui | Non | Référence versionnée, pas une donnée personnelle synchronisée. |

Les cinq lignes alimentaires reprennent le modèle arrêté par [`PRD_FOOD.md`](./PRD_FOOD.md). Les points encore ouverts de ce module sont listés dans sa section 24 et ne remettent pas en cause ces agrégats.

### 10.2 Agrégats synchronisés

Les données liées sont synchronisées comme des agrégats cohérents et non comme une suite de lignes Room indépendantes :

- `Measurement` ;
- `HealthProfile` ;
- `ActivitySession`, avec métriques, équipements, exercices et séries ;
- `CustomExerciseDefinition` ;
- `Food` ;
- `Recipe`, avec ses ingrédients ;
- `FoodLogEntry` ;
- `MealPlanEntry`.

Une ligne de journal est autoportante : elle contient l'instantané nutritionnel de ce qui a été mangé et ne dépend donc pas de la réception préalable de son aliment ou de sa recette.

Les règles propres à ces quatre agrégats sont détaillées en section 21 de [`PRD_FOOD.md`](./PRD_FOOD.md).

Une activité ne peut jamais apparaître sans ses enfants obligatoires à cause d'une synchronisation partielle.

## 11. Exigences fonctionnelles de synchronisation

### FR-SYNC-001 — Écriture locale immédiate

- Toute création, modification ou suppression depuis Android est enregistrée localement en premier.
- La même transaction ajoute une mutation dans la file d'envoi.
- Un échec réseau postérieur ne remet pas en cause l'action locale.
- Une mutation ne peut pas être perdue par fermeture du processus ou redémarrage du téléphone.

### FR-SYNC-002 — Échange bidirectionnel

Une synchronisation réussie réalise logiquement :

1. l'envoi des mutations locales non acquittées ;
2. leur validation et leur application par le serveur ;
3. la récupération des changements serveur postérieurs au dernier curseur connu ;
4. l'application atomique de ces changements dans le stockage Android ;
5. l'avancement du curseur uniquement après succès local complet.

Le protocole peut optimiser cet ordre dans une seule requête, mais ne doit pas en modifier les garanties.

### FR-SYNC-003 — Synchronisation initiale

- Si le serveur est vide, envoyer l'historique local complet.
- Si le téléphone est vide, télécharger l'historique serveur complet.
- Si les deux contiennent des données, les fusionner selon les identifiants, les clés métier et les règles de conflit.
- Ne jamais vider implicitement un côté pour le remplacer par l'autre.
- La reprise d'une synchronisation initiale interrompue est idempotente.

### FR-SYNC-004 — Changements créés par un agent

- Une écriture MCP validée crée immédiatement un changement dans le journal serveur.
- Le changement est retourné au téléphone à sa prochaine synchronisation.
- Le téléphone l'applique dans Room ou DataStore comme une donnée normale du domaine.
- La donnée devient visible dans l'interface sans étape de validation obligatoire.
- La provenance agent est conservée pour l'audit mais ne stigmatise pas la donnée dans l'interface.

### FR-SYNC-005 — Suppressions

- Une suppression produit un tombstone synchronisable au lieu d'effacer immédiatement toute trace technique.
- Le tombstone empêche la résurrection d'une ancienne copie hors ligne.
- Le serveur conserve les tombstones pendant une durée supérieure à la fenêtre maximale de reprise supportée.
- Leur purge ne peut intervenir qu'après une politique de rétention documentée et testée.

### FR-SYNC-006 — Reprise et idempotence

- Chaque mutation possède un identifiant global unique.
- Renvoyer la même mutation retourne le même résultat métier sans répéter son effet.
- Une réponse perdue après application serveur ne crée aucun doublon au nouvel essai.
- Une page de changements serveur peut être redemandée sans réappliquer deux fois ses effets.

### FR-SYNC-007 — Échec partiel

- Une mutation invalide ne bloque pas indéfiniment toutes les mutations suivantes.
- Le serveur retourne une erreur métier structurée et actionnable.
- Le client conserve la mutation concernée et expose `Sync issue`.
- Les autres agrégats indépendants peuvent continuer à se synchroniser.
- Aucune donnée locale n'est supprimée pour tenter de réparer automatiquement l'erreur.

### FR-SYNC-008 — Données en retard

- Aucun avertissement permanent n'est affiché parce que le serveur est simplement injoignable.
- `Changes pending` est un état normal hors du domicile.
- L'âge du dernier état serveur est visible dans `Data & sync`.
- Un agent n'obtient aucune fausse garantie de fraîcheur : les résultats MCP incluent l'instant de dernière synchronisation Android connue.

## 12. Modèle logique de synchronisation

### 12.1 Métadonnées communes

Chaque agrégat synchronisé porte logiquement :

| Champ | Rôle |
|---|---|
| `id` | UUID stable, généré par le créateur initial. |
| `revision` | Révision serveur monotone de l'agrégat. |
| `createdAt` | Instant métier de création. |
| `updatedAt` | Instant métier de dernière modification connue. |
| `deletedAt` | Instant facultatif du tombstone. |
| `originType` | `android`, `agent`, `server` ou future origine déclarée. |
| `originId` | Identifiant de l'appareil, de l'agent ou du processus auteur. |
| `lastMutationId` | Identifiant de l'opération ayant produit la révision. |

Les noms exacts en base peuvent différer, mais ces informations et leurs garanties doivent exister.

### 12.2 Mutation

Une mutation contient au minimum :

- `mutationId` UUID ;
- type d'agrégat ;
- identifiant d'agrégat ;
- opération `upsert` ou `delete` ;
- révision de base connue par l'auteur, si elle existe ;
- payload complet de l'agrégat pour un `upsert` ;
- identité et provenance de l'auteur ;
- version du schéma de payload.

### 12.3 Journal serveur

- Le serveur attribue à chaque changement accepté une séquence strictement croissante.
- Le curseur client se fonde sur cette séquence et non sur l'heure de l'appareil.
- Les horloges civiles servent à l'affichage et à l'audit, jamais à déterminer seules l'ordre de synchronisation.
- Le journal permet de récupérer tous les changements depuis un curseur donné.

### 12.4 Versions de schéma

- Chaque payload synchronisé est versionné.
- Un client ne doit pas avancer son curseur s'il ne peut pas appliquer une version reçue.
- Le serveur accepte les versions clientes encore supportées ou retourne une erreur de mise à niveau explicite.
- Les migrations restent additives autant que possible.

## 13. Règles de conflit

### 13.1 Principes

- Les conflits sont déterministes et ne reposent pas uniquement sur l'horloge du téléphone.
- Aucune résolution ne supprime l'historique d'audit des versions concernées.
- Les créations portant des identifiants distincts sont fusionnées.
- Une même mutation répétée n'est jamais un conflit.

### 13.2 Mesures de poids

- La clé métier reste la date locale de la mesure.
- Une nouvelle mutation acceptée pour une date remplace la valeur courante, conformément au comportement existant de Mue.
- Si deux origines modifient la même date avant synchronisation, la dernière mutation acceptée par le serveur devient la version active.
- La version précédente reste dans l'audit technique mais n'apparaît pas comme seconde mesure.

### 13.3 Activités, recettes et autres agrégats

- Deux créations avec des UUID différents coexistent.
- Une mise à jour portant la dernière révision connue est acceptée.
- Une mise à jour fondée sur une ancienne révision est détectée comme concurrente.
- Pour un ajout non destructif à une collection identifiée par UUID, le serveur fusionne les éléments distincts.
- Pour deux modifications concurrentes du même champ ou du même enfant, la dernière mutation acceptée devient active et la version remplacée reste auditée.
- Une suppression ne peut pas être annulée par une ancienne mise à jour hors ligne ; une restauration exige une mutation explicite fondée sur le tombstone courant.

### 13.4 Profil santé

- Le profil constitue un agrégat unique.
- Les champs indépendants peuvent être fusionnés séparément lorsqu'ils n'ont pas été modifiés concurremment.
- Un conflit sur un même champ suit la dernière mutation acceptée et reste audité.

## 14. Serveur MCP

### 14.1 Principes d'exposition

- Exposer des outils métier, jamais les tables internes ni du SQL libre.
- Retourner des objets structurés et versionnés.
- Fournir des descriptions et schémas suffisamment précis pour qu'un agent choisisse l'outil et corrige ses erreurs.
- Utiliser les annotations MCP standard : lecture seule, destructif, idempotent et interaction externe.
- Ne pas dépendre du nom d'un modèle, d'un fournisseur ou d'un SDK d'IA.

Référence : [outils de la spécification MCP](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

### 14.2 Outils de lecture V1

La liste exacte peut être regroupée sans perdre ces capacités :

- `mue.get_sync_status`
- `mue.get_health_profile`
- `mue.list_weight_measurements`
- `mue.get_weight_measurement`
- `mue.list_activities`
- `mue.get_activity`
- `mue.list_custom_exercises`
- `mue.get_custom_exercise`
- `mue.get_weight_statistics`
- `mue.get_activity_statistics`

Les listes :

- acceptent des filtres de date facultatifs ;
- acceptent une pagination par curseur ;
- ne fixent aucune fenêtre temporelle arbitraire lorsque les filtres sont absents ;
- permettent donc de parcourir l'historique complet ;
- retournent la provenance, la révision et les dates utiles ;
- signalent la date de dernière synchronisation connue avec Android.

### 14.3 Outils d'écriture V1

- `mue.upsert_weight_measurement`
- `mue.delete_weight_measurement`
- `mue.update_health_profile`
- `mue.create_activity`
- `mue.update_activity`
- `mue.delete_activity`
- `mue.create_custom_exercise`
- `mue.update_custom_exercise`
- `mue.delete_custom_exercise`

Après validation du PRD alimentaire, la même V1 du serveur gagne notamment :

- `mue.create_recipe`
- `mue.update_recipe`
- `mue.delete_recipe`
- `mue.create_food`
- `mue.update_food`
- `mue.log_meal`
- `mue.update_meal_log`
- `mue.delete_meal_log`

Les noms précis peuvent évoluer avec le modèle alimentaire, mais les capacités de création complète, modification et suppression sont obligatoires.

### 14.4 Création directe par un agent

Un agent peut créer une donnée finale lorsqu'il possède les informations minimales du domaine.

Exemple accepté :

> Hier, j'ai couru pendant 35 minutes à partir de 18 h.

L'agent peut créer une `ActivitySession` finalisée avec la date, l'heure de début, la durée et le mouvement correspondants. Aucun brouillon ni validation Android supplémentaire n'est imposé.

Lorsqu'une information obligatoire manque :

- l'outil retourne une erreur métier structurée ;
- l'agent peut demander la précision à l'utilisateur puis recommencer ;
- le serveur ne fabrique pas silencieusement une valeur obligatoire.

Les valeurs facultatives restent absentes au lieu d'être inventées.

### 14.5 Recettes et analyses futures

Le serveur doit permettre à un agent de créer une recette complète : identité, portions, ingrédients structurés, quantités, instructions et informations nutritionnelles calculées. Ces champs sont fixés par la section 8 de [`PRD_FOOD.md`](./PRD_FOOD.md).

Les données calculées ou estimées conservent leur provenance et leur méthode d'obtention. Un instantané nutritionnel déjà journalisé n'est jamais recalculé par le serveur.

### 14.6 Mises à jour et suppressions

- Les outils de mise à jour acceptent la révision attendue lorsqu'elle est connue.
- Un outil additif fournit une clé d'idempotence ou utilise l'identifiant de mutation MCP fourni par le client.
- Les suppressions sont explicitement annotées comme destructives.
- La politique de confirmation relève du client agent et de ses permissions ; le serveur applique l'autorisation et les règles métier.
- Le serveur ne transforme pas une suppression demandée et autorisée en brouillon.

### 14.7 Audit des agents

Pour chaque appel d'écriture, conserver :

- l'identité de l'agent ;
- le nom de l'outil ;
- l'instant serveur ;
- l'identifiant de mutation ;
- les agrégats concernés ;
- le résultat ;
- la révision créée ;
- l'erreur éventuelle.

Les prompts et conversations complètes ne sont pas requis dans l'audit Mue.

## 15. Authentification et permissions

### 15.1 Socle Better Auth

- Better Auth est l'autorité d'identité de Mue Platform.
- L'utilisateur humain possède un compte commun aux différents clients.
- Le Web utilise une session par cookie `HttpOnly`, `Secure` et `SameSite`.
- Android utilise une session Bearer obtenue après authentification et conservée dans Android Keystore.
- MCP utilise OAuth 2.1, PKCE, les métadonnées de ressource protégée et le profil CIMD de MCP `2025-11-25` fournis par `@better-auth/mcp` et `@better-auth/cimd`.
- Le plugin Better Auth Agent Auth, encore instable, ne fait pas partie de la V1.
- Chaque session Android et chaque autorisation agent est identifiable et révocable séparément.
- Aucun client ne reçoit le secret maître Better Auth.

L'application Android reste utilisable sans compte et sans session. Une session valide est exigée seulement pour associer le téléphone au serveur et synchroniser.

### 15.2 Portées

Le serveur supporte au minimum les portées OAuth suivantes, ou des noms strictement équivalents :

- `profile:read` et `profile:write` ;
- `weight:read` et `weight:write` ;
- `activity:read` et `activity:write` ;
- `nutrition:read` et `nutrition:write` lorsque disponible ;
- une permission explicite pour les suppressions si elle n'est pas incluse dans le scope d'écriture du domaine.

La configuration personnelle peut accorder toutes les portées à un agent de confiance. L'existence des portées ne transforme pas MCP en lecture seule.

### 15.3 Révocation

- L'administration Web prévue par [`PRD_WEB.md`](./PRD_WEB.md) listera les sessions, appareils et agents associés.
- Elle affiche leur dernière utilisation et leurs portées.
- Une identité peut être révoquée immédiatement.
- Une tentative ultérieure retourne une erreur d'authentification sans révéler de donnée.

Avant la livraison du produit Web complet, les mêmes révocations doivent rester possibles par une commande d'administration locale documentée.

## 16. Sécurité et confidentialité

- Le trafic Android–serveur et MCP HTTP est chiffré.
- La plateforme utilise une URL HTTPS privée avec un certificat reconnu par Android et les clients agents. Une résolution DNS privée et un certificat obtenu par challenge DNS sont préférés à l'installation d'une autorité locale sur chaque client.
- L'association initiale vérifie que l'URL et le certificat correspondent aux informations du QR code ou à l'adresse saisie.
- Les secrets Android sont stockés dans le mécanisme sécurisé fourni par la plateforme.
- Le serveur valide les hôtes et origines HTTP, authentifie chaque connexion et limite les tentatives.
- Les server functions TanStack Start qui lisent ou modifient des données privées contrôlent la session côté serveur ; un guard de route côté navigateur n'est jamais considéré comme une autorisation suffisante.
- L'endpoint MCP ne donne aucun accès brut au système de fichiers, au processus serveur ou à la base.
- Les sauvegardes serveur contenant des données personnelles sont chiffrées ou stockées sur un volume chiffré.
- Les journaux techniques n'enregistrent pas les secrets ni les payloads de santé complets par défaut.
- Le serveur n'envoie aucune donnée à un fournisseur d'IA ; c'est le client agent qui décide de l'utilisation des résultats MCP.
- Le caractère privé du réseau ne remplace ni l'authentification ni le chiffrement.

## 17. Modèle alimentaire et dépendances entre PRD

Le modèle alimentaire est arrêté par [`PRD_FOOD.md`](./PRD_FOOD.md). Le présent document reste développable indépendamment de lui pour :

- l'association du téléphone ;
- l'identité des clients ;
- le journal de mutations ;
- la synchronisation bidirectionnelle ;
- les poids, profils et activités ;
- le serveur MCP générique ;
- l'audit et les permissions.

[`PRD_FOOD.md`](./PRD_FOOD.md) fournit, dans sa section 21, les éléments qui manquaient à ce document :

- les payloads `Food`, `Recipe`, `FoodLogEntry` et `MealPlanEntry` ;
- leurs règles de validation et de conflit propres ;
- la liste de leurs outils MCP de lecture et d'écriture ;
- les calculs nutritionnels, leur provenance et leur immuabilité une fois journalisés.

Le protocole de synchronisation et les enveloppes communes ne dépendent pas de ces détails. L'implémentation du domaine alimentaire reste ordonnancée après le chemin vertical décrit en section 24.

## 18. États et erreurs

| Situation | Comportement attendu |
|---|---|
| Serveur injoignable hors du domicile | Mue fonctionne localement ; les changements restent en attente. |
| Serveur injoignable à domicile | État `Sync issue`, nouvelle tentative avec backoff, aucune perte locale. |
| Agent écrit pendant que le téléphone est absent | Écriture conservée sur le serveur puis téléchargée à la prochaine synchronisation. |
| Téléphone et agent créent deux activités différentes | Les deux activités sont conservées. |
| Téléphone et agent modifient le même poids | La dernière mutation acceptée par le serveur devient active. |
| Requête envoyée deux fois | Une seule mutation est appliquée. |
| Réponse réseau perdue après écriture serveur | Le rejeu retourne le résultat existant. |
| Payload inconnu du client Android | Le curseur n'avance pas ; mise à niveau demandée ; données locales préservées. |
| Mutation locale invalide pour le serveur | Mutation conservée, erreur structurée affichable dans `Data & sync`. |
| Token agent révoqué | Aucun outil ni donnée accessible. |
| Serveur réinstallé mais téléphone intact | Réassociation explicite puis synchronisation initiale sans effacement automatique. |
| Téléphone déconnecté du serveur | Données locales conservées intégralement. |

## 19. Exigences techniques Android

- Ajouter `android.permission.INTERNET` lors de l'implémentation du module.
- Conserver Room comme source observable de l'interface.
- Ajouter des tables locales de journal de mutations, d'état de synchronisation et d'identité des objets distants.
- Utiliser des migrations Room explicites et non destructives.
- Ne jamais utiliser `fallbackToDestructiveMigration`.
- Appliquer un agrégat distant et avancer son curseur dans une transaction locale cohérente.
- Exécuter les synchronisations différées avec WorkManager, sous contraintes de réseau et de batterie, sans promesse d'heure exacte.
- Ne pas exiger un service de premier plan pour une synchronisation ordinaire.
- Préserver les garanties du minuteur local : un minuteur actif n'est pas déplacé vers le serveur.
- Mettre à jour la politique de confidentialité et la fiche Play Store, qui ne pourront plus annoncer une absence totale de permission réseau.

## 20. Exigences techniques serveur

### 20.1 Monorepo et orchestration

- Android et Mue Platform vivent dans un dépôt Git unique.
- Bun est le runtime, le package manager et le gestionnaire des workspaces TypeScript.
- Vite+ orchestre les tâches racine, le graphe des packages TypeScript, leur parallélisation et leur cache, et fournit en outre le bundler, le lanceur de tests, le linter et le formateur.
- Les tâches partagées sont déclarées dans `vite.config.ts` sous `run.tasks` ; les scripts `package.json` restent exécutables tels quels, sans cache.
- Le projet Android est déclaré comme workspace technique au moyen d'un `package.json` minimal dont les scripts délèguent au Gradle Wrapper.
- Gradle reste seul responsable du graphe des modules, des dépendances, du cache et de la compilation Android ; Vite+ traite Android comme un livrable de haut niveau et ne met jamais ses tâches en cache, afin de ne pas mentir sur ce qui a réellement été reconstruit.
- Les versions des dépendances applicatives sont figées dans un `catalog` unique à la racine ; chaque workspace y fait référence par `catalog:`. La version de Vite+ fait foi pour toute la chaîne d'outils qu'il embarque.
- Le Dockerfile n'utilise aucun élagage de monorepo : il s'appuie sur les filtres de workspace de Bun et sur un build multi-stage classique.

Structure cible :

```text
apps/
  platform/        TanStack Start + Hono
  android/         Kotlin + Compose + Gradle
packages/
  api/             routes Hono et MCP
  auth/            Better Auth
  contracts/       schémas HTTP et OpenAPI
  db/              Drizzle et migrations
  domain/          services métier serveur
  design-tokens/   fondation future Web/Android
  ui/              fondation future shadcn
infra/             Dockerfile et fichiers Compose
docs/              PRD et cadrage
proto/             prototypes HTML manipulables
scripts/           utilitaires de build partagés
package.json       workspaces et catalog de versions
vite.config.ts     graphe des tâches Vite+
```

### 20.2 Stack applicative

- TypeScript strict sur Bun.
- TanStack Start fournit le point d'entrée serveur, le rendu minimal requis par l'authentification et les futures pages Web.
- Le point d'entrée TanStack Start délègue `/api/*`, `/mcp` et `/health/*` à Hono puis traite les autres requêtes.
- Hono expose l'API Android, la synchronisation, Better Auth, MCP et les healthchecks.
- Les routes Hono, les server functions TanStack Start et les outils MCP appellent les mêmes services métier ; ils ne réimplémentent pas les règles chacun de leur côté.
- Better Auth utilise l'adaptateur Drizzle PostgreSQL.
- Drizzle et `postgres.js` gèrent l'accès PostgreSQL ; Drizzle Kit produit les migrations versionnées.
- Le SDK MCP TypeScript officiel (`@modelcontextprotocol/sdk`) et son intégration Hono (`@hono/mcp`) implémentent le serveur MCP. Le SDK est en version majeure 1 : aucune version 2 n'est publiée à la date de ce document.

### 20.3 PostgreSQL

- PostgreSQL 18 est le moteur serveur unique.
- En développement, `infra/compose.dev.yml` lance un PostgreSQL local persistant avec healthcheck et base de développement dédiée.
- Le serveur TanStack/Hono reste normalement exécuté sur l'hôte avec Bun afin de conserver le rechargement rapide ; un profil Compose peut lancer toute la plateforme pour les tests d'intégration.
- En production, Mue se connecte au PostgreSQL commun déjà administré sur le serveur personnel par `DATABASE_URL` ; le déploiement Mue ne crée aucun conteneur PostgreSQL de production.
- La production utilise un rôle Mue limité et des schémas dédiés, par exemple `mue_app` et `mue_auth`, afin de ne pas entrer en collision avec les autres applications de la base commune.
- Le conteneur applicatif ne crée ni base ni rôle PostgreSQL. Les migrations créent et modifient uniquement les objets appartenant aux schémas Mue préalablement autorisés.
- Les migrations sont exécutées explicitement pendant le déploiement, jamais concurremment par chaque processus au démarrage.

### 20.4 Contrat HTTP Android et OpenAPI

- `/api/v1/*` et `/api/v1/sync/*` constituent le contrat public de l'application Android.
- Les entrées et sorties sont validées par des schémas partagés côté serveur.
- Ces schémas produisent une spécification `packages/contracts/openapi.json` décrivant routes, authentification, payloads, réponses et erreurs.
- OpenAPI est un format de description d'API indépendant d'OpenAI et de tout fournisseur d'IA.
- La V1 ne génère pas automatiquement tout le client Android : les DTO et appels Ktor restent maîtrisés dans le code Kotlin, avec des tests de compatibilité contre le contrat.
- Les server functions TanStack Start et les outils MCP ne font pas partie de ce contrat Android.
- Toute rupture du contrat `/api/v1` exige une nouvelle version d'API ou une période de compatibilité explicite.

### 20.5 Docker et exploitation

- Un Dockerfile multi-stage compile la plateforme et produit une image Bun minimale exécutée sans privilèges root.
- Les assets TanStack Start et le serveur Hono sont livrés dans la même image.
- Le développement utilise un Compose local comprenant PostgreSQL ; la production utilise un Compose ou une commande de déploiement sans PostgreSQL embarqué.
- L'image expose des checks `live` et `ready` sans donnée personnelle.
- La base, la configuration d'identité et les secrets nécessaires à une restauration sont sauvegardés selon une procédure documentée.
- Un volume Docker n'est jamais considéré comme une sauvegarde.
- L'arrêt et le redémarrage ne perdent aucune mutation acquittée.

### 20.6 Qualité et assistance agent

- TanStack Intent est installé avec une allowlist limitée aux dépendances approuvées ; il découvre les skills versionnés sans exécuter le code des packages.
- Intent étant encore alpha, sa version est figée et `intent stale` signale en CI les instructions potentiellement obsolètes sans modifier automatiquement le code.
- React Doctor analyse `apps/platform` localement et en CI. Il commence en mode informatif puis peut bloquer les nouvelles erreurs après stabilisation de la base.
- oxlint et oxfmt, fournis par Vite+, remplacent ESLint et Prettier ; Vitest est son lanceur de tests. `vp check` enchaîne format, lint et types en une seule commande, localement comme en CI.
- Les outils de qualité ne sont pas des dépendances de production.
- Aucune règle métier ni synchronisation n'appelle un modèle d'IA.

### 20.7 Fondation Web préparée, produit Web séparé

- TanStack Start, Tailwind CSS V4, le package `ui` et le package `design-tokens` sont initialisés dans ce module.
- Les seules pages Web fonctionnellement requises ici sont la connexion, le consentement OAuth MCP et, si nécessaire, l'autorisation par code d'appareil.
- Leur expérience complète, les tableaux de bord, les graphiques et l'administration visuelle relèvent de [`PRD_WEB.md`](./PRD_WEB.md).

### 20.8 Migration du dépôt existant

Le monorepo décrit en 20.1 n'existe pas encore. Sa création est la **première tâche d'implémentation**, avant toute écriture de code serveur.

État de départ :

- la racine du projet n'est pas un dépôt Git ; elle contient les PRD, `proto/` et les APK déjà produits ;
- `mue/` est un dépôt Git autonome, branche `main`, avec l'intégralité de l'historique de l'application ;
- `mue/local.properties` contient le chemin du SDK Android et n'est pas versionné.

Exigences de la migration :

- L'historique de l'application Android est **préservé commit par commit**. Repartir d'un dépôt neuf en écrasant l'historique n'est pas acceptable.
- Après migration, `git log` et `git blame` répondent encore sur chaque fichier de l'application depuis son chemin `apps/android/`.
- Les chemins des commits historiques sont réécrits sous `apps/android/` : un historique greffé sans réécriture, où les anciens commits pointent encore sur `app/`, ne satisfait pas le point précédent.
- Aucun fichier suivi n'est perdu : le nombre et le contenu des fichiers versionnés sont identiques avant et après.
- Les fichiers non versionnés mais nécessaires à la compilation, `local.properties` en premier, sont replacés manuellement ; ils ne transitent pas par Git.
- Une sauvegarde du dépôt d'origine est conservée hors du nouveau dépôt jusqu'à validation, puis supprimée.
- Les artefacts de build existants, APK compris, ne sont pas versionnés.

Contrainte d'outillage constatée sur le poste de développement : Python n'est pas installé, donc `git filter-repo` n'est pas disponible. La réécriture des chemins passe par `git filter-branch --index-filter`, suivie d'un `git merge --allow-unrelated-histories` dans le dépôt racine. `git subtree add` est insuffisant : il greffe l'historique sans réécrire les chemins.

Emplacement des documents : les PRD, le cadrage et les tests manuels rejoignent `docs/`. Les liens relatifs qu'ils contiennent vers `proto/` doivent être corrigés en conséquence.

## 21. Critères d'acceptation

- [ ] Mue reste entièrement utilisable lorsque aucun serveur n'est configuré.
- [ ] Mue reste entièrement utilisable lorsque le serveur associé est indisponible.
- [ ] Un QR code permet d'associer le téléphone à un serveur privé.
- [ ] L'historique local existant est conservé et envoyé lors de la première synchronisation.
- [ ] Une mesure créée hors ligne est présente sur le serveur après le retour sur le réseau domestique.
- [ ] Une activité créée par MCP apparaît sur le téléphone après synchronisation.
- [ ] Une activité MCP contenant mouvement, date, heure et durée est une séance finale, pas un brouillon.
- [ ] Les métriques, équipements, exercices et séries d'une activité sont appliqués atomiquement.
- [ ] Deux créations indépendantes hors ligne sont toutes deux conservées.
- [ ] Deux modifications concurrentes d'un poids produisent une valeur active déterministe et un audit des deux versions.
- [ ] Une suppression synchronisée ne ressuscite pas depuis une ancienne copie hors ligne.
- [ ] Rejouer une mutation ne répète jamais son effet.
- [ ] Perdre une réponse réseau puis recommencer ne produit aucun doublon.
- [ ] Un agent peut parcourir toutes les pesées et activités sans fenêtre temporelle imposée.
- [ ] Les outils MCP peuvent créer, modifier et supprimer les domaines autorisés.
- [ ] Les outils MCP fonctionnent par Streamable HTTP privé sur `/mcp` avec la révision `2025-11-25` et la compatibilité négociée jusqu'à `2025-03-26`.
- [ ] Aucun composant du serveur ne dépend d'OpenAI ou d'un autre fournisseur d'IA.
- [ ] Aucun endpoint Mue n'est publié automatiquement sur Internet.
- [ ] Chaque agent possède une identité révocable et des portées configurables.
- [ ] Les écritures agent sont auditables jusqu'à la révision métier créée.
- [ ] Le serveur annonce aux agents la dernière synchronisation Android connue.
- [ ] L'ajout futur du domaine alimentaire ne demande pas de remplacer le protocole de synchronisation.
- [ ] Les migrations Android conservent les poids, activités et brouillons de minuteur existants.
- [ ] La politique de confidentialité et les déclarations Play Store reflètent l'accès réseau et la synchronisation privée.
- [ ] Le Web utilise une session Better Auth par cookie et Android une session Bearer révocable protégée par Android Keystore.
- [ ] Un client MCP peut accomplir le flux OAuth 2.1 puis utiliser uniquement les scopes qui lui ont été accordés.
- [ ] Le PostgreSQL Docker de développement peut être créé, démarré, arrêté et réinitialisé sans dépendre du PostgreSQL de production.
- [ ] En production, l'application se connecte au PostgreSQL commun sans tenter de créer une instance, une base ou un rôle.
- [ ] Le dépôt racine contient l'application Android sous `apps/android` avec son historique complet, et `git log` répond sur un fichier arbitraire de l'application depuis son nouveau chemin.
- [ ] Le nombre de fichiers versionnés de l'application est identique avant et après la migration.
- [ ] L'application Android se compile depuis le monorepo après remise en place de `local.properties`.
- [ ] Vite+ lance les tâches TypeScript et délègue correctement les tâches Android au Gradle Wrapper depuis le même dépôt.
- [ ] La spécification OpenAPI de `/api/v1` est générée et sa compatibilité avec le client Android est testée.

## 22. Tests prioritaires

### 22.1 Local-first

- Créer et modifier plusieurs données sans serveur configuré.
- Utiliser l'application pendant plusieurs jours avec le serveur indisponible.
- Fermer le processus et redémarrer le téléphone avec des mutations en attente.
- Vérifier que l'interface n'attend jamais une réponse réseau pour afficher une écriture locale.

### 22.2 Synchronisation

- Première synchronisation avec serveur vide.
- Première synchronisation avec téléphone vide.
- Première synchronisation avec données différentes des deux côtés.
- Interruption avant envoi, pendant envoi, après application serveur et pendant application locale.
- Rejeu intégral d'un lot déjà accepté.
- Pagination de l'historique complet.
- Réception de plusieurs changements serveur entre deux synchronisations.
- Payload de version inconnue sans avancement du curseur.

### 22.3 Conflits

- Même date de poids modifiée localement et par un agent.
- Même activité modifiée sur les deux côtés.
- Ajouts concurrents de métriques distinctes à une activité.
- Suppression serveur suivie d'une ancienne mise à jour locale.
- Décalage important de l'horloge Android.

### 22.4 MCP

- Découverte et appel des outils par Streamable HTTP.
- Négociation avec un client MCP récent et test d'un client supportant seulement la révision de compatibilité annoncée.
- Flux OAuth 2.1 complet, consentement, refresh et révocation.
- Lecture d'un historique complet paginé.
- Création directe d'une activité de course de 35 minutes.
- Mise à jour puis suppression autorisée de cette activité.
- Rejeu d'une création avec la même clé d'idempotence.
- Erreur métier exploitable lorsqu'un champ obligatoire manque.
- Refus après révocation de l'identité agent.
- Refus d'un outil hors des portées de l'agent.

### 22.5 Sécurité

- Échec d'association avec une URL ou un certificat serveur incorrect.
- Refus d'un token absent, invalide ou expiré.
- Validation des origines HTTP MCP.
- Vérification qu'aucun secret n'apparaît dans les journaux.
- Vérification qu'aucun service n'écoute sur une interface publique non configurée.
- Restauration d'une sauvegarde serveur sur une installation propre.
- Vérification qu'un token Android révoqué ne synchronise plus sans supprimer les données Room.
- Vérification qu'un scope MCP de lecture ne peut appeler aucun outil d'écriture.

### 22.6 Plateforme et contrat

- Démarrage du PostgreSQL de développement par Docker Compose puis application de toutes les migrations sur une base vide.
- Application des migrations sur une base déjà peuplée sans perte.
- Connexion de la plateforme à une base PostgreSQL externe utilisant les schémas Mue dédiés.
- Construction de l'image Bun multi-stage et démarrage sans dépendre du PostgreSQL de développement.
- Exécution des tâches plateforme et Android par Vite+ sur Windows et en CI Linux, y compris le choix automatique de `gradlew.bat` ou `./gradlew`.
- Génération déterministe de `openapi.json` et détection d'une rupture incompatible du contrat Android.

## 23. Décisions arrêtées

| Question | Décision |
|---|---|
| Le serveur remplace-t-il Room ? | Non. Android reste local-first et lit Room/DataStore. |
| L'application doit-elle fonctionner sans serveur ? | Oui, intégralement. |
| Le serveur peut-il créer des données destinées au téléphone ? | Oui. Le téléphone les récupère et les applique lors d'une synchronisation. |
| La synchronisation est-elle bidirectionnelle ? | Oui. |
| Le serveur pousse-t-il directement dans Room ? | Non. Le client Android initie et applique le pull. |
| Un retard de synchronisation hors du domicile est-il acceptable ? | Oui. |
| MCP est-il limité à la lecture ? | Non, lecture et écriture dès la V1 du module. |
| Une création agent est-elle forcément un brouillon ? | Non. Une donnée valide peut être créée directement comme finale. |
| Un agent peut-il créer entièrement une activité ? | Oui. |
| Un agent pourra-t-il créer entièrement une recette ? | Oui, après validation du modèle alimentaire. |
| L'agent reçoit-il seulement des résumés ? | Non. L'historique complet reste accessible ; les statistiques sont complémentaires. |
| Un export manuel de contexte est-il prévu ? | Non. |
| Le serveur MCP dépend-il d'OpenAI ? | Non. |
| Un pont OpenAI facultatif est-il prévu ? | Non. |
| Le serveur est-il exposé sur Internet ? | Non, jamais par Mue. |
| Un agent cloud sans chemin réseau peut-il accéder au serveur ? | Non. |
| Quel transport MCP est visé ? | Streamable HTTP privé sur `/mcp`. SSE est exclu ; stdio est facultatif après la V1. |
| Quelle révision MCP est visée ? | `2025-11-25`, la plus récente publiée et la valeur de `LATEST_PROTOCOL_VERSION` du SDK. Compatibilité négociée jusqu'à `2025-03-26`. |
| Le PRD alimentaire bloque-t-il le serveur ? | Non. [`PRD_FOOD.md`](./PRD_FOOD.md) fixe ses agrégats, ses conflits et ses outils MCP. |
| Que peut faire un agent sur les données alimentaires ? | Tout ce que peut faire l'utilisateur, y compris modifier une ligne de journal existante. La contrepartie est un audit consultable depuis l'application. |
| Le minuteur actif est-il synchronisé ? | Non. Seule la séance finalisée l'est. |
| Framework de plateforme | TanStack Start pour le rendu et Hono pour API, sync, auth, MCP et healthchecks. |
| Runtime et package manager | Bun, piloté par Vite+. |
| Outillage TypeScript | Vite+ : Vite, Rolldown, tsdown, Vitest, oxlint, oxfmt et Vite Task. |
| Monorepo | Un seul dépôt, workspaces Bun orchestrés par Vite+ ; Gradle reste responsable d'Android. |
| Authentification | Better Auth : cookie Web, Bearer Android, OAuth 2.1 MCP. |
| ORM et driver | Drizzle avec `postgres.js`. |
| Base serveur | PostgreSQL 18. Docker local en développement, instance PostgreSQL commune existante en production. |
| Contrat Android | API Hono `/api/v1` décrite par OpenAPI ; client Ktor maintenu en Kotlin. |
| Produit Web complet | PRD séparé ; seul son socle et les pages indispensables à l'auth sont dans ce module. |
| Fondation UI Web | shadcn/ui sur Base UI, tokens Mue, Sora, sombre et ambre ; réalisation détaillée dans le PRD Web. |
| Assistance des agents de développement | TanStack Intent avec allowlist et version figée. |
| Contrôle React | React Doctor local et CI, d'abord informatif. |

## 24. Spikes et paramètres à fixer pendant l'implémentation

La stack est arrêtée. Les points suivants nécessitent encore un spike ou une valeur mesurée, sans remettre l'architecture en discussion :

- intégration du handler Hono dans le point d'entrée `fetch` de TanStack Start sous Bun ;
- interopérabilité de Better Auth MCP avec plusieurs clients MCP et le profil `2025-11-25` ;
- URL HTTPS privée, résolution DNS et émission du certificat sans exposition du service sur Internet ;
- génération Drizzle des schémas PostgreSQL séparés `mue_app` et `mue_auth` ;
- comportement exact du Bearer Better Auth dans le client Ktor et sa révocation ;
- format versionné des agrégats et des erreurs métier ;
- durée de conservation des tombstones et de l'audit ;
- stratégie de compactage du journal serveur ;
- comportement Android des synchronisations périodiques selon les constructeurs ;
- ajout des métadonnées de synchronisation aux entités existantes sans perte. Les identités sont déjà stables et n'ont pas à être converties : `ActivitySessionEntity`, `StrengthExerciseEntity` et `ExerciseDefinitionEntity` utilisent des identifiants `String`, et `MeasurementEntity` a pour clé primaire sa date, qui est sa clé métier — cohérent avec la règle d'une mesure par date. Aucune table n'utilise d'identifiant auto-incrémenté ;
- choix du système d'intégration continue, aujourd'hui non arrêté alors que plusieurs exigences le supposent ;
- reproductibilité du Dockerfile Bun à partir du lockfile et des filtres de workspace ;
- maturité de Vite+, encore en version 0.2.x : sa version est figée et une bascule vers un lanceur de tâches classique doit rester possible sans réécrire les scripts.

L'implémentation commence par la migration du dépôt décrite en 20.8 : rien d'autre ne peut être écrit tant que le monorepo n'existe pas.

Ensuite, avant de généraliser le moteur, un chemin vertical doit démontrer : PostgreSQL Docker local, création de compte, authentification Android, mutation idempotente, push/pull par curseur, authentification OAuth MCP et création MCP d'une activité retrouvée dans Room.
