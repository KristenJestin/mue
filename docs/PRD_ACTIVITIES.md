# PRD — Mue Activities

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Module | Activities |
| Version | V1 du module |
| Statut | Validé pour le développement |
| Date | 23 août 2026 |
| Plateforme | Android natif, téléphone, portrait |
| Langue de l'application | Anglais uniquement |
| PRD du socle actuel | [`PRD.md`](./PRD.md) |
| Référence visuelle | [`activity`](../proto/fusion/activity.html), [`log-activity`](../proto/fusion/log-activity.html), [`strength-session`](../proto/fusion/strength-session.html) |

Ce document décrit un module ajouté à une application dont le socle est déjà développé. Il ne redéfinit ni les écrans, ni le design system, ni les décisions techniques du socle : il les prolonge. En cas de divergence sur la navigation, la section 7 du présent document fait autorité ; en cas de divergence avec les prototypes, la section 14.3.

## 2. Résumé

Le module Activities permet d'enregistrer manuellement des séances sportives très différentes sans enfermer les données dans une liste rigide de sports. Une marche sur tapis, une course en extérieur et une séance de musculation partagent ainsi un socle commun, puis exposent seulement les mesures qui leur sont utiles.

La V1 du module ajoute un quatrième onglet `Activity` à Mue. Il présente l'activité récente et hebdomadaire, permet de créer, modifier et supprimer une séance, et propose une saisie détaillée adaptée au cardio comme à la musculation.

Les données restent locales. La V1 n'enregistre pas une séance en direct, ne suit pas le GPS et ne se connecte pas encore à Health Connect.

## 3. Problème à résoudre

Les informations disponibles varient selon l'activité et le matériel : un tapis fournit généralement durée, vitesse, distance et calories estimées, tandis qu'une séance de musculation contient plutôt des exercices, des séries, des répétitions, une charge ou une durée.

Un modèle construit autour de types opaques comme `Treadmill walk` dupliquerait inutilement des concepts et deviendrait difficile à étendre. Mue doit donc :

- conserver un socle de séance commun ;
- séparer le mouvement, l'environnement et l'équipement ;
- accepter des mesures indépendantes et facultatives ;
- proposer malgré tout une saisie rapide grâce à des préréglages compréhensibles ;
- gérer la structure particulière d'une séance de musculation sans la réduire à quelques totaux.

## 4. Objectifs produit

### 4.1 Objectifs de la V1 du module

- Enregistrer manuellement une activité terminée.
- Couvrir immédiatement la marche sur tapis et la musculation.
- Rester extensible aux activités de course, vélo, natation, rameur, elliptique et autres.
- Limiter le formulaire aux données pertinentes pour l'activité choisie.
- Permettre une saisie rapide ou détaillée de la musculation.
- Afficher des résumés hebdomadaires simples et factuels.
- Permettre la modification et la suppression d'une séance.
- Conserver la provenance des données estimées, notamment les calories fournies par une machine.
- Préparer une future interopérabilité avec Health Connect sans en dépendre.

### 4.2 Critères de réussite qualitatifs

- Une marche sur tapis peut être enregistrée en moins de trente secondes.
- Une séance de musculation peut être résumée sans détailler chaque série.
- L'utilisateur qui le souhaite peut retrouver ses exercices et performances précédentes.
- Ajouter une nouvelle famille d'activité ne nécessite pas de modifier le socle des séances existantes.
- L'écran reste léger même lorsqu'une activité propose beaucoup de mesures possibles.

## 5. Hors périmètre

- GPS, carte, tracé ou dénivelé calculé par Mue.
- Synchronisation avec Health Connect, une montre ou un service tiers.
- Calcul propriétaire des calories brûlées.
- Objectifs d'activité, streaks, badges, défis ou notifications.
- Programmes de musculation, séances modèles et planification.
- Supersets, circuits, temps de repos guidé et minuteur de repos.
- Calcul ou prédiction du 1RM.
- Analyse des zones cardiaques, de la puissance ou de la récupération.
- Export CSV des activités et import de séances dans cette version.
- Partage social ou classement.

Le chronométrage d'une séance en direct ne relève pas de ce document mais du module [`Activity Timer`](./PRD_ACTIVITY_TIMER.md), qui réutilise le formulaire et le modèle définis ici. Les amendements qu'il apporte sont intégrés au présent document et signalés par une mention explicite.

## 6. Principes d'expérience

1. **Un seul concept de séance** — les différences entre activités sont exprimées par leurs attributs et mesures.
2. **Rapide par défaut** — les préréglages configurent le formulaire mais ne polluent pas le modèle stocké.
3. **Le détail est facultatif** — une séance utile peut ne contenir qu'un mouvement, une date et une durée.
4. **Factuel, jamais culpabilisant** — aucun jugement sur le volume, les calories ou la régularité.
5. **Les estimations restent des estimations** — leur nature et leur source sont visibles et conservées.
6. **Animation fonctionnelle** — le mouvement montre ce qui est sélectionné, ajouté, dupliqué ou enregistré.

## 7. Architecture de l'information

La navigation principale passe à quatre onglets permanents :

| Onglet | Rôle |
|---|---|
| `Entry` | Saisir et enregistrer un poids. |
| `Progress` | Consulter l'évolution du poids. |
| `Activity` | Consulter et enregistrer les séances sportives. |
| `Profile` | Gérer le profil, les préférences et l'export du poids. |

Le module Activities contient les écrans suivants :

| Écran | Rôle |
|---|---|
| `Activity` | Tableau de bord hebdomadaire et cinq séances récentes. |
| `Activity history` | Liste complète des séances, groupée par mois. |
| `Log activity` | Choix d'un préréglage et saisie dynamique d'une séance. |
| `Strength session` | Saisie détaillée des exercices et séries. |
| `Edit activity` | Réutilise le formulaire correspondant avec les données existantes. |

La barre d'onglets reste visible sur tous les écrans du module, y compris `Log activity` et `Strength session` : le socle impose une barre inférieure visible et immobile, et le brouillon d'une séance survivant à la navigation, quitter un formulaire par un onglet ne perd rien.

## 8. Modèle conceptuel

### 8.1 Séparer les axes

Une séance est décrite par des dimensions indépendantes :

- `movement` : ce que fait la personne ;
- `environment` : où l'activité se déroule ;
- `equipment` : le matériel utilisé ;
- `metrics` : les mesures disponibles ;
- `source` : l'origine de la saisie.

Exemples :

| Libellé d'interface | Mouvement | Environnement | Équipement | Mesures suggérées |
|---|---|---|---|---|
| `Treadmill walk` | `walking` | `indoor` | `treadmill` | durée, distance, vitesse, calories, inclinaison |
| `Outdoor run` | `running` | `outdoor` | aucun | durée, distance, calories, effort |
| `Strength training` | `strength_training` | `indoor` ou `unknown` | plusieurs possibles | durée, calories, effort, exercices et séries |

`Treadmill walk` est donc un préréglage de formulaire, pas une valeur stockée comme type métier.

### 8.2 ActivitySession

| Champ | Type logique | Règle |
|---|---|---|
| `id` | UUID | Identifiant stable. |
| `movement` | enum | Obligatoire. Valeurs initiales : `walking`, `running`, `cycling`, `swimming`, `strength_training`, `rowing`, `elliptical`, `hiking`, `yoga`, `climbing`, `dancing`, `pilates`, `mobility`, `team_sport`, `other`. |
| `customMovementName` | texte facultatif | Obligatoire et limité à 60 caractères lorsque `movement = other` ; absent pour les mouvements connus. |
| `environment` | enum | Obligatoire : `indoor`, `outdoor` ou `unknown`. Vaut `unknown` lorsque le préréglage ne l'impose pas. |
| `startedAt` | date locale + heure locale facultative | Date obligatoire, stockée en texte ISO `YYYY-MM-DD`. L'heure facultative est stockée séparément en `HH:MM`. Aucun horodatage, epoch ou fuseau n'est conservé, exactement comme les mesures de poids du socle : un changement de fuseau ne peut donc décaler aucune séance. L'absence d'heure reste distincte de minuit. |
| `durationSeconds` | entier positif | Obligatoire dans la V1, de `1` seconde à `99 h 59 min`. Une séance saisie à la main s'exprime en heures et minutes et vaut donc toujours un multiple de `60` ; une séance issue du minuteur conserve sa durée exacte à la seconde. |
| `perceivedEffort` | entier facultatif | Échelle de 1 à 10. |
| `notes` | texte facultatif | Texte libre, 500 caractères maximum. |
| `source` | enum | `manual` pour une saisie à la main, `timer` pour une séance issue du minuteur, `health_connect` réservé à une importation future. |

`createdAt` et `updatedAt` ne figurent pas dans ce tableau : ce sont des métadonnées d'audit portées par le seul stockage, horodatées à l'écriture, qu'aucune règle d'affichage des sections 10 à 13 ne lit. Elles n'affaiblissent en rien l'interdiction des horodatages absolus, laquelle porte sur la date calendaire d'une séance et non sur l'audit technique.

Plusieurs séances peuvent exister le même jour. Une nouvelle saisie ne remplace jamais automatiquement une séance existante.

### 8.3 ActivityMetric

Les mesures numériques variables sont attachées à la séance :

| Champ | Type logique | Exemple |
|---|---|---|
| `id` | UUID | — |
| `sessionId` | UUID | — |
| `kind` | enum | Voir le tableau des unités canoniques ci-dessous. |
| `value` | entier | `4200`, soit `4.2 km` |
| `source` | enum | `manual`, `equipment`, `wearable`, `calculated` |

L'unité n'est pas un champ : elle est **dérivée du `kind`** par le tableau des unités canoniques ci-dessous et n'est jamais stockée. La conserver à côté du `kind` autoriserait un couple impossible, par exemple une distance exprimée en kilocalories. Elle reste disponible pour la future traduction vers Health Connect, puisque le `kind` la détermine entièrement.

**Aucun flottant n'entre en base.** Chaque `kind` possède une seule unité canonique entière, comme le poids du socle stocké en centièmes de kilogramme. La conversion n'a lieu qu'à l'affichage et à la saisie.

| `kind` | Unité canonique | Stocké | Affiché | Décimales | Saisissable en V1 |
|---|---|---|---|---|---|
| `distance` | `metre` | `4200` | `4.2 km` | 2 | oui |
| `reported_speed` | `centi_km_per_hour` | `560` | `5.6 km/h` | 2 | oui |
| `average_speed` | `centi_km_per_hour` | `2450` | `24.5 km/h` | 2 | oui |
| `average_pace` | `second_per_kilometre` | `430` | `7:10 /km` | 0 | oui |
| `estimated_energy` | `kcal` | `280` | `≈280 kcal` | 0 | oui |
| `incline` | `deci_percent` | `25` | `2.5 %` | 1 | oui |
| `steps` | `count` | `6200` | `6 200` | 0 | non |
| `average_heart_rate` | `bpm` | `132` | `132 bpm` | 0 | non |
| `elevation_gain` | `metre` | `180` | `180 m` | 0 | non |
| `power` | `watt` | `210` | `210 W` | 0 | non |
| `cadence` | `rpm` | `82` | `82 rpm` | 0 | non |

La colonne `Décimales` donne le nombre de décimales affichées et acceptées à la saisie pour chaque grandeur ; les zéros de fin sont toujours supprimés, si bien que `3000 m` s'affiche `3 km` et non `3.00 km`. `average_pace` n'en porte aucune parce que sa partie fractionnaire est le champ des secondes et non un chiffre après la virgule. La section 12 énonce la règle générale et l'invariant d'aller-retour qui l'accompagne.

Les `kind` non saisissables existent uniquement pour qu'une importation future n'impose pas de migration. Aucun formulaire de la V1 ne les propose.

Règles :

- une mesure est facultative ;
- une donnée absente n'est jamais enregistrée comme zéro ; elle n'a simplement pas de ligne ;
- une séance ne porte jamais deux mesures du même `kind` ;
- une vitesse saisie depuis l'écran d'un tapis est `reported_speed`, pas une vitesse recalculée ;
- l'allure moyenne de marche ou de course est stockée en secondes par kilomètre afin de conserver une valeur canonique ;
- la vitesse moyenne d'une activité comme le vélo utilise `average_speed`, distinct de la vitesse rapportée par une machine ;
- les calories sont toujours présentées comme `Estimated energy` et jamais comme une valeur médicale exacte ;
- une estimation saisie depuis une machine utilise la source `equipment` ;
- une mesure historique n'est pas recalculée lorsque le poids ou le profil change.

### 8.4 SessionEquipment

Une séance peut référencer aucun, un ou plusieurs équipements :

| Champ | Type logique | Règle |
|---|---|---|
| `id` | UUID | — |
| `sessionId` | UUID | — |
| `equipmentType` | enum | Type connu ou `other`. |
| `customName` | texte facultatif | Nom local obligatoire lorsque `equipmentType = other`, limité à 40 caractères. |
| `position` | entier | Ordre d'affichage des étiquettes, dans l'ordre d'ajout. |

Valeurs de l'enum : `treadmill`, `stationary_bike`, `bicycle`, `rowing_machine`, `elliptical_machine`, `yoga_mat`, `resistance_bands`, `barbell`, `dumbbells`, `kettlebell`, `machine`, `bodyweight`, `climbing_wall`, `pool`, `other`.

Pour la musculation détaillée, l'équipement est de préférence attaché à l'exercice ; l'équipement de séance sert uniquement au résumé ou à une saisie rapide.

### 8.5 Préréglages d'interface

Les préréglages sont une configuration de présentation et ne sont pas nécessaires à la lecture des données historiques.

La V1 propose :

- `Treadmill walk` ;
- `Outdoor walk` ;
- `Run` ;
- `Cycling` ;
- `Strength training` ;
- `Other`.

Chaque préréglage choisit un mouvement, un environnement, un équipement éventuel et les champs suggérés. L'utilisateur peut laisser toute mesure facultative vide.

Le préréglage `Other` est un constructeur. Il commence par un catalogue recherchable d'activités moins fréquentes. Il ne stocke `movement = other` et un nom libre que lorsque l'utilisateur crée réellement une activité absente du catalogue. Il permet ensuite de choisir l'environnement ainsi que zéro, un ou plusieurs équipements connus ou personnalisés.

## 9. Musculation

### 9.1 Deux niveaux de saisie

#### Quick log

Enregistre seulement :

- la date et éventuellement l'heure ;
- la durée ;
- l'effort perçu facultatif ;
- les calories estimées facultatives ;
- les équipements facultatifs ;
- une note facultative.

#### Detailed log

Ajoute la liste ordonnée des exercices et de leurs séries. Le passage au mode détaillé ne rend pas les calories ou l'effort obligatoires.

#### Bascule entre les deux modes

- La bascule est réversible à tout moment tant que l'écran reste ouvert : les exercices déjà saisis restent en mémoire et reviennent intacts si l'utilisateur retourne au mode détaillé.
- L'enregistrement n'écrit que ce que le mode actif expose : sauvegarder en `Quick log` ne conserve aucun exercice.
- Lors de la **modification** d'une séance qui possède déjà des exercices enregistrés, le passage en `Quick log` demande une confirmation explicite — `Switch to quick log? Your 3 exercises will be removed.` — car il détruit des données déjà écrites. C'est la seule confirmation du module en dehors de la suppression d'une séance.
- Lors d'une création, aucune confirmation n'est demandée : rien n'a encore été écrit.

### 9.2 ExerciseDefinition

| Champ | Type logique | Règle |
|---|---|---|
| `id` | UUID | — |
| `name` | texte | Obligatoire. |
| `trackingMode` | enum | Définit les champs proposés pour une série. |
| `equipment` | enum facultatif | Équipement principal de l'exercice. |
| `isCustom` | booléen | Distingue les exercices fournis et créés localement. |

La V1 ne porte **aucun champ de groupes musculaires** : ni attribut, ni énumération, ni colonne. Il resterait vide et inexploité, et son introduction ultérieure sera une évolution purement additive, sans donnée à convertir.

Modes de suivi de la V1 :

- `weight_and_reps` ;
- `reps_only` ;
- `duration` ;
- `weight_and_duration`.

Ce sont les quatre modes proposés par le prototype à la création d'un exercice personnalisé. Un cinquième mode de type `weight_and_distance` n'est pas retenu : aucun exercice fourni ne l'utilise et il alourdirait la validation sans usage réel.

Ces quatre identifiants sont ceux qui sont enregistrés. Les formes abrégées visibles dans le prototype ne sont jamais stockées.

#### Catalogue fourni

Dix-sept exercices sont installés au premier lancement avec `isCustom = false` :

| Nom | Mode de suivi | Équipement |
|---|---|---|
| `Barbell squat` | `weight_and_reps` | `barbell` |
| `Deadlift` | `weight_and_reps` | `barbell` |
| `Bench press` | `weight_and_reps` | `barbell` |
| `Overhead press` | `weight_and_reps` | `barbell` |
| `Barbell row` | `weight_and_reps` | `barbell` |
| `Dumbbell row` | `weight_and_reps` | `dumbbells` |
| `Dumbbell curl` | `weight_and_reps` | `dumbbells` |
| `Lateral raise` | `weight_and_reps` | `dumbbells` |
| `Goblet squat` | `weight_and_reps` | `kettlebell` |
| `Lat pulldown` | `weight_and_reps` | `machine` |
| `Leg press` | `weight_and_reps` | `machine` |
| `Leg curl` | `weight_and_reps` | `machine` |
| `Chest press` | `weight_and_reps` | `machine` |
| `Pull-up` | `reps_only` | `bodyweight` |
| `Push-up` | `reps_only` | `bodyweight` |
| `Plank` | `duration` | `bodyweight` |
| `Weighted plank` | `weight_and_duration` | `bodyweight` |

Les six premiers noms du prototype figurent dans cette liste ; les autres couvrent les principaux groupes musculaires pour que la recherche ait un sens dès la première séance.

#### Cycle de vie

- La V1 ne propose **aucun écran de gestion des exercices** : ni renommage, ni suppression, ni édition d'une définition.
- Une définition personnalisée est conservée définitivement, y compris lorsqu'aucune séance ne l'utilise plus. Elle n'est jamais supprimée par la suppression d'une séance.
- À la création, un nom déjà présent dans le catalogue, **sans distinction de casse ni d'espaces de bordure**, réutilise la définition existante au lieu d'en créer une seconde.

### 9.3 StrengthExercise

| Champ | Type logique | Règle |
|---|---|---|
| `id` | UUID | — |
| `sessionId` | UUID | — |
| `exerciseDefinitionId` | UUID | — |
| `position` | entier | Ordre d'affichage dans la séance. |
| `notes` | texte facultatif | Commentaire propre à l'exercice. |

### 9.4 StrengthSet

| Champ | Type logique | Règle |
|---|---|---|
| `id` | UUID | — |
| `strengthExerciseId` | UUID | — |
| `position` | entier | Ordre de la série. |
| `setType` | enum | `working`, `warmup`, `drop` ; `working` par défaut. |
| `repetitions` | entier facultatif | Strictement positif lorsqu'il est renseigné. |
| `loadGrams` | entier facultatif | Strictement positif lorsqu'il est renseigné. Stocké en grammes : `60000` vaut `60 kg`, ce qui couvre le pas de `0.5 kg` et les disques de `1.25 kg` sans dérive d'arrondi. |
| `durationSeconds` | entier facultatif | Strictement positif. |
| `perceivedEffort` | entier facultatif | Échelle de 1 à 10. |

Le `trackingMode` de l'exercice détermine les champs visibles et les combinaisons valides. Une série ne conserve pas de champ numérique vide ou non pertinent : un champ non renseigné vaut `null`, jamais `0`.

Une série ajoutée **démarre entièrement vide** : ni charge, ni répétitions, ni durée, ni effort préremplis, et surtout aucun `0` présenté comme une valeur saisie. Reprendre des valeurs est le rôle de la duplication, pas de l'ajout.

#### Série valide

Une série est valide lorsqu'elle porte **la mesure principale de son mode**. La charge est toujours facultative : une série de développé couché à la barre à vide, un tirage à l'élastique ou une traction se notent sans aucun poids.

| `trackingMode` | Obligatoire | Facultatif |
|---|---|---|
| `weight_and_reps` | `repetitions` ≥ 1 | `loadGrams`, `perceivedEffort` |
| `reps_only` | `repetitions` ≥ 1 | `perceivedEffort` |
| `duration` | `durationSeconds` ≥ 1 | `perceivedEffort` |
| `weight_and_duration` | `durationSeconds` ≥ 1 | `loadGrams`, `perceivedEffort` |

Une série qui ne porte pas sa mesure principale est invalide : elle n'est pas enregistrée et n'entre dans aucun total.

**Effort perçu par série** — la ligne de série n'expose ce champ que dans les modes `reps_only` et `duration`, où une colonne reste libre. En `weight_and_reps` et `weight_and_duration`, une troisième colonne numérique ferait passer les cibles tactiles de la ligne sous les 48 dp exigés par la section 15. L'effort perçu de la séance, lui, reste proposé dans tous les modes.

## 10. Exigences fonctionnelles

### 10.1 Tableau de bord Activity

#### FR-ACTIVITY-001 — Résumé hebdomadaire

- La semaine commence le lundi et se termine le dimanche. Elle est calculée sur la date locale enregistrée de chaque séance ; aucune conversion de fuseau n'intervient nulle part.
- Afficher l'intervalle de la semaine courante en en-tête, par exemple `Aug 18–24`.
- Afficher en titre éditorial la durée cumulée de la semaine, par exemple `You moved for 2h 15m.`.
- Afficher le nombre de séances de la semaine courante.
- Afficher l'énergie estimée cumulée uniquement à partir des séances qui possèdent cette mesure.
- Utiliser le préfixe `≈` pour toute énergie cumulée.
- Afficher une visualisation des sept jours où la hauteur représente la durée totale de chaque jour.
- L'absence d'activité utilise un état calme, sans message culpabilisant.

##### Échelle des barres hebdomadaires

- La hauteur est **relative à la semaine affichée** : le jour le plus long occupe toute la hauteur disponible, les autres en proportion.
- Un jour sans activité n'affiche que son rail vide.
- Un jour actif ne descend jamais sous `3 dp` de hauteur, afin qu'une séance courte reste visible à côté d'une séance longue.
- Le total de la semaine étant écrit en clair au-dessus, l'échelle n'a pas besoin d'être comparable d'une semaine à l'autre.
- Le jour courant est distingué par la couleur d'accent, et par son libellé mis en évidence.

#### FR-ACTIVITY-002 — Séances récentes

- Afficher les **cinq séances les plus récentes**, de la plus récente à la plus ancienne.
- Présenter le libellé dérivé du mouvement, de l'environnement et de l'équipement.
- Afficher au minimum la date et la durée.
- Ajouter au plus deux informations secondaires pertinentes : distance, nombre de séries ou énergie estimée.
- Toucher une séance ouvre son formulaire d'édition.
- Une action `See all` ouvre l'écran `Activity history`. Elle est masquée lorsque l'historique contient cinq séances ou moins.

#### FR-ACTIVITY-012 — Historique complet

- L'écran `Activity history` affiche toutes les séances dans une liste défilante, de la plus récente à la plus ancienne.
- Grouper les séances par mois sous un intitulé, par exemple `August 2026`.
- Réutiliser exactement la carte de séance du tableau de bord.
- Toucher une séance ouvre son formulaire d'édition.
- N'appliquer aucune limite de nombre, conformément à ce que fait déjà l'historique des mesures de poids.

#### FR-ACTIVITY-003 — Actions du tableau de bord

Le tableau de bord expose deux actions permanentes, dans cet ordre :

| Action | Présentation | Rôle |
|---|---|---|
| `Start activity` | Action principale, pleine largeur, accent ambre | Démarrer un minuteur maintenant (FR-TIMER-001). |
| `Log past activity` | Action secondaire, discrète | Ouvrir le formulaire de saisie manuelle. |

- `Log past activity` ouvre l'écran de choix des préréglages avec une transition ascendante courte.
- L'ordre vertical de l'écran est fixe : résumé hebdomadaire, brouillons à réviser, actions, raccourci `Start again`, séances récentes. La section 6.1 du module minuteur décrit les deux blocs qui lui appartiennent.
- Lorsque le minuteur n'a jamais été utilisé, ni le raccourci `Start again` ni les brouillons à réviser n'occupent d'espace.

### 10.2 Création et édition

#### FR-ACTIVITY-004 — Choix du préréglage

- Afficher les six préréglages de la section 8.5 sous forme de cartes illustrées par des icônes.
- Rendre les six préréglages visibles et sélectionnables sans nécessiter de geste horizontal caché.
- Présélectionner `Treadmill walk` lors d'une nouvelle saisie.
- Changer de préréglage remplace les champs suggérés après une transition courte.
- Les valeurs communes déjà saisies — date, heure de début, durée, effort et notes — sont conservées.
- **Aucune confirmation n'est demandée lors d'un changement de préréglage.** Le brouillon conserve en mémoire toutes les valeurs saisies tant que l'écran reste ouvert : revenir au préréglage précédent restitue ses champs intacts, y compris ceux que le préréglage courant n'affiche pas.
- L'enregistrement n'écrit que les mesures exposées par le préréglage actif. Une inclinaison saisie sous `Treadmill walk` puis abandonnée au profit de `Run` n'est donc jamais enregistrée, sans que l'utilisateur ait eu à trancher.

#### FR-ACTIVITY-005 — Champs communs

- Utiliser la date du jour par défaut.
- Interdire les dates futures.
- Rendre la durée obligatoire, de 1 minute à 99 heures 59 minutes lorsqu'elle est saisie à la main.
- Saisir la durée en heures et en minutes, sur le formulaire général comme dans l'éditeur de séance de musculation ; aucun écran ne propose une saisie en minutes seules.
- La durée se règle avec un **sélecteur à molette verticale** — deux colonnes, heures et minutes, défilant sous un repère central fixe, avec inertie et arrêt magnétique sur la valeur — et non avec deux champs numériques. C'est la règle de la balance du socle basculée sur le côté (FR-ENTRY-002).
- La molette est exposée comme un contrôle ajustable et reste entièrement manœuvrable sans le moindre geste de lancer, ainsi que l'exige la section 15.
- **Une durée non renseignée affiche `0 h 0 min`, jamais `--`** : une molette repose toujours sur une valeur. La règle de la section 12 qui interdit de présenter une valeur absente comme `0` n'en est pas affaiblie : elle porte sur les valeurs facultatives, et la durée est obligatoire.
- Une durée de `0:00` est refusée à l'enregistrement, le message rappelant la plage autorisée.
- La borne basse d'une minute est celle de la saisie manuelle, qui ne sait pas exprimer de secondes. Le domaine accepte toute durée strictement positive : une séance issue du minuteur peut donc valoir moins d'une minute et conserve ses secondes, selon FR-TIMER-006.
- Proposer une heure de début facultative. Elle se choisit dans un panneau remontant du bas habillé aux couleurs du produit, jamais par une saisie de chiffres au clavier, et une action explicite permet de l'effacer. Une heure absente reste distincte de minuit.
- Proposer un effort perçu facultatif de 1 à 10.
- Proposer une note facultative de 500 caractères maximum dans une zone multiligne pleine largeur, placée après l'effort perçu.

#### FR-ACTIVITY-006 — Marche sur tapis

Le préréglage `Treadmill walk` utilise :

- `movement = walking` ;
- `environment = indoor` ;
- `equipment = treadmill` ;
- durée obligatoire ;
- distance facultative en kilomètres ;
- vitesse rapportée facultative en kilomètres par heure ;
- énergie estimée facultative en kilocalories, source `equipment` par défaut ;
- inclinaison facultative en pourcentage ;
- effort et notes facultatifs.

La distance, la vitesse et la durée ne sont pas forcées à être mathématiquement cohérentes : ce sont les valeurs rapportées par l'utilisateur ou la machine.

#### FR-ACTIVITY-007 — Marche, course et vélo

- `Outdoor walk` et `Run` proposent la distance, l'allure moyenne et l'énergie estimée. L'allure se saisit et s'affiche en minutes et secondes par kilomètre, sous la forme `7:10 /km`, et se stocke en secondes par kilomètre.
- `Cycling` propose la distance, la vitesse moyenne en kilomètres par heure et l'énergie estimée.
- La durée reste obligatoire et l'heure de début reste facultative comme pour toutes les séances.
- Toutes les mesures propres à l'activité restent facultatives : une estimation raisonnable peut être enregistrée sans imposer une précision artificielle.
- Les champs utiles sont affichés directement ; aucun encart générique ne remplace les informations de distance, d'allure ou de vitesse.

#### FR-ACTIVITY-008 — Activité personnalisée

- Le préréglage `Other` propose un sélecteur `Main activity` recherchable contenant `Yoga`, `Hiking`, `Rowing`, `Elliptical`, `Swimming`, `Climbing`, `Dancing`, `Pilates`, `Mobility` et `Team sport`.
- Chacune de ces entrées correspond à une valeur de l'enum `movement` : les choisir n'utilise jamais `movement = other` ni de nom libre.
- Sélectionner une activité du catalogue conserve son identifiant stable et son libellé d'affichage.
- Si aucun résultat ne convient, l'action `Create` permet d'enregistrer une activité personnalisée de 1 à 60 caractères ; c'est le seul chemin qui produit `movement = other`.
- Proposer les environnements `Indoor`, `Outdoor` et `Not set`, ce dernier correspondant à `unknown` et étant sélectionné par défaut.
- Proposer un catalogue d'équipements recherchable et permettre d'en sélectionner zéro, un ou plusieurs sous forme d'étiquettes supprimables.
- Le sélecteur d'équipement reste ouvert tant que les choix s'accumulent : toucher une entrée déjà retenue la retire, et seule une action explicite ferme le panneau. C'est ce qui rend la sélection de zéro, un ou plusieurs équipements réelle dans l'interface et non seulement dans le modèle.
- Le sélecteur `Main activity` se ferme au contraire dès qu'une activité est choisie : c'est un choix unique.
- Si aucun équipement ne convient, permettre une création personnalisée limitée à 40 caractères.
- Un équipement connu conserve son identifiant stable ; un équipement personnalisé conserve son nom libre.
- Le même équipement, connu ou personnalisé sans distinction de casse, n'est pas ajouté deux fois à la même séance.
- La durée reste obligatoire ; l'heure, l'énergie estimée, l'effort et la note restent facultatifs.
- Le nom du mouvement personnalisé devient le libellé principal de la séance.

#### FR-ACTIVITY-009 — Musculation

- Après sélection de `Strength training`, proposer `Quick log` et `Detailed log`.
- `Quick log` reste dans le formulaire général.
- `Detailed log` ouvre l'éditeur de séance de musculation.
- L'action `Add exercise` ouvre un sélecteur remontant du bas.
- Le sélecteur propose une recherche et une liste unique intitulée `Recent & common` : les exercices récemment utilisés d'abord, puis le catalogue fourni. Aucune autre section n'est nécessaire.
- Toucher un résultat ajoute l'exercice avec une première série adaptée à son mode de suivi.
- Permettre de créer un exercice personnalisé à partir du texte recherché et de choisir son mode de suivi parmi les quatre modes avant l'ajout.
- Afficher la dernière performance connue d'un exercice lorsqu'elle existe, selon la section 11.4.
- Permettre d'ajouter, modifier, supprimer et réordonner les exercices.
- Réordonner un exercice s'effectue par deux actions `Move up` et `Move down` placées dans son en-tête, jamais par un glisser-déposer : aucune action essentielle ne doit dépendre d'un geste complexe.
- Permettre d'ajouter, modifier, supprimer et dupliquer une série.
- Exposer deux actions distinctes : `Add set` ajoute une série vide, `Duplicate last set` reprend les valeurs de la précédente.
- La duplication copie les valeurs de la série immédiatement précédente puis place le curseur sur la première valeur modifiable.
- Une séance détaillée doit contenir au moins une série valide pour être enregistrée.
- À l'enregistrement, un exercice ne contenant aucune série valide est **retiré silencieusement**, sans message ni confirmation.

#### FR-ACTIVITY-010 — Enregistrement

- `Save activity` crée une nouvelle séance avec un nouvel identifiant.
- `Save changes` met à jour la séance existante.
- Plusieurs séances identiques peuvent exister à la même date.
- Après succès, revenir au tableau de bord Activity.
- Confirmer l'enregistrement, avant ou pendant le retour, par la décharge lumineuse du bouton définie en FR-ENTRY-006 du socle : le libellé devient `Saved`, sans pictogramme ni caractère de police. Tous les boutons d'enregistrement de Mue portent le même mot et le même traitement.
- Produire une vibration courte lorsque les vibrations sont activées dans Mue.

#### FR-ACTIVITY-011 — Suppression

- L'édition expose `Delete activity`.
- Demander une confirmation explicite avant la suppression.
- Supprimer en cascade les mesures, équipements, exercices et séries associés.
- Après suppression, revenir au tableau de bord et afficher `Activity deleted`.

## 11. Statistiques et libellés

### 11.1 Libellé d'une séance

Le libellé est dérivé à l'affichage, avec la règle la plus spécifique disponible :

1. `customMovementName` lorsque `movement = other`, par exemple `Padel` ;
2. mouvement + équipement titrant, par exemple `Treadmill walk` ;
3. mouvement + environnement, par exemple `Outdoor run` ;
4. mouvement seul, par exemple `Strength training` ;
5. `Other activity` pour une ancienne donnée `other` sans nom personnalisé.

**Équipement titrant** — un équipement n'entre dans le libellé que lorsque la séance en porte exactement un, et que celui-ci appartient à la liste `treadmill`, `stationary_bike`, `rowing_machine`, `elliptical_machine`. Ce sont les seuls équipements qui changent la nature de l'activité aux yeux de l'utilisateur. Une séance portant trois équipements, ou un tapis de yoga, retombe donc sur la règle suivante.

Le libellé peut évoluer sans migration des données.

### 11.2 Nombre de séries

Le nombre affiché pour une séance de musculation correspond aux séries valides au sens de la section 9.4, tous exercices confondus. Les séries d'échauffement sont incluses dans le total en V1.

### 11.3 Énergie estimée

- Additionner les valeurs enregistrées sans les recalculer.
- Afficher `≈` devant la valeur et `kcal` après celle-ci.
- Ne rien afficher lorsque la donnée est absente ; ne jamais afficher `0 kcal` par défaut.
- Une future importation doit pouvoir distinguer une valeur saisie, rapportée par un équipement ou fournie par une source tierce.

### 11.4 Dernière performance d'un exercice

La valeur affichée sous le nom d'un exercice provient de **la dernière série valide de la séance la plus récente** contenant cet exercice, la séance courante exclue. Le rendu suit le mode de suivi :

| Mode | Rendu |
|---|---|
| `weight_and_reps` avec charge | `Last time · 60 kg × 8` |
| `weight_and_reps` sans charge | `Last time · 8 reps` |
| `reps_only` | `Last time · 12 reps` |
| `duration` | `Last time · 1:30` |
| `weight_and_duration` | `Last time · 20 kg · 1:30` |

- Les charges s'affichent sans décimale inutile : `60 kg`, `62.5 kg`.
- Les durées s'affichent en minutes et secondes, ou en secondes seules sous une minute.
- Rien n'est affiché lorsque l'exercice n'a jamais été pratiqué.

## 12. Validation des données

- Les nombres acceptent point et virgule à la saisie, quelle que soit la langue du téléphone, comme le fait déjà la saisie du poids.
- Les valeurs sont affichées selon la langue du téléphone, bien que les libellés restent en anglais.
- **Aucune valeur numérique n'est stockée sous forme de flottant.** Chaque grandeur possède une unité canonique entière : les mesures suivent le tableau de la section 8.3, les charges de musculation sont stockées en grammes et les durées en secondes.
- **Chaque grandeur déclare son propre nombre de décimales à l'affichage**, donné par le tableau de la section 8.3 : une décimale est un plancher, pas un plafond. Les zéros de fin sont supprimés.
- Les distances sont stockées en mètres et affichées en kilomètres avec **deux** décimales. Une seule décimale détruisait le centième : `2.95 km` était réaffiché `3`, et un nouvel enregistrement écrivait alors `3000 m`. La perte se produisait à la réédition, jamais à la saisie initiale.
- **Afficher une valeur stockée puis la relire doit rendre exactement la même valeur.** Aucun aller-retour entre le stockage et le formulaire ne peut faire dériver une mesure.
- Les charges sont saisies et affichées en kilogrammes, avec au plus deux décimales acceptées à la saisie.
- La V1 ne propose pas les unités impériales.
- Une valeur négative est toujours refusée.
- Une valeur facultative vide est valide et vaut `null` ; elle n'est jamais convertie en `0`.
- Une erreur est affichée près du champ et résumée au niveau de l'action d'enregistrement pour l'accessibilité.

## 13. États vides et erreurs

### 13.1 Aucun historique

Afficher :

- le titre `Ready when you are.` ;
- un court texte expliquant que toute activité terminée peut être ajoutée ;
- l'action `Log your first activity`.

### 13.2 Semaine sans activité

Lorsque l'historique existe mais que la semaine courante est vide :

- le titre éditorial devient `No activity this week.`, sans reformulation encourageante ni relance ;
- le nombre de séances affiche `0 sessions`, la durée cumulée n'est pas affichée ;
- l'énergie estimée n'est pas affichée ;
- les sept rails de la visualisation restent visibles et vides ;
- les séances récentes et l'action `Log activity` restent inchangées.

### 13.3 Aucune énergie estimée

Le résumé hebdomadaire n'affiche pas de valeur énergétique et conserve le nombre de séances et la durée. Il ne remplace pas la valeur par zéro.

### 13.4 Erreur locale

- Conserver le brouillon en mémoire.
- Afficher `Couldn’t save. Your activity is still here.`.
- Proposer `Try again`.

## 14. Design, icônes et mouvement

Le module reprend le design system validé de Mue : fond sombre, accent ambre `#EFB45F`, typographie Sora, cartes aux contours subtils et ton éditorial calme.

**Troisième et quatrième exceptions Material assumées** — l'effort perçu est saisi avec le `Slider` Material et l'heure de début avec le `TimePicker` Material, tous deux habillés aux couleurs du produit. Ils rejoignent le sélecteur de date et la boîte de confirmation de suppression déjà admis par la section 12.1 du PRD du socle : Material fournit nativement des contrôles denses et accessibles, là où un contrôle personnalisé à dix segments placerait chaque segment sous 40 dp et où une horloge redessinée coûterait plus qu'elle ne rapporterait. Chacun de ces quatre composants redéfinit l'intégralité de ses couleurs par le `…Defaults.colors(...)` correspondant, si bien qu'aucun ne se lit comme du Material à l'écran.

**Action épinglée et défilement** — lorsqu'une action est épinglée au-dessus d'un contenu défilant, le dégradé qui fond ce contenu dans l'action doit laisser passer les gestes, et l'action doit porter un liseré sur son bord supérieur dès qu'elle recouvre du contenu défilant, et seulement dans ce cas. Une surface non marquée qui avale un défilement est précisément le défaut que cette règle interdit.

### 14.1 Icônes

- La famille retenue est **Lucide**, celle des prototypes : trait régulier, extrémités arrondies.
- Les icônes sont **importées une à une en `VectorDrawable`** dans les ressources de l'application. Aucune dépendance réseau ni bibliothèque d'icônes complète n'est ajoutée, conformément au fonctionnement entièrement local du socle.
- Associer une icône à chaque préréglage, métrique importante et action de série.
- Toujours accompagner une icône ambiguë d'un libellé accessible.
- Ne pas utiliser l'icône comme seul indicateur d'un état sélectionné.

#### Barre d'onglets

Le module fait passer la barre inférieure à quatre onglets et remplace le point indicateur actuel par une icône surmontant le libellé, comme dans les prototypes :

| Onglet | Icône Lucide |
|---|---|
| `Entry` | `scale` |
| `Progress` | `chart-no-axes-combined` |
| `Activity` | `activity` |
| `Profile` | `user-round` |

L'état sélectionné reste porté par la couleur d'accent appliquée à l'icône **et** au libellé ; la barre demeure immobile pendant les transitions.

#### Icônes du module

| Usage | Icône |
|---|---|
| Préréglage marche, tapis ou marche extérieure | `footprints` |
| Préréglage course | `route` |
| Préréglage vélo | `bike` |
| Préréglage musculation | `dumbbell` |
| Préréglage `Other` | `shapes` |
| Durée | `timer` |
| Distance | `route` |
| Vitesse, allure et effort perçu | `gauge` |
| Énergie estimée | `flame` |
| Inclinaison | `trending-up` |
| Environnement intérieur ou extérieur | `map-pin`, `trees` |
| Équipement | `wrench` |
| Notes | `notebook-pen` |
| Ajout d'un exercice ou d'une série | `plus`, `plus-circle` |
| Duplication d'une série | `copy-plus` |
| Recherche dans un catalogue | `search` |
| Création d'un élément personnalisé | `sparkles` |

Les noms de ces deux tableaux font autorité sur les prototypes : le préréglage de marche extérieure utilise `footprints` et non `trees`, le préréglage de course `route` et non `person-standing`.

### 14.2 Animations

- Transition entre onglets : fondu et déplacement horizontal de `180–240 ms`.
- Ouverture de `Log activity` : panneau ou écran montant de `240–300 ms`.
- Changement de préréglage : ancien formulaire en fondu court, nouveau formulaire avec léger déplacement vertical.
- Ajout d'un exercice ou d'une série : expansion verticale et fondu de `180–220 ms`.
- Duplication d'une série : bref halo ambre sur la nouvelle ligne.
- Enregistrement : compression du bouton, décharge lumineuse et libellé `Saved` selon FR-ENTRY-006 du socle, vibration courte ; aucune coche.
- Barres hebdomadaires : croissance depuis leur base au premier affichage uniquement.
- Respecter le réglage Android de réduction des animations ; dans ce cas, supprimer déplacements et croissance tout en conservant les changements d'état instantanés.

### 14.3 Prototypes et divergences assumées

Les trois prototypes cités en section 1 restent la référence visuelle du module : ils fixent la hiérarchie, l'ambiance et les interactions principales. Ils ne constituent ni du code de production ni une mesure exacte des dimensions Android. Partout où ils divergent du présent document, c'est le présent document qui fait autorité.

Les divergences assumées sont les suivantes :

- une série ajoutée démarre vide, là où le prototype préremplit charge, répétitions, durée et effort (section 9.4) ;
- les identifiants des modes de suivi sont ceux de la section 9.2, et non les formes abrégées du prototype ;
- la durée se saisit en heures et en minutes sur les deux formulaires, y compris l'éditeur de musculation où le prototype ne montre qu'un champ de minutes (FR-ACTIVITY-005). Une séance venue du minuteur fait exception et affiche ses secondes (FR-TIMER-006) ;
- la durée se règle à la molette, et non dans les deux champs numériques que dessine le prototype (FR-ACTIVITY-005) ;
- le sélecteur de date de `Log activity` est le panneau habillé remontant du bas utilisé partout ailleurs dans l'application, et non la boîte de dialogue Material du prototype (FR-ENTRY-005 du socle) ;
- le tableau de bord affiche cinq séances récentes et non deux (FR-ACTIVITY-002) ;
- `Add set` et `Duplicate last set` coexistent, là où le prototype ne propose que la duplication (FR-ACTIVITY-009) ;
- les boutons d'enregistrement sont `Save activity` et `Save changes`, et non `Save session` (FR-ACTIVITY-010) ;
- la confirmation d'enregistrement est la décharge lumineuse du socle et le mot `Saved`, sans coche (FR-ACTIVITY-010) ;
- les noms d'icônes de la section 14.1 l'emportent : `footprints` pour la marche extérieure, `route` pour la course.

## 15. Accessibilité

- Zone tactile minimale de 48 dp.
- Contraste AA pour les textes et actions essentiels.
- Ordre de lecture cohérent : titre, résumé, action, historique.
- Libellé vocal complet pour les icônes et boutons de série.
- Ne pas dépendre uniquement de la couleur pour indiquer une sélection ou une erreur.
- Annoncer l'ajout, la duplication, le déplacement d'un exercice, la suppression et l'enregistrement via les services d'accessibilité.
- Clavier numérique adapté aux champs décimaux. La durée fait exception : elle se règle à la molette et n'ouvre aucun clavier (FR-ACTIVITY-005).
- Exposer la molette de durée comme un contrôle ajustable, réglable pas à pas et sans aucun geste de lancer.

## 16. Persistance et architecture technique

### 16.1 Principes

- Kotlin et Jetpack Compose, MVVM et injection manuelle, conformément au socle de Mue.
- Room pour les séances, mesures, équipements, exercices et séries. Aucun de ces objets ne relève de DataStore.
- Suppressions en cascade à l'intérieur d'une transaction.
- Écriture atomique d'une séance détaillée complète : la séance, ses mesures, ses équipements, ses exercices et ses séries sont écrits dans une seule transaction, ou pas du tout.
- Repositories séparant le domaine de la persistance.
- Calcul des agrégats hebdomadaires dans le domaine, indépendamment de l'UI.
- Enums persistés par identifiants stables et non par libellés anglais, afin qu'un renommage d'interface ne touche jamais les données.

### 16.2 Migration de la base

- Le module ajoute ses tables à la base existante par une **migration versionnée additive**, sans toucher à la table des mesures de poids.
- Le schéma reste exporté et versionné à chaque évolution.
- `fallbackToDestructiveMigration` demeure interdit, sous toutes ses variantes : la règle du socle sur l'intégrité de l'historique s'applique intégralement au module.
- La migration est couverte par un test d'intégration au même titre que celles du socle.

### 16.3 Contraintes de stockage

- Identifiants en `TEXT`, sous forme d'UUID.
- Dates en `TEXT` ISO `YYYY-MM-DD`, heures facultatives en `TEXT` `HH:MM` : le tri lexicographique équivaut au tri chronologique et aucun fuseau n'est stocké.
- Toutes les grandeurs numériques en `INTEGER`, dans les unités canoniques des sections 8.3 et 9.4.
- L'unité d'une mesure n'a pas de colonne : elle se déduit du `kind` selon la section 8.3.
- `createdAt` et `updatedAt` sont les seules valeurs horodatées absolues du module. Elles appartiennent au stockage, servent uniquement l'audit et n'interviennent jamais dans la date d'une séance.
- Clés étrangères déclarées avec `ON DELETE CASCADE`, la cascade vivant dans SQLite et non dans le code applicatif.
- Index sur `ActivityMetric.sessionId`, `SessionEquipment.sessionId`, `StrengthExercise.sessionId`, `StrengthSet.strengthExerciseId` et `ActivitySession.startedAt`, ce dernier servant l'agrégat hebdomadaire et le tri de l'historique.
- Le catalogue d'exercices fourni est inséré à la création de la base et ne peut pas être supprimé par l'utilisateur.

### 16.4 Comportements techniques

- Le brouillon d'une séance — préréglage, champs communs, mesures, exercices et séries — survit à une rotation comme à une destruction du processus, via `rememberSaveable` et `SavedStateHandle`, ainsi que l'exige le socle pour toute saisie en cours. Ce mécanisme reste celui de la saisie manuelle ; la révision d'une séance chronométrée lui ajoute une persistance en base décrite en section 8.2 du module minuteur.
- Les listes de séances sont exposées en `Flow` et consommées par l'UI sans requête bloquante sur le fil principal.
- Les agrégats hebdomadaires sont calculés en base ou dans le domaine, jamais recalculés à chaque recomposition.

### 16.5 Tests attendus

| Niveau | Périmètre |
|---|---|
| Unitaire, sans Android | Validité d'une série par mode de suivi, comptage des séries, agrégats hebdomadaires, dérivation du libellé d'une séance, formatage de la dernière performance, conversions entre unités canoniques et unités affichées. |
| Intégration, base en mémoire | Migration additive, écriture atomique d'une séance détaillée, suppression en cascade, absence de doublon d'équipement, réutilisation d'une définition d'exercice existante. |
| Interface Compose | Enregistrement d'une marche sur tapis, changement de préréglage conservant les champs communs, création d'une séance de musculation détaillée, duplication et suppression d'une série, suppression d'une séance. |

Les calculs de la première ligne sont déterministes et doivent être couverts avant toute mise au point visuelle.

### 16.6 Compatibilité future

La structure doit pouvoir être traduite plus tard vers les concepts Health Connect : une séance avec début, fin et type, accompagnée de mesures distinctes. Cette compatibilité est une contrainte de conception, pas une fonctionnalité V1.

## 17. Critères d'acceptation transversaux

La V1 du module Activities est acceptée lorsque :

- [ ] Le quatrième onglet `Activity` est accessible depuis tous les écrans principaux, avec les quatre icônes de la section 14.1, et la barre d'onglets reste visible sur les écrans de saisie du module.
- [ ] Une marche sur tapis peut être créée avec durée, distance, vitesse, calories estimées et inclinaison.
- [ ] Les champs facultatifs peuvent rester vides sans créer de valeurs nulles présentées comme zéro.
- [ ] Deux séances peuvent être enregistrées le même jour sans remplacement.
- [ ] Le constructeur `Other` permet de choisir une activité et des équipements prédéfinis, tout en conservant une création personnalisée de dernier recours.
- [ ] Une séance de musculation peut être enregistrée en mode rapide.
- [ ] Une séance détaillée peut contenir plusieurs exercices et plusieurs séries de modes de suivi différents.
- [ ] Une série peut être enregistrée sans charge, et une série sans sa mesure principale est refusée.
- [ ] Le sélecteur permet de rechercher, choisir ou créer un exercice personnalisé, sans jamais créer deux définitions pour le même nom.
- [ ] La dernière performance d'un exercice déjà pratiqué est affichée au format de la section 11.4.
- [ ] Une série peut être ajoutée vide, dupliquée, modifiée et supprimée.
- [ ] Les séances peuvent être modifiées et supprimées, avec suppression en cascade complète.
- [ ] Passer une séance détaillée existante en `Quick log` demande une confirmation avant de retirer ses exercices.
- [ ] Les cinq séances récentes et l'écran `Activity history` affichent les mêmes cartes et ouvrent la même édition.
- [ ] Les totaux hebdomadaires de durée, séances et énergie disponible sont corrects, et la semaine ne dépend d'aucun fuseau.
- [ ] Une semaine sans activité affiche l'état calme de la section 13.2 sans jamais montrer `0 kcal`.
- [ ] Les calories sont explicitement présentées comme une estimation et conservent leur source.
- [ ] Aucune valeur numérique n'est stockée sous forme de flottant.
- [ ] La migration de base est additive et ne perd aucune mesure de poids existante.
- [ ] Le brouillon d'une séance survit à une rotation et à une destruction du processus.
- [ ] Le module fonctionne hors ligne et ne nécessite aucun compte.
- [ ] Les animations respectent la réduction des animations du système.
- [ ] Toutes les actions essentielles sont utilisables avec TalkBack sans dépendre d'un geste complexe.

## 18. Données de démonstration du prototype

Le prototype utilise les exemples suivants uniquement pour illustrer l'interface :

- `Treadmill walk` — 45 min, 4.2 km, 5.6 km/h, ≈280 kcal ;
- `Strength training` — 55 min, 12 sets, ≈320 kcal ;
- exercices : `Barbell squat`, `Bench press`, `Plank`.

Ces valeurs ne constituent ni une recommandation d'entraînement ni une estimation médicale.
