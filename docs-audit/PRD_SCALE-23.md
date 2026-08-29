# Audit de `PRD_SCALE.md` §23 — Critères d'acceptation de la V1

Relecture case par case du §23, sur `chore/prd23-audit` (worktree `flow`), au commit
`206a602`. Le §22 (serveur, synchronisation, MCP) étant implémenté, les cases qui le
concernent sont jugées sur du code exécutable et non sur une intention.

Trois questions distinctes par case, jamais confondues :

1. **le code fait-il ce que la case demande ?** — fichier et ligne ;
2. **un test le couvre-t-il ?** — nommé ;
3. **ce test prouve-t-il la règle ?** — un test qui passerait encore si la règle était
   inversée ne couvre rien. Les tests cités ont été **lus**, pas déduits de leur nom, et
   les quatre tests ajoutés par cette relecture ont été vérifiés par mutation : la règle
   inversée dans le code de production, chacun devient rouge et lui seul.

Vocabulaire des verdicts : **couvert** / **couvert sans test** / **test insuffisant** /
**non couvert**. Le verdict décrit l'état **après** les corrections de cette relecture ;
les quatre cases qui ne l'étaient pas avant sont signalées par « ⟲ corrigé ici ».

Chemins relatifs à `apps/android/app/src/` sauf mention contraire.

---

## Sans balance

### 1. « L'écran `Entry` est pixel pour pixel celui d'avant le module lorsqu'aucune balance n'est associée. » — **couvert**

**Code.** Tout ce que le module peut dessiner sur `Entry` est derrière une garde
`paired` : `main/.../ui/entry/EntryScreen.kt:790` (`ScaleNote` — « not an invisible box,
not a zero-height spacer, nothing »), `:879` (`SaveBlockedReason`), `:915`
(`EntryHeaderChips` retourne sans émettre quand la pastille est nulle et que la date est
aujourd'hui). La pastille elle-même est nulle sans balance :
`main/.../ui/entry/EntryScaleUiState.kt:112`. L'état par défaut est
`EntryScaleUiState.ABSENT` (`:174`), qui est aussi le défaut de `EntryUiState`.

**Tests.** JVM : `EntryScaleTest.sans balance enregistrée l'écran n'a rien de plus`
(`test/.../ui/entry/EntryScaleTest.kt:110`) et `oublier la dernière balance ramène l'écran
à celui du PRD socle` (`:138`). Instrumenté :
`EntryScaleScreenTest.without_a_paired_scale_the_screen_adds_nothing_at_all`
(`androidTest/.../ui/entry/EntryScaleScreenTest.kt:151`) et
`the_step_controls_are_there_with_or_without_a_scale` (`:177`).

**Ces tests prouvent-ils la règle ?** Oui, et par une assertion structurelle plutôt qu'un
inventaire : `assertEquals(EntryScaleUiState.ABSENT, state.scale)` échoue dès qu'un
**futur** champ pose un badge, là où six booléens à `false` auraient laissé passer le
septième. Le test instrumenté nie les cinq poignées du module puis affirme que l'écran
socle est intact et ses trois contrôles actifs. Le second test JVM porte la trace du défaut
signalé par la relecture précédente et **il est réparé** : il part maintenant d'un état
`Searching` réellement affiché, de sorte que le retour à `Absent` est une vraie émission ;
la version qui réémettait `Absent` sur `Absent` comparait l'état initial à lui-même et un
`StateFlow` supprimait l'émission.

**Réserve.** Aucun test ne compare des pixels : « pixel pour pixel » est prouvé comme
« aucun nœud ajouté et l'état est structurellement le défaut », ce qui est la meilleure
approximation atteignable sans capture d'écran de référence.

### 2. « Aucune permission Bluetooth n'est demandée tant qu'aucun appairage n'est tenté. » — **couvert**

**Code.** `rememberScalePermissions()` (`main/.../ui/scale/ScalePermissions.kt:206`) est
entièrement **passif** : il lit `PackageManager`, l'état de la radio et
`shouldShowRequestPermissionRationale`, et n'ouvre rien. Le seul chemin qui lance le
dialogue est `ScalePermissionsState.request()` (`:181`) → `onRequest` (`:268`) →
`launcher.launch`. Ses trois appelants sont `ScaleScanScreen.kt:88`, `ScalesScreen.kt:102`
et rien d'autre ; `rememberScalePermissions()` n'est appelé que depuis les trois écrans
`Scales` (`ScaleScanScreen.kt:60`, `ScalesScreen.kt:76`, `ScaleDetailScreen.kt:63`).
`EntryScreen` n'importe que l'objet `ScalePermissions` pour construire des `Intent`
(`EntryScreen.kt:180-183`) et n'appelle jamais le composable. Vérifié : trois
`rememberLauncherForActivityResult` dans tout `src/main`, pour la notification du minuteur,
la caméra alimentaire et cette permission-ci.

**Tests.** `ScaleScanScreenTest.theFirstPairingIsWhereTheBluetoothPermissionIsAskedFor`
(`androidTest/.../ui/scale/ScaleScanScreenTest.kt:178`) et
`ScalesScreenTest.aRevokedPermissionIsExplainedAndAsksForNothingOnItsOwn`
(`androidTest/.../ui/scale/ScalesScreenTest.kt:188`).

**Ces tests prouvent-ils la règle ?** Oui, par un **compteur** et non par une absence de
plantage : `assertEquals(0, permissionRequested)` alors que la carte est déjà composée à
l'écran, puis `assertEquals(1, …)` après le clic. La différence mesurée est exactement
« composer ne demande rien, toucher demande ». `aFinalRefusalLeadsToSettingsAndNowhereElse`
(`:197`) et `aFinalRefusalLeadsToTheSettingsAndIsNeverAskedAgain` (`ScalesScreenTest.kt:202`)
ferment l'autre moitié : après un refus définitif le bouton n'existe plus et le compteur
reste à zéro.

**Réserve, et c'est la seule chose à faire.** Rien n'interdit à quelqu'un d'ajouter
`rememberScalePermissions()` à `EntryScreen` : les tests instrumentés d'`Entry` pilotent
`EntryContent` (sans état) et ne verraient rien. Le garde-fou est aujourd'hui la revue.
**À faire** : un test instrumenté sur `EntryScreen` lui-même, ou à défaut une assertion de
source (aucun `rememberScalePermissions` hors du paquet `ui/scale`) — hors périmètre ici,
puisque le premier demande un appareil et le second un mécanisme que le dépôt n'a pas.

### 3. « Aucun scan n'est effectué. » — **couvert**

**Code.** `BleScaleSessionSource.runSession` lit le dépôt **avant** d'interroger la
disponibilité et sort sur `Absent` si la liste est vide
(`main/.../data/scale/ble/BleScaleSessionSource.kt:268-273`) ; le commentaire dit pourquoi
l'ordre est une exigence — interroger la disponibilité d'abord reviendrait à lire l'état
des permissions de quelqu'un qui n'a jamais appairé. Le collecteur d'`init` (`:134-141`)
suit le dépôt sans jamais toucher au transport.

**Tests.** `BleScaleSessionSourceTest.sans balance enregistrée rien n'est demandé au
transport` (`test/.../data/scale/ble/BleScaleSessionSourceTest.kt:814`) et `l'état au repos
distingue aucune balance d'une balance au repos` (`:832`).

**Ces tests prouvent-ils la règle ?** Oui. `FakeScaleTransport.untouched`
(`test/.../data/scale/ble/ScaleSessionTestDoubles.kt:263`) vaut
`availabilityChecks == 0 && scanStarts == 0 && connectRequests.isEmpty()` : l'exigence
négative est une assertion sur des compteurs, pas un espoir. Le test rejoue même un
`retry()` involontaire.

**Observation à consigner (pas un manque de test, un écart de code).**
`ScalesScreen` démarre un scan de présence dès que l'écran est visible et que la porte
Android est ouverte (`main/.../ui/scale/ScalesScreen.kt:92-95` →
`ScalesViewModel.onScreenVisible`, `main/.../ui/scale/ScalesViewModel.kt:82`), **sans
regarder si la liste est vide**. Le cas est étroit — il faut que la permission ait été
accordée puis toutes les balances oubliées — et l'écran est celui de l'appairage, donc hors
de la lettre de FR-SCALE-020, qui parle d'`Entry`. Si le propriétaire lit la case au sens
strict, la correction tient en une ligne (`if (state.scales.isEmpty()) return`) plus un
test dans `ScalesViewModelTest`. Consigné plutôt que corrigé : c'est une décision de
comportement, pas un défaut de test.

### 4. « Toutes les fonctions existantes de Mue restent disponibles avec le Bluetooth désactivé. » — **couvert**

**Code.** BR-SCALE-011 est tenue par le chemin et non par un garde : la valeur reçue vit
dans `EntryUiState.weight` comme n'importe quelle autre, et le seul prédicat qui éteigne
quoi que ce soit est `streaming` (`EntryScaleUiState.kt:75`), qui n'éteint que `−`/`+`, le
clavier et `Save measurement` — les trois contrôles qui se disputeraient la même valeur
avec la balance. La règle qui compte est côté métier : `EntryViewModel.onSave` refuse
pendant le flux, indépendamment du bouton.

**Tests.** JVM : `EntryScaleTest.Bluetooth éteint propose de l'activer sans bloquer la
saisie` (`:631`), `une permission absente renvoie aux réglages, une seule fois par
affichage` (`:649`). Instrumenté :
`EntryScaleScreenTest.no_state_of_the_scale_blocks_typing_a_weight_by_hand` (`:707`),
`only_the_three_controls_that_fight_the_stream_go_quiet` (`:559`).
Côté liaison : `BleScaleSessionSourceTest.bluetooth éteint, permission absente et
localisation éteinte empêchent tout scan` (`:848`), qui boucle sur
`ScaleUnavailableReason.entries` — une quatrième cause ajoutée sera automatiquement
éprouvée.

**Ces tests prouvent-ils la règle ?** Oui. `no_state_of_the_scale_blocks_typing_a_weight_by_hand`
parcourt les états d'indisponibilité et **tape réellement un poids**, il ne se contente pas
de constater que le bouton n'est pas grisé.

---

## Appairage

### 5. « Le scan distingue les appareils pris en charge de ceux qui ne le sont pas, et affiche les deux. » — **couvert**

**Code.** `ScaleScanViewModel.state` construit deux listes disjointes,
`recognised` et `unsupported` (`main/.../ui/scale/ScaleScanViewModel.kt:71-91`), la première
portant le `modelName` du pilote reconnu et un drapeau `selectable`.

**Tests.** JVM : `ScaleScanViewModelTest.les appareils reconnus portent le modèle identifié`
(`test/.../ui/scale/ScaleScanViewModelTest.kt:39`) et `les appareils non pris en charge sont
listés à part` (`:57`). Instrumenté :
`ScaleScanScreenTest.recognisedDevicesComeFirstWithTheModelThatWasIdentified` (`:104`),
`unsupportedDevicesAreListedAndCannotBeChosen` (`:118`).

**Ces tests prouvent-ils la règle ?** Oui : le test JVM émet deux annonces — une balance,
un « Living room speaker » — et affirme **l'adresse de chacune dans la bonne liste**, pas
seulement des tailles.

### 6. « La balance de référence est reconnue par son nom annoncé et associée sans appairage système. » — **couvert**

**Code.** Reconnaissance par nom annoncé normalisé, jamais par marque :
`main/.../data/scale/protocol/Hb9027Driver.kt` (`recognises`). Association immédiate,
sans *bonding* : `ScaleScanViewModel.onDeviceSelected`
(`main/.../ui/scale/ScaleScanViewModel.kt:146`) écrit directement un `ScaleDevice` (`:198`).

**Tests.** `Hb9027DriverTest.le pilote reconnaît l'appareil par son nom annoncé`
(`test/.../data/scale/protocol/Hb9027DriverTest.kt:50`) et `le pilote ne reconnaît ni une
marque ni un appareil quelconque` (`:61`) ;
`MueScaleDriversTest.le registre reconnaît HB BODY FAT`
(`test/.../data/scale/protocol/MueScaleDriversTest.kt:21`), sur l'annonce **réelle** relevée
le 26/08/2026 (`ScaleProtocolFixtures.kt:41`) ;
`ScaleScanViewModelTest.choisir un appareil reconnu l'associe immédiatement` (`:154`) et
`une association réussie arrête le scan et se signale une fois` (`:174`).

**Ces tests prouvent-ils la règle ?** Oui pour la reconnaissance : le vecteur est l'annonce
réelle, avec son adresse statique aléatoire et son UUID de service, et le test négatif
couvre le nom de marque — la panne exacte que PRD_SCALE 14.1 décrit.

**Réserve.** « Sans appairage système » est garanti par une **absence** :
`grep -rn "createBond\|BOND_\|bondState" src/main` ne rend rien. Aucun test ne le nomme, et
aucun ne le peut en JVM. C'est une propriété vérifiable en une commande, à défaut d'être
verrouillée.

### 7. « Une balance peut être renommée et oubliée. » — **couvert**

**Code.** `ScalesViewModel.onRenamed` (`main/.../ui/scale/ScalesViewModel.kt:120`), qui
refuse un nom vide sans message, et `onForgetConfirmed` (`:145`) derrière
`onForgetRequested`/`onForgetCancelled`.

**Tests.** `ScalesViewModelTest.renommer ne touche que le nom` (`:149`), `un nom vide laisse
la balance telle quelle` (`:162`), `oublier passe par une confirmation` (`:179`), `garder la
balance ne change rien` (`:190`), `confirmer sans avoir demandé n'oublie rien` (`:221`) ;
instrumenté `ScaleDetailScreenTest.theNameCanBeReplaced` (`:69`), `forgettingAsksFirst`
(`:205`), `theSafeAnswerKeepsTheScale` (`:233`), `confirmingForgetsIt` (`:243`).

**Ces tests prouvent-ils la règle ?** Oui, et mieux que la formulation de la case : le
double `FakeScaleRepository` retient **chaque écriture nommée** (`writes`,
`test/.../ui/scale/ScaleTestDoubles.kt:39-46`), si bien que `renommer ne touche que le nom`
affirme la liste d'écritures entière, pas seulement le nom final.

### 8. « Oublier une balance ne supprime aucune mesure. » — **couvert**

**Code.** Contrainte de schéma, pas de code applicatif :
`main/.../data/local/database/MeasurementEntity.kt:57` déclare la clé étrangère en
`ON DELETE SET NULL`, et `ScalesViewModel.onForgetConfirmed` n'appelle que `scales.forget`.

**Tests.** JVM : `ScalesViewModelTest.oublier une balance n'écrit rien d'autre que son
oubli` (`:206`). Instrumentés :
`ScaleDaoTest.forgettingAScaleKeepsItsMeasurementsAndOnlyClearsTheLink`
(`androidTest/.../data/local/database/ScaleDaoTest.kt:140`),
`RoomMeasurementRepositoryTest.forgettingAScaleKeepsTheMeasurementAndOnlyClearsTheLink`
(`androidTest/.../data/repository/RoomMeasurementRepositoryTest.kt:262`),
`ScaleMigrationTest.aScaleMeasurementSurvivesForgettingItsScaleOnTheMigratedFile`
(`androidTest/.../data/local/database/ScaleMigrationTest.kt:338`).

**Ces tests prouvent-ils la règle ?** Oui, sur les deux étages, et le test JVM couvre
précisément le trou qu'un test de base laisserait : un ViewModel qui supprimerait des
mesures **au passage** ferait quand même disparaître la balance. C'est la liste `writes`
qui l'attrape. Le troisième test instrumenté est le plus intéressant : il exerce la règle
sur un fichier **migré** depuis la version 1, pas sur une base neuve.

### 9. « Une balance dont l'adresse a changé est proposée au rattachement, jamais rattachée en silence. » — **couvert** ⟲ *corrigé ici*

**Code.** Deux questions séparées, et la séparation *est* la règle :
`ScaleMatching.matchByAddress` (`main/.../data/scale/ble/ScaleMatching.kt:38`) est la seule
que la session de pesée pose ; `proposeReattachment` (`:60`) n'est appelée que par le flux
d'appairage, qui peut demander (`ScaleScanViewModel.onReattachConfirmed`, `:162`,
`onReattachDeclined`, `:176`). `candidateFor`
(`main/.../data/scale/ble/BleScaleSessionSource.kt:339-343`) n'emploie que l'adresse.

**Tests.** Côté fonctions pures : les huit de `ScaleMatchingTest`
(`test/.../data/scale/ble/ScaleMatchingTest.kt`), dont `deux balances identiques produisent
deux candidates et jamais un choix` (`:96`). Côté flux :
`ScaleScanViewModelTest.un rattachement est proposé et jamais appliqué en silence` (`:217`),
`rattacher met l'adresse à jour et conserve l'identité et l'historique` (`:242`), `refuser le
rattachement appaire un second appareil` (`:272`), `aucun rattachement n'est proposé tant que
l'adresse enregistrée répond` (`:309`), `un pilote différent n'est jamais un candidat` (`:324`).
Instrumenté : `ScaleScanScreenTest.aScaleThatMightHaveChangedAddressIsNeverReattachedOnItsOwn`
(`:244`), `reattachingIsAQuestionWithTwoUsableAnswers` (`:262`).

**Ce qui manquait.** Le côté **session** — « la pesée ne rattache jamais » — n'était éprouvé
par aucun test. Câbler `proposeReattachment` dans `candidateFor` (la « correction » qui se
propose d'elle-même le jour où l'on cherche pourquoi une balance ne répond plus) compilait
et laissait la suite verte, tout en liant Mue en silence à l'appareil d'à côté.
**Ajouté** : `BleScaleSessionSourceTest.une adresse inconnue au même nom n'est jamais
rattachée par la session` (`test/.../data/scale/ble/BleScaleSessionSourceTest.kt:791`). Une
annonce au même nom annoncé et au même pilote, à une autre adresse : aucune liaison ne
s'ouvre, la recherche continue, et la vraie adresse est ensuite reconnue. Vérifié par
mutation.

### 10. « Plusieurs balances peuvent coexister dans la liste. » — **couvert**

**Code.** `ScaleRepository.observeAll()` rend une liste, aucune notion de balance
principale nulle part (FR-SCALE-015 est explicite et le code la suit :
`BleScaleSessionSource.findCandidate:327`, « la première qui répond »).

**Tests.** `ScaleDaoTest.severalScalesCoexist`
(`androidTest/.../data/local/database/ScaleDaoTest.kt:69`) et `readsBackInPairingOrder`
(`:59`) ; `ScalesScreenTest.eachScaleShowsItsNameItsModelAndItsLastContact`
(`androidTest/.../ui/scale/ScalesScreenTest.kt:85`) affiche deux balances et vérifie chaque
ligne ; `BleScaleSessionSourceTest.la première balance qui répond verrouille la session`
(`:754`) prouve que deux balances **enregistrées** ne se disputent pas la session.

---

## Mesure

### 11. « Monter sur la balance, écran `Entry` ouvert et téléphone posé, pose le poids sur la règle sans aucune interaction. » — **couvert**

**Code.** `LifecycleStartEffect` démarre et arrête la session avec la visibilité de l'écran
(`EntryScreen.kt:134-137`) ; la machine va du scan à la mesure sans rien demander
(`BleScaleSessionSource.runSession:263` … `awaitStableWeight:443`) ; `EntryViewModel`
recopie le poids stable dans `EntryUiState.weight` via `acceptReading`
(`main/.../ui/entry/EntryViewModel.kt:533`) ; l'écran reste éveillé le temps qu'on pose le
téléphone (`EntryScreen.kt:151`).

**Tests.** `BleScaleSessionSourceTest.une pesée nominale va du scan à la composition
corporelle` (`:125`) rejoue le chemin complet avec les **trames réelles** de PRD_SCALE 14 ;
`EntryScaleTest.une mesure stable pose sa valeur sur la règle avec sa provenance` (`:159`) ;
`EntryScaleScreenTest.a_received_measurement_lands_on_the_ruler_and_the_chip_carries_its_provenance`
(`:203`).

**Ces tests prouvent-ils la règle ?** Chacun sur sa moitié, oui. Le test de session affirme
les acquittements écrits octet pour octet et le `markSeen` de FR-SCALE-001 ; celui d'`Entry`
affirme que `weightRevision` **avance**, ce qui n'est pas un détail : la règle ne suit que
ce compteur, donc une pesée qui n'y passerait pas ne bougerait pas la règle.

**Réserve.** Il n'existe aucun test qui câble le vrai `BleScaleSessionSource` au vrai
`EntryViewModel` : la couture est le contrat `ScaleSessionState`, éprouvé des deux côtés
séparément. C'est un choix cohérent avec PRD_SCALE 21.2 (« l'écran n'observe qu'un état »)
et avec l'absence de matériel en test, mais c'est une couture non couverte, et elle mérite
d'être dite.

### 12. « La recherche s'arrête après deux minutes, ne redémarre pas en boucle et peut être relancée avec `Try again`. » — **couvert**

**Code.** `SEARCH_WINDOW_MS = 120_000` (`BleScaleSessionSource.kt:34`) ; `startDeadline`
(`:244`) vit **hors** de `sessionJob` pour pouvoir annuler puis conclure ; `start()` refuse
de balayer un état conclu (`:157-161`, `isConcluded:642`) ; `retry()` (`:184`) est le seul
chemin de relance et frappe un `sessionId` neuf.

**Tests.** `BleScaleSessionSourceTest.au bout de deux minutes la recherche s'arrête
définitivement et retry en rouvre une pleine` (`:433`) et `retry rouvre une session pleine
depuis un état conclu comme depuis le repos` (`:491`) ; côté écran,
`EntryScaleTest.aucune balance trouvée propose une nouvelle session` (`:684`), `entre deux
sessions la pastille propose d'en rouvrir une` (`:959`), `après un enregistrement la
pastille propose une nouvelle recherche` (`:992`).

**Ces tests prouvent-ils la règle ?** Oui, et les trois clauses séparément. L'horloge est
virtuelle : `SEARCH_WINDOW_MS - 1` puis `+2` encadrent l'échéance à la milliseconde ; « ne
redémarre pas en boucle » est affirmé en avançant **deux fenêtres de plus** et en
constatant `scanStarts == 1` ; « peut être relancée » vérifie que la nouvelle session dure
une fenêtre **entière** et porte `session-2`.

### 13. « L'écran reste éveillé pendant ces deux minutes seulement. » — **couvert**

**Code.** `ScaleSessionState.keepsScreenAwake` (`main/.../ui/scale/ScaleScreenAwake.kt:32`),
un `when` **sans `else`** : un douzième état ne compile pas tant que personne n'a décidé
s'il garde le téléphone allumé. Lu une seule fois, dans `EntryViewModel`, posé dans
`EntryScaleUiState.keepScreenOn`, recopié dans la fenêtre par `EntryScreen.kt:151-153`.

**Tests.** `ScaleScreenAwakeTest.l'écran ne reste éveillé que pendant la session de
recherche` (`test/.../ui/scale/ScaleScreenAwakeTest.kt:32`), qui range **les onze états** et
compte qu'ils y sont tous ; `EntryScaleTest.l'écran reste éveillé pendant la session de
recherche et pas au-delà` (`:721`), `l'écran ne reste pas éveillé après un délai écoulé`
(`:736`), `quitter l'écran retire l'indication de recherche` (`:90`).

**Ces tests prouvent-ils la règle ?** Oui pour les trois conditions d'arrêt de FR-SCALE-020,
et l'exhaustivité est tenue par le compilateur plutôt que par le fichier de test.

**Réserve, actionnable.** Le dernier maillon — l'affectation `view.keepScreenOn` — n'est
couvert par rien : les tests instrumentés d'`Entry` pilotent `EntryContent`, pas
`EntryScreen`. Supprimer le `DisposableEffect` de `EntryScreen.kt:151` laisserait la suite
verte et le téléphone s'éteindrait pendant qu'on monte sur la balance. **À faire** : un test
instrumenté sur `EntryScreen` lisant `LocalView.current.keepScreenOn`. Demande un appareil,
donc hors du périmètre de cette relecture.

### 14. « La valeur reçue est modifiable au doigt, aux boutons et au clavier. » — **couvert**

**Code.** Par construction : la valeur reçue est `EntryUiState.weight`, sans champ propre
(voir le KDoc de `EntryScaleUiState.kt:16-19`). Les trois chemins de reprise passent par
`onWeightChanged` (`EntryViewModel.kt:130`), `onStep` (`:150`) et la saisie manuelle, qui
appellent tous `takeValueBack()` (`:645`).

**Tests.** `EntryScaleTest.un glissement pendant la mesure reprend la valeur et clôt la
session` (`:254`), `les boutons et le clavier reprennent aussi la main pendant la mesure`
(`:278`) ; instrumenté `EntryScaleScreenTest.a_received_value_can_still_be_typed_over`
(`:288`), `a_drag_during_the_stream_takes_the_value_back` (`:589`),
`only_the_three_controls_that_fight_the_stream_go_quiet` (`:559`).

**Ces tests prouvent-ils la règle ?** Oui : les tests instrumentés **font le geste**
(`performTouchInput { swipe(…) }`, frappe au clavier) au lieu de vérifier un booléen
d'activation.

### 15. « La modification manuelle efface la marque de provenance et toute composition reçue ou attendue. » — **couvert**

**Code.** `takeValueBack()` (`EntryViewModel.kt:645-660`) retire `fromScale`, le flux,
l'indicateur, `barefootHint`, et **clôt la session** — c'est la clôture qui invalide
l'impédance *attendue*, par la garde de `sessionId` (`BleScaleSessionSource.conclude:605`,
`publish:597`).

**Tests.** `EntryScaleTest.une modification aux boutons retire la provenance et invalide
l'impédance` (`:302`), `une modification au clavier retire la provenance` (`:329`),
`republier la même valeur ne retire pas la provenance` (`:348`), `une impédance tardive ne
rétablit rien après une modification manuelle` (`:362`), `une nouvelle session remplace une
valeur reprise en main` (`:383`) ; instrumenté
`EntryScaleScreenTest.taking_the_value_back_ends_the_provenance_and_its_announcement` (`:270`).

**Ces tests prouvent-ils la règle ?** Oui, et le quatrième est celui qui compte : il émet
une trame d'impédance **après** la reprise en main et affirme qu'elle ne rétablit rien.
`republier la même valeur ne retire pas la provenance` est le garde-fou inverse — une
reprise déclenchée par une republication identique aurait fait clignoter la provenance.

### 16. « Rien n'est enregistré tant que `Save measurement` n'a pas été activé. » — **couvert**

**Code.** Aucun chemin d'écriture depuis les états de session : seul `onSave`
(`EntryViewModel.kt:267`) appelle le dépôt.

**Test.** `EntryScaleTest.recevoir un poids n'enregistre rien` (`:441`).

**Ce test prouve-t-il la règle ?** Oui : il émet `Stable` **puis** `Complete` avec une
impédance exploitable et un profil complet — c'est-à-dire le cas où tout serait prêt à
écrire — et affirme `repository.stored.isEmpty()`.

### 17. « Le poids est enregistrable dès qu'il est stable ; enregistrer avant l'impédance crée un poids seul et une impédance tardive ne le complète pas. » — **couvert**

**Code.** L'état passe en `Stable` **avant** l'acquittement GATT
(`BleScaleSessionSource.kt:490-494`, « la valeur se pose sur la règle sans attendre un
aller-retour ») ; `onSave` clôt la session (`closeSession`), après quoi la garde de
`sessionId` rejette toute trame (`awaitImpedance` : `ImpedanceOutcome.Stale -> return`,
`:537`).

**Test.** `EntryScaleTest.enregistrer pendant que l'impédance est attendue n'enregistre que
le poids` (`:459`).

**Ce test prouve-t-il la règle ?** Oui, et il va au bout : après l'enregistrement il émet un
`Complete` **portant le même `sessionId`** et une impédance de 520 Ω, puis affirme que la
mesure stockée n'a ni composition ni impédance, et que le conseil « pieds nus » ne s'est pas
allumé. C'est BR-SCALE-012 mot pour mot.

### 18. « Une mesure stable hors bornes est refusée avec son message et laisse l'écran intact. » — **couvert**

**Code.** `awaitStableWeight` distingue stable et instable :
hors bornes et instable → ignoré en silence ; hors bornes et **stable** → `OutOfRange`, et
**les réponses du pilote ne sont pas émises**, faute de quoi la balance lancerait une mesure
d'impédance pour un poids que Mue refuse (`BleScaleSessionSource.kt:463-480`).

**Tests.** `BleScaleSessionSourceTest.un poids stable hors bornes produit OutOfRange sans
rien d'enregistrable` (`:583`), `les valeurs instables hors bornes sont ignorées sans changer
l'état` (`:600`), `un état Stable ou Complete ne peut pas porter un poids hors du domaine`
(`:902`) ; `EntryScaleTest.une mesure stable hors bornes laisse l'écran inchangé` (`:596`) ;
instrumenté `EntryScaleScreenTest.an_out_of_range_measurement_says_so_and_changes_nothing`
(`:613`).

**Ces tests prouvent-ils la règle ?** Oui, sur les deux moitiés de FR-SCALE-024 — le silence
sur le flux instable *et* le message sur le stable — et « laisse l'écran intact » est
affirmé en comparant l'état complet d'avant à celui d'après, valeur, date et provenance
comprises.

### 19. « Une déconnexion en cours de mesure ne produit aucune erreur visible et la mesure suivante fonctionne. » — **couvert**

**Code.** `runLink` rend `false` sur `LinkLost`, la boucle de `runSession` repasse en
`Searching` **avant** le backoff (`:284-315`) ; le `catch` avale tout et journalise
(`:305-312`).

**Test.** `BleScaleSessionSourceTest.une déconnexion en cours de mesure reprend en silence
et la mesure suivante aboutit` (`:306`).

**Ce test prouve-t-il la règle ?** Oui, et c'est un bon test : il collecte les états
traversés et affirme qu'aucun n'est `Unavailable` ni `NotFound` — une assertion **négative
sur la trajectoire**, pas seulement sur l'état final —, il avance l'horloge du backoff, fait
réapparaître la balance, obtient un poids stable, et vérifie que le `sessionId` est
**toujours `session-1`** : la fenêtre de deux minutes n'a pas été rouverte au passage.
`une connexion refusée relance le scan sans ouvrir de nouvelle session` (`:351`),
`un scan refusé par la plateforme se reprend en silence puis conclut sur NotFound` (`:385`)
et `un scan toujours refusé finit sur NotFound et non sur un silence` (`:409`) couvrent les
autres pannes silencieuses, dont la limite Android de cinq scans par trente secondes.

### 20. « Recevoir un poids stable sélectionne aujourd'hui ; choisir ensuite une autre date conserve le poids mais le transforme en saisie manuelle sans composition. » — **couvert**

**Code.** `acceptReading` force la date du jour ; `onDateSelected`
(`EntryViewModel.kt:241-248`) appelle `takeValueBack()` dès que la date choisie n'est pas
celle du jour.

**Tests.** `EntryScaleTest.un poids stable reçu sélectionne aujourd'hui` (`:177`), `choisir
une autre date transforme la valeur reçue en saisie manuelle` (`:400`), `revenir sur
aujourd'hui ne rétablit pas une provenance perdue` (`:427`).

**Ces tests prouvent-ils la règle ?** Oui, et le deuxième va jusqu'au disque : il enregistre
après le changement de date et affirme `MeasurementSource.MANUAL`, `impedanceOhm == null`,
`bodyComposition == null`. Le troisième ferme la porte de derrière — revenir sur aujourd'hui
ne ressuscite pas une provenance.

### 21. « Bluetooth désactivé et permission révoquée produisent les états actionnables exacts de FR-SCALE-025 sans bloquer la saisie manuelle. » — **couvert**

**Code.** Les quatre lignes actionnables et leur geste vivent dans une seule table,
`EntryScaleStatus` (`EntryScaleUiState.kt:308-340`), phrases de PRD_SCALE 18.5 comprises ;
`EntryScreen.kt:176-186` n'ouvre un écran système que sur appui.

**Tests.** `ScaleMessagesTest.les trois lignes d'etat actionnables portent le point median
exact` (`test/.../ui/scale/ScaleMessagesTest.kt:49`) verrouille les phrases **caractère par
caractère**, point médian `·` compris ; `EntryScaleTest.Bluetooth éteint propose de
l'activer sans bloquer la saisie` (`:631`), `une permission absente renvoie aux réglages, une
seule fois par affichage` (`:649`), `la localisation système coupée est actionnable comme une
permission` (`:673`) ; instrumenté
`EntryScaleScreenTest.bluetooth_off_is_offered_word_for_word_and_is_actionable` (`:651`),
`a_missing_permission_points_at_the_settings_without_opening_anything` (`:666`).

**Ces tests prouvent-ils la règle ?** Oui. Le mot « exact » de la case est pris au mot par
`ScaleMessagesTest`, avec l'échappement Unicode écrit à la main pour que l'attendu ne puisse
pas être corrompu par le même accident que la valeur testée. La clause « sans ouvrir de
dialogue » est vérifiée par un compteur d'intentions.

### 22. « Lorsque deux balances associées répondent, la première connexion verrouille la session et aucune seconde balance ne peut remplacer un poids stable. » — **couvert**

**Code.** `findCandidate` (`BleScaleSessionSource.kt:327-330`) : `first()` porte les deux
exigences — il annule le flux dès la première candidate et ignore tout ce qui suit. Après un
poids stable, le scan est éteint et la session se conclut.

**Test.** `BleScaleSessionSourceTest.la première balance qui répond verrouille la session`
(`:754`).

**Ce test prouve-t-il la règle ?** Sur la première moitié, sans réserve : deux annonces
simultanées, **une seule** `connectRequest`, une seule liaison, puis la seconde balance
réannonce et rien ne bouge. Sur la seconde moitié — « aucune seconde balance ne peut
remplacer un poids stable » — la preuve est indirecte : le test lit `scaleId` sur la mesure
stable, mais ne rejoue pas une annonce concurrente **après** le poids stable. La règle est
vraie par construction (plus de scan, session conclue), et deux lignes de plus la rendraient
explicite. **À faire, si l'on veut la case verrouillée à la lettre** : après le poids
stable, `advertise(otherAdvertisement)` puis affirmer que l'état et `connectRequests` n'ont
pas bougé.

---

## Composition corporelle

### 23. « Une pesée pieds nus enregistre l'impédance brute avec le poids, dans la même transaction. » — **couvert**

**Code.** L'impédance est portée par la **mesure**, pas par la composition
(`main/.../data/local/database/MeasurementEntity.kt:76-77`) ; l'agrégat entier s'écrit dans
un `@Transaction` unique (`main/.../data/local/database/MeasurementDao.kt:95`,
`upsertAggregate`), et le remplacement en fait autant (`:111`).

**Tests.** `EntryScaleTest.enregistrer avec une impédance exploitable écrit poids provenance
impédance et composition` (`:488`) ;
`RoomMeasurementRepositoryTest.writesTheWholeAggregateAndReadsItBackWhole`
(`androidTest/.../data/repository/RoomMeasurementRepositoryTest.kt:164`) ;
`BodyCompositionDaoTest.aCompositionIsReadBackWithItsMeasurement`
(`androidTest/.../data/local/database/BodyCompositionDaoTest.kt:47`).

**Ces tests prouvent-ils la règle ?** Ils prouvent que l'agrégat entier fait l'aller-retour,
et que BR-SCALE-015 tient (`inputWeightCg == weight.hundredthsKg`). Ils ne prouvent pas
l'**atomicité** : aucun test n'interrompt l'écriture entre les deux tables. C'est délégué à
l'annotation Room, ce qui est raisonnable — un test de déchirure demanderait d'injecter une
panne dans SQLite —, mais la case dit « dans la même transaction » et c'est le seul mot
qu'aucune assertion ne porte.

### 24. « Une pesée en chaussettes enregistre le poids et aucune composition, sans message d'erreur. » — **couvert**

**Code.** Le marqueur `0xFFFF` devient `null` chez le pilote (BR-SCALE-005) ;
`awaitImpedance` conclut `Complete(impedanceRefused = true)`
(`BleScaleSessionSource.kt:538`) ; l'écran allume un **conseil**, jamais une erreur
(`EntryScreen.kt:855-866`, `ScaleFootnotes` — le KDoc rappelle que rien ici ne désactive
quoi que ce soit).

**Tests.** `EntryScaleTest.une impédance refusée n'est pas enregistrée` (`:534`), `le conseil
pieds nus n'apparaît que sur une impédance explicitement refusée` (`:570`) ;
`BleScaleSessionSourceTest.une impédance explicitement impossible marque le refus` (`:650`) ;
instrumenté
`EntryScaleScreenTest.the_barefoot_hint_only_shows_when_the_driver_refused_the_impedance` (`:629`).

**Ces tests prouvent-ils la règle ?** Oui, et la distinction que PRD_SCALE 18.3 demande est
prouvée par un test qui joue **les deux cas de suite** dans deux sessions différentes : un
refus explicite allume le conseil, un délai écoulé ne l'allume pas. Un test qui n'aurait joué
que le premier serait passé sur une implémentation confondant les deux.

### 25. « La composition n'apparaît pas tant que taille, date de naissance ou sexe manque, ni hors du domaine défini ; l'écran explique la cause sans jugement. » — **couvert**

**Code.** Portes dans l'ordre impédance → profil → âge → IMC
(`main/.../domain/logic/BodyCompositionCalculator.kt:202-226`), l'IMC de la porte étant
**non arrondi** (`BodyCompositionFormula.bmiOrNull:251`, dont le KDoc explique que
l'arrondir élargirait le domaine publié) ; côté écran,
`BodyCompositionUiState.showIncompleteProfile:56` et `showUnavailableForProfile:93`.

**Tests.** `BodyCompositionUiStateTest` (`test/.../ui/progress/BodyCompositionUiStateTest.kt`) :
`une balance associée et un profil incomplet expliquent ce qui manque` (`:173`), `un profil
complet hors du domaine d'âge n'a pas d'estimations disponibles` (`:230`), `… d'IMC …`
(`:244`), `un profil incomplet n'est jamais dit hors domaine` (`:261`), `un profil dans le
domaine ne dit rien du tout` (`:274`), `une taille hors domaine compte comme une taille
manquante` (`:421`). Instrumenté :
`ProgressBodyCompositionScreenTest.anIncompleteProfileNamesWhatIsMissingAndOffersProfile`
(`androidTest/.../ui/progress/ProgressBodyCompositionScreenTest.kt:245`) et
`anOutOfDomainProfileIsExplainedWithoutShowingTheBmiOrTheAge` (`:274`).

**Ces tests prouvent-ils la règle ?** Oui, y compris la partie « sans jugement », qui est
la plus facile à laisser filer : le test instrumenté affirme que **ni l'IMC ni l'âge** ne
sont à l'écran, et `noReferenceBarIsRenderedInTheCompositionSection` (`:81`) interdit la
barre de référence que FR-BODY-003 range parmi les catégories déguisées.

### 26. « Les vecteurs de référence de `mue-foot-to-foot-v1` produisent les mêmes entiers stockés sur Android et sur le serveur. » — **couvert**

C'est la case la mieux tenue du §23, et la seule dont la preuve est un **fichier partagé**.

**Code.** Arithmétique décimale spécifiée pour être reportée : échelle de travail 12,
`HALF_UP`, arrondi à chaque produit et chaque quotient, un seul arrondi de stockage à la fin
(`main/.../domain/logic/BodyCompositionFormula.kt:54-94`). Le portage TypeScript reproduit
la règle en `BigInt` sans dépendance
(`packages/domain/src/body-composition/decimal.ts`, `calculator.ts`).

**Vecteurs.** `app/src/test/resources/bodycomposition/mue-foot-to-foot-v1.json` — 20 cas, un
`_readme` qui spécifie le format et l'arithmétique, les six issues possibles, les deux bornes
d'âge et les deux années hors domaine, les deux bornes d'IMC et les deux pas de poids valides
juste dehors, **une mi-chemin exacte sur chacune des quatre sorties**, une impédance
aberrante refusée, les trois formes d'impédance inexploitable, un profil incomplet.

**Tests.** Android : `BodyCompositionCalculatorTest.chaque vecteur versionné produit
exactement les entiers attendus`
(`test/.../domain/logic/BodyCompositionCalculatorTest.kt:56`), plus `les vecteurs versionnés
déclarent la formule et l'arithmétique de cette implémentation` (`:41`), `… couvrent les six
issues possibles` (`:68`), `chaque identifiant de vecteur est unique` (`:85`).
Serveur : `packages/domain/src/body-composition/body-composition.test.ts` lit **le même
fichier, à son emplacement Android** (`:42`), rejoue chaque cas avec `toStrictEqual` sur
l'objet entier (`:205-210`) et refuse qu'un cas disparaisse via la liste `REQUIRED_CASES`
(`:100-124`).

**Ces tests prouvent-ils la règle ?** Oui, aussi bien qu'une case de ce genre peut l'être.
Les trois propriétés qui le garantissent : le fichier est **unique** (aucune des deux suites
ne peut se mettre d'accord avec elle-même) ; la comparaison est faite sur l'objet complet, si
bien qu'un cas à quatre entiers est vérifié sur quatre entiers ; et supprimer le cas qui
échoue ne fait pas disparaître l'échec, `REQUIRED_CASES` et le test des six issues le
rattrapant. Les quatre vecteurs `half-up-*` sont ceux qui tuent réellement un portage :
c'est exactement là que `HALF_EVEN` ou un pipeline `Double` divergeraient d'une unité.

**Exécuté pendant cette relecture.** `bun --env-file=../../.env test
src/body-composition/body-composition.test.ts` → **41 pass, 0 fail** ; le rejeu Android est
dans les 2 553 tests JVM verts. La parité est vraie aujourd'hui, pas seulement écrite.

### 27. « Aucun résultat physiquement incohérent n'est enregistré ou ramené artificiellement dans les bornes. » — **couvert**

**Code.** Deux étages de contrôle, avant et après l'arrondi de stockage
(`BodyCompositionCalculator.kt:227-243`, `plausibilityFailureOf` puis `storedFailureOf`) ;
en cas d'échec, `PhysicallyImplausible` et **aucune composition**, jamais une valeur ramenée
dans les bornes.

**Tests.** Vecteur `implausible-fat-free-mass-above-weight` (rejoué des deux côtés) ;
`BodyCompositionFormulaTest.un résultat physiquement incohérent est refusé et jamais ramené
dans les bornes` (`test/.../domain/logic/BodyCompositionFormulaTest.kt:230`) ;
serveur : `an aberrant reading is refused, and nothing is pulled back inside the bounds`
(`body-composition.test.ts:312`) et `a composition the equations refuse is dropped, and the
weighing stands` (`packages/domain/src/sync/measurement-composition.test.ts:417`).

**Ces tests prouvent-ils la règle ?** Oui pour la règle : le cas est construit sur une
impédance aberrante (150 Ω pour 175 cm) dans un domaine d'âge et d'IMC **valide**, donc
c'est bien le contrôle de sortie qui refuse et pas une porte d'entrée ; l'assertion porte sur
le motif exact et sur `compositionOrNull == null`. Le test serveur ajoute ce que le test
Kotlin ne dit pas : la pesée, elle, reste enregistrée.

**Réserve.** `PlausibilityCheck` compte **six** valeurs
(`BodyCompositionCalculator.kt:117-135`) et une seule est atteinte par un test. Les cinq
autres — masse maigre nulle, pourcentages hors `]0,100[`, eau au-dessus du poids, dépense
non positive — pourraient être supprimées sans qu'aucune assertion ne bouge. Elles sont
probablement inatteignables depuis le domaine d'entrée, ce qui est une bonne nouvelle et
mériterait d'être **dit dans un test** plutôt que supposé. **À faire** : un vecteur par
contrôle, ou un commentaire qui démontre l'inatteignabilité des cinq autres.

### 28. « L'écran `Progress` présente les quatre cartes, choisit la dernière composition de la période et calcule l'écart contre la précédente de cette même période. » — **couvert**

**Code.** `BodyCompositionUiState.from` (`main/.../ui/progress/BodyCompositionUiState.kt:118`)
filtre les mesures sans composition, trie par date et prend `last`/`lastIndex - 1`
(`:131-139`) ; quatre cartes dans `ProgressScreen.kt:240-249`, une par
`BodyCompositionMetric`.

**Tests.** JVM : `BodyCompositionUiStateTest.la valeur principale est la composition la plus
récente de la période` (`:42`), `sans seconde composition dans la période l'écart est un
tiret` (`:57`), `l'écart se calcule sur les entiers stockés` (`:386`), `l'écart porte toujours
son signe` (`:370`) ; `ProgressBodyCompositionTest.la section suit la période sélectionnée`
(`test/.../ui/progress/ProgressBodyCompositionTest.kt:86`). Instrumenté :
`ProgressBodyCompositionScreenTest.theFourEstimatesAreOnScreen` (`:59`),
`eachCardShowsTheSignedChangeAgainstThePreviousComposition` (`:101`),
`aSingleCompositionInThePeriodShowsNoChange` (`:142`),
`anEmptyPeriodShowsDashesOnAllFourCards` (`:158`).

**Ces tests prouvent-ils la règle ?** Oui. Le test de période change réellement de fenêtre
(`selectPeriod(Period.THREE_MONTHS)`) et affirme que `previous` **passe de `null` à une date
précise** : une implémentation qui aurait pris la précédente hors période serait rouge sur la
première moitié du test, pas seulement sur la seconde.

### 29. « Une pesée sans composition n'efface pas les cartes si une composition antérieure existe dans la période ; leur date visible reste celle de la valeur affichée. » — **couvert**

**Code.** Les pesées sans composition sont **écartées avant le tri**
(`BodyCompositionUiState.kt:131-133`) ; la date de la valeur affichée est écrite dans le
chapeau de section (`main/.../ui/progress/BodyCompositionSection.kt:91`,
`ScaleMessages.measuredOn`).

**Tests.** `BodyCompositionUiStateTest.une pesée sans impédance n'efface pas les cartes`
(`:101`) et `une pesée dont le calcul a été refusé ne compte pas comme composition` (`:119`) ;
instrumenté `ProgressBodyCompositionScreenTest.theDateOfTheDisplayedValueStaysVisible` (`:175`)
et `eachValueIsReadOutWithItsUnitAndItsDate` (`:123`).

**Ces tests prouvent-ils la règle ?** Oui, et le second test JVM est celui qui donne du
mordant : une pesée avec impédance dont le calcul a été **refusé** ne doit pas plus compter
qu'une pesée sans impédance — une implémentation qui aurait filtré sur `impedanceOhm != null`
plutôt que sur `bodyComposition != null` passerait le premier test et échouerait le second.

### 30. « L'IMC et ses catégories sont identiques avant et après renseignement du sexe. » — **couvert**

**Code.** Le sexe n'entre dans aucun calcul d'IMC : `BmiCalculator` ne le connaît pas, et
`BodyCompositionFormula.sexCoefficient` (`:286`) n'est lu que par l'équation de masse maigre
et par Mifflin–St Jeor.

**Test.** `ProgressBodyCompositionTest.l'IMC est identique avant et après le renseignement du
sexe` (`test/.../ui/progress/ProgressBodyCompositionTest.kt:70`).

**Ce test prouve-t-il la règle ?** Oui, et il évite le piège de la case : il construit **deux
ViewModels** sur le même historique avec deux profils différents (`sex = null` puis
`Sex.MALE`) et compare les deux `Bmi` obtenus, puis affirme `without is Bmi.Classified` —
sans quoi le test passerait aussi sur deux `Bmi.Unavailable` identiques, c'est-à-dire sur un
IMC absent des deux côtés. C'est précisément la forme qu'aurait eue un test creux ici.

### 31. « Les valeurs dérivées sont reproductibles depuis l'impédance, l'instantané des entrées et la version de formule stockés. » — **couvert** ⟲ *corrigé ici*

**Code.** L'instantané complet est écrit avec le résultat
(`BodyCompositionCalculator.kt:243-259`) et l'impédance reste sur la mesure (FR-BODY-004),
ce qui est ce qui rend le rejeu possible quand le profil a changé depuis. Côté serveur,
`recalculateBodyComposition` (`packages/domain/src/body-composition/calculator.ts:342`)
refuse une version inconnue **avant** de regarder la mesure.

**Ce qui manquait côté Android.** `la composition porte l'instantané exact de ses entrées`
(`:165`) prouve que l'instantané est **écrit** ; aucun test ne prouvait qu'il **suffit**. Une
entrée oubliée de l'instantané aurait laissé ce test vert et rendu impossible le recalcul
d'historique de FR-BODY-004 le jour où une version 2 arrive.
**Ajouté** : `BodyCompositionCalculatorTest.l'instantané stocké suffit à retrouver les quatre
entiers` (`test/.../domain/logic/BodyCompositionCalculatorTest.kt:200`) — la composition est
recalculée **uniquement** depuis ses propres champs d'instantané plus l'impédance de la
mesure, sans profil, et comparée à l'originale en entier. Vérifié par mutation
(`inputWeightCg = weightCg + 5` : rouge).

**Tests existants qui complètent.** `l'âge employé est celui de la date de la mesure, pas
celui du jour du calcul` (`:231`), `une mesure et un profil suffisent à recalculer une
composition ancienne` (`:257`) ; serveur : `the known version computes exactly what the
current set computes` (`body-composition.test.ts:361`), `an unknown formula version is
rejected, and nothing at all is written`
(`packages/domain/src/sync/measurement-composition.test.ts:351`), `derived values a client
got wrong are replaced by the server's own` (`:384`).

### 32. « Une pesée effectuée avec un profil incomplet enregistre quand même son impédance sur la mesure de poids. » — **couvert**

**Code.** L'impédance est un champ de `Measurement` et non de `BodyComposition`
(`MeasurementEntity.kt:76`) ; `EntryViewModel` l'écrit indépendamment du résultat du calcul
(`EntryViewModel.kt:327-337`).

**Tests.** `EntryScaleTest.l'impédance est conservée même sans composition calculable`
(`:514`) ; `RoomMeasurementRepositoryTest.anImpedanceIsKeptEvenWhenNoCompositionCouldBeComputed`
(`androidTest/.../data/repository/RoomMeasurementRepositoryTest.kt:181`) ; vecteur
`missing-sex` (impédance exploitable, profil incomplet) ; serveur : `the impedance survives a
weighing that has no composition` (`measurement-composition.test.ts:237`).

**Ces tests prouvent-ils la règle ?** Oui, et à trois étages : ViewModel, base et fil. Le
test JVM part d'un `UserProfile.EMPTY` et affirme `impedanceOhm == 520` **et**
`bodyComposition == null` — les deux, pas seulement le second.

### 33. « Compléter le profil permet de proposer le calcul rétroactif des compositions manquantes, en utilisant l'âge de chaque date de mesure, sans jamais écraser une composition existante. » — **couvert**

**Code.** `RetroactiveBodyComposition.plan` (`main/.../domain/logic/RetroactiveBodyComposition.kt:59`)
et `count` (`:77`), fonctions pures ; `ProgressViewModel.completePastWeighIns`
(`main/.../ui/progress/ProgressViewModel.kt:250`) **relit le dépôt** au moment d'écrire plutôt
que de repartir de l'état affiché ; la proposition ne s'affiche qu'avec un compte non nul
(`BodyCompositionUiState.showRetroactiveProposal:63`).

**Tests.** Les dix-sept de `RetroactiveBodyCompositionTest`, dont `chaque composition porte l'âge
de la date de sa mesure` (`test/.../domain/logic/RetroactiveBodyCompositionTest.kt:106`), `deux
dates éloignées ne produisent pas la même masse maigre` (`:124`), `une composition déjà
enregistrée n'est jamais écrasée` (`:69`), `rejouer le plan sur son propre résultat ne propose
plus rien` (`:83`), `le compte vaut exactement le nombre de compositions que le plan écrirait`
(`:265`) ; côté flux, `ProgressBodyCompositionTest.accepter la proposition écrit les
compositions manquantes` (`:143`), `… n'écrase aucune composition existante` (`:167`),
`l'écriture rétroactive ne touche ni au poids ni à la provenance` (`:190`) ; instrumenté
`ProgressBodyCompositionScreenTest.theRetroactiveOfferStatesItsCountAndItsApproximation` (`:300`).

**Ces tests prouvent-ils la règle ?** Oui, et trois d'entre eux sont remarquables.
`deux dates éloignées ne produisent pas la même masse maigre` transforme « l'âge de chaque
date » en une assertion **numérique** : un plan qui aurait employé l'âge du jour rendrait deux
résultats identiques. `une composition déjà enregistrée n'est jamais écrasée` s'appuie sur un
fixture `frozen()` dont les entrées sont volontairement absurdes (`formulaVersion = 99`,
`inputWeightCg = 1`), donc un recalcul se **verrait**. `le compte vaut exactement le nombre de
compositions que le plan écrirait` interdit la dérive entre le nombre annoncé à l'écran et le
nombre de lignes réellement écrites.

### 34. « Modifier manuellement un poids reçu retire son impédance en même temps que sa composition. » — **couvert** ⟲ *corrigé ici*

**Code.** Deux chemins, et les deux le font. Sur `Entry` : `takeValueBack()`
(`EntryViewModel.kt:645`). Depuis l'historique : `ProgressViewModel.saveEdit`
(`main/.../ui/progress/ProgressViewModel.kt:198-206`) construit un `Measurement(date, weight)`
nu — donc `MANUAL`, sans `sourceScaleId`, sans impédance, sans composition.

**Ce qui manquait.** Le chemin `Entry` est bien couvert (`EntryScaleTest:302`, `:362`). Le
chemin **historique** ne l'était pas : `RoomMeasurementRepositoryTest.aManualReplacementRemovesTheCompositionThatWasThere`
(`androidTest/…:196`) prouve que le dépôt honore un payload sans composition, ce qui ne dit
rien de ce que cet écran **envoie**. `saveEdit` pouvait recopier l'impédance et la provenance
de la mesure d'origine sur le poids retapé, et le symptôme aurait été une impédance mesurée
sur 74,5 kg rattachée à 71,2 kg — une donnée **fausse** plutôt qu'absente, que rien à l'écran
ne distingue.
**Ajouté** : `ProgressBodyCompositionTest.retoucher un poids reçu depuis l'historique lui
retire impédance et composition`
(`test/.../ui/progress/ProgressBodyCompositionTest.kt:221`). Vérifié par mutation (faire
`copy(date, weight)` sur la mesure existante au lieu d'en construire une neuve : rouge, et
seul ce test).

### 35. « Remplacer manuellement, déplacer ou supprimer un poids retire atomiquement sa composition. » — **couvert**

**Code.** `BodyComposition.date` est à la fois clé primaire et clé étrangère, en
`ON DELETE CASCADE` / `ON UPDATE CASCADE` ; `MeasurementDao.replace` (`:111`) et
`upsertAggregate` (`:95`) sont des `@Transaction`.

**Tests.** Instrumentés : `BodyCompositionDaoTest.aPayloadWithoutACompositionRemovesTheOneThatWasThere`
(`:88`), `deletingAMeasurementCascadesToItsComposition` (`:104`),
`movingAMeasurementLeavesNoOrphanedComposition` (`:117`),
`aDateHoldsAtMostOneComposition` (`:70`), `theDateIsBothThePrimaryKeyAndTheForeignKey` (`:147`) ;
`RoomMeasurementRepositoryTest.aManualReplacementRemovesTheCompositionThatWasThere` (`:196`),
`movingAMeasurementLeavesNoCompositionBehindOnTheOldDate` (`:215`),
`aCompositionFollowsItsMeasurementToTheNewDate` (`:230`),
`aCompositionWhoseInputWeightDivergesCannotReachTheDatabase` (`:296`).
Serveur : `BR-SCALE-007 — a complete payload without a composition removes the stored one`
(`measurement-composition.test.ts:293-341`), dont `restoring a deleted date does not bring the
old composition back` (`:322`).

**Ces tests prouvent-ils la règle ?** Oui, et les deux moitiés de BR-SCALE-007 y sont : le
`DELETE` en cascade *et* le payload complet sans composition, qui est le cas qu'une lecture
rapide oublie. `aCompositionWhoseInputWeightDivergesCannotReachTheDatabase` verrouille en
plus BR-SCALE-015 au niveau du dépôt.

**Réserve.** Ces tests sont **instrumentés** : ils ne tournent pas dans la suite JVM et
n'ont donc pas été exécutés ici (seulement compilés). Ils sont la seule preuve de cette case
côté Android.

### 36. « Oublier une balance conserve poids, composition et provenance `scale`, mais retire l'identifiant local de la balance. » — **couvert**

**Code.** `ON DELETE SET NULL` sur `source_scale_id` uniquement
(`MeasurementEntity.kt:57`) ; `sourceType` n'est pas touché.

**Tests.** `ScaleDaoTest.forgettingAScaleKeepsItsMeasurementsAndOnlyClearsTheLink` (`:140`),
`RoomMeasurementRepositoryTest.forgettingAScaleKeepsTheMeasurementAndOnlyClearsTheLink`
(`:262`), `ScaleMigrationTest.theScaleReferenceIsASetNullForeignKeyWithItsOwnIndex` (`:198`) ;
JVM : `BodyCompositionUiStateTest.la section reste visible quand la balance a été oubliée`
(`:142`), qui couvre le corollaire de PRD_SCALE 18.1 — oublier la dernière balance ne masque
jamais des compositions déjà enregistrées.

**Ces tests prouvent-ils la règle ?** Oui, et l'assertion qui compte est bien présente : la
provenance reste `scale` **et** l'identifiant devient nul. Un test qui n'aurait vérifié que
la survie du poids serait passé sur une implémentation retombant en `manual`.

---

## Protocole

### 37. « Les trames réelles de la section 14 sont décodées correctement par des tests unitaires sans Bluetooth. » — **couvert**

**Code.** Codec d'enveloppe pur, sans Android et sans métier
(`main/.../data/scale/protocol/HbFrames.kt:123` `decode`) ; décodage du pilote séparé de la
liaison (`Hb9027Driver`).

**Tests.** `ScaleProtocolFixtures.kt:16-38` porte les trames **verbatim** du PRD, sous la
forme hexadécimale exacte du document ; `HbFramesTest` (14 tests) et `Hb9027DriverTest`
(20 tests) les rejouent ; `BleScaleSessionSourceTest.les trames fabriquées par les doubles
sont celles relevées sur matériel` (`:195`) verrouille les constructeurs de trames des
doubles contre les fixtures réelles. Aucun n'a besoin de Bluetooth ni d'Android.

**Ces tests prouvent-ils la règle ?** Oui. Deux choses les distinguent d'un test de forme :
la trame de poids stable est jouée **verbatim** dans la pesée nominale plutôt que
reconstruite (le KDoc de `:177` explique pourquoi : la trame réelle porte un `0x21` en
position 7 que le protocole documenté n'attribue à rien, et le fabriquer serait inventer une
observation) ; et `ScaleProtocolFuzzTest` (5 tests) ajoute toutes les troncatures, toutes les
mutations d'un octet, des octets aléatoires déterministes et l'écho d'une commande sortante —
aucun ne doit lever ni produire de mesure.

### 38. « Une trame dont le contrôle est faux est rejetée. » — **couvert**

**Code.** `HbFrames.decode:147-153`, contrôle calculé sur `[1, size-2[`, motif de rejet
nommé.

**Tests.** `HbFramesTest.une trame dont le contrôle est faux est rejetée`
(`test/.../data/scale/protocol/HbFramesTest.kt:86`) ; `Hb9027DriverTest.une trame dont le
contrôle est faux est rejetée et jamais interprétée` (`:242`) ;
`ScaleProtocolFuzzTest.toute mutation d'un octet de la trame réelle est rejetée sans
exception` (`:52`).

**Ces tests prouvent-ils la règle ?** Oui. Le premier part de la **trame réelle** et modifie
son octet de contrôle de `0x67` à `0x66` — un seul bit d'écart — puis affirme le motif. Le
fuzz test généralise : chaque octet muté de la trame réelle doit être rejeté ou rester sans
mesure. Un `decode` qui ne vérifierait pas le contrôle rendrait le fuzz test rouge sur des
dizaines de cas.

### 39. « Une trame dont l'octet de produit diffère est acceptée. » — **couvert**

**Code.** L'octet est lu, exposé pour le journal, et **jamais comparé**
(`HbFrames.kt:155-160`, commentaire BR-SCALE-004). Le KDoc du fichier (`:26-29`) nomme le
défaut d'origine : le code du spike exigeait `0x26` et rejetait donc toutes les trames de
l'appareil réel, qui émet `0x00`.

**Tests.** `HbFramesTest.une trame dont l'octet de produit diffère est acceptée et décode
identiquement` (`:106`) ; `Hb9027DriverTest.la variante de la trame réelle avec l'octet de
produit 0x26 décode identiquement` (`:258`).

**Ces tests prouvent-ils la règle ?** Oui, mieux que « la trame n'est pas rejetée » : le test
construit la variante `0x26` de la trame réelle avec le contrôle **recalculé** (le contrôle
couvre l'octet de produit), affirme la trame résultante caractère par caractère, puis compare
`type`, `byteAt(4)` et `uint16At(8)` à ceux de la trame réelle. « Décode identiquement » est
une égalité, pas une absence d'erreur.

### 40. « `0xFFFF` en impédance est traité comme une absence. » — **couvert**

**Code.** Le pilote convertit le marqueur en `null` (`Hb9027Driver`, trames de type `0x11`) ;
le domaine ne connaît aucun marqueur de protocole
(`BodyCompositionFormula.isImpedanceUsable:282`, dont le KDoc dit pourquoi le filtrage se
fait chez le pilote).

**Tests.** `Hb9027DriverTest.le marqueur FFFF est une absence de mesure et non 65535 ohms`
(`:201`), sur la trame réelle `REAL_IMPEDANCE_ABSENT_FRAME` ;
`FakeScaleDriverTest.une impédance non mesurable est une absence chez le pilote fictif aussi`
(`:67`) ; `BleScaleSessionSourceTest.une impédance explicitement impossible marque le refus`
(`:650`) ; vecteurs `impedance-unusable-zero`, `-absent`, `-negative`.

**Ces tests prouvent-ils la règle ?** Oui, et l'assertion est bien « `null` », pas
« ≠ 65535 » : la valeur 65535 est refusée en tant que **valeur**, elle ne devient pas une
impédance improbable.

### 41. « Les écritures de la séquence sont sérialisées et attendent leur acquittement. » — **couvert**

C'était, d'après le KDoc de son propre fichier de test, la seule case du §23 sans aucune
assertion derrière elle. Elle en a maintenant sept.

**Code.** `ScaleWriteQueue` (`main/.../data/scale/ble/ScaleWriteQueue.kt`), extraite de la
liaison GATT précisément pour être testable : `Mutex` tenu sur toute la durée de l'attente
(`:97`, `:123`), corrélation par **rang croissant** et non par caractéristique — les
écritures successives partagent toutes `0xFFF2` — (`:99-106`, `:148-166`), chien de garde de
4 s traduit en panne de liaison et jamais en annulation (`:27`, `:213-222`), libération
immédiate sur déconnexion (`:175`). Câblée dans le transport réel :
`AndroidScaleTransport.kt:201` (émetteur), `:232` (`write`), `:325-329`
(`onCharacteristicWrite` → `acknowledge`). Au-dessus, `BleScaleSessionSource.sendAll:589`
relit la garde de `sessionId` **entre chaque écriture**.

**Tests.** `ScaleWriteQueueTest` (`test/.../data/scale/ble/ScaleWriteQueueTest.kt`) :
`la seconde écriture n'est pas émise avant l'acquittement de la première` (`:51`),
`un acquittement tardif de la première n'acquitte jamais la seconde` (`:83`),
`une écriture sans acquittement attendu consomme son rang` (`:117`),
`une écriture refusée par la pile échoue sans consommer de rang` (`:142`),
`le chien de garde produit une panne de liaison et non une annulation` (`:170`),
`une déconnexion pendant une attente ne laisse pas de continuation en suspens` (`:198`),
`un acquittement en échec fait échouer l'écriture qu'il désigne` (`:219`).
Complété par `Hb9027DriverTest.chaque écriture d'initialisation attend l'acquittement de la
précédente` (`:81`, côté déclaration) et
`BleScaleSessionSourceTest.un stop pendant une écriture n'en laisse plus partir aucune
autre` (`:943`).

**Ces tests prouvent-ils la règle ?** Oui, et c'est le meilleur fichier de test du module.
Le premier n'affirme pas que la deuxième écriture *se termine* après la première mais
qu'elle **n'est pas même remise à la radio** avant l'acquittement — la seule formulation qui
distingue un `Mutex` d'un simple `await`. Le deuxième reproduit le mode de panne exact que
PRD_SCALE 14.3 appelle « le plus probable, et silencieux » : chien de garde sur la n°1,
verrou libéré, n°2 installée, puis acquittement **tardif** de la n°1 — et affirme que la n°2
reste en attente *et* que l'orphelin est journalisé. Un test qui aurait corrélé par
caractéristique passerait sur une implémentation cassée ; celui-ci non. L'horloge est
virtuelle, donc les quatre secondes du chien de garde coûtent quelques microsecondes.

---

## Extensibilité

### 42. « Ajouter un pilote fictif au registre le rend découvrable sans modifier un seul écran. » — **couvert**

**Code.** `MueScaleDrivers` (`main/.../data/scale/protocol/MueScaleDrivers.kt:44`) est la
**seule** liste partagée qu'un nouveau modèle touche ; le registre n'a aucun critère de
reconnaissance, il interroge ses pilotes dans l'ordre (`ScaleDriverList.recognise:91`).
`FakeScaleDriver` parle un protocole **étranger** à la famille HB — enveloppe de cinq
octets, en-tête `0xFA` (`main/.../data/scale/protocol/FakeScaleDriver.kt:26-38`) — ce qui est
ce qui rend la démonstration valable : réutiliser le codec HB n'aurait prouvé qu'on sait
ajouter un appareil du même protocole. Il n'est enregistré que dans une variante déboguable
(`forBuild:72`).

**Tests.** `MueScaleDriversTest.une build déboguable rend le pilote fictif découvrable`
(`test/.../data/scale/protocol/MueScaleDriversTest.kt:67`), `le pilote fictif est absent du
registre par défaut` (`:49`), `une build de production n'expose que les pilotes matériels`
(`:56`), `la reconnaissance rend le premier pilote qui répond oui` (`:98`), `deux pilotes ne
partagent jamais un identifiant` (`:109`) ;
`FakeScaleDriverTest` (8 tests) pour le protocole fictif lui-même.

**« Sans modifier un seul écran » est-il prouvé ?** Oui, et pas seulement au niveau du
registre — c'est le point que j'ai vérifié en particulier. `ScaleScanViewModelTest` construit
son `ScaleScanViewModel` avec un `FakeScaleDriverRegistry` portant un `FakeUiScaleDriver` que
l'application **n'enregistre nulle part**
(`test/.../ui/scale/ScaleScanViewModelTest.kt:356-359`), et ce pilote inconnu ressort dans
`state.recognised`, avec son `modelName`, `selectable = true` (`:39`). L'écran est donc
prouvé agnostique au pilote sur son chemin réel, pas seulement le registre sur le sien.

### 43. « Un pilote déclarant ne pas fournir d'impédance produit un module cohérent. » — **couvert** ⟲ *corrigé ici*

**Code.** `FakeWeightOnlyScaleDriver` (`main/.../data/scale/protocol/FakeScaleDriver.kt:100`)
déclare `providesImpedance = false` et sa session **ignore** une trame d'impédance même si
des octets plausibles arrivent (`:178`). Côté machine, `awaitImpedance` conclut
**immédiatement** sans ouvrir la fenêtre de dix secondes
(`main/.../data/scale/ble/BleScaleSessionSource.kt:521-524`).

**Ce qui manquait, et c'est le cas typique du « test qui ne teste pas la règle ».**
`MueScaleDriversTest.un pilote sans impédance s'enregistre comme les autres` (`:86`) prouve
que le registre le traite comme les autres ; `FakeScaleDriverTest.le pilote sans impédance
ignore une trame d'impédance` (`:102`) prouve que la session du pilote l'ignore. Aucun des
deux ne fait passer ce pilote par la **machine à états**, donc les lignes 521-524 pouvaient
disparaître sans qu'une assertion ne rougisse. La session serait alors restée dix secondes à
attendre une grandeur que l'appareil n'annonce pas, sur un matériel où la pesée est finie :
un écran qui reste éveillé et une pastille qui pulse dix secondes de trop, ce que personne ne
rapporte comme un bug.
**Ajouté** : `BleScaleSessionSourceTest.un pilote sans impédance conclut sans ouvrir la
fenêtre d'impédance` (`test/.../data/scale/ble/BleScaleSessionSourceTest.kt:684`). Le
`Fixture` accepte désormais un registre (`:69-108`), comme `ScaleContainer` le fait avec le
pilote fictif de débogage, donc le chemin éprouvé est celui de production. Le test affirme
`Complete(impedanceRefused = false)` **sans avoir avancé l'horloge**, la liaison refermée, et
qu'une trame d'impédance arrivant quand même est présentée à une session close sans rien y
changer (`lateFrames`). Vérifié par mutation.

---

## Synthèse

### Décompte par groupe

| Groupe | Cases | Couvert | Couvert sans test | Test insuffisant | Non couvert |
|---|---:|---:|---:|---:|---:|
| Sans balance | 4 | 4 | 0 | 0 | 0 |
| Appairage | 6 | 6 | 0 | 0 | 0 |
| Mesure | 12 | 12 | 0 | 0 | 0 |
| Composition corporelle | 14 | 14 | 0 | 0 | 0 |
| Protocole | 5 | 5 | 0 | 0 | 0 |
| Extensibilité | 2 | 2 | 0 | 0 | 0 |
| **Total** | **43** | **43** | 0 | 0 | 0 |

**Avant les corrections de cette relecture** : 39 couvert, 2 couvert sans test (cases 31 et
34), 1 test insuffisant (case 43), et la moitié « session » de la case 9 sans test.

### Ce qui est solide

- **La parité inter-langages (case 26).** Un fichier de vecteurs unique, lu par les deux
  suites depuis son emplacement Android, vingt cas dont une mi-chemin exacte par sortie
  stockée, une liste de cas requis qui interdit de faire disparaître un échec en supprimant le
  vecteur. C'est le modèle que les autres cases devraient suivre.
- **La sérialisation des écritures (case 41).** Le mode de panne le plus coûteux du module,
  extrait de `BluetoothGatt` pour devenir testable, avec un test dédié au défaut précis —
  l'acquittement tardif corrélé par caractéristique — que la formulation naïve ne voit pas.
- **La machine à états de pesée.** Vingt-sept tests JVM sur horloge virtuelle, sans Bluetooth
  ni Android ni Robolectric, couvrant les deux minutes, la reprise silencieuse, la limite de
  scan d'Android, la garde de `sessionId` et jusqu'à l'invalidation pendant `markSeen`.
- **Le protocole.** Trames réelles verbatim, variante d'octet de produit, fuzz sur toutes les
  troncatures et toutes les mutations d'un octet.
- **La qualité générale des assertions.** Les tests de ce module affirment le plus souvent la
  chose qui casse — une liste d'écritures nommées plutôt qu'un état final, une trajectoire
  d'états plutôt qu'un état, un compteur d'intentions plutôt qu'une absence de plantage. Les
  deux tests creux signalés par la relecture précédente sont réparés, et leurs KDoc consignent
  le défaut d'origine.

### Ce qui ne l'est pas

- **Les coutures inter-couches ne sont couvertes nulle part.** Trois d'entre elles : le vrai
  `BleScaleSessionSource` n'est jamais câblé au vrai `EntryViewModel` (case 11) ; le
  `view.keepScreenOn` d'`EntryScreen` n'est observé par aucun test (case 13) ; les tests
  instrumentés d'`Entry` pilotent `EntryContent` et ne verraient pas une permission demandée
  par `EntryScreen` (case 2). Chacun de ces trois trous laisse supprimer quelques lignes de
  production sans qu'une suite rougisse.
- **Cinq des six contrôles de plausibilité ne sont atteints par rien** (case 27).
- **L'atomicité des écritures d'agrégat** est déléguée à `@Transaction` et n'est affirmée par
  aucun test (cases 23 et 35).
- **`Profile > Scales` démarre un scan de présence avec zéro balance enregistrée**
  (case 3) — écart de code, pas de test, à trancher par le propriétaire.
- **Toute la couche base est instrumentée**, donc invisible d'un `testDebugUnitTest` : les
  cases 8, 23, 35 et 36 reposent sur des tests que la boucle de développement quotidienne ne
  fait pas tourner.

### Les trois choses à faire en premier

1. **Un test instrumenté sur `EntryScreen` lui-même**, qui lit `LocalView.current.keepScreenOn`
   pendant `Searching` puis après `Stable` (case 13), et qui compte les demandes de permission
   à l'ouverture de l'écran (case 2). Un seul fichier ferme les deux trous les plus coûteux :
   une batterie vide et une permission demandée hors appairage sont exactement les deux pannes
   que ce module s'était interdites.
2. **Un vecteur par contrôle de plausibilité**, ou la démonstration écrite que les cinq autres
   sont inatteignables depuis le domaine d'entrée (case 27). Cinq branches de refus de données
   de santé que rien n'exerce, c'est cinq branches qu'une refonte supprimera de bonne foi.
3. **Trancher le scan de présence de `Profile > Scales` sur une liste vide** (case 3) : soit
   un `if (state.scales.isEmpty()) return` et un test dans `ScalesViewModelTest`, soit une
   phrase dans le PRD qui dit que la case ne vise qu'`Entry`. Aujourd'hui le code et la case se
   contredisent dans un cas étroit, et c'est le genre de contradiction qui se découvre par une
   batterie vide plutôt que par une lecture.

### Corrections apportées par cette relecture

Quatre tests JVM ajoutés, aucun code de production modifié. Chacun a été vérifié par mutation
— la règle inversée dans le code de production, le test devient rouge, et lui seul.

| Fichier | Test ajouté | Case |
|---|---|---|
| `test/.../data/scale/ble/BleScaleSessionSourceTest.kt:684` | `un pilote sans impédance conclut sans ouvrir la fenêtre d'impédance` | 43 |
| `test/.../data/scale/ble/BleScaleSessionSourceTest.kt:791` | `une adresse inconnue au même nom n'est jamais rattachée par la session` | 9 |
| `test/.../domain/logic/BodyCompositionCalculatorTest.kt:200` | `l'instantané stocké suffit à retrouver les quatre entiers` | 31 |
| `test/.../ui/progress/ProgressBodyCompositionTest.kt:221` | `retoucher un poids reçu depuis l'historique lui retire impédance et composition` | 34 |

Le `Fixture` de `BleScaleSessionSourceTest` accepte désormais un `ScaleDriverRegistry`
(défaut `MueScaleDrivers`), ce qui est la seule modification structurelle et ce qui rend la
case 43 éprouvable.

### Ce qui a été consigné plutôt que corrigé

- Les trois coutures inter-couches (cases 2, 11, 13) : demandent un test instrumenté, donc un
  appareil.
- Le scan de présence de `Profile > Scales` (case 3) : décision de comportement.
- Les cinq contrôles de plausibilité non exercés (case 27) : demande de dériver cinq jeux
  d'entrées, ou de démontrer leur inatteignabilité — plus qu'une trentaine de lignes et une
  discussion sur la conception.
- La clause « aucune seconde balance ne peut remplacer un poids stable » (case 22) : deux
  lignes, mais elles ajoutent une assertion à un test existant dont le sujet est autre ;
  laissé à l'appréciation du propriétaire.

### Vérification

```
./gradlew testDebugUnitTest --console=plain          BUILD SUCCESSFUL — 2553 tests, 0 échec
./gradlew compileDebugAndroidTestKotlin --console=plain   BUILD SUCCESSFUL
bun --env-file=../../.env test src/body-composition/body-composition.test.ts
                                                     41 pass, 0 fail
```

Baseline avant modification : 2549 tests, 0 échec. Aucun fichier TypeScript modifié, donc
`bun run check` n'était pas requis ; la suite `@mue/domain` n'a été lancée que sur le fichier
de parité des vecteurs, pour vérifier la case 26 sur du code exécuté.
