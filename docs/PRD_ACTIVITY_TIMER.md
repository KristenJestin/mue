# PRD — Mue Activity Timer

## 1. Informations du document

| Champ | Valeur |
|---|---|
| Produit | Mue |
| Module | Activity Timer |
| Version | V1 du minuteur |
| Statut | Validé pour le développement |
| Date | 24 août 2026 |
| Plateforme | Android natif, téléphone, portrait |
| Langue de l'application | Anglais uniquement |
| Dépendance | [`PRD_ACTIVITIES.md`](./PRD_ACTIVITIES.md) |
| Référence visuelle | [`start-activity`](../../proto/fusion/start-activity.html), [`active-activity`](../../proto/fusion/active-activity.html) |

Le module Activities est déjà spécifié et en cours d'implémentation. Le minuteur ne le réécrit pas : il s'y greffe. Les amendements qu'il impose au document et au code existants sont rassemblés en section 17 ; aucun autre changement du module Activities n'est implicite.

## 2. Résumé

Activity Timer permet de démarrer une activité au moment où elle commence, de la mettre en pause, de la reprendre et de signaler sa fin.

Le minuteur ne constitue pas un second système d'enregistrement. À la fin, il ouvre le formulaire `Log activity` déjà défini dans le module Activities et le préremplit avec l'activité, la date, l'heure de début et la durée exacte. L'utilisateur complète les informations que le téléphone ne connaît pas — par exemple distance, vitesse, inclinaison et calories affichées par un tapis — puis enregistre normalement la séance.

La V1 du minuteur reste locale et ne mesure ni GPS, ni pas, ni fréquence cardiaque. Elle chronomètre uniquement le temps actif.

## 3. Objectifs

- Démarrer une séance en quelques secondes.
- Calculer la durée sans demander à l'utilisateur de regarder l'heure.
- Continuer à représenter correctement la séance lorsque l'application passe en arrière-plan ou que l'écran s'éteint.
- Permettre une pause qui ne compte pas dans la durée active.
- Réutiliser intégralement le formulaire et le modèle `ActivitySession` existants.
- Éviter la perte d'une séance en cas de fermeture ou de destruction du processus Android.
- Garder la consommation de batterie minimale en l'absence de capteurs.

## 4. Hors périmètre

- GPS, tracé, vitesse ou distance mesurés par le téléphone.
- Comptage des pas et détection automatique de l'activité.
- Fréquence cardiaque, puissance, cadence ou capteurs corporels.
- Calcul des calories par Mue.
- Guidage audio, intervalles, tours et objectifs de durée.
- Minuteur de repos propre à la musculation.
- Démarrage automatique à partir d'une montre ou de Health Connect.
- Plusieurs minuteurs actifs simultanément.
- Affichage permanent sur Wear OS.

## 5. Principe d'intégration

Le flux retenu est :

`Activity` → `Start activity` → choix de l'activité → minuteur actif → `Finish` → formulaire prérempli → `Save activity`

Le minuteur crée un `TimedActivityDraft`, jamais directement une `ActivitySession`.

La séance définitive n'existe qu'après l'action `Save activity` du formulaire existant. L'enregistrement transforme alors le brouillon en `ActivitySession`, crée ses équipements et mesures, puis supprime le brouillon dans la même transaction.

## 6. Intégration à l'interface existante

### 6.1 Tableau de bord Activity

L'ordre vertical du tableau de bord devient :

1. le résumé hebdomadaire existant — titre éditorial, rythme, énergie estimée, barres des sept jours ;
2. les brouillons à réviser, lorsqu'il en existe ;
3. les deux actions ci-dessous ;
4. le raccourci `Start again`, lorsqu'il existe ;
5. les séances récentes et l'accès à l'historique complet.

| Action | Présentation | Rôle |
|---|---|---|
| `Start activity` | Action principale, pleine largeur, accent ambre | Démarrer une activité maintenant. |
| `Log past activity` | Action secondaire, discrète | Ouvrir le formulaire manuel existant. |

Ces deux actions remplacent l'unique action `Log activity` du module Activities, dont l'exigence FR-ACTIVITY-003 est amendée en conséquence.

Lorsque des activités ont déjà été chronométrées, afficher directement sous ces deux actions un raccourci `Start again` reprenant la dernière séance dont la source est `timer`, par exemple `Treadmill walk`.

`Start again` appartient uniquement au tableau de bord Activity. Il n'est pas répété sur l'écran de choix suivant.

### 6.2 Choix avant démarrage

- Réutiliser les cartes de préréglages de `Log activity`.
- Afficher directement les six choix principaux sous le titre, sans bloc `Last timed activity`, `Use again` ou autre rappel de la séance précédente.
- Rendre tous les choix visibles sans nécessiter de défilement horizontal caché.
- Permettre l'utilisation du constructeur `Other` avec son catalogue d'activité, son environnement et ses équipements.
- Ne demander avant le démarrage que les informations nécessaires pour identifier la séance.
- Ne pas demander distance, vitesse, énergie, effort ou note à ce stade.
- L'action finale est `Start timer`.

### 6.3 Écran du minuteur

Afficher :

- l'icône et le libellé de l'activité ;
- un chronomètre central au format `HH:MM:SS` ;
- l'heure de début ;
- l'état `Active` ou `Paused` ;
- l'action principale `Pause` ou `Resume` ;
- l'action distincte `Finish` ;
- une action secondaire `Discard timer` dans le menu de débordement.

L'écran utilise le fond sombre de Mue, un halo ambre lent lorsque le minuteur est actif et aucun halo lorsqu'il est en pause. La valeur du chronomètre reste stable visuellement grâce à des chiffres tabulaires.

### 6.4 Indicateur dans l'application

Lorsqu'un minuteur est actif ou en pause, afficher un bandeau compact au-dessus de la navigation principale sur les écrans `Entry`, `Progress`, `Activity` et `Profile`.

Le bandeau contient :

- l'icône de l'activité ;
- son libellé ;
- le temps écoulé ou `Paused` ;
- une action `Open` implicite sur toute sa surface.

Toucher le bandeau revient à l'écran du minuteur. Il n'est jamais possible de démarrer silencieusement un deuxième minuteur.

Le bandeau appartient au **châssis fixe** de l'application, au même titre que la barre d'onglets : il est placé hors du contenu animé par la navigation, de sorte qu'un changement d'onglet ne le fasse ni glisser ni disparaître. Son apparition et sa disparition sont les seuls moments où il bouge, en expansion verticale courte.

Ce bandeau sert également de support aux messages du minuteur définis en FR-TIMER-002 et FR-TIMER-010 : aucun d'eux n'utilise de boîte de dialogue.

### 6.5 Notification Android

Si les notifications sont autorisées, afficher une notification silencieuse `Activity in progress` avec :

- le libellé de l'activité ;
- un chronomètre système ;
- `Pause` ou `Resume` ;
- `Finish`.

Toucher la notification ouvre l'écran du minuteur. `Finish` ouvre Mue directement sur le formulaire prérempli.

Sur Android 13 et plus, demander `POST_NOTIFICATIONS` au premier démarrage d'un minuteur, après une courte explication contextuelle. Si la permission est refusée, le minuteur fonctionne tout de même et le bandeau interne reste disponible ; seule la notification est absente.

#### Redémarrage du téléphone

Android efface toutes les notifications au redémarrage. Un minuteur restauré depuis Room redeviendrait donc invisible hors de l'application, alors que la notification est précisément le moyen de le contrôler en arrière-plan.

La notification est par conséquent **repostée au démarrage du téléphone** par un receveur `BOOT_COMPLETED`, qui lit l'état persistant et ne fait rien lorsqu'aucun minuteur n'est `running` ou `paused`. Cela ajoute la permission `RECEIVE_BOOT_COMPLETED`, actée en section 17.

Le receveur ne démarre aucun service, ne fait aucun travail périodique et se contente de reconstruire une notification dont le contenu est entièrement dérivé de l'état stocké.

## 7. Exigences fonctionnelles

### FR-TIMER-001 — Démarrage

- Un seul minuteur peut exister dans l'état `running` ou `paused`.
- `Start timer` enregistre immédiatement le brouillon avant d'afficher le chronomètre.
- La date et l'heure de début correspondent à l'instant du démarrage.
- Le brouillon conserve le mouvement, l'environnement et les équipements choisis.
- Le chronomètre commence à `00:00:00`.
- Produire une vibration courte lorsque les vibrations sont activées dans Mue.

### FR-TIMER-002 — Minuteur déjà existant

Si l'utilisateur tente de démarrer une autre activité :

- ne pas créer de second brouillon actif ;
- ouvrir le minuteur existant ;
- annoncer `An activity is already in progress.` dans le bandeau du minuteur, sans boîte de dialogue ni interruption.

### FR-TIMER-003 — Calcul du temps

- Le temps actif comprend les périodes entre `Start` ou `Resume` et `Pause` ou `Finish`.
- Une période en pause ne compte jamais dans la durée.
- L'affichage se met à jour chaque seconde tant que l'écran du minuteur ou le bandeau est visible. Le rythme d'une seconde s'arrête lorsque l'application passe en arrière-plan et reprend au retour, la valeur étant alors recalculée et non rattrapée.
- La valeur persistée est une durée en secondes entières.
- Ne pas incrémenter une valeur en base de données toutes les secondes ; recalculer le temps depuis les instants persistés.
- Utiliser une horloge monotone pendant la vie du processus pour ne pas être affecté par un changement manuel de l'heure du téléphone.
- Conserver également les instants civils nécessaires à la restauration après destruction du processus ou redémarrage du téléphone.

##### Détection d'un redémarrage

La référence monotone `elapsedRealtime` repart de zéro au démarrage du téléphone : il faut donc savoir si un redémarrage a eu lieu avant de s'en servir.

- Persister, à chaque écriture du brouillon, **l'instant de démarrage du téléphone** dérivé par `currentTimeMillis − elapsedRealtime`.
- Au retour, recalculer cette valeur et la comparer à celle stockée. Un écart inférieur à `10 s` désigne le même démarrage : la référence monotone est valide et fait foi.
- Un écart supérieur signifie un redémarrage, ou un changement manuel de l'heure. La référence monotone est alors ignorée et le segment actif est mesuré entre les instants civils persistés.
- Cette comparaison est la seule autorisée pour trancher : aucune heuristique fondée sur la seule valeur d'`elapsedRealtime` ne doit être utilisée.

### FR-TIMER-004 — Pause et reprise

- `Pause` fige le temps actif et place le brouillon dans l'état `paused`.
- `Resume` démarre un nouveau segment actif sans modifier l'heure de début initiale.
- Les actions de l'écran et de la notification produisent exactement le même résultat.
- Afficher `Paused` à la place de l'animation active.

### FR-TIMER-005 — Fin

- `Finish` arrête définitivement le chronomètre et place le brouillon dans l'état `pending_review`.
- Ouvrir immédiatement le formulaire `Log activity` existant.
- Préremplir le mouvement, l'environnement, les équipements, la date de début, l'heure de début et la durée active exacte.
- Ne préremplir aucune mesure non observée par Mue.
- Pour une marche sur tapis, laisser distance, vitesse, énergie estimée et inclinaison disponibles mais non renseignées.
- Permettre de modifier toutes les valeurs préremplies avant l'enregistrement.
- Pour une séance de musculation, la révision propose le choix `Quick log` ou `Detailed log` du module Activities, avec la durée déjà connue. Le choix intervient à la révision et jamais avant le démarrage : chronométrer n'oblige pas à décider à l'avance du niveau de détail.
- L'heure de début prérenseignée est l'heure locale du démarrage **tronquée à la minute**, le modèle de séance ne stockant pas les secondes d'une heure de début. La durée, elle, garde ses secondes : une séance démarrée à `18:32:47` s'enregistre à `18:32` avec sa durée exacte.

### FR-TIMER-006 — Précision de la durée dans le formulaire

- Conserver la durée exacte à la seconde dans le brouillon et la séance définitive.
- Afficher un résumé comme `42 min 18 sec` dans le formulaire prérempli.
- Toucher ce résumé permet de corriger heures, minutes et secondes.
- La saisie manuelle d'une activité passée conserve son interface à la minute ; seules les séances issues du minuteur affichent et corrigent des secondes.
- Une séance chronométrée peut durer **moins d'une minute**. La borne basse d'une minute du module Activities est celle de la saisie manuelle, qui ne sait pas exprimer de secondes : elle ne s'applique pas ici. Un `Finish` déclenché après quelques secondes enregistre donc une séance valide plutôt que de perdre le brouillon.
- La borne haute de `99 h 59 min` reste commune aux deux modes de saisie.

### FR-TIMER-007 — Enregistrement final

- `Save activity` crée une seule `ActivitySession` à partir du brouillon.
- La séance créée porte `source = timer`, ce qui la distingue d'une saisie manuelle et alimente le raccourci `Start again`.
- L'opération crée également les équipements, mesures et éventuels détails de musculation saisis dans le formulaire.
- La création de la séance et la suppression du brouillon sont atomiques.
- Après succès, revenir au tableau de bord Activity et afficher la confirmation d'enregistrement du module Activities.

### FR-TIMER-008 — Formulaire quitté avant enregistrement

- Revenir en arrière depuis le formulaire ne supprime pas le brouillon `pending_review`.
- Afficher sur le tableau de bord une carte `Activity ready to review` portant le libellé de l'activité, sa date et sa durée.
- Toucher cette carte rouvre le formulaire avec toutes les valeurs déjà renseignées.
- Plusieurs activités peuvent attendre une validation ; elles sont présentées de la plus récente à la plus ancienne.
- **Au plus trois cartes sont affichées.** Au-delà, une ligne compacte `+2 more to review` déroule le reste sur place, sans ouvrir d'écran supplémentaire.
- Aucun brouillon n'est supprimé automatiquement, quelle que soit son ancienneté : Mue ne détruit jamais une durée mesurée sans action explicite de l'utilisateur.
- Il est possible de démarrer une nouvelle activité même lorsqu'un ancien brouillon est `pending_review` ; les brouillons à réviser restent distincts de l'unique minuteur actif.

### FR-TIMER-009 — Abandon

- `Discard timer` demande une confirmation explicite.
- Le message est `Discard this timer? The elapsed time will be lost.`.
- `Keep timer` ferme la confirmation sans changement.
- `Discard` supprime le brouillon et retire le bandeau et la notification.
- Un brouillon `pending_review` utilise la formulation `Discard this activity draft?`.

### FR-TIMER-010 — Restauration

- Restaurer un minuteur `running`, `paused` ou `pending_review` au démarrage de Mue.
- La destruction du processus ne remet jamais la durée à zéro.
- Après redémarrage du téléphone, restaurer le brouillon à partir des instants civils persistés, la référence monotone étant invalidée selon FR-TIMER-003.
- Une durée est jugée incohérente lorsqu'elle est **négative** ou lorsqu'elle dépasse la borne haute de `99 h 59 min`. Dans ce cas, placer le minuteur en pause sur la dernière durée valide connue et annoncer `Check activity time` dans le bandeau, sans jamais corriger la valeur silencieusement ni la remettre à zéro.
- L'utilisateur peut alors terminer la séance et corriger la durée dans le formulaire, ou abandonner le brouillon.

### FR-TIMER-011 — Changement de jour

- Une séance qui traverse minuit conserve la date et l'heure de son démarrage.
- Sa durée peut continuer sur le jour suivant.
- Les statistiques journalières attribuent la séance au jour de démarrage, comme les autres activités de Mue.

### FR-TIMER-012 — Permission de notification

- Demander `POST_NOTIFICATIONS` uniquement dans le contexte du premier démarrage de minuteur, sur Android 13 et plus.
- Le refus n'empêche ni le démarrage, ni la pause, ni la fin depuis l'application.
- Ne pas redemander automatiquement après un refus explicite.
- Le profil propose ensuite un lien `Open notification settings`.
- `RECEIVE_BOOT_COMPLETED` est une permission d'installation, sans demande à l'exécution. Elle ne sert qu'à reposter la notification d'un minuteur en cours après un redémarrage et n'a aucun effet lorsque aucun minuteur n'existe.

## 8. Modèle de données

### 8.1 TimedActivityDraft

| Champ | Type logique | Règle |
|---|---|---|
| `id` | UUID | Identifiant stable. |
| `status` | enum | `running`, `paused`, `pending_review`. |
| `movement` | enum | Même taxonomie que `ActivitySession`. |
| `customMovementName` | texte facultatif | Même règle que `ActivitySession`. |
| `environment` | enum | `indoor`, `outdoor`, `unknown`. |
| `startedAt` | instant | Instant initial, immuable. |
| `currentSegmentStartedAt` | instant facultatif | Renseigné uniquement lorsque le minuteur est actif. |
| `currentSegmentStartedElapsedRealtimeMs` | entier facultatif | Référence monotone Android du segment actif, valide tant que le téléphone n'a pas redémarré. |
| `bootReferenceMillis` | entier facultatif | Instant de démarrage du téléphone dérivé par `currentTimeMillis − elapsedRealtime`, réécrit à chaque mise à jour du brouillon. Sert uniquement à décider si la référence monotone est encore valide, selon FR-TIMER-003. |
| `accumulatedActiveSeconds` | entier positif ou nul | Somme des segments actifs déjà terminés. |
| `finishedAt` | instant facultatif | Renseigné lors de `Finish`. |
| `createdAt` | instant | Audit local. |
| `updatedAt` | instant | Audit local. |

Les équipements du brouillon utilisent une relation `TimedDraftEquipment` identique dans son principe à `SessionEquipment`.

### 8.2 Persistance du formulaire de révision

Ce que l'utilisateur saisit après `Finish` doit survivre à une fermeture de l'application, et plusieurs brouillons peuvent coexister : `SavedStateHandle` seul, tel qu'il sert la saisie manuelle, ne suffit donc pas. La solution retenue sépare deux natures d'information.

**Ce que le minuteur connaît reste en colonnes typées** — mouvement, nom personnalisé, environnement, équipements, instant de départ, durée active. Ce sont elles qui alimentent le bandeau, la notification et la carte de révision, et elles ne dépendent d'aucun formulaire.

**Ce que l'utilisateur saisit ensuite tient dans une seule colonne sérialisée** :

| Champ | Type logique | Règle |
|---|---|---|
| `reviewFormState` | texte facultatif | État du formulaire de révision, sérialisé ; contient les mesures, l'effort, la note et, le cas échéant, les exercices et séries. |
| `reviewFormSchemaVersion` | entier | Version du format ci-dessus, incrémentée à chaque changement de structure. |

Règles :

- l'état est réécrit à chaque changement significatif du formulaire, sans attendre l'enregistrement ;
- une version de schéma inconnue ou un contenu illisible n'est jamais une erreur bloquante : le formulaire se rouvre alors depuis les seules colonnes typées, et seule la saisie en cours est perdue — jamais la durée mesurée ni l'activité ;
- aucune de ces données ne crée partiellement une `ActivitySession` : la séance n'existe qu'après `Save activity` ;
- **aucune table miroir** du modèle de séance n'est créée. Dupliquer les cinq tables du module Activities pour un état transitoire obligerait à doubler chacune de leurs migrations futures, pour un brouillon qui vit quelques minutes à quelques jours.

`SavedStateHandle` conserve son rôle pour la rotation et la mort du processus pendant que le formulaire est à l'écran ; la source de vérité d'un brouillon `pending_review` reste la base.

### 8.3 Calcul

Lorsque le statut est `running` :

`elapsed = accumulatedActiveSeconds + currentActiveSegmentDuration`

`currentActiveSegmentDuration` utilise en priorité la différence entre les références `elapsedRealtime`, après avoir vérifié par `bootReferenceMillis` que le téléphone n'a pas redémarré depuis la dernière écriture. Lorsque cette vérification échoue, le calcul retombe sur la différence entre les instants civils persistés.

Lorsque le statut est `paused` ou `pending_review` :

`elapsed = accumulatedActiveSeconds`

Une pause ou une fin ajoute d'abord le segment courant à `accumulatedActiveSeconds`, puis efface `currentSegmentStartedAt` dans la même transaction.

## 9. Architecture Android recommandée

- Room pour persister le brouillon et ses équipements, par une **migration additive** portant la base de la version `3` à la version `4`, sans toucher aux tables existantes. La valeur `timer` de l'énumération `source` relève de la même migration : elle ne change aucun schéma, seule une valeur nouvelle apparaît en écriture.
- `fallbackToDestructiveMigration` reste interdit et le schéma reste exporté, comme l'exige le module Activities.
- Un repository unique comme source de vérité du minuteur.
- Une horloge injectée pour tester les changements de temps sans attendre réellement.
- Un flux observable partagé par l'écran du minuteur, le bandeau global et la notification.
- Un `BroadcastReceiver` ou des `PendingIntent` immuables pour les actions de notification.
- Une notification utilisant le chronomètre système afin de ne pas exécuter une mise à jour chaque seconde en arrière-plan.
- Aucun `AlarmManager` exact : la fonctionnalité n'a pas d'échéance future à déclencher.
- Aucun suivi capteur, GPS ou calcul permanent dans la V1.

### 9.1 Service de premier plan

La V1 ne doit pas ajouter un service de premier plan uniquement pour faire progresser un nombre. Le temps est dérivé des instants persistés et le système peut afficher le chronomètre de notification.

Si une future version mesure en continu la localisation ou des capteurs pendant l'activité, elle devra alors adopter le type de service de premier plan Android correspondant et demander les permissions associées. Cette décision sera recadrée avec la fonctionnalité de mesure concernée.

## 10. Notification et batterie

- Canal : `Ongoing activity`.
- Importance faible, sans son ni vibration à chaque mise à jour.
- Une seule notification, mise à jour lors de `Start`, `Pause`, `Resume` et `Finish`, pas chaque seconde.
- Le chronomètre visible est rendu par Android à partir de son instant de référence.
- `Finish` annule la notification active avant d'ouvrir le formulaire.
- `Discard` annule immédiatement la notification.
- Aucun réveil périodique du processeur n'est requis.

## 11. Mouvement et accessibilité

- Transition vers le minuteur : expansion de la carte d'activité en `240–300 ms`.
- Halo actif lent, sans pulsation agressive.
- Pause : le halo s'éteint et le bouton change en fondu court.
- Finish : compression du bouton, vibration courte, puis transition vers le formulaire.
- Respecter la réduction des animations Android.
- Les actions `Pause`, `Resume` et `Finish` utilisent des libellés textuels en plus des icônes.
- Zone tactile minimale de 48 dp.
- TalkBack annonce l'état et la durée sans annoncer chaque seconde automatiquement.
- L'état `Active` ou `Paused` ne dépend pas seulement de la couleur.

## 12. États limites

| Situation | Comportement |
|---|---|
| Application envoyée en arrière-plan | Le brouillon reste actif ; le temps est recalculé au retour. |
| Écran éteint | Aucun travail périodique nécessaire ; le temps reste dérivable. |
| Processus détruit | Restauration depuis Room. |
| Téléphone redémarré | Redémarrage détecté par comparaison de la référence de démarrage ; durée recalculée depuis les instants civils et notification repostée. |
| Notifications refusées | Minuteur fonctionnel dans Mue, sans notification. |
| Changement manuel de l'heure | Horloge monotone tant que possible ; sinon mise en pause en cas d'incohérence. |
| Pause prolongée | Aucune limite automatique ; l'utilisateur reprend, termine ou abandonne. |
| Finish après minuit | Date de séance égale à la date de démarrage. |
| Appui répété sur Finish | Opération idempotente ; aucun second brouillon n'est créé pour la même séance. |
| Échec lors de Save activity | Le brouillon et les données du formulaire sont conservés. |

## 13. Critères d'acceptation

- [ ] `Start activity` et `Log past activity` sont accessibles depuis le tableau de bord Activity, dans l'ordre de la section 6.1.
- [ ] Une marche sur tapis peut être démarrée en sélectionnant son préréglage puis `Start timer`.
- [ ] Le minuteur affiche heures, minutes et secondes.
- [ ] Pause et reprise excluent correctement le temps en pause.
- [ ] Le minuteur reste exact après mise en arrière-plan et extinction de l'écran.
- [ ] Le minuteur reste exact après un redémarrage du téléphone, et sa notification réapparaît.
- [ ] L'application refuse un deuxième minuteur actif et l'annonce dans le bandeau.
- [ ] Le bandeau global permet de retrouver le minuteur depuis chaque onglet principal, sans décaler la barre d'onglets.
- [ ] La notification affiche le temps et les actions lorsque la permission est accordée.
- [ ] Le refus de notification ne bloque pas la fonctionnalité.
- [ ] `Finish` ouvre le formulaire existant avec activité, environnement, équipements, date, heure et durée préremplis.
- [ ] Une séance de musculation chronométrée propose `Quick log` et `Detailed log` à la révision.
- [ ] Les métriques non mesurées restent vides.
- [ ] La durée exacte à la seconde est conservée après l'enregistrement, y compris sous une minute.
- [ ] L'heure de début enregistrée est celle du démarrage, tronquée à la minute.
- [ ] Quitter le formulaire conserve un brouillon à réviser, y compris après fermeture complète de l'application.
- [ ] Au-delà de trois brouillons, les suivants restent accessibles sans occuper le tableau de bord.
- [ ] Sauvegarder crée une seule `ActivitySession`, portant `source = timer`, et supprime atomiquement le brouillon.
- [ ] Détruire puis recréer le processus ne remet pas le minuteur à zéro.
- [ ] Abandonner exige une confirmation et retire tous les indicateurs du minuteur.
- [ ] La migration vers la version `4` de la base ne perd ni mesure de poids ni séance existante.
- [ ] Les animations et annonces respectent les préférences d'accessibilité.

## 14. Tests prioritaires

- Start → Finish sans pause.
- Start → Pause → Resume → Finish.
- Plusieurs cycles de pause et reprise.
- Passage en arrière-plan pendant une période active.
- Destruction du processus pendant une période active et pendant une pause.
- Redémarrage du téléphone pendant une période active, puis pendant une pause : durée exacte et notification repostée.
- Détection du redémarrage, en simulant une référence de démarrage différente par l'horloge injectée.
- Changement manuel de l'heure du téléphone pendant une période active.
- Refus de `POST_NOTIFICATIONS`.
- Finish depuis la notification.
- Finish exactement autour de minuit.
- Finish moins d'une minute après le démarrage.
- Retour arrière depuis le formulaire prérempli, puis fermeture complète de l'application.
- Réouverture d'un brouillon dont l'état de formulaire sérialisé est illisible ou d'une version inconnue.
- Échec Room pendant la conversion du brouillon.
- Appuis rapides et répétés sur Pause, Resume ou Finish.

## 15. Références Android

- [Permission de notification sur Android 13 et plus](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Chronomètre dans les notifications Android](https://developer.android.com/reference/android/app/Notification.Builder#setUsesChronometer(boolean))
- [Recommandations Android pour les opérations temporelles et les alarmes](https://developer.android.com/develop/background-work/services/alarms)
- [Types de services de premier plan Android](https://developer.android.com/develop/background-work/services/fgs/service-types)

## 16. Décisions arrêtées

Ces choix sont tranchés et n'ont pas à être rediscutés pendant l'implémentation.

| Question | Décision |
|---|---|
| Une pause doit-elle être exclue de la durée ? | Oui, la durée enregistrée est le temps actif. |
| `Finish` doit-il sauvegarder immédiatement ? | Non, il ouvre toujours le formulaire prérempli pour vérification. |
| Peut-on lancer plusieurs minuteurs ? | Non, un seul minuteur `running` ou `paused` à la fois. |
| `Start again` doit-il démarrer immédiatement ? | Non, il ouvre d'abord l'écran prérempli avec `Start timer` pour éviter un démarrage accidentel. |
| Faut-il conserver les secondes ? | Oui pour une séance chronométrée, même si la saisie manuelle reste à la minute. |
| Que faire après une musculation chronométrée ? | Ouvrir le même choix `Quick log` ou `Detailed log` lors de la révision. |
| Peut-on commencer une nouvelle séance avec un brouillon non validé ? | Oui ; plusieurs brouillons `pending_review` sont possibles, mais un seul minuteur actif. |
| La notification est-elle obligatoire ? | Non ; elle améliore le contrôle en arrière-plan mais son refus ne bloque pas le minuteur. |

### Risque technique à lever

Le prototype suppose qu'une notification chronométrée standard, associée à un état Room persistant, suffit tant qu'aucun capteur ou GPS n'est suivi. Cette approche doit être testée sur les versions Android et constructeurs ciblés, notamment après extinction de l'écran, destruction du processus et refus de notification.

Si sa fiabilité s'avère insuffisante, l'alternative est un service de premier plan de type `health`. Cette solution ajoute cependant des permissions Android, une déclaration Google Play et davantage de complexité ; elle ne doit donc être retenue qu'après ce test.

## 17. Amendements au module Activities

Le minuteur modifie les points suivants du module Activities. Cette liste est limitative : tout le reste du module demeure inchangé.

| Élément | Amendement |
|---|---|
| Hors périmètre, section 5 | Le chronométrage sort du hors-périmètre et renvoie au présent document. |
| `ActivitySession.durationSeconds`, section 8.2 | La durée n'est plus nécessairement un multiple de `60`. Le domaine accepte de `1` seconde à `99 h 59 min` ; seule la saisie manuelle reste bornée à la minute. |
| `ActivitySession.source`, section 8.2 | L'énumération gagne la valeur `timer`, écrite par FR-TIMER-007. |
| FR-ACTIVITY-003 | L'unique action `Log activity` devient `Start activity` et `Log past activity`, et l'ordre vertical du tableau de bord est fixé. |
| FR-ACTIVITY-005 | La borne basse d'une minute est explicitement celle de la saisie manuelle. |
| Section 14.3 | Une séance issue du minuteur affiche ses secondes, par exception à la saisie en heures et minutes. |
| Section 16.4 | La révision d'une séance chronométrée ajoute une persistance en base au mécanisme `SavedStateHandle`. |

### 17.1 Permissions Android

Le minuteur introduit les deux premières permissions de Mue :

| Permission | Nature | Motif |
|---|---|---|
| `POST_NOTIFICATIONS` | Demandée à l'exécution, Android 13 et plus | Notification de séance en cours. Le refus n'empêche rien d'autre que la notification. |
| `RECEIVE_BOOT_COMPLETED` | Accordée à l'installation | Reposter la notification d'un minuteur en cours après un redémarrage. |

- L'absence de permission `INTERNET` reste intacte **pour ce module** : le minuteur n'ouvre aucun accès réseau. Elle cesse de l'être dès la fusion du module serveur ([`PRD_SERVER_SYNC_MCP.md`](./PRD_SERVER_SYNC_MCP.md) §14.4), dont le module alimentaire dépend.
- Aucune de ces deux permissions ne donne accès à une donnée personnelle, à un capteur ou au réseau.
- La fiche Play Store et la politique de confidentialité doivent néanmoins être relues avant publication, puisqu'elles annonçaient jusqu'ici une application sans aucune permission.

### 17.2 Impact sur le code existant

Le module Activities étant déjà implémenté, les amendements ci-dessus touchent du code écrit :

- les bornes de durée et le champ de saisie de la durée, qui ne connaissent aujourd'hui que les heures et les minutes ;
- l'énumération de source persistée ;
- le tableau de bord et son unique action d'enregistrement ;
- la version de la base, portée de `3` à `4`.

Aucun de ces changements n'est destructif, et aucun ne demande de réécrire une séance déjà enregistrée.

## 18. Prototypes et divergences assumées

Les deux prototypes cités en section 1 fixent la hiérarchie, l'ambiance et les interactions du minuteur. Ils ne constituent ni du code de production ni une mesure exacte des dimensions Android. Partout où ils divergent du présent document, c'est le présent document qui fait autorité.

Les divergences assumées sont les suivantes :

- l'état du minuteur s'annonce par les mots `Active` et `Paused`, là où le prototype écrit `Active time` au-dessus du chronomètre ;
- l'heure de début se lit `Started at 18:32`, sans secondes, conformément à la troncature de FR-TIMER-005 ;
- le bandeau global, la carte `Activity ready to review` et la ligne `+N more to review` ne figurent dans aucun prototype et sont à concevoir pendant l'implémentation, dans le respect du design system ;
- la confirmation d'enregistrement est celle du module Activities, et non une formulation propre au minuteur.
