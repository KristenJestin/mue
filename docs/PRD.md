# PRD — Mue

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Version | V1 |
| Statut | Validé pour le développement |
| Date | 23 août 2026 |
| Plateforme | Android natif, téléphone, portrait |
| Langue de l'application | Anglais uniquement |
| Source de cadrage | [`CADRAGE.md`](./CADRAGE.md) |
| Référence visuelle | [`proto/fusion/saisie.html`](../proto/fusion/saisie.html) |

## 2. Résumé

Mue est une application Android personnelle de suivi du poids. Elle doit rendre la saisie quotidienne rapide, agréable et non culpabilisante grâce à une interface évoquant une balance physique, un historique lisible et des animations sobres.

La V1 permet de saisir, consulter, modifier, supprimer et exporter des mesures de poids. Elle inclut également un profil santé minimal avec la taille, une date de naissance facultative et un IMC informatif pour les adultes.

Mue fonctionne localement, sans compte ni serveur. Le suivi alimentaire et des calories ne fait pas partie de ce PRD.

## 3. Problème à résoudre

Les applications de suivi du poids sont souvent soit trop médicales, soit trop chargées en fonctionnalités liées aux régimes, objectifs et calories. La saisie quotidienne devient alors une opération administrative plutôt qu'un geste simple.

Mue doit proposer :

- une saisie du poids en quelques secondes ;
- une interaction tactile distinctive et agréable ;
- une lecture simple de l'évolution ;
- un ton calme et factuel ;
- une conservation locale et exportable des données.

## 4. Objectifs produit

### 4.1 Objectifs de la V1

- Permettre d'enregistrer une mesure de poids pour une date donnée.
- Faire de la balance tactile le mode de saisie principal.
- Proposer une saisie manuelle fiable au clavier.
- Afficher l'évolution du poids sous forme de courbe et d'historique.
- Permettre la modification et la suppression des anciennes mesures.
- Calculer et contextualiser l'IMC lorsque les informations nécessaires existent.
- Exporter l'historique complet dans un CSV durable et lisible.
- Fonctionner intégralement hors ligne.
- Offrir une expérience visuelle sombre, personnalisée et fluide.

### 4.2 Critères de réussite qualitatifs

- Un nouvel utilisateur comprend comment enregistrer son premier poids sans tutoriel.
- Une mesure peut être ajoutée en quelques secondes.
- Le contrôle de la balance paraît précis et naturel sur un téléphone Android réel.
- Les informations principales restent compréhensibles sans connaissances médicales.
- L'utilisateur peut récupérer tout son historique de poids dans un format ouvert.
- Les opérations essentielles restent accessibles sans utiliser le geste de la balance.

## 5. Hors périmètre de la V1

- iOS, tablette et interface optimisée pour le paysage.
- Compte utilisateur, authentification et synchronisation cloud.
- Import ou restauration de données.
- Suivi de l'alimentation et des calories.
- Objectif de poids, notifications et rappels.
- Unités impériales.
- Thème clair.
- Catégorisation pédiatrique de l'IMC.
- Diagnostic ou recommandation médicale.

> **Cette liste borne la V1, pas le produit.** Trois de ces exclusions ont depuis leur propre PRD validé : la synchronisation et le compte utilisateur dans [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md), le suivi alimentaire dans [`PRD_FOOD.md`](./PRD_FOOD.md), l'activité physique dans [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md). Elles restent hors périmètre de la V1, et en sont sorties depuis.

## 6. Utilisateur cible

La V1 s'adresse à une personne anglophone utilisant un téléphone Android et souhaitant observer son évolution de poids sans utiliser un système complexe de régime ou de coaching.

L'expérience doit rester utile pour une utilisation quotidienne comme occasionnelle.

## 7. Principes d'expérience

1. **Simple avant tout** — une action principale évidente par écran.
2. **Factuel, jamais culpabilisant** — décrire les données sans félicitation ou alarme excessive.
3. **Tactile mais précis** — l'aspect physique de la balance ne doit jamais réduire le contrôle.
4. **Animation fonctionnelle** — chaque mouvement doit expliquer une transition ou confirmer une action.
5. **Données appartenant à l'utilisateur** — fonctionnement local et export dans un format ouvert.

## 8. Architecture de l'information

La navigation principale utilise trois onglets permanents :

| Onglet | Rôle |
|---|---|
| `Entry` | Saisir et enregistrer un poids pour une date. |
| `Progress` | Consulter la courbe, les indicateurs et l'historique. |
| `Profile` | Renseigner le profil santé, gérer les préférences et exporter les données. |

La barre inférieure reste visible et immobile pendant les transitions entre onglets.

## 9. Exigences fonctionnelles

### 9.1 Écran Entry

#### FR-ENTRY-001 — Valeur initiale

- Au démarrage de l'application, sélectionner la mesure la plus récente chronologiquement.
- En l'absence de mesure, utiliser `70.00 kg`.
- Utiliser la date du jour par défaut.
- La valeur reste ensuite celle choisie par l'utilisateur pendant toute la session, y compris après un enregistrement et après un passage par un autre onglet. Elle n'est réinitialisée qu'au démarrage suivant de l'application.

#### FR-ENTRY-002 — Balance tactile

- Afficher une grande valeur avec deux décimales et le suffixe `kg`, par exemple `74.05 kg`.
- Afficher une règle horizontale sous un repère central fixe.
- Le repère reste immobile ; c'est la règle qui se déplace sous le doigt.
- **Sens du geste** : glisser vers la gauche **augmente** le poids, glisser vers la droite le **diminue**. Ce sens découle directement du visuel — les valeurs croissent de gauche à droite sur la règle, donc amener une valeur supérieure sous le repère impose de faire défiler la règle vers la gauche.
- Faire varier le poids par pas de `0.05 kg`.
- Autoriser les valeurs de `30.0 kg` à `250.0 kg` incluses.
- Utiliser les kilogrammes entiers comme graduations principales.
- Espacer les graduations secondaires visibles de `0.1 kg`. Un trait tous les `0.05 kg` serait distant d'environ 4 dp et illisible ; les graduations ne représentent donc pas toutes les valeurs atteignables.
- Produire un retour haptique léger tous les `0.5 kg` — soit tous les dix pas — lorsque les vibrations sont activées.
- Proposer une courte inertie `Balanced` au relâchement.
- Arrêter la règle sur le multiple de `0.05` valide le plus proche, indépendamment de l'espacement des graduations.
- Interrompre immédiatement l'inertie lorsqu'un nouveau geste commence.
- **Butées** : en atteignant `30.0` ou `250.0`, la règle s'arrête net sans rebond et l'inertie est annulée. Aucun retour haptique de butée n'est produit.

Les paramètres physiques exacts — sensibilité, friction, durée d'inertie — sont ajustables par l'équipe pendant les essais sur téléphone, tant que la sensation reste celle d'une courte glisse précise. Le sens du geste, lui, n'est pas un paramètre ajustable.

#### FR-ENTRY-003 — Contrôles accessibles

- Proposer des actions `−` et `+` accessibles en complément du geste, visibles en permanence de part et d'autre de la balance.
- Chaque appui modifie le poids de `0.05 kg`.
- Un appui maintenu répète l'action après un délai d'environ `400 ms`, puis accélère progressivement.
- La répétition s'arrête à la butée correspondante.
- Exposer la valeur et les actions aux services d'accessibilité Android.

Ces contrôles n'apparaissent pas dans le prototype de référence : leur intégration visuelle est à concevoir pendant l'implémentation, dans le respect du design system.

#### FR-ENTRY-004 — Saisie manuelle

- Toucher la grande valeur transforme la valeur en champ de saisie.
- Ouvrir le clavier numérique Android.
- Accepter le point et la virgule comme séparateurs lors de la saisie, quelle que soit la langue du téléphone.
- Afficher la valeur selon les conventions de la langue du téléphone, virgule décimale comprise.
- Accepter jusqu'à deux décimales en saisie.
- Arrondir au multiple de `0.05` le plus proche.
- Refuser toute valeur en dehors de `30.0–250.0 kg` avec le message `Weight must be between 30.0 and 250.0 kg`.
- L'action `Done` du clavier ferme le mode manuel et restaure la balance.

#### FR-ENTRY-005 — Sélection de date

- Afficher la date de la mesure.
- Utiliser aujourd'hui par défaut.
- Ouvrir un sélecteur de date dans un panneau remontant du bas.
- Permettre de fermer le panneau par validation ou glissement vers le bas.
- Les dates postérieures à aujourd'hui ne sont pas sélectionnables.
- **Changer la date ne modifie jamais le poids affiché**, même si une mesure existe déjà à la date choisie. La valeur de la balance reste celle que l'utilisateur a réglée.

#### FR-ENTRY-006 — Enregistrement

- Utiliser un bouton explicite `Save measurement`.
- Si aucune mesure n'existe à la date choisie, créer une entrée.
- Si une mesure existe déjà, la remplacer sans avertissement.
- Ne jamais conserver plus d'une mesure pour une même date.
- Après succès, rester sur l'écran Entry.
- Confirmer l'enregistrement pendant environ une seconde par la décharge lumineuse décrite ci-dessous, aussi bien pour une création que pour un remplacement.
- Produire une vibration courte de confirmation lorsque les vibrations sont activées.

**Confirmation d'enregistrement** — le bouton décharge sa lumière puis se tait. Aucun pictogramme ni caractère de police n'intervient. Référence approuvée : la variante `Éclat + repos` de [`proto/fusion/confirmation.html`](../proto/fusion/confirmation.html).

- Le bouton se contracte au toucher.
- Le libellé s'estompe puis revient sous la forme du mot `Saved`.
- Le remplissage quitte l'ambre plein pour l'ambre sourd du design system, référence prototype `#33291E`, avec un libellé à la couleur d'accent.
- Au même instant, un halo ambre se propage vers l'extérieur du bouton puis s'éteint : la lumière quitte le bouton pendant que celui-ci s'assombrit. La simultanéité des deux mouvements fait tout l'intérêt de la combinaison.
- En synchronisation sur l'écran Entry : le repère central de la règle s'illumine, une onde parcourt la règle, les graduations proches du repère s'éclaircissent.
- L'ensemble dure environ une seconde, après quoi le bouton retrouve l'ambre plein et son libellé `Save measurement`.
- Le même traitement confirme l'enregistrement du profil (FR-PROFILE-003), avec le même mot `Saved` sur les deux boutons ; seul l'élément d'écran synchronisé change.
- Sous réduction des animations, le traitement est allégé selon la section 14.

#### FR-ENTRY-007 — Message d'accueil

- Afficher `Hello {name},` au-dessus du titre lorsqu'un nom d'affichage est renseigné dans le profil.
- Lorsque le nom est vide ou absent, masquer entièrement cette ligne ; le titre reste affiché seul.
- Ce message est purement décoratif et n'influence aucun calcul.

### 9.2 Écran Progress

#### FR-PROGRESS-001 — Périodes

Proposer les filtres :

- `7 days` ;
- `30 days` ;
- `3 months` ;
- `All`.

Les périodes sont des fenêtres calendaires se terminant à la date du jour. `All` contient toutes les mesures.

La période sélectionnée gouverne l'ensemble de l'écran : courbe, indicateurs et historique.

#### FR-PROGRESS-002 — Courbe

- Afficher une mesure par date, triée chronologiquement.
- Conserver la structure du graphique lorsque le filtre change.
- Toucher un point affiche sa date et son poids.
- La courbe ne doit ni clignoter ni être recréée brutalement lors d'un changement de période.

#### FR-PROGRESS-003 — Indicateurs

Afficher lorsque les données sont disponibles :

- le poids actuel, défini comme la mesure à la date la plus récente **de la période** ;
- la variation entre la première et la dernière mesure de la période ;
- le rythme moyen hebdomadaire ;
- l'IMC actuel si une taille est renseignée.

Le rythme moyen hebdomadaire est calculé ainsi :

`(dernier poids − premier poids) / nombre de jours entre la première et la dernière mesure de la période × 7`

Règles d'affichage :

- Si la période contient moins de deux mesures sur des dates différentes, la variation et le rythme sont indisponibles et affichent `—`.
- Si la période ne contient aucune mesure, le poids actuel et l'IMC affichent également `—`. Aucune valeur extérieure à la période n'est utilisée comme repli.
- Le poids actuel s'affiche avec deux décimales, comme sur l'écran Entry.
- La variation est une différence de poids : elle s'affiche avec deux décimales et un signe toujours visible, par exemple `−0.35` ou `+0.20`.
- Le rythme moyen hebdomadaire est une valeur dérivée : il s'affiche avec une décimale et un signe toujours visible, par exemple `−0.3` ou `+0.2`.
- L'IMC de cet écran suit intégralement les règles de la section 9.4, y compris l'interdiction d'afficher une catégorie avant 20 ans.

**Carte IMC** — l'écran Progress porte la présentation complète de l'IMC : carte `Current BMI` à remplissage ambre, valeur à une décimale, barre de référence à quatre zones avec son repère et texte de prudence. Cette carte figurait sur l'écran Profile dans le prototype ; elle appartient désormais à Progress, parce que Profile est l'écran où la taille est saisie et Progress celui où l'état est lu. L'écran Profile n'en conserve qu'un affichage compact (FR-PROFILE-001).

- La carte suit la période sélectionnée comme tous les autres indicateurs : une période vide affiche `—` et n'utilise jamais une valeur extérieure à la période comme repli.
- La barre de référence et le libellé de zone n'apparaissent que pour un IMC classé, selon FR-BMI-002.
- La carte IMC devient le seul élément à remplissage ambre de cette zone : `Average pace` perd le sien et adopte la présentation des autres indicateurs.
- Son emplacement exact dans l'écran est laissé à l'implémentation.

#### FR-PROGRESS-004 — Historique

- Afficher sous le graphique les mesures appartenant à la période sélectionnée.
- Trier la liste de la plus récente à la plus ancienne.
- Afficher chaque poids avec deux décimales.
- N'appliquer aucune limite de nombre ; la liste défile avec le contenu de l'écran.
- Toucher une mesure ouvre un panneau d'édition remontant du bas.

#### FR-PROGRESS-005 — Modification

Le panneau contient :

- la date ;
- le poids ;
- l'action `Save changes` ;
- l'action `Delete measurement`.

Règles :

- Le poids respecte les mêmes limites, le même pas de `0.05 kg`, les mêmes deux décimales à l'affichage et le même message d'erreur que l'écran Entry.
- La date peut être modifiée, sans jamais dépasser aujourd'hui.
- Si la nouvelle date contient déjà une mesure, celle-ci est remplacée sans confirmation supplémentaire.
- La modification est enregistrée avec `Save changes`.

#### FR-PROGRESS-006 — Suppression

- Demander une confirmation avant toute suppression.
- Après confirmation, supprimer définitivement la mesure.
- Recalculer immédiatement le poids actuel, le graphique, la variation, le rythme et l'IMC.

### 9.3 Écran Profile

#### FR-PROFILE-001 — Taille

- Permettre de saisir la taille en centimètres.
- Utiliser une valeur comprise entre `120 cm` et `230 cm`.
- Refuser toute valeur hors de cette plage avec le message `Height must be between 120 and 230 cm`.
- Un champ vide est accepté et équivaut à une taille non renseignée ; l'IMC disparaît alors.
- Recalculer l'IMC avec la mesure de poids la plus récente.
- Afficher sous le champ de taille un IMC compact : la valeur, suivie du libellé de zone lorsque celui-ci est autorisé, par exemple `BMI 25.9 · Overweight`.
- Cet affichage se met à jour en direct depuis le formulaire, avant tout enregistrement, afin que l'utilisateur voie sa saisie prendre effet.
- L'écran Profile ne porte ni carte ambre, ni barre de référence, ni texte de prudence : la présentation complète de l'IMC appartient à l'écran Progress (FR-PROGRESS-003).

#### FR-PROFILE-002 — Date de naissance

- La date de naissance est facultative.
- Refuser une date postérieure à aujourd'hui ou antérieure de plus de `120 ans`, avec le message `Enter a valid date of birth`.
- Calculer l'âge sans le stocker séparément.
- Utiliser l'âge uniquement pour décider si une catégorie adulte d'IMC peut être affichée.

#### FR-PROFILE-003 — Sauvegarde du profil

- Utiliser un bouton `Save profile`.
- Confirmer le succès par la décharge lumineuse définie en FR-ENTRY-006 : contraction, libellé remplacé par `Saved`, passage à l'ambre sourd avec texte à la couleur d'accent, halo ambre sortant au même instant, retour à l'état initial après environ une seconde.
- En synchronisation, l'IMC compact placé sous le champ de taille effectue un court saut vertical.
- Produire une vibration courte de confirmation lorsque les vibrations sont activées, identique à celle de l'écran Entry : les deux boutons partagent la même confirmation, donc le même retour tactile.
- Conserver les informations localement sur l'appareil.
- En présence d'une valeur invalide, ne rien enregistrer, conserver la saisie pour correction et afficher le message correspondant.

#### FR-PROFILE-004 — Préférences

- Permettre d'activer ou désactiver les retours haptiques.
- Respecter automatiquement le réglage Android de réduction des animations.

#### FR-PROFILE-005 — Données

Ajouter une section `Your data` avec l'action `Export weight data`.

#### FR-PROFILE-006 — Nom d'affichage

- Permettre de saisir un nom d'affichage facultatif, placé en tête du formulaire.
- Limiter la saisie à `40` caractères.
- Alimenter le message d'accueil de l'écran Entry décrit en FR-ENTRY-007.
- Le champ vide est valide et ne bloque jamais l'enregistrement du profil.
- Ce nom n'est jamais exporté et ne quitte pas l'appareil.

### 9.4 IMC

#### FR-BMI-001 — Calcul

Calculer :

`BMI = weight in kg / (height in metres × height in metres)`

- Utiliser le poids le plus récent chronologiquement : sur l'écran Progress, la mesure la plus récente de la période sélectionnée (FR-PROGRESS-003) ; sur l'écran Profile, la mesure la plus récente de tout l'historique.
- Afficher une décimale.
- Ne rien afficher si la taille ou le poids manque.
- Ne pas stocker l'IMC ; toujours le recalculer.

#### FR-BMI-002 — Catégories adultes

Afficher une catégorie uniquement lorsque la date de naissance confirme que l'utilisateur a au moins 20 ans :

| IMC | Libellé anglais |
|---|---|
| `< 18.5` | `Underweight` |
| `18.5–24.9` | `Healthy weight` |
| `25.0–29.9` | `Overweight` |
| `≥ 30.0` | `Obesity` |

- Sans date de naissance, afficher uniquement la valeur numérique.
- Avant 20 ans, afficher uniquement la valeur numérique dans la V1.
- Ne pas demander le sexe dans la V1.
- Afficher, avec la carte IMC de l'écran Progress, un texte précisant que l'IMC est un indicateur général de dépistage, pas un diagnostic.

**Barre de référence** — la barre à quatre zones nommées et son repère mobile sont portés par la carte IMC de l'écran Progress. Un repère positionné sur une zone nommée constitue une catégorie : la barre et son repère ne sont donc affichés **que** lorsque la catégorie est autorisée. Dans les autres cas, seuls la valeur numérique et le texte de prudence restent visibles.

La même règle gouverne l'IMC compact de l'écran Profile, dont le libellé de zone disparaît lorsque la catégorie n'est pas autorisée ; seule la valeur numérique subsiste alors.

Références : [Organisation mondiale de la Santé](https://www.who.int/docs/default-source/nutritionlibrary/events/9789241505529-eng.pdf) et [CDC](https://www.cdc.gov/bmi/faq/).

### 9.5 Export CSV

#### FR-CSV-001 — Déclenchement

- L'action `Export weight data` se trouve dans `Profile > Your data`.
- Générer le fichier à la demande.
- Ouvrir la feuille de partage Android pour enregistrer ou transmettre le fichier.

#### FR-CSV-002 — Contenu

Le CSV contient uniquement les mesures de poids. Il exclut :

- le nom d'affichage ;
- la taille ;
- la date de naissance ;
- l'IMC ;
- la variation et le rythme ;
- les préférences.

L'export porte toujours sur l'historique complet, indépendamment de la période sélectionnée dans l'écran Progress.

#### FR-CSV-003 — Format

| Propriété | Valeur |
|---|---|
| Nom | `mue-weight-YYYY-MM-DD.csv`, où la date est celle de l'export |
| Encodage | UTF-8 sans BOM |
| Fin de ligne | `LF` |
| Séparateur | Virgule |
| Décimales | Point |
| Précision du poids | Deux décimales, par exemple `74.05` |
| Dates | ISO `YYYY-MM-DD` |
| Tri | Plus ancienne vers plus récente |
| En-tête | `date,weight_kg` |

Le format du fichier est invariant : ni la langue du téléphone ni ses réglages régionaux ne modifient le séparateur décimal, le format des dates ou l'encodage.

Exemple :

```csv
date,weight_kg
2026-08-12,74.80
2026-08-18,74.95
2026-08-23,74.55
```

## 10. Règles métier

| ID | Règle |
|---|---|
| BR-001 | Une date possède au maximum une mesure. |
| BR-002 | Toute écriture sur une date existante remplace la valeur précédente. |
| BR-003 | Le poids est compris entre 30.0 et 250.0 kg, par multiples de 0.05 kg, et s'affiche avec deux décimales. |
| BR-004 | Le poids actuel est la mesure ayant la date la plus récente de la période observée, pas la dernière entrée techniquement. |
| BR-005 | L'âge et l'IMC sont des valeurs dérivées et ne sont pas stockés. |
| BR-006 | Une suppression demande toujours une confirmation. |
| BR-007 | Une modification ou un remplacement ne demande pas de confirmation. |
| BR-008 | Toutes les fonctionnalités V1 fonctionnent sans réseau. |
| BR-009 | Aucune mesure ne peut porter une date postérieure à aujourd'hui. |
| BR-010 | L'affichage des nombres et des dates suit la langue du téléphone ; le CSV n'en dépend jamais. |

## 11. Modèle de données logique

### 11.1 WeightEntry

| Champ | Type logique | Contraintes |
|---|---|---|
| `date` | Date locale | Clé unique, format calendrier sans heure, jamais dans le futur |
| `weightKg` | Décimal | `30.0–250.0`, précision `0.05` |

Le poids est stocké sous forme de nombre entier de centièmes de kilogramme, `7405` représentant `74.05 kg`, afin d'éliminer toute dérive d'arrondi. La conversion n'a lieu qu'à l'affichage.

La date est stockée sous forme de texte ISO `YYYY-MM-DD` représentant une date locale pure. Aucun horodatage, epoch ou fuseau horaire n'est conservé, ce qui rend impossible tout décalage d'un jour lors d'un changement de fuseau.

### 11.2 UserProfile

| Champ | Type logique | Contraintes |
|---|---|---|
| `displayName` | Texte facultatif | `0–40` caractères |
| `heightCm` | Entier facultatif | `120–230` |
| `birthDate` | Date locale facultative | Pas dans le futur, pas antérieure de plus de 120 ans, ne doit pas être transformée en âge stocké |

### 11.3 Preferences

| Champ | Type logique | Valeur par défaut |
|---|---|---|
| `hapticsEnabled` | Booléen | Activé |

Le réglage de réduction des animations vient du système Android et n'est pas dupliqué comme préférence locale.

## 12. Direction visuelle

### 12.1 Design system

- Thème sombre uniquement.
- Accent principal ambre, référence prototype `#EFB45F`.
- Typographie principale Sora.
- Grande lisibilité des valeurs numériques.
- Espacements généreux et faible densité.
- Composants personnalisés ; éviter l'apparence Material générique.
- Material peut être utilisé comme fondation technique invisible.

**Exception assumée pour la V1** — quatre composants Material intégrés sont utilisés tels quels, habillés aux couleurs du produit : le sélecteur de date, la boîte de confirmation de suppression, le `Slider` de l'effort perçu et le `TimePicker` de l'heure de début, ces deux derniers introduits par le module Activities. Ce sont chaque fois des contrôles denses et déjà accessibles, qu'il coûterait plus cher de reconstruire que d'habiller. Chacun redéfinit l'intégralité de ses couleurs par le `…Defaults.colors(...)` correspondant, si bien qu'aucun ne se lit comme du Material à l'écran. Leur remplacement par des composants entièrement personnalisés sera réévalué une fois l'application en main.

### 12.2 Prototypes approuvés

- [`Entry`](../proto/fusion/saisie.html)
- [`Progress`](../proto/fusion/evolution.html)
- [`Profile`](../proto/fusion/profil.html)
- [`Confirmation d'enregistrement`](../proto/fusion/confirmation.html)

Les prototypes fixent la hiérarchie, l'ambiance et les interactions principales. Ils ne constituent pas du code de production ni une mesure exacte des dimensions Android.

**Éléments à concevoir pendant l'implémentation**, absents des prototypes mais exigés par ce PRD :

- les contrôles `−` et `+` de l'écran Entry (FR-ENTRY-003) ;
- le champ de nom d'affichage de l'écran Profile (FR-PROFILE-006) ;
- le réglage des retours haptiques (FR-PROFILE-004) ;
- la section `Your data` et son action d'export (FR-PROFILE-005).

**Divergences assumées** — l'implémentation s'écarte volontairement des prototypes d'écran sur deux points :

- la confirmation d'enregistrement remplace le `Saved ✓` de `saisie.html` et le `Profile saved ✓` de `profil.html` par la décharge lumineuse du bouton (FR-ENTRY-006, FR-PROFILE-003). La référence approuvée est la variante `Éclat + repos` de [`proto/fusion/confirmation.html`](../proto/fusion/confirmation.html) ;
- la carte IMC de `profil.html` passe à l'écran Progress, où elle remplace la carte `Current BMI` de `evolution.html` et où `Average pace` perd son remplissage ambre ; l'écran Profile n'en garde qu'un affichage compact sous le champ de taille (FR-PROGRESS-003, FR-PROFILE-001).

Le formatage des nombres visible dans les prototypes, qui utilise la virgule décimale, illustre un rendu en français et ne préjuge pas du rendu réel, lequel suit la langue du téléphone.

## 13. Mouvement et retours

| Interaction | Comportement | Durée cible |
|---|---|---|
| Changement d'onglet | Léger glissement directionnel + fondu ; barre fixe | 220 ms |
| Saisie manuelle | La balance s'atténue et descend, la valeur devient un champ | 180 ms |
| Date picker | Panneau du bas + fond assombri | 220 ms |
| Changement de période | Morphing de la courbe et des indicateurs | 280 ms |
| Modification IMC | Valeur progressive + déplacement du repère | 250 ms |
| Confirmation d'enregistrement | Contraction, libellé `Saved`, passage à l'ambre sourd, halo ambre sortant simultané, réponse synchronisée de l'écran et haptique | Environ 1 s |

Principes :

- Aucun rebond sur la navigation.
- La balance est le seul élément à mouvement physique marqué.
- Toute animation est interruptible par une nouvelle interaction.
- L'interface ne doit jamais attendre la fin d'une animation pour accepter une action critique.

## 14. Accessibilité

- Fournir des libellés TalkBack pour tous les champs, actions et valeurs.
- Exposer la balance comme un contrôle ajustable.
- Fournir les actions `−` et `+` comme alternative complète au geste, utilisables par appui simple sans maintien prolongé.
- Maintenir des zones tactiles adaptées aux standards Android.
- Garantir un contraste suffisant entre l'ambre, le fond sombre et le texte.
- Permettre de désactiver les vibrations.
- Lorsque la réduction des animations est active :
  - remplacer les glissements par un fondu d'environ 100 ms ;
  - désactiver le défilement animé des chiffres ;
  - conserver le magnétisme fonctionnel de la balance ;
  - réduire la confirmation d'enregistrement au seul changement de remplissage et de libellé, en fondu croisé d'environ 100 ms : ni halo, ni onde sur la règle, ni illumination du repère, ni saut de l'IMC. La confirmation doit rester parfaitement identifiable.

## 15. États particuliers et erreurs

### 15.1 Aucune mesure

- Entry démarre à `70.00 kg` et à la date du jour.
- Progress affiche un état vide invitant à ajouter une première mesure.
- L'IMC n'est pas affiché.
- L'export produit un CSV contenant uniquement l'en-tête.

### 15.2 Données de profil incomplètes

- Sans taille : ne pas calculer l'IMC ; ni la carte de l'écran Progress ni l'affichage compact de l'écran Profile n'apparaissent.
- Sans date de naissance : afficher la valeur de l'IMC sans catégorie — sans barre de référence sur la carte de l'écran Progress, sans libellé de zone sur l'affichage compact de l'écran Profile.
- Avant 20 ans : même traitement que sans date de naissance.
- Sans nom d'affichage : masquer le message d'accueil de l'écran Entry.

### 15.3 Valeur invalide

- Ne pas enregistrer.
- Conserver la valeur saisie pour correction.
- Afficher le message correspondant :
  - poids : `Weight must be between 30.0 and 250.0 kg` ;
  - taille : `Height must be between 120 and 230 cm` ;
  - date de naissance : `Enter a valid date of birth`.

### 15.4 Erreur de stockage ou d'export

- Ne pas afficher de confirmation de succès.
- Afficher un message compréhensible et permettre de réessayer.
- Ne jamais laisser un fichier partiel présenté comme un export réussi.

## 16. Exigences non fonctionnelles

### 16.1 Fonctionnement local

- Aucun compte ni serveur requis.
- Lecture, écriture, graphique, profil et export disponibles hors ligne.
- Les données personnelles restent dans le stockage local de l'application jusqu'à une action explicite d'export.

### 16.2 Performance perçue

- Le geste de la balance doit rester fluide et suivre le doigt sans retard perceptible.
- Le graphique doit se mettre à jour sans blocage visible.
- Les opérations de base de données et d'export ne doivent pas bloquer le fil d'interface.

### 16.3 Intégrité

- Les écritures et suppressions doivent être transactionnelles.
- La contrainte d'unicité sur la date doit exister dans le stockage, pas uniquement dans l'interface.
- Une migration de base ne doit pas supprimer l'historique existant. Le passage de l'unité de stockage du dixième au centième de kilogramme relève de cette règle : les mesures déjà enregistrées doivent être converties, jamais effacées.
- L'état de saisie en cours doit survivre à une rotation et à une destruction du processus par le système.

### 16.4 Confidentialité

- Ne demander que les informations nécessaires à la V1.
- Ne pas présenter l'IMC comme un diagnostic.
- Préparer une politique de confidentialité avant publication publique.

## 17. Critères d'acceptation de la V1

### Entry

- [ ] Le premier lancement affiche `70.00 kg` et la date du jour.
- [ ] Un utilisateur peut choisir un poids avec la balance, les boutons ou le clavier.
- [ ] La balance respecte le pas de `0.05 kg`, la plage, le sens du geste, l'inertie et le magnétisme définis.
- [ ] Les poids s'affichent avec deux décimales sur Entry, Progress et dans l'historique.
- [ ] Les butées `30.0` et `250.0` arrêtent la règle sans rebond.
- [ ] Changer la date ne modifie pas le poids affiché.
- [ ] Une date postérieure à aujourd'hui ne peut pas être sélectionnée.
- [ ] L'enregistrement crée ou remplace exactement une mesure pour la date.
- [ ] La confirmation apparaît sans quitter l'écran.
- [ ] Le message d'accueil apparaît avec un nom renseigné et disparaît sans nom.

### Progress

- [ ] Les quatre périodes filtrent la courbe, les indicateurs et l'historique.
- [ ] Le graphique, la variation et le rythme utilisent les bonnes dates.
- [ ] Une période sans mesure affiche `—` pour tous les indicateurs, carte IMC comprise.
- [ ] La carte IMC est présente, avec sa barre de référence lorsque la catégorie est autorisée, et suit la période sélectionnée.
- [ ] Un point affiche son poids et sa date.
- [ ] Une ancienne mesure peut être modifiée ou supprimée.
- [ ] La suppression demande confirmation et met tous les indicateurs à jour.

### Profile et IMC

- [ ] Le nom, la taille et la date de naissance sont persistés localement.
- [ ] Une taille ou une date de naissance invalide bloque l'enregistrement avec le message exact attendu.
- [ ] L'IMC utilise le poids le plus récent et la taille actuelle.
- [ ] La catégorie, la barre de référence de l'écran Progress et le libellé de zone de l'écran Profile n'apparaissent que pour un utilisateur d'au moins 20 ans.
- [ ] L'IMC compact de l'écran Profile se met à jour en direct depuis le formulaire.
- [ ] Le texte de prudence est visible avec la carte IMC de l'écran Progress.
- [ ] Les vibrations peuvent être désactivées.

### CSV

- [ ] Le fichier suit exactement le schéma `date,weight_kg`.
- [ ] Les dates, nombres, encodage, fins de ligne, tri et nom de fichier sont conformes.
- [ ] Le format reste identique sur un téléphone configuré en français.
- [ ] Le fichier contient toutes les mesures et aucune donnée dérivée ou de profil.
- [ ] La feuille de partage Android s'ouvre après génération.

### Expérience et accessibilité

- [ ] Les trois écrans correspondent à la direction visuelle approuvée.
- [ ] Les transitions respectent les durées et comportements définis.
- [ ] La réduction des animations est respectée.
- [ ] Les fonctions essentielles sont utilisables avec TalkBack et sans geste de glissement.
- [ ] L'application fonctionne sans connexion réseau, dès le premier lancement.

## 18. Risques et dépendances

| Risque | Réponse prévue |
|---|---|
| La balance web ne reproduit pas exactement la physique Android | Ajuster les constantes sur un appareil réel tout en conservant le comportement Balanced. |
| L'IMC est interprété comme un diagnostic | Limiter les catégories aux adultes et afficher le texte de prudence. |
| Une exportation échoue ou produit un fichier incomplet | Génération atomique, message d'erreur et absence de faux succès. |
| Le nom Mue est indisponible | Vérifier marques, boutiques et domaines avant la publication. |
| Le design personnalisé réduit l'accessibilité | Tester TalkBack, contrastes, zones tactiles et réduction des animations. |
| Un changement de téléphone fait perdre l'historique | Sauvegarde automatique désactivée par choix de confidentialité ; l'export CSV puis l'import de la V1.1 constituent le chemin de migration. |

## 19. Préparation de la publication

Avant une publication publique :

- vérifier la disponibilité juridique et commerciale du nom **Mue** ;
- préparer l'icône, les captures et la fiche de boutique ;
- préparer la politique de confidentialité ;
- effectuer les tests sur plusieurs tailles de téléphones Android ;
- valider la balance et les retours haptiques sur matériel réel ;
- vérifier les mentions liées à l'IMC.

## 20. Décisions techniques de la V1

Ces décisions sont arrêtées et n'ont pas à être rediscutées pendant l'implémentation. Elles complètent les choix du cadrage sans les remplacer.

### 20.1 Cible et identité

| Élément | Valeur | Motif |
|---|---|---|
| `applicationId` | `fr.kristenjestin.mue` | Figé définitivement dès la première publication. |
| `minSdk` | `26` — Android 8.0 | Accès natif à `java.time` sans désucrage. |
| `compileSdk` / `targetSdk` | `36` — Android 16 | Exigence du Play Store. |
| Orientation | Portrait uniquement | Conforme au périmètre de la V1. |

### 20.2 Stack

- Kotlin et Jetpack Compose, une seule `Activity`.
- Architecture MVVM : `ViewModel` exposant un état via `StateFlow`.
- Navigation Compose pour les trois onglets.
- **Room** pour les mesures de poids uniquement.
- **DataStore Preferences** pour le profil et les préférences ; quatre champs ne justifient pas une base.
- Injection de dépendances manuelle via un conteneur d'application. Hilt coûterait plus en temps de compilation qu'il n'apporterait sur trois écrans.

### 20.3 Contraintes de stockage

- `WeightEntry.date` en `TEXT` ISO `YYYY-MM-DD`, déclarée `PRIMARY KEY` : la contrainte d'unicité vit dans SQLite et non dans l'interface, comme l'exige la section 16.3. Le tri lexicographique équivaut au tri chronologique.
- `WeightEntry.weightCg` en `INTEGER`, exprimé en centièmes de kilogramme.
- Remplacement d'une mesure existante par une insertion en mode `REPLACE`, dans une transaction.

Le passage du dixième au centième de kilogramme modifie l'unité de la colonne et impose donc une migration Room versionnée, qui incrémente la version de la base et multiplie par dix les valeurs déjà stockées. La migration destructive est interdite : `fallbackToDestructiveMigration` ne doit pas être utilisée, sous aucune de ses variantes, car elle effacerait l'historique que la section 16.3 impose de conserver.

### 20.4 Fonctionnement hors ligne et confidentialité

- La typographie **Sora** est embarquée en ressources `.ttf`. Les Downloadable Fonts sont exclues : la typographie ne se chargerait pas au premier lancement sans réseau.
- **Aucune permission `INTERNET`** dans le manifeste. C'est la preuve technique et vérifiable du fonctionnement entièrement local, et un argument direct pour la fiche du Play Store.
  - **Portée : application de base seule.** [`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) §14.4 ajoute `INTERNET`, et [`PRD_FOOD.md`](./PRD_FOOD.md) §16.3 s'appuie dessus. La garantie ci-dessus vaut pour toute version livrée sans ces deux modules ; elle ne survit pas à leur fusion. La fiche Play Store et la politique de confidentialité doivent être réécrites au même moment, et non après.
- `android:allowBackup="false"` et règles d'extraction vides. La sauvegarde automatique enverrait les mesures sur Google Drive, ce que contredit la section 16.4. Conséquence assumée et documentée dans la section 18.

### 20.5 Comportements techniques

- Export généré sur un dispatcher d'entrées-sorties, écrit dans un fichier temporaire puis renommé de façon atomique dans le cache de l'application, partagé via `FileProvider` et `ACTION_SEND`.
- Réduction des animations détectée via `Settings.Global.ANIMATOR_DURATION_SCALE`.
- État de saisie conservé par `rememberSaveable` et `SavedStateHandle` afin de survivre à une rotation comme à une destruction du processus.
- Compilation de la version de production avec R8 et réduction des ressources.

### 20.6 Tests attendus

| Niveau | Périmètre |
|---|---|
| Unitaire, sans Android | Calcul de l'IMC et de ses catégories, rythme hebdomadaire, variation, validation des bornes, génération du CSV. |
| Intégration, base en mémoire | Unicité de la date, remplacement d'une mesure, suppression, migrations. |
| Interface Compose | Enregistrement d'une mesure, édition depuis l'historique, refus d'une valeur hors bornes. |

Les calculs de la première ligne sont entièrement déterministes et doivent être couverts avant toute mise au point visuelle.
