# PRD — Mue Food

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Module | Food |
| Version | V1 du module |
| Statut | Cadrage arbitré ; points ouverts listés en section 24 |
| Date | 26 août 2026 |
| Plateforme | Android natif, téléphone, portrait |
| Langue de l'application | Anglais uniquement |
| PRD du socle | [`PRD.md`](./PRD.md) |
| Modules voisins | [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md), [`PRD_ACTIVITY_TIMER.md`](./PRD_ACTIVITY_TIMER.md) |
| Dépendance serveur | [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) |
| Référence visuelle | [`food`](../proto/fusion/food.html) |

Ce document décrit un module ajouté à une application dont le socle poids et le module activité sont déjà développés. Il ne redéfinit ni le design system, ni la navigation générale, ni les décisions techniques du socle : il les prolonge. En cas de divergence sur la navigation, la section 7 fait autorité ; en cas de divergence avec le prototype, la section 19.

Ce document fixe les agrégats `Food`, `Recipe`, `FoodLogEntry` et `MealPlanEntry` attendus par la section 17 de [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md), ainsi que leurs règles de conflit et leurs outils MCP.

## 2. Résumé

Le module Food enregistre ce que l'on a mangé. Il ajoute un cinquième onglet `Food` à Mue.

Son écran principal est un **journal**, organisé en quatre moments de la journée. Chaque moment est un **contenant**, pas un repas : il accueille autant de lignes que nécessaire, une pomme à dix heures comme un plat complet le soir.

Une ligne est toujours l'une de trois choses : un aliment avec sa quantité, une portion de recette, ou une estimation libre lorsqu'on ne dispose que d'un ordre de grandeur.

Un agent connecté par MCP — le coach — peut préparer les journées à l'avance, écrire des recettes et les faire évoluer. Ses repas apparaissent comme des **propositions** que l'utilisateur confirme en un geste. Ce n'est jamais une dépendance : sans agent, l'application reste entièrement utilisable et l'utilisateur crée lui-même ses aliments, ses recettes et son planning.

Les données restent locales. Le module fonctionne hors ligne, à l'exception du scan de code-barres qui interroge un service externe et dégrade proprement en création manuelle.

## 3. Problème à résoudre

Mue sait suivre le poids et l'activité, mais rien de ce qui les explique le plus directement. Ajouter l'alimentation expose cependant l'application à deux dérives opposées.

La première est le **compteur de calories façon tableur** : une immense base d'aliments, une saisie exigeante, un objectif quotidien qui transforme chaque repas en examen. C'est contraire au principe fondateur de Mue et c'est le chemin le plus court vers un usage obsessionnel puis un abandon.

La seconde est l'**application de préparation de repas** : recettes, menus, liste de courses. Elle oblige à créer une recette pour enregistrer un yaourt et suppose que l'on cuisine toujours ce que l'on mange.

Le module tient la position intermédiaire : un journal, dont la friction est levée par un agent qui prépare la journée quand il en a l'occasion.

Une troisième difficulté est propre au domaine : **une journée vide ne se remplit jamais toute seule**. Sans amorce, l'utilisateur ne revient pas saisir ce qu'il a mangé. C'est la raison d'être des propositions décrites en section 12 — pas la planification pour elle-même.

Les repères nutritionnels retenus sont ceux de [Manger Bouger](https://www.mangerbouger.fr/manger-mieux/a-tout-age-et-a-chaque-etape-de-la-vie/les-recommandations-alimentaires-pour-les-adultes) et de la [HAS](https://www.has-sante.fr/jcms/c_964938/fr/surpoids-et-obesite-de-l-adulte-prise-en-charge-medicale-de-premier-recours) : changements durables, densité énergétique moindre, portions adaptées. Mue n'édicte aucune règle plus stricte et ne prescrit rien.

## 4. Objectifs produit

### 4.1 Objectifs de la V1 du module

- Enregistrer un aliment consommé en moins de quinze secondes.
- Confirmer un repas proposé en un seul geste.
- Couvrir les quatre origines réelles d'un repas : produit emballé, aliment générique, recette, estimation.
- Calculer automatiquement l'énergie et les macronutriments à partir des quantités saisies.
- Accepter la pesée exacte comme l'absence de pesée.
- Conserver une trace fidèle de ce qui a été mangé, y compris quand la recette d'origine change.
- Permettre de créer, modifier et supprimer aliments et recettes à la main.
- Rester entièrement utilisable sans agent, sans compte et sans réseau.

### 4.2 Critères de réussite qualitatifs

- Un yaourt scanné est enregistré sans passer par une recette.
- Une pomme à dix heures trouve sa place sans qu'on se demande où.
- Une journée entière peut être saisie sans jamais ouvrir un formulaire de recette.
- Couper le coach ne casse aucun parcours.
- Aucune valeur affichée ne laisse croire à une précision au gramme près.
- Ajouter une nouvelle source d'aliments ne modifie ni le journal ni les recettes.

## 5. Hors périmètre de la V1

- Objectif calorique personnalisé et tout indicateur de dépassement.
- **Rapprochement entre énergie ingérée et énergie dépensée.** Food enregistre ce qui entre et s'arrête là. La mise en regard des deux séries relève de `Progress` et sera cadrée séparément ; elle n'apparaît nulle part dans ce module.
- Menus hebdomadaires générés automatiquement et liste de courses agrégée.
- Moteur de recommandation embarqué : les suggestions viennent de l'agent, pas d'un algorithme local.
- Micronutriments, vitamines, minéraux, index glycémique et Nutri-Score.
- Photographie d'un plat analysée pour en déduire son contenu.
- Import de recettes depuis une URL ou un fichier.
- Partage de recettes, communauté et notes.
- Rappels, notifications et streaks alimentaires.
- Export CSV du journal alimentaire.
- Traduction du catalogue d'aliments.

## 6. Principes d'expérience

1. **Le moment est un contenant, pas un repas.** Il accueille autant de lignes que la réalité en produit.
2. **Confirmer avant de saisir.** Le geste le plus fréquent est de valider une proposition, pas de remplir un formulaire.
3. **Prévu n'est pas mangé.** Aucun total ne bouge tant que l'utilisateur n'a pas confirmé.
4. **L'agent aide, il ne conditionne rien.** Toute fonction reste atteignable à la main.
5. **Les estimations restent des estimations.** Toute valeur calculée ou externe s'affiche précédée de `≈` et conserve sa provenance.
6. **Factuel, jamais culpabilisant.** Aucun seuil, aucune couleur d'alerte, aucun verdict sur une journée.
7. **Rien de permanent à l'écran pour un réglage occasionnel.** Les options vivent dans les préférences.
8. **Hors ligne par défaut.** Seule une action explicitement réseau, le scan, peut échouer faute de connexion.

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

Le module contient quatre vues et un jeu de feuilles modales :

| Vue | Rôle |
|---|---|
| `Day` | Journal du jour, quatre moments, chacun avec ses lignes et son total. |
| `Trends` | Sept jours de ce qui a été enregistré, et l'historique. |
| `Recipes` | Recettes, les siennes et celles du coach. Création, édition, suppression. |
| `Foods` | Catalogue d'ingrédients. Création, édition, suppression des aliments personnels. |

| Feuille | Rôle |
|---|---|
| `Add food` | Quatre chemins d'ajout, puis quantité et moment. |
| `Recipe detail` | Fiche d'une recette, quantités recalculées selon les portions. |
| `Recipe editor` | Formulaire de recette à ingrédients structurés. |
| `Food picker` | Choix d'un aliment pour une recette. |
| `Food editor` | Création ou édition d'un aliment. |
| `Swap` | Remplacement d'une proposition. |
| `Preferences` | Réglages du module. |

L'écran `Day` ne comporte aucun bandeau d'en-tête : la date, puis les quatre moments. Rien n'y résume la journée, aucun bouton permanent n'y expose un réglage.

## 8. Modèle conceptuel

### 8.1 Trois objets de contenu, un objet d'intention

| Objet | Question à laquelle il répond |
|---|---|
| `Food` | Que vaut cet aliment pour 100 g ou 100 ml ? |
| `Recipe` | Comment j'assemble plusieurs aliments en un plat réutilisable ? |
| `FoodLogEntry` | Qu'ai-je réellement mangé, quand, et en quelle quantité ? |
| `MealPlanEntry` | Qu'est-ce qui m'est proposé pour un moment donné ? |

**Il n'existe pas d'objet « repas ».** `Breakfast`, `Lunch`, `Snack` et `Dinner` sont une étiquette portée par chaque ligne du journal, pas une entité. Le moment de la journée est un regroupement d'affichage : il n'a ni identité, ni nom, ni valeurs propres. C'est ce qui autorise plusieurs lignes hétérogènes au même moment.

Une recette n'est rien d'autre qu'un **raccourci enregistré** : « ces aliments, dans ces quantités ». Pour une pomme, elle est inutile.

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
- servingLabel          portion usuelle, facultatif : « pot », « apple », « handful »
- servingGrams          poids de cette portion
- cookedRatio           facultatif, voir 8.6
- rawLabel              libellé de l'état de référence, par défaut « Raw »
- cookedLabel           libellé de l'état cuit, par défaut « Cooked »
- imageRef
- createdAt
- updatedAt
```

`caloriesPer100` est exprimée en kilocalories, les macronutriments en grammes. Les valeurs décrivent toujours l'**état de référence** de l'aliment, celui nommé par `rawLabel`.

### 8.3 Recette

```text
Recipe
- id
- name
- description
- author: USER | AGENT
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

Une recette **ne stocke aucune valeur nutritionnelle** : elle est recalculée à partir de ses ingrédients et de `baseServings` à chaque affichage. Corriger la valeur d'un aliment corrige donc toutes les recettes qui l'utilisent, sans migration.

Les quantités des ingrédients sont exprimées pour la recette entière, jamais par portion.

`author` distingue ce que l'utilisateur a écrit de ce que l'agent a écrit. C'est une information d'affichage, pas une permission : la section 21 précise que l'agent peut modifier les deux.

### 8.4 Ligne de journal

```text
FoodLogEntry
- id
- consumedOn            date locale
- consumedAt            heure locale, voir 10.3
- slot: BREAKFAST | LUNCH | SNACK | DINNER
- kind: FOOD | RECIPE | QUICK
- sourceRef             foodId ou recipeId, facultatif
- title
- amountLabel
- quantity              en grammes ou millilitres pour un aliment, en portions pour une recette
- quantityUnit: GRAM | MILLILITRE | SERVING
- portions              nombre de portions usuelles saisies, facultatif
- weighedCooked         vrai si la quantité a été pesée à l'état cuit
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

Les valeurs nutritionnelles sont **copiées** au moment de l'enregistrement. Modifier ou supprimer ensuite l'aliment ou la recette d'origine ne change jamais une ligne déjà journalisée. `sourceRef` sert à proposer « refaire la même chose » et à ouvrir la fiche d'origine si elle existe encore.

`estimation` vaut `APPROXIMATE` pour un ajout rapide et pour toute recette dont un ingrédient est lui-même approximatif.

### 8.5 Proposition

```text
MealPlanEntry
- id
- plannedOn             date locale
- slot: BREAKFAST | LUNCH | SNACK | DINNER
- recipeId
- foodId
- plannedServings
- author: USER | AGENT
- consumedLogEntryId
- createdAt
- updatedAt
```

Un moment ne porte **au maximum qu'une proposition**, à la différence du journal qui accepte autant de lignes que voulu. Proposer sur un moment déjà pourvu remplace la proposition précédente.

`consumedLogEntryId` est renseigné lorsque l'utilisateur confirme ; l'annulation le vide et supprime la ligne de journal correspondante.

### 8.6 Unités, portions et cuisson

- Le stockage nutritionnel n'utilise que le gramme et le millilitre.
- Un aliment liquide utilise `MILLILITRE` ; Mue n'applique aucune densité implicite entre les deux.
- Une portion de recette n'est pas une unité nutritionnelle : c'est une fraction de la recette entière.

**Portions usuelles.** Un aliment peut déclarer une portion courante — `1 pot = 150 g`, `1 apple = 150 g`, `1 handful = 25 g`. Elle n'est qu'une aide à la saisie : la quantité reste stockée en grammes. La saisie exacte en grammes reprend toujours la main sur la portion.

**Cuisson.** Un aliment porte un `cookedRatio` facultatif, égal à `masse cuite / masse de référence`. Il ne s'applique qu'aux aliments dont **seule l'eau bouge** :

| Aliment | Ratio | Sens |
|---|---:|---|
| Pâtes complètes, sèches | 2,3 | absorbent de l'eau |
| Riz blanc, sec | 2,8 | absorbe de l'eau |
| Lentilles corail, sèches | 2,4 | absorbent de l'eau |
| Blanc de poulet | 0,72 | perd de l'eau |

Perdre de l'eau et en absorber sont le même phénomène : la matière sèche est conservée, seule la masse change. Un ratio unique suffit donc dans les deux sens, et un rôti nature ne demande **aucune entrée supplémentaire**.

Ce qu'un ratio ne peut pas modéliser, c'est ce qui est **ajouté ou retiré** : l'huile absorbée à la poêle, le gras qui goutte sur une grille, l'eau de cuisson jetée. Règle correspondante : **le gras ajouté se journalise comme une ligne distincte.** Un poulet rôti s'enregistre comme le poulet, pesé cuit, plus les dix grammes d'huile utilisés. Jamais comme un aliment hybride.

Deux entrées de catalogue distinctes ne sont créées que lorsque la **composition** change, pas seulement la masse : conserve au sirop, friture, produit sucré.

Le ratio n'est jamais saisi à la main : Ciqual contient les deux états d'un même aliment, il se déduit de la paire à l'import.

## 9. Catalogue d'aliments

### 9.1 Aliments génériques, Ciqual

Le catalogue générique embarqué provient de la table Ciqual 2025 de l'Anses, qui décrit 3 484 aliments et 74 constituants nutritionnels et est distribuée sous Licence Ouverte. [Table Ciqual 2025](https://ciqual.anses.fr/cms/fr/la-table-ciqual-2025)

- Un sous-ensemble est intégré à l'application, suffisant pour couvrir les aliments courants sans embarquer la table entière.
- Seuls les constituants utilisés par Mue sont conservés : énergie, protéines, glucides, lipides, fibres.
- Le sous-ensemble est versionné et régénérable ; sa version est enregistrée dans `sourceVersion`.
- Il est disponible hors ligne, sans compte, dès la première ouverture.
- Ses aliments ne sont ni modifiables ni supprimables ; ils sont duplicables en aliment personnalisé.

### 9.2 Produits emballés, Open Food Facts

Le scan d'un code-barres interroge Open Food Facts, dont l'API v3.6 est recommandée pour les nouvelles intégrations. [Documentation Open Food Facts](https://openfoodfacts.github.io/openfoodfacts-server/api/)

- Le décodage est **local** : la caméra lit le numéro avec ML Kit, aucune image ne quitte le téléphone. [Documentation Android ML Kit](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- Seul le numéro est transmis, pour récupérer nom, marque, image et nutriments pour 100 g.
- Le produit est **copié** dans le catalogue local au moment de l'ajout. Une modification ultérieure de la fiche distante ne change rien.
- Une fiche incomplète est acceptée : les valeurs manquantes restent vides et saisissables, jamais devinées.
- Un produit introuvable bascule sur la création manuelle, pré-remplie du code-barres.
- Open Food Facts est publié sous licence ODbL : l'attribution et les obligations de réutilisation sont respectées et affichées dans `Profile`, section `About`. [Conditions de licence](https://openfoodfacts.github.io/openfoodfacts-server/api/tutorials/license-be-on-the-legal-side/)

### 9.3 Aliments personnalisés

- Créés depuis l'étiquette d'un produit, pour un aliment absent des catalogues, ou en dupliquant une entrée de référence.
- Champs requis : nom et énergie pour 100 g ou 100 ml. Marque, macronutriments et fibres sont facultatifs.
- Modifiables et supprimables. La suppression n'affecte aucune ligne de journal déjà enregistrée.
- Un aliment utilisé par une recette **ne peut pas être supprimé** : Mue nomme les recettes concernées et demande de l'en retirer d'abord.

### 9.4 Recherche

- Une seule barre de recherche interroge Ciqual, les produits copiés et les aliments personnalisés.
- Un filtre restreint à une source.
- Les aliments récemment utilisés apparaissent en tête lorsque la recherche est vide.
- La recherche est insensible à la casse et aux accents, et fonctionne hors ligne.
- Une recherche sans résultat propose la création d'un aliment pré-rempli du terme saisi.

### 9.5 Granularité du catalogue

Le catalogue se découpe **là où les chiffres changent, pas là où le vocabulaire change**.

Deux entrées distinctes ne se justifient que si leurs valeurs diffèrent de plus d'environ **15 %**, c'est-à-dire au-delà du bruit que le module assume déjà.

Séparation justifiée :

- morceaux de viande — un blanc de poulet et une cuisse avec peau n'ont pas la même teneur en lipides ;
- taux de matière grasse — lait entier, demi-écrémé, écrémé ; viande hachée 5 % et 20 % ;
- raffiné ou complet — riz blanc et riz complet n'ont pas les mêmes fibres ;
- préparations — cru, cuit, frit, en conserve au sirop, en compote sucrée.

Séparation injustifiée :

- variétés d'un même fruit ou légume — l'écart entre pommes est de l'ordre de 10 %, quand le poids réel du fruit varie du simple au double ;
- marques d'un même produit générique — c'est le rôle du code-barres ;
- provenances et labels.

Trois raisons à cette règle. Elle évite une précision de façade dans un module qui affiche tout avec `≈`. Elle garde la recherche praticable. Et surtout elle garde le **choix de l'agent déterministe** : entre huit variétés de pomme, un agent en prend une arbitrairement, et deux recettes équivalentes cessent de donner les mêmes chiffres.

Rien n'enferme l'utilisateur : le scan apporte les valeurs exactes d'un produit précis, et l'aliment personnalisé couvre les cas où la finesse compte réellement.

## 10. Journal alimentaire

### 10.1 L'écran `Day`

- Ouvre sur la date du jour ; une navigation par date permet de consulter et de compléter les jours passés.
- Quatre moments dans l'ordre `Breakfast`, `Lunch`, `Snack`, `Dinner`.
- Chaque moment affiche, dans cet ordre : la proposition non confirmée s'il y en a une, puis ses lignes triées par heure, puis un bouton d'ajout toujours présent.
- Chaque moment affiche **son propre total** lorsqu'il contient au moins une ligne. C'est une addition locale, pas un cumul de journée.
- Aucun bandeau, aucun résumé, aucun bouton de réglage en tête d'écran.

### 10.2 Une ligne, trois formes possibles

| Forme | Cas d'usage | Contenu |
|---|---|---|
| `FOOD` | Une pomme, 150 g de skyr, un produit scanné. | Aliment + quantité |
| `RECIPE` | Une portion et demie de curry. | Recette + nombre de portions |
| `QUICK` | Un plat de restaurant dont on ne connaît que l'ordre de grandeur. | Nom + énergie, marqué `APPROXIMATE` |

Un même moment peut mélanger les trois. Un yaourt et une banane au petit-déjeuner sont deux lignes, jamais une recette à créer.

### 10.3 L'heure et le choix du moment

Chaque ligne porte l'heure locale à laquelle elle a été enregistrée. Elle sert à ordonner les lignes d'un même moment et prépare une vue chronologique ultérieure sans redessiner le modèle.

Le moment est **présélectionné d'après cette heure**, et reste modifiable en un geste :

| Plage | Moment proposé |
|---|---|
| 05:00 – 10:00 | `Breakfast` |
| 11:30 – 14:30 | `Lunch` |
| 18:00 – 22:00 | `Dinner` |
| tout le reste | `Snack` |

Une pomme à dix heures tombe donc en collation, un dessert à quatorze heures au déjeuner. Ces plages ne créent aucune contrainte : elles ne font que choisir la valeur par défaut.

### 10.4 Ce que le journal ne fait pas

- Il n'invente jamais une journée. Un jour sans saisie reste vide.
- Il ne considère jamais une proposition comme mangée.
- Il n'affiche ni objectif, ni reste à consommer, ni pourcentage d'un besoin.
- Il ne met aucune valeur en regard de l'énergie dépensée.
- Il ne compare pas deux journées entre elles.

### 10.5 `Trends`

Sept jours de ce qui a été enregistré : une barre par jour, la moyenne des jours renseignés, le nombre de jours renseignés, le nombre de lignes, et l'historique cliquable.

Aucune autre série n'y figure. La mise en regard avec l'activité relève de `Progress`.

## 11. Recettes

- Une recette est une préparation réutilisable, créée depuis `Recipes`, depuis le journal, ou par l'agent.
- Le formulaire comprend nom, type de moment, temps de préparation, nombre de portions, ingrédients, étapes et couverture.
- Les ingrédients sont ajoutés par un sélecteur commun : recherche, scan, création.
- Chaque ingrédient affiche sa contribution énergétique dès que sa quantité est saisie.
- Le bloc `Per serving` recalcule en direct énergie, protéines, glucides et lipides.
- Les étapes sont saisies une par ligne.
- Une recette peut être mise en favori, recherchée par nom, filtrée par auteur.
- La fiche permet de faire varier le nombre de portions affichées ; les quantités d'ingrédients suivent proportionnellement.
- Modifier une recette n'a **aucun effet rétroactif** sur le journal.
- Supprimer une recette n'affecte ni le journal ni les repas déjà consommés ; les propositions qui la référencent sont libérées.
- La création manuelle reste obligatoire même si la majorité des recettes viennent de l'agent.

## 12. Propositions et planification

La planification n'est pas une fonction de prévision : c'est **l'amorce qui rend le journal possible**. Une journée vide ne se remplit pas ; une journée proposée se confirme.

- Une proposition est posée par l'agent ou par l'utilisateur, sur une date et un moment.
- Elle s'affiche dans le moment concerné comme une carte visiblement distincte des lignes réelles, marquée de son auteur.
- Elle porte trois actions : `I ate this`, `Swap`, `Dismiss`.
- `I ate this` crée la ligne de journal correspondante et lie les deux objets.
- Supprimer cette ligne de journal remet la proposition en attente.
- `Swap` remplace la recette proposée et **bascule l'auteur sur l'utilisateur** : Mue ne s'attribue jamais un choix qui n'est pas le sien.
- `Dismiss` retire la proposition, laisse le moment libre, et ne touche ni la recette ni le journal.
- Une proposition n'entre dans aucun total tant qu'elle n'est pas confirmée.
- Une préférence permet de couper entièrement les propositions de l'agent, afin de vérifier que le module reste utilisable seul.

## 13. Calculs nutritionnels

### 13.1 Formules

```text
poids de référence      = poids pesé / cookedRatio si pesé cuit, sinon poids pesé
contribution d'un aliment = poids de référence × valeurPour100 / 100

total d'une recette     = somme des contributions de ses ingrédients
valeur par portion      = total de la recette / baseServings

ligne FOOD              = contribution de l'aliment
ligne RECIPE            = valeur par portion × portions consommées
ligne QUICK             = valeurs saisies

total d'un moment       = somme de ses lignes
```

Aucune valeur nutritionnelle n'est stockée ailleurs que dans les lignes de journal, où elle est figée.

### 13.2 Affichage

- L'énergie est arrondie à l'unité, les macronutriments au dixième de gramme.
- Toute valeur issue d'un calcul ou d'une source externe est précédée de `≈`.
- Une valeur inconnue est affichée `—`, jamais `0`.
- Les nombres utilisent `font-variant-numeric: tabular-nums`.
- Le libellé d'une quantité conserve les deux lectures quand elles existent : `1.5 × apple (225 g)`, `150 g cooked`.
- Une préférence `Show energy` masque toutes les valeurs énergétiques et de macronutriments du module. Elle vit dans les préférences et n'a aucun raccourci permanent à l'écran.

### 13.3 Ce que Mue assume

Mue affiche des ordres de grandeur, pas des mesures. Les limites suivantes sont actées, ce ne sont pas des défauts à corriger :

- les fiches Open Food Facts sont collaboratives et parfois incomplètes ;
- le gras absorbé à la poêle ou perdu sur une grille n'est pas mesurable ;
- l'eau de cuisson jetée emporte une part variable des nutriments ;
- une portion réelle n'est jamais exactement une portion théorique ;
- le poids d'un fruit varie plus que sa variété.

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
- L'image choisie est copiée dans Mue plutôt que référencée dans la galerie.
- Redimensionnement à environ 1 200 px sur le plus grand côté, avec une miniature pour les listes.
- Aucune image en BLOB dans Room : Room conserve les données, le système de fichiers conserve les images.
- La suppression d'une recette utilisateur supprime son fichier et sa miniature.

## 15. Validation des données

| Champ | Règle |
|---|---|
| Nom d'aliment ou de recette | 1 à 80 caractères après nettoyage des espaces. |
| Énergie pour 100 | 0 à 900 kcal. |
| Macronutriment pour 100 | 0 à 100 g. La somme protéines + glucides + lipides ne peut dépasser 100 g. |
| `cookedRatio` | Strictement positif, de 0,3 à 5. |
| Portion usuelle | 1 à 2 000 g ou ml. |
| Nombre de portions usuelles saisi | 0,5 à 20, par pas de 0,5. |
| Quantité d'un ingrédient | Strictement supérieure à 0, maximum 5 000 g ou ml. |
| Portions d'une recette | Entier de 1 à 12. |
| Portions consommées | 0,25 à 10, par pas de 0,25. |
| Ajout rapide | Nom requis, énergie requise de 0 à 5 000 kcal, protéines facultatives. |
| Heure de consommation | Heure locale valide ; par défaut celle de la saisie. |
| Date de consommation | Aujourd'hui ou dans le passé, jamais dans le futur. |
| Date proposée | Aujourd'hui ou dans le futur, dans les 60 jours. |
| Étapes d'une recette | 0 à 30 lignes, 500 caractères par ligne. |
| Ingrédients d'une recette | 1 à 40. Une recette sans ingrédient ne peut pas être enregistrée. |

Une valeur refusée est signalée à côté du champ concerné, sans jamais vider le formulaire.

## 16. Exigences fonctionnelles

### 16.1 Journal

#### FR-FOOD-001 — Le moment est une liste

Chaque moment affiche ses lignes, son total lorsqu'il en a, et un bouton d'ajout toujours disponible. Plusieurs lignes de nature différente coexistent sans restriction.

#### FR-FOOD-002 — Ajout par recherche

L'utilisateur recherche un aliment, le sélectionne, saisit une quantité, choisit un moment, puis enregistre. L'aperçu nutritionnel se met à jour à chaque frappe.

#### FR-FOOD-003 — Ajout par scan

L'utilisateur cadre un code-barres, obtient une fiche produit, saisit une quantité, choisit un moment, puis enregistre. En cas d'échec réseau ou de produit inconnu, Mue propose la création manuelle pré-remplie du code-barres.

#### FR-FOOD-004 — Ajout par recette

L'utilisateur choisit une recette, indique un nombre de portions consommées, choisit un moment, puis enregistre. Les valeurs sont celles de la recette à cet instant.

#### FR-FOOD-005 — Ajout rapide

L'utilisateur saisit un nom, une énergie et éventuellement des protéines. La ligne est marquée approximative.

#### FR-FOOD-006 — Quantité pesée ou non

L'écran de quantité propose, selon l'aliment : le sélecteur d'état de cuisson si un `cookedRatio` existe, le compteur de portions usuelles si l'aliment en déclare une, et toujours le poids exact. Saisir le poids exact reprend la main sur les portions.

#### FR-FOOD-007 — Moment présélectionné par l'heure

Le moment proposé découle de l'heure de saisie selon le tableau 10.3, et reste modifiable en un geste.

#### FR-FOOD-008 — Correction et suppression

Chaque ligne peut être corrigée — quantité, portions, état de cuisson, moment — ou supprimée, avec recalcul immédiat du total du moment. La provenance et les valeurs pour 100 ne changent pas.

#### FR-FOOD-009 — Journées passées

La navigation par date permet de consulter, compléter et corriger un jour passé. Aucune saisie n'est possible sur une date future.

#### FR-FOOD-010 — Masquage de l'énergie

La préférence `Show energy` masque toutes les valeurs énergétiques et de macronutriments. Le reste du module continue de fonctionner à l'identique. La préférence est locale à l'appareil.

### 16.2 Recettes

#### FR-RECIPE-001 — Création

Nom, portions et au moins un ingrédient sont requis ; temps, description, étapes et couverture sont facultatifs.

#### FR-RECIPE-002 — Ingrédients structurés

Un ingrédient est toujours un `Food` du catalogue. Le texte libre n'est pas accepté comme ingrédient nutritionnel.

#### FR-RECIPE-003 — Calcul par portion

Le formulaire affiche en direct les valeurs par portion, recalculées à chaque modification.

#### FR-RECIPE-004 — Consultation et portions

La fiche permet de faire varier le nombre de portions affichées ; les quantités d'ingrédients suivent.

#### FR-RECIPE-005 — Favoris, recherche et auteur

Une recette peut être mise en favori, retrouvée par son nom, et filtrée selon qu'elle vient de l'utilisateur ou de l'agent.

#### FR-RECIPE-006 — Modification et suppression

Ni l'une ni l'autre ne modifie une ligne de journal existante. La suppression demande confirmation et indique les propositions concernées.

### 16.3 Catalogue

#### FR-CATALOG-001 — Catalogue embarqué

Le sous-ensemble Ciqual est disponible dès la première ouverture, hors ligne, sans compte.

#### FR-CATALOG-002 — Produit scanné

Le décodage est local, seul le code-barres est transmis, le produit est copié localement avec sa source et son identifiant d'origine.

#### FR-CATALOG-003 — Aliment personnalisé

Un aliment peut être créé, modifié, supprimé et dupliqué depuis une entrée de référence. Un aliment utilisé par une recette n'est pas supprimable.

#### FR-CATALOG-004 — Provenance

Chaque aliment affiche sa source. Chaque ligne de journal conserve la source de ses valeurs.

#### FR-CATALOG-005 — Granularité

Le découpage du catalogue respecte la règle de la section 9.5.

### 16.4 Propositions

#### FR-PLAN-001 — Proposer

Une proposition est posée par l'agent ou l'utilisateur sur une date et un moment. Un moment déjà pourvu voit sa proposition remplacée.

#### FR-PLAN-002 — Remplacer et retirer

`Swap` remplace la recette et bascule l'auteur sur l'utilisateur. `Dismiss` libère le moment sans toucher au reste.

#### FR-PLAN-003 — Confirmer

`I ate this` crée la ligne de journal correspondante. Supprimer cette ligne remet la proposition en attente.

#### FR-PLAN-004 — Fonctionnement sans agent

Une préférence coupe toutes les propositions. Dans cet état, chaque fonction du module reste atteignable manuellement.

## 17. États vides et erreurs

| Situation | Comportement attendu |
|---|---|
| Aucune ligne aujourd'hui | Quatre moments vides et leur bouton d'ajout, aucun total inventé. |
| Aucune proposition | Rien ne le signale : l'absence de carte suffit. |
| Aucune recette enregistrée | Message d'invitation et bouton de création, sans recette factice. |
| Recherche sans résultat | Proposition de créer un aliment pré-rempli du terme recherché. |
| Code-barres illisible | Le scanner continue ; saisie manuelle du numéro possible. |
| Produit absent d'Open Food Facts | Bascule vers la création manuelle pré-remplie. |
| Réseau indisponible pendant un scan | Message explicite ; les trois autres chemins restent utilisables. |
| Fiche produit incomplète | Valeurs manquantes vides et modifiables, jamais estimées. |
| Permission caméra refusée | Le scan est désactivé avec explication ; le reste du module est intact. |
| Quantité invalide | Erreur à côté du champ, formulaire conservé. |
| Aliment utilisé par une recette | Suppression refusée, recettes concernées nommées. |
| Aliment supprimé mais journalisé | La ligne reste intacte ; sa fiche d'origine indique qu'elle n'existe plus. |
| Recette supprimée mais proposée | Le moment est libéré et signalé. |
| Stockage image indisponible | La recette est enregistrée sans image, avec un message non bloquant. |

## 18. Accessibilité

- Toutes les cibles tactiles mesurent au moins 48 dp.
- Chaque bouton d'icône possède un libellé lisible par un lecteur d'écran.
- Les totaux et valeurs nutritionnelles sont annoncés avec leur unité et la mention d'approximation.
- L'heure d'une ligne est annoncée avec son contenu.
- L'ajout d'une ligne annonce le résultat sans voler le focus.
- Le contraste respecte les seuils du design system, y compris pour l'ambre sur fond sombre.
- Le scanner propose une alternative complète à la caméra : la saisie manuelle du code-barres.
- Aucune information n'est portée par la seule couleur : une proposition se distingue aussi par son libellé d'auteur et son contour en pointillés.
- Les animations respectent la réduction de mouvement du système.

## 19. Design, icônes et mouvement

- Le module reprend la direction `Fusion` : fond très sombre, accent ambre, Sora, cartes tactiles, rayons généreux.
- Le prototype de référence est [`food.html`](../proto/fusion/food.html). Ce document fait autorité pour le modèle et les règles ; le prototype fait autorité pour la mise en page et le rythme visuel.
- Une proposition est visuellement secondaire : contour en pointillés, fond très léger, hauteur réduite. Une ligne réelle est pleine et opaque. La différence doit se lire sans lire le texte.
- Les icônes distinguent les moments : lever de soleil, soleil, fruit, lune.
- Chaque source d'aliment possède une icône stable : catalogue générique, produit emballé, aliment personnalisé, recette, estimation rapide.
- Le mouvement reste fonctionnel : apparition d'une ligne ajoutée, recalcul visible du total, feuille modale glissante. Aucune animation ne retarde une saisie.

## 20. Persistance et architecture technique

- Room reste la source observable de l'interface.
- Cinq tables sont ajoutées : `food`, `recipe`, `recipe_ingredient`, `food_log_entry`, `meal_plan_entry`.
- Les migrations sont additives et explicites. `fallbackToDestructiveMigration` reste interdit.
- Le sous-ensemble Ciqual est livré comme ressource et inséré au premier démarrage, avec sa version ; une mise à jour ne modifie jamais un aliment personnalisé ni une ligne de journal.
- Les index couvrent au minimum `food_log_entry(consumedOn, slot, consumedAt)`, `meal_plan_entry(plannedOn, slot)` en unicité, `recipe_ingredient(recipeId)` et la recherche par nom d'aliment.
- Les préférences du module — affichage de l'énergie, propositions de l'agent — vivent dans DataStore et ne sont pas synchronisées.
- Le calcul nutritionnel est une fonction pure du domaine, testée indépendamment de l'interface et réutilisable par le serveur.
- Le scan utilise ML Kit en décodage local ; l'appel réseau à Open Food Facts est isolé derrière une interface remplaçable et n'est jamais requis par un autre parcours.
- L'accès réseau du module se limite à Open Food Facts et aux images de produits. Il partage la permission `android.permission.INTERNET` introduite par le module serveur, et impose la même mise à jour de la politique de confidentialité et de la fiche Play Store.
- Aucune donnée personnelle n'est transmise à Open Food Facts.

## 21. Serveur, synchronisation et MCP

Cette section fournit ce qui manquait à la section 17 de [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md). Elle ne redéfinit pas le protocole : elle décrit le comportement du domaine alimentaire à l'intérieur de celui-ci.

### 21.1 Domaines synchronisés

| Domaine | Synchronisé | Accessible par MCP | Écriture agent | Règle |
|---|---:|---:|---:|---|
| Aliments personnalisés | Oui | Oui | Oui | Agrégat autonome, identifiant stable. |
| Produits copiés depuis Open Food Facts | Oui | Oui | Oui | Copie locale synchronisée, jamais re-téléchargée par le serveur. |
| Catalogue Ciqual embarqué | Non | Oui | Non | Référence versionnée, pas une donnée personnelle. |
| Recettes | Oui | Oui | Oui | Agrégat complet avec ses ingrédients. |
| Lignes de journal | Oui | Oui | Oui | Une ligne par consommation, avec son instantané. |
| Propositions | Oui | Oui | Oui | Une proposition maximum par date et moment. |
| Préférences du module | Non | Non | Non | Spécifiques à l'appareil. |

### 21.2 Agrégats

- `Food` : l'aliment seul.
- `Recipe` : la recette **avec** ses ingrédients, synchronisée atomiquement. Une recette n'apparaît jamais sans ses ingrédients.
- `FoodLogEntry` : la ligne seule, autoportante puisqu'elle contient son instantané.
- `MealPlanEntry` : la proposition seule.

Une recette peut référencer un aliment que le client n'a pas encore reçu. Le client applique la recette et affiche l'ingrédient par son instantané de nom et de quantité jusqu'à réception de l'aliment ; il ne rejette pas l'agrégat.

### 21.3 Règles de conflit

- `FoodLogEntry` : les lignes sont indépendantes. Deux lignes créées séparément coexistent, elles ne fusionnent jamais. Une modification concurrente de la même ligne applique la dernière mutation acceptée par le serveur.
- `Recipe` : dernière mutation acceptée, agrégat entier. Les ingrédients ne sont pas fusionnés ligne à ligne.
- `Food` personnalisé : dernière mutation acceptée.
- `MealPlanEntry` : la clé métier est `(date, moment)`. Deux propositions concurrentes sur le même moment se résolvent par la dernière mutation acceptée ; la précédente est remplacée, jamais dupliquée.

### 21.4 Périmètre de l'agent

L'agent dispose du **même pouvoir que l'utilisateur**, sans restriction de périmètre. Il crée des données finales, jamais des brouillons, conformément à la décision 7 du PRD serveur.

Il peut donc :

- créer, modifier et supprimer des aliments personnalisés ;
- créer, modifier et supprimer des recettes, **y compris celles écrites par l'utilisateur** ;
- poser, remplacer et retirer des propositions ;
- créer, **modifier** et supprimer des lignes de journal, y compris rétroactivement.

Ce choix est assumé : l'utilisateur a explicitement demandé que le coach puisse tout faire. Sa contrepartie est que l'historique peut changer sans action de l'utilisateur. Deux garanties compensent :

- toute écriture d'agent est auditée selon la section 14.7 du PRD serveur — identité, outil, instant, agrégats, résultat ;
- l'écran `Data & sync` doit permettre de consulter cet audit, afin qu'une valeur modifiée soit toujours explicable.

Seule limite conservée : l'agent ne peut ni modifier ni supprimer une entrée du catalogue Ciqual, qui n'est pas une donnée personnelle.

### 21.5 Outils MCP

Lecture :

- `list_food_logs` — journal sur une période, filtrable par moment.
- `get_daily_nutrition` — totaux d'une journée, avec le détail des lignes et la mention d'approximation.
- `search_foods` — recherche dans les aliments accessibles à l'utilisateur.
- `get_recipe` — recette complète avec ingrédients et valeurs par portion.
- `list_recipes` — recettes enregistrées, filtrables par type, auteur et favoris.
- `list_meal_plan` — propositions sur une période.

Écriture :

- `create_food_log`, `update_food_log`, `delete_food_log`
- `create_food`, `update_food`, `delete_food`
- `create_recipe`, `update_recipe`, `delete_recipe`
- `plan_meal`, `unplan_meal`

Règles communes :

- une ligne de journal ne peut pas être créée dans le futur ;
- les suppressions sont annotées comme destructives ;
- les valeurs calculées par le serveur conservent leur provenance et leur méthode d'obtention ;
- un outil d'écriture fournit une clé d'idempotence ou réutilise l'identifiant de mutation MCP du client.

## 22. Critères d'acceptation de la V1

### Journal

- [ ] Un moment accepte plusieurs lignes de nature différente sans qu'aucune n'en remplace une autre.
- [ ] Le total d'un moment se recalcule à chaque ajout, correction et suppression.
- [ ] Une pomme enregistrée à dix heures est proposée en collation, un dessert à quatorze heures au déjeuner.
- [ ] Les lignes d'un moment sont ordonnées par heure.
- [ ] Un produit scanné est enregistré sans passer par une recette.
- [ ] Un ajout rapide est enregistré et signalé comme approximatif.
- [ ] Une journée sans saisie reste vide et n'affiche aucun total inventé.
- [ ] Un jour passé peut être complété ; un jour futur ne peut pas l'être.
- [ ] Aucun écran de Food n'affiche d'énergie dépensée.

### Quantités

- [ ] Un aliment déclarant une portion usuelle propose le compteur de portions et le poids exact.
- [ ] Saisir un poids exact désactive la lecture en portions et le libellé n'en garde qu'une.
- [ ] Le sélecteur cru/cuit n'apparaît que sur les aliments portant un `cookedRatio`.
- [ ] 150 g de blanc de poulet pesés cuits donnent une valeur supérieure aux mêmes 150 g pesés crus.
- [ ] Un rôti s'enregistre comme l'aliment plus une ligne de matière grasse, sans entrée de catalogue supplémentaire.

### Aliments

- [ ] Le catalogue générique est utilisable hors ligne dès la première ouverture.
- [ ] Une recherche sans résultat propose la création d'un aliment pré-rempli.
- [ ] Un produit absent d'Open Food Facts bascule sur la création manuelle avec son code-barres.
- [ ] Un produit scanné puis modifié à la source garde ses valeurs d'origine dans Mue.
- [ ] Un aliment utilisé par une recette ne peut pas être supprimé et les recettes concernées sont nommées.
- [ ] Le catalogue ne contient pas plusieurs variétés d'un même fruit ou légume.

### Recettes

- [ ] Une recette ne peut pas être enregistrée sans nom, sans portions ou sans ingrédient.
- [ ] Le bloc `Per serving` se recalcule à chaque modification.
- [ ] Faire varier les portions sur la fiche recalcule les quantités d'ingrédients.
- [ ] Modifier une recette ne modifie aucune ligne de journal antérieure.
- [ ] Supprimer une recette libère les propositions et laisse le journal intact.
- [ ] Une recette peut être créée entièrement à la main, sans agent.

### Propositions

- [ ] Une proposition est visuellement distincte d'une ligne réelle sans lire son texte.
- [ ] Un moment ne porte jamais deux propositions.
- [ ] `I ate this` crée la ligne et sa suppression remet la proposition en attente.
- [ ] `Swap` remplace la proposition et l'attribue à l'utilisateur.
- [ ] Une proposition n'entre dans aucun total avant confirmation.
- [ ] Propositions coupées, chaque fonction du module reste atteignable.

### Expérience et accessibilité

- [ ] L'écran du jour ne comporte ni bandeau de résumé ni bouton de réglage permanent.
- [ ] Masquer l'énergie depuis les préférences retire tous les chiffres nutritionnels sans casser un parcours.
- [ ] Toute valeur calculée est affichée avec `≈`.
- [ ] Une valeur inconnue s'affiche `—` et jamais `0`.
- [ ] Le module est entièrement utilisable sans caméra et sans réseau, hors scan.

### Technique

- [ ] Les migrations Room sont additives et testées sur une base peuplée.
- [ ] Le calcul nutritionnel, conversion de cuisson comprise, est couvert par des tests unitaires indépendants de l'interface.
- [ ] Aucune image n'est stockée dans Room.
- [ ] La suppression d'une recette utilisateur supprime ses fichiers image.
- [ ] Le décodage du code-barres est local et seul le numéro est transmis.
- [ ] L'attribution Open Food Facts et la Licence Ouverte Ciqual sont affichées dans l'application.
- [ ] Une écriture d'agent sur une ligne de journal existante est visible dans l'audit.

## 23. Décisions arrêtées

| Sujet | Décision |
|---|---|
| Nom de l'onglet | `Food`. |
| Cœur du module | Journal de ce qui a été mangé. |
| Existe-t-il un objet « repas » ? | Non. Le moment est une étiquette portée par chaque ligne. |
| Que contient un moment ? | Une liste de lignes et son total, pas un repas unique. |
| Formes possibles d'une ligne | Aliment + quantité, portion de recette, estimation libre. |
| Rôle de la planification | Amorcer le journal, pas prévoir. Une proposition par moment. |
| Une proposition compte-t-elle dans les totaux ? | Non, jamais avant confirmation. |
| L'application dépend-elle de l'agent ? | Non. Une préférence coupe les propositions et tout reste atteignable. |
| Périmètre de l'agent | Total : aliments, recettes, propositions et lignes de journal, en création, modification et suppression. |
| Contrepartie du périmètre de l'agent | L'audit MCP est consultable depuis l'application. |
| Création manuelle d'aliments et de recettes | Obligatoire, même si l'agent en produit la majorité. |
| Les ingrédients sont-ils du texte libre ? | Non. Toujours un aliment structuré. |
| Une recette stocke-t-elle ses calories ? | Non. Toujours recalculées depuis ses ingrédients. |
| Une modification de recette est-elle rétroactive ? | Non. Le journal conserve un instantané. |
| Pesée | Les deux : portions usuelles et poids exact, le poids exact primant. |
| Cru et cuit | Un seul aliment et un `cookedRatio` quand seule l'eau bouge, dans les deux sens. |
| Matière grasse de cuisson | Une ligne de journal distincte, jamais un aliment hybride. |
| Deux entrées de catalogue | Seulement quand la composition change, pas la masse. |
| Granularité du catalogue | Générique par défaut ; séparation au-delà d'environ 15 % d'écart. |
| Variétés d'un même fruit ou légume | Une seule entrée. |
| Choix du moment | Présélectionné par l'heure de saisie, modifiable en un geste. |
| Heure sur chaque ligne | Oui, pour l'ordre et une vue chronologique ultérieure. |
| Énergie dépensée dans Food | Absente. Le rapprochement relève de `Progress`. |
| Objectif calorique quotidien | Hors périmètre. |
| Bandeau de résumé sur l'écran du jour | Aucun. |
| Bouton de masquage de l'énergie à l'écran | Aucun. L'option vit dans les préférences. |
| Source des aliments génériques | Table Ciqual 2025, sous-ensemble embarqué, Licence Ouverte. |
| Source des produits emballés | Open Food Facts, API v3.6, licence ODbL avec attribution. |
| Décodage du code-barres | Local, ML Kit. Seul le numéro est transmis. |
| Les valeurs externes sont-elles relues à chaque affichage ? | Non. Copiées localement à l'ajout. |
| Suppression d'un aliment utilisé | Refusée, avec la liste des recettes concernées. |
| Auteur après un `Swap` | Bascule sur l'utilisateur. |
| Stockage des images | Système de fichiers, jamais en BLOB dans Room. |
| Précision affichée | Ordre de grandeur, toujours précédé de `≈`. |
| Fonctionnement hors ligne | Total, sauf le scan. |
| Compte utilisateur requis | Non. |
| Le module dépend-il du serveur ? | Non. Il le prépare. |

## 24. Ce qui reste à arbitrer

### 24.1 Produit

- **Les restes.** Une recette de trois portions dont une seule est consommée : les deux autres sont-elles des restes à reproposer les jours suivants, ou mangées par quelqu'un d'autre ? La réponse change ce que l'agent doit proposer le lendemain.
- **Le scan.** Retenu dans le périmètre complet, mais c'est le chemin d'ajout le plus coûteux : permission caméra, dépendance réseau, licence ODbL dans une application jusqu'ici sans permission réseau. Sa valeur réelle dépend de la part de produits emballés dans l'alimentation quotidienne, à mesurer après quelques semaines d'usage.
- **Le masquage de l'énergie.** L'option survit dans les préférences mais n'a jamais été demandée. À supprimer si elle ne sert pas.
- **Une vue chronologique.** L'heure est enregistrée sur chaque ligne ; reste à décider si une vue par heure remplace un jour le regroupement en quatre moments.

### 24.2 Technique

- Taille et composition exactes du sous-ensemble Ciqual embarqué, et procédure de mise à jour de sa version.
- Dérivation automatique des `cookedRatio` depuis les paires cru/cuit de Ciqual, et liste des aliments qui en reçoivent un.
- Choix des portions usuelles fournies avec le catalogue, et leur poids de référence.
- Comportement souhaité pour un même aliment consommé plusieurs fois dans la journée : lignes distinctes ou regroupement à l'affichage.
- Politique de purge du cache d'images distantes.
- Volume réel du catalogue et performance de la recherche sur un appareil d'entrée de gamme.
- Forme de la consultation de l'audit des écritures d'agent dans `Data & sync`.
