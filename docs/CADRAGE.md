# Cadrage du projet — Mue

## Statut du document

Ce document rassemble les besoins et choix validés à ce stade, ainsi que les points explicitement laissés ouverts. Les prototypes servent de référence pour la direction visuelle et fonctionnelle, mais ne constituent pas du code de production. Le développement de l'application Android n'a pas encore commencé.

## Nom validé

- Nom de l'application : **Mue**.
- Le nom évoque une transformation progressive et personnelle sans limiter le produit au seul poids.
- Sa disponibilité juridique, sur les boutiques d'applications et comme nom de domaine devra être vérifiée avant publication.

## Objectif du produit

Créer une application personnelle permettant de suivre une perte de poids.

La première phase porte uniquement sur la saisie et la conservation du poids. Le suivi de l'alimentation, des calories et d'autres données pourra être étudié dans une phase ultérieure, mais ne fait pas partie du premier périmètre.

## Plateforme cible

- Application Android uniquement.
- Aucun besoin iOS ou multiplateforme n'est prévu actuellement.
- La première version cible uniquement les téléphones.
- L'interface est conçue pour une utilisation en orientation portrait.
- La première version est proposée uniquement en anglais.

## Première phase : suivi du poids

### Écran principal de saisie

L'application doit proposer une page simple consacrée à la saisie du poids, avec deux modes de saisie.

Le prototype visuel final de l'écran `Entry` — thème sombre, accent ambre, typographie Sora, balance tactile, date et action d'enregistrement — est validé comme référence de conception.

#### Mode principal : balance tactile

- Présenter une interface évoquant une vraie balance.
- Afficher clairement le poids sélectionné.
- Afficher une règle horizontale graduée avec un repère central fixe.
- Permettre de faire défiler la règle vers la gauche ou la droite avec le doigt.
- Mettre à jour le poids en fonction du déplacement de la règle.
- Ce mode constitue le moyen de saisie principal.

La référence visuelle initiale montre une grande valeur de poids au-dessus d'une règle graduée qui défile sous un curseur central.

#### Mode secondaire : saisie manuelle

- Permettre de basculer vers une saisie au clavier.
- Ouvrir le clavier numérique Android.
- Permettre à l'utilisateur de taper directement son poids.
- Ouvrir la saisie manuelle lorsque l'utilisateur touche la grande valeur du poids.
- Accepter la virgule et le point comme séparateurs décimaux.
- Afficher le séparateur correspondant à la langue du téléphone, notamment la virgule en français.
- Arrondir la valeur au dixième.
- Refuser les valeurs inférieures à 30 kg ou supérieures à 250 kg avec un message compréhensible.
- Proposer une action `Terminé` sur le clavier pour revenir à la balance.

### Date associée à la saisie

- La date est facultative lors de la saisie.
- En l'absence de choix explicite, la date du jour est utilisée.
- L'utilisateur doit pouvoir modifier cette date.

### Règle d'enregistrement

Il ne peut exister qu'une seule saisie de poids par date.

- Si aucune saisie n'existe pour la date choisie, une nouvelle entrée est ajoutée.
- Si une saisie existe déjà pour cette date, elle est remplacée directement.
- Le remplacement ne demande ni alerte ni confirmation.

## Historique et graphiques

La première version doit inclure une page distincte présentant l'évolution du poids :

- graphique simple de l'évolution ;
- historique des mesures ;
- aucune alimentation, calorie, synchronisation ou compte utilisateur dans cette première version.

Le prototype visuel final de l'écran `Progress` — graphique, sélecteur de période, indicateurs et historique — est validé comme référence de conception.

Le graphique doit proposer :

- les périodes `7 jours`, `30 jours`, `3 mois` et `Tout` ;
- le poids actuel ;
- la variation sur la période sélectionnée ;
- le rythme moyen par semaine ;
- l'IMC actuel lorsque la taille est renseignée ;
- l'affichage de la date et du poids lorsqu'un point est sélectionné ;
- une liste des dernières mesures sous le graphique.

### Gestion d'une ancienne mesure

- Toucher une mesure de l'historique permet de l'ouvrir.
- L'utilisateur peut modifier son poids ou sa date.
- Si la nouvelle date contient déjà une mesure, celle-ci est remplacée silencieusement.
- L'utilisateur peut supprimer une ancienne mesure.
- Une confirmation est demandée uniquement avant une suppression.
- L'édition s'effectue dans un panneau remontant du bas plutôt que sur une nouvelle page.
- Le panneau contient la date, le poids et les actions `Save changes` et `Delete measurement`.

## Valeurs et comportement validés

### Format du poids

- Unité : kilogrammes uniquement.
- Pas de saisie : 0,1 kg.
- Affichage : une décimale.
- Valeur minimale : 30 kg.
- Valeur maximale : 250 kg.

### Comportement initial de la balance

- Ouvrir la balance sur le dernier poids enregistré.
- En l'absence de mesure existante, utiliser 70 kg comme valeur initiale.
- Utiliser une inertie légère lors du glissement.
- Retenir le comportement `Balanced` du prototype : une courte glisse naturelle après le relâchement, suivie d'un arrêt précis sur la graduation la plus proche.
- Les constantes physiques exactes seront ajustées sur un téléphone Android réel par l'équipe de développement, sans nécessiter une nouvelle décision produit tant que la sensation reste conforme à ce comportement.
- Arrêter automatiquement la règle sur la graduation valide la plus proche.
- Prévoir un retour haptique tous les 0,5 kg, et non à chaque dixième.
- Utiliser les kilogrammes entiers comme graduations principales.
- Confirmer le sens exact du geste pendant les essais du prototype.

### Validation d'une mesure

- Utiliser un bouton explicite `Enregistrer`.
- Afficher une confirmation visuelle courte après l'enregistrement.
- Rester sur l'écran de saisie après l'enregistrement.
- Conserver le remplacement silencieux lorsqu'une mesure existe déjà à la même date.

## Choix techniques validés

- Langage : Kotlin.
- Interface : Jetpack Compose.
- Type d'application : Android natif.
- Stockage local structuré : Room, au-dessus de SQLite.
- L'application doit pouvoir fonctionner localement, sans imposer de serveur pour la première phase.

Jetpack Compose est retenu comme moteur d'interface, mais son apparence Material par défaut ne constitue pas la direction graphique du produit.

## Direction visuelle et animations

L'application ne doit pas ressembler à une interface Material générique ou à un assemblage de composants Android par défaut.

- Créer une identité visuelle propre à l'application.
- Concevoir un design simple, moderne et soigné.
- Utiliser un design system personnalisé.
- Les fondations techniques de Material peuvent être utilisées si elles sont utiles, à condition que le résultat visible ne conserve pas son apparence générique.
- Accorder une attention particulière à la typographie, aux espacements, aux couleurs et aux composants personnalisés.
- Prévoir des animations fluides et soignées pour les interactions et les transitions entre les écrans.
- L'animation doit renforcer la compréhension et la sensation de qualité, sans devenir décorative ou envahissante.
- La première version utilise uniquement un thème sombre.

Le contrôle de la balance doit donner une sensation tactile et naturelle. Son comportement produit est défini ci-dessous ; seuls ses paramètres physiques fins seront ajustés sur un téléphone Android réel pendant l'implémentation.

### Direction retenue après les premières variantes

- Reprendre la simplicité, les messages et la hiérarchie de la piste `Aube`.
- Utiliser une interface sombre inspirée de la piste `Nuit`.
- Ne conserver ni la palette chaude et verte d'`Aube`, ni l'accent vert acide de `Nuit`.
- Utiliser l'ambre comme couleur d'accent principale (`#EFB45F` dans le prototype, valeur exacte ajustable lors de la conception finale).
- Utiliser **Sora** comme typographie principale de l'application.

### Transitions entre les onglets

- La barre de navigation inférieure reste immobile.
- Le contenu glisse légèrement vers la gauche lors du passage vers l'onglet suivant, avec un fondu simultané.
- Le mouvement est inversé lors du retour vers un onglet précédent.
- Durée cible : environ 220 ms.
- Aucun rebond n'est utilisé pour la navigation.

### Animation de la balance

- Le poids suit immédiatement le doigt pendant le geste.
- Après le relâchement, appliquer l'inertie `Balanced`, puis un arrêt magnétique sur le dixième le plus proche.
- Produire un retour haptique léger tous les 0,5 kg.
- Faire défiler très légèrement les chiffres verticalement lors d'un changement de valeur.
- Interrompre immédiatement l'animation des chiffres si un nouveau geste commence.
- La balance est le seul élément utilisant une animation physique marquée.

### Passage en saisie manuelle

- Toucher la grande valeur du poids atténue et descend légèrement la balance.
- Transformer visuellement la valeur en champ de saisie pendant l'ouverture du clavier Android.
- Utiliser une durée cible d'environ 180 ms.
- Jouer le mouvement inverse lors de la fermeture du clavier.

### Animation d'enregistrement

- Contracter légèrement le bouton au toucher.
- Remplacer temporairement son texte par `Saved ✓`.
- Produire une petite vibration de confirmation.
- Ajouter une lumière ambre très discrète sur le curseur central.
- Conserver la confirmation environ une seconde.
- Ne pas changer de page après l'enregistrement.

### Sélecteur de date

- Ouvrir le calendrier dans un panneau remontant du bas.
- Assombrir légèrement l'écran situé derrière le panneau.
- Utiliser une durée cible d'environ 220 ms.
- Permettre la fermeture par glissement vers le bas ou après validation.

### Animation du graphique

- Conserver la structure du graphique lors d'un changement de période.
- Transformer progressivement la courbe vers ses nouvelles positions.
- Animer simultanément les valeurs numériques associées.
- Utiliser une durée cible d'environ 280 ms.
- Éviter tout clignotement ou redessin brutal de la courbe.

### Animation du profil

- Faire évoluer progressivement la valeur d'IMC lorsque la taille change.
- Déplacer le repère de la barre d'IMC en environ 250 ms.
- Remplacer temporairement le texte du bouton par `Profile saved ✓` après validation.

### Réduction des animations

Lorsque le réglage Android de réduction des animations est actif :

- remplacer les glissements par un fondu simple d'environ 100 ms ;
- désactiver le défilement animé des chiffres ;
- conserver le magnétisme de la balance, car il remplit une fonction d'aide à la saisie.

## Navigation validée

La navigation principale utilise trois onglets permanents en bas de l'écran :

- `Saisie` ;
- `Évolution` ;
- `Profil`.

## Profil santé validé

La page `Profil` fait partie de la première version avec les informations suivantes :

- date de naissance facultative, permettant de déterminer l'âge ;
- taille en centimètres ;
- IMC calculé à partir de la taille et de la dernière mesure de poids ;
- affichage d'un niveau ou d'une zone d'IMC accompagné d'un contexte compréhensible.

L'IMC doit être présenté comme un indicateur général et non comme un diagnostic médical. La date de naissance n'est pas nécessaire à son calcul chez un adulte, mais pourra contextualiser de futurs indicateurs.

Le prototype visuel final de l'écran `Profile` — thème sombre, accent ambre, typographie Sora, formulaire et présentation de l'IMC — est validé comme référence de conception.

### Règles d'affichage de l'IMC

- Calculer la valeur numérique dès qu'une taille et au moins une mesure de poids sont disponibles.
- Afficher une catégorie uniquement si la date de naissance confirme que l'utilisateur a au moins 20 ans.
- Si la date de naissance n'est pas renseignée, afficher la valeur numérique sans catégorie.
- Pour un utilisateur de moins de 20 ans, afficher la valeur numérique sans catégorie dans la V1.
- Ne pas demander le sexe dans la V1.
- Utiliser les catégories adultes standard :
  - moins de 18,5 : `Underweight` ;
  - de 18,5 à 24,9 : `Healthy weight` ;
  - de 25,0 à 29,9 : `Overweight` ;
  - 30,0 ou plus : `Obesity`.
- Accompagner systématiquement la catégorie d'un texte indiquant que l'IMC est un indicateur de dépistage général et non un diagnostic.
- Références retenues : [OMS](https://www.who.int/docs/default-source/nutritionlibrary/events/9789241505529-eng.pdf) et [CDC](https://www.cdc.gov/bmi/faq/).

## Accessibilité validée

- Permettre de désactiver les retours haptiques.
- Respecter le réglage Android de réduction des animations.
- Proposer une solution accessible complémentaire à la balance, notamment des contrôles `−` et `+`.
- Maintenir des contrastes lisibles entre le fond sombre, l'ambre et les textes.

## Export et restauration des données

- Un export CSV des mesures fait partie de la V1.
- La V1 ne propose pas encore de restauration ou d'import.
- La V1.1 doit permettre de restaurer ou d'importer les données à partir d'un fichier CSV précédemment exporté.
- Le fichier contient uniquement l'historique des poids. La taille, la date de naissance, l'IMC et les statistiques dérivées ne sont pas exportés.

### Format CSV validé

- Nom du fichier : `mue-weight-YYYY-MM-DD.csv`, avec la date de l'export.
- Encodage : UTF-8.
- Séparateur de colonnes : virgule.
- Séparateur décimal : point, indépendamment de la langue ou du téléphone.
- Format des dates : ISO `YYYY-MM-DD`.
- Ordre : de la mesure la plus ancienne à la plus récente.
- En-tête exact : `date,weight_kg`.

Exemple :

```csv
date,weight_kg
2026-08-12,74.8
2026-08-18,74.9
2026-08-23,74.5
```

### Emplacement de l'export

- Ajouter une section `Your data` dans l'écran `Profile`.
- La V1 propose l'action `Export weight data`.
- La V1.1 ajoute l'action `Import weight data`.
- L'export ouvre la feuille de partage Android pour enregistrer ou transmettre le fichier.

### Import prévu pour la V1.1

- Valider l'intégralité du fichier avant toute écriture.
- Afficher avant confirmation le nombre total de mesures, le nombre de nouvelles dates et le nombre de dates existantes qui seront remplacées.
- Effectuer l'import en une seule opération atomique après confirmation globale.
- Si une ligne est invalide, ne rien importer et indiquer précisément la ligne concernée.
- Si une date apparaît plusieurs fois dans le fichier, la dernière ligne gagne.
- Si une date existe déjà dans l'application, la valeur importée remplace l'ancienne après la confirmation globale.

## Évolutions futures

- Le suivi de l'alimentation et des calories est confirmé comme évolution future, hors V1.
- Son périmètre fonctionnel sera défini séparément.

## État du cadrage

Les décisions produit nécessaires au développement de la V1 sont validées. Les ajustements techniques mineurs pourront être réalisés pendant l'implémentation tant qu'ils respectent les comportements, l'apparence et les règles définis dans ce document.

Avant une publication publique, il restera notamment à vérifier la disponibilité juridique et commerciale du nom **Mue**, puis à préparer les éléments de boutique et la politique de confidentialité.
