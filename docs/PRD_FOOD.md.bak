# PRD — Mue Food

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Module | Food |
| Version | V1 du module |
| Statut | **Proposition de cadrage — rien n'est arbitré** |
| Date | 25 août 2026 |
| Plateforme | Android natif, téléphone, portrait |
| Langue de l'application | Anglais uniquement |
| PRD du socle | [`PRD.md`](./PRD.md) |
| Modules voisins | [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md), [`PRD_ACTIVITY_TIMER.md`](./PRD_ACTIVITY_TIMER.md) |
| Dépendance serveur | [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) |
| Référence visuelle | [`meals`](../proto/fusion/meals.html) |

Ce document décrit un module ajouté à une application dont le socle poids et le module activité sont déjà développés. Il ne redéfinit ni le design system, ni la navigation générale, ni les décisions techniques du socle : il les prolonge. En cas de divergence sur la navigation, la section 7 fait autorité ; en cas de divergence avec le prototype, la section 19.

**Ce document n'a pas été validé.** Il formalise une exploration menée par prototype, pas une série de décisions prises. La session de cadrage s'est arrêtée sur « montre-moi en vrai, je valide via l'interface et les interactions » : la démonstration a été livrée, la validation n'a jamais eu lieu. Deux remarques restées sans réponse pèsent directement sur son contenu : « je pense que là tu vas trop loin » et « ça sous-entend qu'on oriente l'application sur la préparation de repas pure ? ».

La section 23 indique, pour chaque orientation, si elle vient d'une demande explicite ou si elle n'est qu'une proposition. La section 24 liste ce qui doit être arbitré avant la moindre ligne de code.

Ce document **propose** de lever la dépendance déclarée par la section 17 de [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) : il décrit les agrégats `Food`, `Recipe`, `FoodLogEntry` et `MealPlanEntry`, leurs règles de validation et de conflit, et la liste de leurs outils MCP. La dépendance ne sera réellement levée qu'après arbitrage.

## 2. Résumé

Le module Food permet d'enregistrer simplement ce que l'on a mangé, puis d'en tirer une lecture honnête de la journée. Il ajoute un cinquième onglet `Food` à Mue.

Le cœur du module est un **journal alimentaire**, pas un livre de recettes ni un planificateur de menus. L'écran principal décrit ce qui a réellement été consommé. Quatre chemins y mènent : scanner un produit emballé, rechercher un aliment générique, utiliser une recette enregistrée, ou saisir rapidement un plat dont on ne connaît que l'ordre de grandeur.

La recette n'est qu'un moyen parmi d'autres d'enregistrer un repas : elle sert aux préparations que l'on refait. La planification reste un parcours secondaire, explicitement séparé du journal : un repas prévu n'entre dans les totaux qu'après l'action `I ate this`.

Les données restent locales. Le module fonctionne intégralement hors ligne, à l'exception du scan de code-barres qui interroge un service externe et dégrade proprement en création manuelle.

## 3. Problème à résoudre

Mue sait aujourd'hui suivre le poids et l'activité, mais rien de ce qui les explique le plus directement. Ajouter l'alimentation expose cependant l'application à deux dérives opposées.

La première est le **compteur de calories façon tableur** : une immense base d'aliments, une saisie exigeante, un objectif quotidien qui transforme chaque repas en examen. C'est contraire au principe fondateur de Mue, qui refuse le discours culpabilisant, et c'est le chemin le plus court vers un usage obsessionnel puis un abandon.

La seconde est l'**application de préparation de repas** : recettes, menus hebdomadaires, liste de courses. Le premier prototype a exploré cette branche et a montré sa limite : elle oblige à créer une recette pour enregistrer un yaourt, et elle suppose que l'utilisateur cuisine toujours ce qu'il mange.

Le module doit donc :

- rendre l'enregistrement d'un repas plus rapide que sa description ;
- accepter aussi bien un produit scanné, un fruit, un plat de restaurant qu'une recette maison ;
- calculer des valeurs nutritionnelles crédibles sans prétendre à une précision qu'aucune source ne possède ;
- séparer nettement ce qui est prévu de ce qui est consommé ;
- rester utilisable sans jamais afficher un seul chiffre, si l'utilisateur le décide.

Les repères nutritionnels retenus sont ceux de [Manger Bouger](https://www.mangerbouger.fr/manger-mieux/a-tout-age-et-a-chaque-etape-de-la-vie/les-recommandations-alimentaires-pour-les-adultes) et de la [HAS](https://www.has-sante.fr/jcms/c_964938/fr/surpoids-et-obesite-de-l-adulte-prise-en-charge-medicale-de-premier-recours) : changements durables, densité énergétique moindre, portions adaptées, ordre de grandeur réaliste d'un à deux kilogrammes par mois. Mue n'édicte aucune règle plus stricte et ne prescrit rien.

## 4. Objectifs produit

### 4.1 Objectifs de la V1 du module

- Enregistrer un aliment consommé en moins de quinze secondes.
- Couvrir les quatre origines réelles d'un repas : produit emballé, aliment générique, recette maison, estimation.
- Calculer automatiquement l'énergie et les macronutriments à partir de quantités saisies.
- Conserver une trace immuable de ce qui a été mangé, indépendante des modifications ultérieures des recettes.
- Permettre de créer une recette réutilisable composée de vrais aliments.
- Séparer visuellement et logiquement `Eaten` et `Planned`.
- Rendre l'affichage de l'énergie entièrement facultatif.
- Fonctionner hors ligne, sans compte et sans serveur.
- Préparer la synchronisation et l'accès agent définis par le PRD serveur, sans en dépendre.

### 4.2 Critères de réussite qualitatifs

- Un yaourt scanné est enregistré sans passer par une recette.
- Une journée entière peut être saisie sans jamais ouvrir un formulaire de recette.
- Un utilisateur qui masque les calories garde un module utile.
- Une recette modifiée aujourd'hui ne réécrit pas l'historique d'il y a trois semaines.
- Aucune valeur nutritionnelle affichée ne laisse croire à une précision au gramme près.
- Ajouter une nouvelle source d'aliments ne modifie ni le journal ni les recettes.

## 5. Hors périmètre de la V1

- Objectif calorique personnalisé, calculé ou saisi, et tout indicateur de dépassement.
- Récupération automatique des calories brûlées pendant une activité pour « créditer » la journée.
- Menus hebdomadaires générés automatiquement et liste de courses agrégée.
- Suggestions de repas, remplacement automatique et moteur de recommandation.
- Micronutriments, vitamines, minéraux, index glycémique et Nutri-Score.
- Photographie d'un plat analysée pour en déduire son contenu.
- Import de recettes depuis une URL ou un fichier.
- Partage de recettes, communauté et notes.
- Rappels, notifications et streaks alimentaires.
- Export CSV du journal alimentaire.
- Traduction du catalogue d'aliments.

Le planning hebdomadaire complet, l'échange de repas et la liste de courses ont été prototypés et restent des candidats sérieux pour une V1.1. Ils sortent de la V1 parce qu'ils appartiennent à la branche « préparation de repas », alors que la V1 doit d'abord réussir le journal. Seule la planification unitaire d'un repas, décrite en section 12, est conservée.

## 6. Principes d'expérience

1. **Enregistrer d'abord** — le geste principal est `Add food`, jamais `Create a recipe`.
2. **Quatre chemins, une destination** — scan, recherche, recette et ajout rapide produisent la même entrée de journal.
3. **Prévu n'est pas mangé** — aucun total ne bouge tant que l'utilisateur n'a pas confirmé.
4. **Les estimations restent des estimations** — toute valeur calculée ou externe est affichée précédée de `≈` et conserve sa provenance.
5. **Les chiffres sont facultatifs** — masquer l'énergie ne dégrade aucune autre fonction.
6. **Factuel, jamais culpabilisant** — aucun seuil, aucune couleur d'alerte, aucun jugement sur une journée.
7. **Hors ligne par défaut** — seule une action explicitement réseau, le scan, peut échouer faute de connexion.

## 7. Architecture de l'information

La navigation principale passe à cinq onglets permanents :

| Onglet | Rôle |
|---|---|
| `Entry` | Saisir et enregistrer un poids. |
| `Progress` | Consulter l'évolution du poids. |
| `Activity` | Consulter et enregistrer les séances sportives. |
| `Food` | Enregistrer et consulter ce qui a été mangé. |
| `Profile` | Gérer le profil, les préférences et l'export. |

L'onglet est nommé `Food` et non `Meals` ou `Nutrition` : `Meals` oriente vers la cuisine, `Nutrition` vers le discours médical, alors que le module doit accueillir aussi bien un fruit qu'un plat de restaurant.

Le module contient les écrans suivants :

| Écran | Rôle |
|---|---|
| `Food` | Journal du jour, groupé par créneau, avec le total de la journée. |
| `Add food` | Choix du chemin d'ajout, puis quantité et créneau. |
| `Food search` | Recherche dans le catalogue d'aliments. |
| `Scan` | Lecture d'un code-barres et fiche produit. |
| `Custom food` | Création d'un aliment absent du catalogue. |
| `Recipes` | Recettes enregistrées et favorites. |
| `Recipe detail` | Fiche d'une recette, portions recalculées. |
| `Create recipe` | Formulaire de recette à ingrédients structurés. |
| `Plan a meal` | Planification unitaire d'une recette ou d'un aliment. |

La barre d'onglets reste visible sur tous les écrans du module. Les parcours d'ajout sont présentés en feuilles modales empilables, conformément au vocabulaire d'interaction déjà retenu par le module Activities.

## 8. Modèle conceptuel

### 8.1 Quatre objets distincts

Le module repose sur quatre objets qu'il ne faut jamais confondre :

| Objet | Question à laquelle il répond |
|---|---|
| `Food` | Que vaut cet aliment pour 100 g ou 100 ml ? |
| `Recipe` | Comment j'assemble plusieurs aliments en un plat réutilisable ? |
| `FoodLogEntry` | Qu'ai-je réellement mangé, quand, et en quelle quantité ? |
| `MealPlanEntry` | Qu'est-ce que je prévois de manger ? |

Le journal ne référence jamais directement un `Food` ou une `Recipe` pour calculer ses totaux : il en conserve un instantané. C'est ce qui rend l'historique stable.

### 8.2 Aliment

```text
Food
- id
- name
- brand
- barcode
- source: CIQUAL | OPEN_FOOD_FACTS | CUSTOM
- sourceId
- sourceVersion
- referenceUnit: GRAM | MILLILITRE
- caloriesPer100
- proteinPer100
- carbsPer100
- fatPer100
- fibrePer100
- servingSize
- servingLabel
- imageRef
- createdAt
- updatedAt
```

`caloriesPer100` est exprimée en kilocalories. Les macronutriments sont exprimés en grammes. `servingSize` et `servingLabel` sont facultatifs et décrivent une portion usuelle, par exemple `125 g` et `1 pot`, uniquement pour proposer une quantité rapide.

### 8.3 Recette

```text
Recipe
- id
- name
- description
- type: BREAKFAST | MAIN | SNACK
- baseServings
- prepTimeMinutes
- steps
- imageRef
- isFavourite
- createdAt
- updatedAt

RecipeIngredient
- id
- recipeId
- foodId
- quantity
- unit: GRAM | MILLILITRE
- position
```

Une recette ne stocke aucune valeur nutritionnelle : elle est toujours recalculée à partir de ses ingrédients et de `baseServings`. Cela évite qu'un total et sa composition divergent.

Les quantités des ingrédients sont exprimées pour la recette entière, jamais par portion. L'affichage recalcule pour le nombre de portions choisi.

### 8.4 Entrée de journal

```text
FoodLogEntry
- id
- consumedOn            date locale
- slot: BREAKFAST | LUNCH | SNACK | DINNER
- kind: FOOD | RECIPE | QUICK
- sourceRef             foodId ou recipeId, facultatif
- title
- amountLabel
- quantity              grammes, millilitres ou portions
- quantityUnit: GRAM | MILLILITRE | SERVING
- calories
- protein
- carbs
- fat
- fibre
- estimation: MEASURED | APPROXIMATE
- fromPlanEntryId
- createdAt
- updatedAt
```

Les valeurs nutritionnelles sont **copiées** au moment de l'enregistrement. Modifier ou supprimer ensuite l'aliment ou la recette d'origine ne change jamais une entrée déjà journalisée. `sourceRef` sert uniquement à proposer « refaire la même chose » et à ouvrir la fiche d'origine si elle existe encore.

`estimation` vaut `APPROXIMATE` pour un ajout rapide et pour toute recette dont un ingrédient est lui-même approximatif.

### 8.5 Repas planifié

```text
MealPlanEntry
- id
- plannedOn             date locale
- slot: BREAKFAST | LUNCH | SNACK | DINNER
- recipeId
- foodId
- plannedServings
- consumedLogEntryId
- createdAt
- updatedAt
```

Un créneau ne contient au maximum qu'un repas planifié. Planifier sur un créneau occupé remplace le précédent. `consumedLogEntryId` est renseigné lorsque l'utilisateur confirme avoir mangé le repas ; l'annulation le vide et supprime l'entrée de journal correspondante.

### 8.6 Unités et conversions

- Le stockage nutritionnel n'utilise que le gramme et le millilitre.
- Les mesures ménagères — cuillère, tranche, unité — ne sont pas des unités de stockage. Elles ne peuvent exister que via `servingSize`, qui les convertit en grammes.
- Un aliment liquide utilise `MILLILITRE` ; Mue n'applique aucune densité implicite pour convertir des millilitres en grammes.
- Une portion de recette n'est pas une unité nutritionnelle : c'est une fraction de la recette entière.
- Cru et cuit sont considérés comme deux aliments distincts du catalogue, jamais comme une conversion.

## 9. Catalogue d'aliments

### 9.1 Aliments génériques, Ciqual

Le catalogue générique embarqué provient de la table Ciqual 2025 de l'Anses, qui décrit 3 484 aliments et 74 constituants nutritionnels et est distribuée sous Licence Ouverte. [Table Ciqual 2025](https://ciqual.anses.fr/cms/fr/la-table-ciqual-2025)

- Un sous-ensemble est intégré à l'application, suffisant pour couvrir les aliments courants sans embarquer la table entière.
- Seuls les constituants utilisés par Mue sont conservés : énergie, protéines, glucides, lipides, fibres.
- Le sous-ensemble est versionné et régénérable ; sa version est enregistrée dans `sourceVersion`.
- Ce catalogue est disponible hors ligne, sans compte et dès la première ouverture.
- Les aliments Ciqual ne sont ni modifiables ni supprimables par l'utilisateur ; ils peuvent être dupliqués en aliment personnalisé.

### 9.2 Produits emballés, Open Food Facts

Le scan d'un code-barres interroge Open Food Facts, dont l'API v3.6 est recommandée pour les nouvelles intégrations. [Documentation Open Food Facts](https://openfoodfacts.github.io/openfoodfacts-server/api/)

- Le décodage du code-barres est **local** : la caméra lit le numéro avec ML Kit, aucune image ne quitte le téléphone. [Documentation Android ML Kit](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Seul le numéro est envoyé au service pour récupérer nom, marque, image et nutriments pour 100 g.
- Le produit récupéré est **copié** dans le catalogue local de Mue au moment de l'ajout. Une modification ultérieure de la fiche distante ne change rien.
- Une fiche incomplète est acceptée : les valeurs manquantes restent vides et sont saisissables, jamais devinées.
- Un produit introuvable bascule directement sur la création manuelle, pré-remplie du code-barres.
- Open Food Facts est publié sous licence ODbL : l'attribution et les obligations de réutilisation sont respectées et affichées dans `Profile`, section `About`. [Conditions de licence](https://openfoodfacts.github.io/openfoodfacts-server/api/tutorials/license-be-on-the-legal-side/)

### 9.3 Aliments personnalisés

- Créés depuis l'étiquette d'un produit ou pour un aliment absent des deux catalogues.
- Champs requis : nom et énergie pour 100 g ou 100 ml. Marque, macronutriments et fibres sont facultatifs.
- Modifiables et supprimables. La suppression n'affecte aucune entrée de journal déjà enregistrée.
- Un aliment personnalisé utilisé par une recette ne peut pas être supprimé sans avertissement explicite listant les recettes concernées.

### 9.4 Recherche

- Une seule barre de recherche interroge simultanément Ciqual, les produits déjà copiés et les aliments personnalisés.
- Un filtre permet de restreindre à une source.
- Les aliments récemment utilisés apparaissent en tête lorsque la recherche est vide.
- La recherche est insensible à la casse et aux accents, et fonctionne hors ligne.

## 10. Journal alimentaire

### 10.1 Écran `Food`

- L'écran ouvre sur la date du jour.
- Les entrées sont groupées par créneau, dans l'ordre `Breakfast`, `Lunch`, `Snack`, `Dinner`.
- Chaque groupe affiche ses entrées, puis un bouton `Add` propre au créneau.
- Un créneau vide affiche `Nothing recorded` et reste cliquable.
- Chaque entrée affiche son titre, sa quantité, sa nature, et si l'énergie est visible, son énergie et ses protéines.
- Le total de la journée est affiché en tête, précédé de `≈`.
- Une navigation par date permet de consulter et de compléter les jours passés.

### 10.2 Les quatre chemins d'ajout

`Add food` propose quatre entrées de même niveau :

| Chemin | Cas d'usage | Résultat |
|---|---|---|
| `Scan a product` | Produit emballé, yaourt, céréales, plat préparé. | Entrée `FOOD`, valeurs Open Food Facts. |
| `Search food` | Pomme, pain, poulet, riz. | Entrée `FOOD`, valeurs Ciqual. |
| `Use a recipe` | Plat cuisiné maison déjà enregistré. | Entrée `RECIPE`, valeurs recalculées par portion. |
| `Quick add` | Restaurant, plat inconnu, ordre de grandeur. | Entrée `QUICK`, marquée `APPROXIMATE`. |

Chaque chemin se termine par le même écran de confirmation : quantité, créneau, aperçu nutritionnel, enregistrement.

### 10.3 Ce que le journal ne fait pas

- Il n'invente jamais une journée. Un jour sans saisie reste vide.
- Il ne considère jamais un repas planifié comme mangé.
- Il n'affiche ni objectif, ni reste à consommer, ni pourcentage d'un besoin quotidien.
- Il ne compare pas deux journées entre elles.

## 11. Recettes

- Une recette est une préparation réutilisable, créée depuis `Recipes` ou depuis le journal.
- Le formulaire comprend nom, type de repas, temps de préparation, nombre de portions, ingrédients, étapes et couverture.
- Les ingrédients sont ajoutés par le même sélecteur que le journal : `Search`, `Scan`, `Custom`.
- Chaque ingrédient affiche sa contribution énergétique dès que sa quantité est saisie.
- Le bloc `Per serving` recalcule en direct énergie, protéines, glucides et lipides, et affiche la mention de calcul approximatif.
- Les étapes sont saisies une par ligne.
- Une recette peut être mise en favori et recherchée par nom.
- Modifier une recette n'a aucun effet rétroactif sur le journal.
- Supprimer une recette n'affecte ni le journal, ni les repas déjà consommés ; les repas planifiés qui la référencent sont libérés et leur créneau redevient vide.

## 12. Planification

La planification est un parcours secondaire, accessible depuis `Plan a meal` ou depuis la fiche d'une recette.

- Deux étapes : choisir la recette ou l'aliment, puis choisir le jour, le créneau et le nombre de portions prévu.
- Confirmer remplace le repas déjà prévu sur ce créneau.
- Un repas planifié affiche trois actions : `I ate this`, `Swap`, `Remove`.
- `I ate this` crée l'entrée de journal correspondante et lie les deux objets.
- Annuler `I ate this` supprime l'entrée de journal et rend le repas de nouveau simplement planifié.
- `Remove` retire uniquement le repas du planning, laisse le créneau vide et ne touche ni la recette ni le journal.
- Les repas planifiés n'entrent dans aucun total tant qu'ils ne sont pas confirmés.

## 13. Calculs nutritionnels

### 13.1 Formules

```text
contribution d'un aliment = quantité × valeurPour100 / 100

total d'une recette      = somme des contributions de ses ingrédients
valeur par portion       = total de la recette / baseServings

entrée de journal FOOD   = quantité × valeurPour100 / 100
entrée de journal RECIPE = valeur par portion × portions consommées
entrée de journal QUICK  = valeurs saisies

total de la journée      = somme des entrées de journal du jour
```

### 13.2 Affichage

- L'énergie est arrondie à l'unité, les macronutriments au dixième de gramme.
- Toute valeur issue d'un calcul ou d'une source externe est précédée de `≈`.
- Une valeur inconnue est affichée `—`, jamais `0`.
- Les nombres utilisent `font-variant-numeric: tabular-nums` afin de ne pas sauter pendant les recalculs.
- Le masquage de l'énergie retire les chiffres d'énergie et de macronutriments de toutes les listes ; il ne masque ni les quantités, ni les ingrédients.

### 13.3 Ce que Mue assume

Mue affiche des ordres de grandeur, pas des mesures. Le PRD acte explicitement les limites suivantes, qui ne sont pas des défauts à corriger :

- les fiches Open Food Facts sont collaboratives et parfois incomplètes ou fausses ;
- cru et cuit n'ont pas les mêmes valeurs pour 100 g ;
- le poids égoutté et les matières grasses de cuisson ne sont pas déduits automatiquement ;
- une portion de recette réelle n'est jamais exactement une portion théorique.

## 14. Images

Trois origines, trois traitements, aucune image en base :

| Origine | Stockage | Suppression |
|---|---|---|
| Recette fournie avec Mue | Ressource applicative | Aucune |
| Photo ajoutée par l'utilisateur | Fichier WebP dans le stockage interne privé | Supprimée avec la recette |
| Image Open Food Facts | URL distante en base, cache local d'affichage | Cache purgeable |

```text
RecipeImage
- recipeId
- source: BUNDLED | USER | OPEN_FOOD_FACTS
- localFileName
- remoteUrl
```

- Chemin des images utilisateur : `files/recipe-images/{uuid}.webp`.
- L'image choisie est copiée dans Mue plutôt que référencée dans la galerie, dont l'accès n'est pas garanti dans la durée.
- L'image est redimensionnée à environ 1 200 px sur son plus grand côté, avec une miniature générée pour les listes.
- Aucune image n'est stockée en BLOB dans Room : cela alourdirait la base, les migrations et les sauvegardes. Room conserve les données, le système de fichiers conserve les images.
- La suppression d'une recette utilisateur supprime son fichier image et sa miniature.

## 15. Validation des données

| Champ | Règle |
|---|---|
| Nom d'aliment ou de recette | 1 à 80 caractères après nettoyage des espaces. |
| Énergie pour 100 | 0 à 900 kcal. Au-delà, valeur refusée avec explication. |
| Macronutriment pour 100 | 0 à 100 g. La somme protéines + glucides + lipides ne peut dépasser 100 g. |
| Quantité d'un ingrédient | Strictement supérieure à 0, maximum 5 000 g ou ml. |
| Portions d'une recette | Entier de 1 à 12. |
| Portions consommées | 0,25 à 10, par pas de 0,25. |
| Ajout rapide | Nom requis, énergie requise de 0 à 5 000 kcal, protéines facultatives. |
| Date de consommation | Aujourd'hui ou dans le passé, jamais dans le futur. |
| Date planifiée | Aujourd'hui ou dans le futur, dans les 60 jours. |
| Étapes d'une recette | 0 à 30 lignes, 500 caractères par ligne. |
| Ingrédients d'une recette | 1 à 40. Une recette sans ingrédient ne peut pas être enregistrée. |

Une valeur refusée est signalée à côté du champ concerné, sans jamais vider le formulaire.

## 16. Exigences fonctionnelles

### 16.1 Journal

#### FR-FOOD-001 — Journal du jour

L'onglet `Food` affiche le journal de la date sélectionnée, groupé par créneau, avec le total du jour et un bouton d'ajout par créneau. La date par défaut est aujourd'hui.

#### FR-FOOD-002 — Ajout par recherche

L'utilisateur recherche un aliment, le sélectionne, saisit une quantité en grammes ou millilitres, choisit un créneau, puis enregistre. L'aperçu nutritionnel se met à jour à chaque frappe.

#### FR-FOOD-003 — Ajout par scan

L'utilisateur ouvre le scanner, cadre un code-barres, obtient une fiche produit, saisit une quantité, choisit un créneau, puis enregistre. En cas d'échec réseau ou de produit inconnu, Mue propose la création manuelle pré-remplie du code-barres.

#### FR-FOOD-004 — Ajout par recette

L'utilisateur choisit une recette enregistrée, indique un nombre de portions consommées, choisit un créneau, puis enregistre. Les valeurs sont celles de la recette au moment de l'enregistrement.

#### FR-FOOD-005 — Ajout rapide

L'utilisateur saisit un nom, une énergie et éventuellement des protéines, choisit un créneau, puis enregistre. L'entrée est marquée comme approximative.

#### FR-FOOD-006 — Quantité et créneau

Tous les chemins partagent le même écran final. Le créneau est pré-sélectionné selon le point d'entrée et reste modifiable. La quantité propose des valeurs rapides lorsque l'aliment déclare une portion usuelle.

#### FR-FOOD-007 — Suppression et correction

Chaque entrée peut être supprimée depuis le journal, avec recalcul immédiat du total. Modifier une entrée revient à modifier sa quantité et son créneau ; sa provenance et ses valeurs pour 100 ne changent pas.

#### FR-FOOD-008 — Masquage de l'énergie

Une préférence `Show meal energy` masque toutes les valeurs énergétiques et de macronutriments du module. Le reste du module continue de fonctionner à l'identique. La préférence est locale à l'appareil.

#### FR-FOOD-009 — Journées passées

La navigation par date permet de consulter, compléter et corriger un jour passé. Aucune saisie n'est possible sur une date future.

### 16.2 Recettes

#### FR-RECIPE-001 — Création

Une recette est créée depuis `Recipes` ou depuis le journal. Nom, portions et au moins un ingrédient sont requis ; temps, description, étapes et couverture sont facultatifs.

#### FR-RECIPE-002 — Ingrédients structurés

Un ingrédient est toujours un `Food` du catalogue, ajouté par recherche, scan ou création. Le texte libre n'est pas accepté comme ingrédient nutritionnel.

#### FR-RECIPE-003 — Calcul par portion

Le formulaire affiche en direct les valeurs par portion, recalculées à chaque modification de quantité ou du nombre de portions.

#### FR-RECIPE-004 — Consultation et portions

La fiche d'une recette permet de faire varier le nombre de portions affichées ; les quantités d'ingrédients sont recalculées proportionnellement.

#### FR-RECIPE-005 — Favoris et recherche

Une recette peut être mise en favori et retrouvée par recherche sur son nom.

#### FR-RECIPE-006 — Modification et suppression

Une recette peut être modifiée ou supprimée. Ni l'une ni l'autre opération ne modifie une entrée de journal existante. La suppression demande une confirmation explicite et indique les repas planifiés concernés.

### 16.3 Catalogue

#### FR-CATALOG-001 — Catalogue embarqué

Le sous-ensemble Ciqual est disponible dès la première ouverture, hors ligne, sans compte.

#### FR-CATALOG-002 — Produit scanné

Le décodage est local. Seul le code-barres est transmis. Le produit est copié localement à l'ajout, avec sa source et son identifiant d'origine.

#### FR-CATALOG-003 — Aliment personnalisé

Un aliment peut être créé manuellement avec ses valeurs pour 100 g ou 100 ml, puis réutilisé comme n'importe quel autre aliment.

#### FR-CATALOG-004 — Provenance

Chaque aliment affiche sa source. Chaque entrée de journal conserve la source de ses valeurs.

### 16.4 Planification

#### FR-PLAN-001 — Planifier

Un repas est planifié en choisissant une recette ou un aliment, une date, un créneau et un nombre de portions. Un créneau déjà occupé est remplacé.

#### FR-PLAN-002 — Déplanifier

`Remove` libère le créneau, qui redevient vide et directement remplissable. Aucun autre objet n'est modifié.

#### FR-PLAN-003 — Confirmer la consommation

`I ate this` crée l'entrée de journal correspondante et lie les deux objets. L'action est réversible : annuler supprime l'entrée de journal et laisse le repas planifié.

## 17. États vides et erreurs

| Situation | Comportement attendu |
|---|---|
| Aucune entrée aujourd'hui | Quatre créneaux vides, `Nothing recorded`, aucun total inventé. |
| Aucune recette enregistrée | Message d'invitation et bouton de création, sans recette factice. |
| Recherche sans résultat | Proposition de créer un aliment personnalisé avec le terme recherché. |
| Code-barres illisible | Le scanner continue ; saisie manuelle du numéro possible. |
| Produit absent d'Open Food Facts | Bascule vers la création manuelle pré-remplie du code-barres. |
| Réseau indisponible pendant un scan | Message explicite ; les trois autres chemins d'ajout restent utilisables. |
| Fiche produit incomplète | Valeurs manquantes vides et modifiables, jamais estimées. |
| Permission caméra refusée | Le scan est désactivé avec explication ; le reste du module est intact. |
| Quantité invalide | Erreur à côté du champ, formulaire conservé. |
| Aliment supprimé mais journalisé | L'entrée de journal reste intacte ; l'ouverture de la fiche d'origine indique qu'elle n'existe plus. |
| Recette supprimée mais planifiée | Le créneau est libéré et signalé. |
| Stockage image indisponible | La recette est enregistrée sans image, avec un message non bloquant. |

## 18. Accessibilité

- Toutes les cibles tactiles mesurent au moins 48 dp.
- Chaque bouton d'icône possède un libellé lisible par un lecteur d'écran, notamment `Add`, `Remove`, `Scan`, `Favourite`.
- Les totaux et les valeurs nutritionnelles sont annoncés avec leur unité et la mention d'approximation.
- L'ajout d'une entrée annonce le résultat sans voler le focus.
- Le contraste respecte les seuils du design system existant, y compris pour l'ambre sur fond sombre.
- Le scanner propose une alternative complète à la caméra : la saisie manuelle du code-barres.
- Aucune information n'est portée par la seule couleur.
- Les animations respectent la réduction de mouvement du système.

## 19. Design, icônes et mouvement

- Le module reprend intégralement la direction `Fusion` : fond très sombre, accent ambre, Sora, cartes tactiles, rayons généreux.
- Le prototype de référence est [`meals.html`](../proto/fusion/meals.html). En cas de divergence entre ce document et le prototype, ce document fait autorité pour le modèle et les règles ; le prototype fait autorité pour la mise en page et le rythme visuel.
- Les icônes proviennent du jeu déjà utilisé par Mue et distinguent les créneaux : lever de soleil, soleil, fruit, lune.
- Chaque source d'aliment possède une icône stable : catalogue générique, produit emballé, aliment personnalisé, recette, estimation rapide.
- Le mouvement reste fonctionnel : apparition d'une entrée ajoutée, recalcul visible du total, feuille modale glissante. Aucune animation ne retarde une saisie.
- Le prototype devra être aligné sur deux points avant développement : le renommage de l'onglet `Meals` en `Food`, et le retrait du planning hebdomadaire et de la liste de courses du périmètre V1.

## 20. Persistance et architecture technique

- Room reste la source observable de l'interface, conformément au socle.
- Cinq tables sont ajoutées : `food`, `recipe`, `recipe_ingredient`, `food_log_entry`, `meal_plan_entry`.
- Les migrations sont additives et explicites. `fallbackToDestructiveMigration` reste interdit.
- Le sous-ensemble Ciqual est livré comme ressource et inséré au premier démarrage du module, avec sa version ; une mise à jour du sous-ensemble ne modifie jamais un aliment personnalisé ni une entrée de journal.
- Les index couvrent au minimum `food_log_entry(consumedOn, slot)`, `recipe_ingredient(recipeId)` et la recherche par nom d'aliment.
- Les préférences du module — affichage de l'énergie, végétarien, temps de préparation maximal — vivent dans DataStore et ne sont pas synchronisées.
- Le calcul nutritionnel est une fonction pure du domaine, testée indépendamment de l'interface et réutilisable par le serveur.
- Le scan utilise ML Kit en décodage local ; l'appel réseau à Open Food Facts est isolé derrière une interface remplaçable et n'est jamais requis par un autre parcours.
- L'accès réseau du module se limite à Open Food Facts et aux images de produits. Il partage la permission `android.permission.INTERNET` introduite par le module serveur, et impose la même mise à jour de la politique de confidentialité et de la fiche Play Store.
- Aucune donnée personnelle n'est transmise à Open Food Facts : ni identifiant, ni journal, ni profil.

## 21. Serveur, synchronisation et MCP

Cette section fournit ce qui manquait à la section 17 de [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md). Elle ne redéfinit pas le protocole : elle décrit uniquement le comportement du domaine alimentaire à l'intérieur de celui-ci. Elle dépend entièrement du modèle proposé en section 8 : si l'arbitrage change ce modèle, elle change avec lui.

### 21.1 Domaines synchronisés

| Domaine | Synchronisé | Accessible par MCP | Écriture agent | Règle |
|---|---:|---:|---:|---|
| Aliments personnalisés | Oui | Oui | Oui | Agrégat autonome, identifiant stable. |
| Produits copiés depuis Open Food Facts | Oui | Oui | Oui | Copie locale synchronisée, jamais re-téléchargée par le serveur. |
| Catalogue Ciqual embarqué | Non | Oui | Non | Référence versionnée, pas une donnée personnelle. |
| Recettes | Oui | Oui | Oui | Agrégat complet avec ses ingrédients. |
| Entrées de journal | Oui | Oui | Oui | Instantané immuable, une entrée par consommation. |
| Repas planifiés | Oui | Oui | Oui | Un repas maximum par date et créneau. |
| Préférences du module | Non | Non | Non | Spécifiques à l'appareil. |

### 21.2 Agrégats

- `Food` : l'aliment seul.
- `Recipe` : la recette **avec** ses ingrédients, synchronisée atomiquement. Une recette n'apparaît jamais sans ses ingrédients.
- `FoodLogEntry` : l'entrée seule, autoportante puisqu'elle contient son instantané nutritionnel.
- `MealPlanEntry` : le repas planifié seul.

Une recette peut référencer un aliment que le client n'a pas encore reçu. Le client applique la recette et affiche l'ingrédient par son instantané de nom et de quantité jusqu'à réception de l'aliment ; il ne rejette pas l'agrégat.

### 21.3 Règles de conflit

- `FoodLogEntry` : les entrées sont indépendantes. Deux entrées créées séparément coexistent, elles ne fusionnent jamais. Une modification concurrente de la même entrée applique la dernière mutation acceptée par le serveur.
- `Recipe` : dernière mutation acceptée, agrégat entier. Les ingrédients ne sont pas fusionnés ligne à ligne.
- `Food` personnalisé : dernière mutation acceptée.
- `MealPlanEntry` : la clé métier est `(date, créneau)`. Deux planifications concurrentes sur le même créneau se résolvent par la dernière mutation acceptée ; la précédente est remplacée, jamais dupliquée.
- Un instantané nutritionnel déjà journalisé n'est jamais recalculé par le serveur.

### 21.4 Outils MCP

Lecture :

- `list_food_logs` — journal sur une période, filtrable par créneau.
- `get_daily_nutrition` — totaux d'une journée, avec le détail des entrées et la mention d'approximation.
- `search_foods` — recherche dans les aliments accessibles à l'utilisateur.
- `get_recipe` — recette complète avec ingrédients et valeurs par portion.
- `list_recipes` — recettes enregistrées, filtrables par type et favoris.
- `list_meal_plan` — repas planifiés sur une période.

Écriture :

- `create_food_log` — enregistre une consommation, à partir d'un aliment, d'une recette ou de valeurs directes.
- `update_food_log` — corrige la quantité ou le créneau d'une entrée.
- `delete_food_log` — supprime une entrée, annotée comme destructive.
- `create_food` — crée un aliment personnalisé.
- `create_recipe` — crée une recette complète, ingrédients compris.
- `update_recipe` — met à jour une recette entière.
- `delete_recipe` — supprime une recette, annotée comme destructive.
- `plan_meal` — planifie un repas sur une date et un créneau.
- `unplan_meal` — libère un créneau.

Règles communes :

- Une création d'agent est une donnée finale, pas un brouillon, conformément à la décision 7 du PRD serveur.
- Un agent ne peut pas créer une entrée de journal dans le futur.
- Un agent ne peut pas modifier l'instantané nutritionnel d'une entrée existante ; il peut la supprimer et en créer une autre.
- Un agent ne peut ni modifier ni supprimer un aliment du catalogue Ciqual.
- Les valeurs calculées par le serveur conservent leur provenance et leur méthode d'obtention.
- Toute écriture est auditée selon la section 14.7 du PRD serveur.

## 22. Critères d'acceptation de la V1

### Journal

- [ ] L'onglet `Food` ouvre sur la journée courante et affiche quatre créneaux.
- [ ] Un aliment recherché est enregistré avec sa quantité et son créneau, et le total se recalcule.
- [ ] Un produit scanné est enregistré sans passer par une recette.
- [ ] Un ajout rapide est enregistré et signalé comme approximatif.
- [ ] Une portion de recette est enregistrée avec les valeurs de la recette au moment de l'ajout.
- [ ] La suppression d'une entrée recalcule immédiatement le total.
- [ ] Une journée sans saisie reste vide et n'affiche aucun total inventé.
- [ ] Un jour passé peut être complété ; un jour futur ne peut pas l'être.

### Aliments

- [ ] Le catalogue générique est utilisable hors ligne dès la première ouverture.
- [ ] Une recherche sans résultat propose la création d'un aliment personnalisé.
- [ ] Un produit absent d'Open Food Facts bascule sur la création manuelle avec son code-barres.
- [ ] Un produit scanné puis modifié à la source garde ses valeurs d'origine dans Mue.
- [ ] La source de chaque aliment est visible.

### Recettes

- [ ] Une recette ne peut pas être enregistrée sans nom, sans portions ou sans ingrédient.
- [ ] Le bloc `Per serving` se recalcule à chaque modification de quantité ou de portions.
- [ ] Faire varier les portions sur la fiche recalcule les quantités d'ingrédients.
- [ ] Modifier une recette ne modifie aucune entrée de journal antérieure.
- [ ] Supprimer une recette libère les repas planifiés et laisse le journal intact.

### Planification

- [ ] Planifier sur un créneau occupé remplace le repas précédent sans en créer un second.
- [ ] `Remove` libère le créneau et n'altère ni la recette ni le journal.
- [ ] `I ate this` crée l'entrée de journal et l'annulation la supprime.
- [ ] Un repas planifié n'entre dans aucun total avant confirmation.

### Expérience et accessibilité

- [ ] Masquer l'énergie retire tous les chiffres nutritionnels sans casser un parcours.
- [ ] Toute valeur calculée est affichée avec `≈`.
- [ ] Une valeur inconnue s'affiche `—` et jamais `0`.
- [ ] Le module est entièrement utilisable sans caméra et sans réseau, hors scan.
- [ ] Chaque bouton d'icône possède un libellé accessible.

### Technique

- [ ] Les migrations Room sont additives et testées sur une base peuplée.
- [ ] Le calcul nutritionnel est couvert par des tests unitaires indépendants de l'interface.
- [ ] Aucune image n'est stockée dans Room.
- [ ] La suppression d'une recette utilisateur supprime ses fichiers image.
- [ ] Le décodage du code-barres est local et seul le numéro est transmis.
- [ ] L'attribution Open Food Facts et la Licence Ouverte Ciqual sont affichées dans l'application.

## 23. Orientations proposées et leur origine

Aucune ligne de ce tableau n'a été arbitrée. La colonne `Origine` distingue ce qui découle d'une demande explicite pendant la session de cadrage, et ce qui n'est qu'une proposition issue du prototype.

| Sujet | Orientation proposée | Origine |
|---|---|---|
| Existence d'un parcours de création de recette | Requis | Demandé explicitement |
| Les ingrédients sont-ils du texte libre ? | Non, toujours un aliment structuré avec ses valeurs pour 100 | Demandé explicitement, sous condition « si on part là-dessus » |
| Calcul automatique des calories d'un repas | Requis, à partir des quantités et des portions | Demandé explicitement, même condition |
| Existence d'un parcours d'ajout au planning | Requis | Demandé explicitement |
| Possibilité de déplanifier un repas | Requise | Demandé explicitement |
| Cœur du module | Journal de ce qui a été mangé | Proposition, en réponse à l'objection sur l'orientation préparation de repas |
| L'écran principal affiche-t-il le prévu ou le consommé ? | Le consommé uniquement | Proposition, issue de la question sur le sens de `Done` |
| Un repas planifié compte-t-il dans les totaux ? | Non, jamais avant `I ate this` | Proposition, issue de la même question |
| Stockage des images | Système de fichiers, jamais en BLOB dans Room | Proposition, en réponse à la question sur les images |
| Menus hebdomadaires et liste de courses | Hors périmètre V1, candidats V1.1 | Proposition, cohérente avec « tu vas trop loin » mais non confirmée |
| Nom de l'onglet | `Food` | Proposition |
| La recette est-elle obligatoire pour enregistrer un repas ? | Non, elle n'est qu'un chemin sur quatre | Proposition |
| Source des aliments génériques | Table Ciqual 2025, sous-ensemble embarqué, Licence Ouverte | Proposition |
| Source des produits emballés | Open Food Facts, API v3.6, licence ODbL avec attribution | Proposition |
| Décodage du code-barres | Local, ML Kit, seul le numéro est transmis | Proposition |
| Les valeurs externes sont-elles relues à chaque affichage ? | Non, copiées localement à l'ajout | Proposition |
| Une modification de recette est-elle rétroactive ? | Non, le journal conserve un instantané | Proposition |
| Objectif calorique quotidien | Hors périmètre V1 | Proposition |
| Compensation des calories brûlées par le sport | Hors périmètre, y compris après la V1 | Proposition |
| Affichage de l'énergie | Facultatif, masquable | Proposition |
| Précision affichée | Ordre de grandeur, toujours précédé de `≈` | Proposition |
| Fonctionnement hors ligne | Total, sauf le scan | Proposition |
| Compte utilisateur requis | Non | Proposition |
| Le module dépend-il du serveur ? | Non, il le prépare | Proposition |

## 24. Ce qui doit être arbitré avant tout développement

### 24.1 Arbitrages structurants

Ces quatre points déterminent le document entier. Tant qu'ils ne sont pas tranchés, rien ne doit être développé.

1. **Journal ou préparation de repas.** Le prototype orientait Mue vers la préparation de repas, ce qui a été relevé pendant la session. Ce document choisit l'inverse : un journal de ce qui a été mangé, avec la recette en option. C'est le choix le plus lourd du document et il n'a jamais été confirmé.
2. **Ampleur du module.** « Je pense que là tu vas trop loin » n'a jamais reçu de réponse. Le périmètre décrit reste large : catalogue d'aliments, scan, recettes structurées, journal, planification. Une V1 volontairement plus petite est parfaitement défendable — par exemple journal et ajout rapide seuls, en repoussant recettes et scan.
3. **Le scan vaut-il son coût.** Il fait entrer un service externe, une permission caméra, une dépendance réseau et une licence ODbL dans une application jusqu'ici entièrement locale et sans permission réseau. Le bénéfice réel dépend de la part de produits emballés dans l'alimentation réelle de l'utilisateur.
4. **Planning hebdomadaire et liste de courses.** Prototypés, fonctionnels, manipulés pendant la démonstration, mais placés hors V1 par ce document. Les garder changerait la nature du module.

### 24.2 Points techniques à mesurer

- Taille et composition exactes du sous-ensemble Ciqual embarqué, et procédure de mise à jour de sa version.
- Comportement souhaité pour un même aliment consommé plusieurs fois dans la journée : entrées distinctes ou regroupement à l'affichage.
- Opportunité d'un indicateur de protéines quotidiennes, seul repère nutritionnel qui resterait compatible avec le principe non culpabilisant.
- Politique de purge du cache d'images distantes.
- Volume réel du catalogue et performance de la recherche sur un appareil d'entrée de gamme.
