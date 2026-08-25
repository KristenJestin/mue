# Mue — Ce qu'il reste à tester à la main

Trois modules sont désormais terminés : le **socle** (poids, courbe, profil, export), le module **Activities** (séances sportives) et le module **Activity Timer** (le chronomètre). Tout ce qui pouvait être vérifié par la machine l'a été, sur émulateur, y compris en build release minifié.

Ce document ne contient que ce qu'aucun outil ne peut trancher.

---

## 0. D'abord, installer

Ton téléphone s'est déconnecté pendant la nuit. Réactive le débogage sans fil et donne-moi l'adresse `IP address & Port`, j'installe en trente secondes. Sinon :

```
D:\Projects\weights\build\mue-release.apk     (2,01 Mo, minifié)
D:\Projects\weights\build\mue-debug.apk       (14,2 Mo)
```

Prends la **release** : c'est celle qui reflète ce qui serait publié, et elle a été exercée de bout en bout sous R8 — minuteur compris.

---

## 1. Socle — reste inchangé

- [ ] **La sensation de la balance.** Précision, sensibilité, inertie, magnétisme. Toutes les constantes sont regroupées dans un fichier prévu pour ça ; décris avec tes mots, je traduis.
- [ ] **Les deux vibrations.** Tu m'as dit que c'était bon, mais retente sur la nouvelle version : le tic de 22 ms au glissement, et le double battement à l'enregistrement.

## 2. Activities — écoute TalkBack

C'est le seul critère d'acceptation que je n'ai pas pu clore. L'arbre d'accessibilité a été relu écran par écran et chaque contrôle porte bien son libellé — mais je ne peux pas *entendre* ce que TalkBack prononce.

- [ ] Active TalkBack, parcours l'onglet `Activity`, ouvre `Log activity`, puis une séance de musculation détaillée.
- [ ] Vérifie surtout que les **barres de la semaine** s'annoncent bien (« Monday, Today, 2h 30m ») et que les boutons `Move up` / `Move down` sont clairs.

> Un défaut réel a été trouvé et corrigé sur ce terrain : la maquette met `Recent & common` en majuscules par CSS, ce qui laisse le nom accessible intact. Le portage Compose utilisait `uppercase()` de Kotlin, qui atteint aussi la sémantique — et un lecteur d'écran **épelle** les majuscules lettre par lettre. Le texte dessiné crie toujours, le texte parlé non.

## 3. Activities — quatre choix d'icônes à valider

Le PRD ne tabule d'icônes que pour les six préréglages. Les dix autres mouvements partageaient donc le même glyphe générique ; j'ai tranché et importé une icône pour chacun. Quatre méritent ton avis :

| Mouvement | Icône choisie | Pourquoi c'est discutable |
|---|---|---|
| Elliptique | `orbit` | Aucun glyphe Lucide ne dessine cette machine. C'est un vrai pis-aller. |
| Pilates | `person-standing` | Défendable, pas évident. |
| Mobilité | `move` | Idem. |
| Randonnée / Escalade | `mountain` / `mountain-snow` | Volontairement proches — peut-être trop. |

## 4. Quatre décisions produit qui t'appartiennent

**Une cellule de série hors bornes ne montre aucune erreur.** Tape un chiffre de trop dans les répétitions (`10` → `1012`) : la série devient invalide et **disparaît silencieusement à l'enregistrement**, le compteur du haut baisse. C'est exactement ce que le PRD impose (FR-ACTIVITY-009 exige le retrait silencieux), mais c'est déroutant, et c'est le seul champ de l'app qui n'affiche pas d'erreur. Deux sorties possibles : borner la cellule à trois chiffres comme le sont déjà les champs de séance, ou lui donner un état d'erreur visible.

**Le mode de suivi est figé une fois l'exercice au catalogue.** `trackingMode` appartient à `ExerciseDefinition`, pas à la séance. Si tu crées « Gainage » en durée, tu ne pourras jamais le compter en répétitions ni corriger une erreur initiale. C'est le modèle du PRD. Les alternatives — une colonne de mode par séance, ou l'édition de la définition qui réinterpréterait rétroactivement les séries passées — sont des changements de modèle, pas des correctifs.

**Changer de préréglage sur une séance enregistrée affiche les mesures vides du nouveau.** Tu édites une marche sur tapis, tu réalises qu'elle était dehors, tu bascules : la distance est vide. Rien n'est perdu — revenir en arrière la restaure — et c'est la lecture littérale de FR-ACTIVITY-004. Mais tu ne l'avais peut-être pas imaginé ainsi en **édition** plutôt qu'en création.

**Une séance chronométrée de moins d'une minute s'affiche `0 min` dans l'historique.** Sa durée est parfaitement conservée — 11 secondes en base, 11 secondes dans le formulaire — mais la carte de `Recent activity` et le résumé de la semaine arrondissent à la minute, et une séance de 11 secondes s'y lit donc « 0 min ». C'est conforme : la section 17 du PRD du minuteur est **limitative**, et l'exception qui affiche les secondes ne porte que sur le formulaire. Mais rien n'est plus déroutant qu'une séance qui affirme avoir duré zéro. Deux sorties possibles : laisser tel quel, ou faire descendre les cartes à la seconde quand la source est `timer`. Je n'ai pas tranché tout seul parce que ce serait sortir de la liste limitative.

---

## 5. Activity Timer — ce que seul le A71 sous Android 13 peut trancher

Le minuteur a été déroulé entièrement sur émulateur : démarrage, pause, reprise, fin, révision préremplie, abandon, bandeau, notification, mort du processus, et un **vrai redémarrage** de la machine virtuelle. Tout est passé.

Mais les deux émulateurs sont en **API 36**, et ton A71 est en **Android 13 sur One UI**. Les points ci-dessous diffèrent entre les deux de façon qui compte, ou ne peuvent tout simplement pas exister sur une machine virtuelle. Ils sont classés du plus au moins risqué.

### 5.1 Le vrai redémarrage, sur un vrai téléphone

C'est le risque technique n°1 du PRD (§16), et l'émulateur ne dit que la moitié de l'histoire : il redémarre sans code de déverrouillage.

- [ ] **Redémarrage avec un minuteur actif.** Démarre un minuteur, note l'heure, redémarre le téléphone, **déverrouille**, et attends une trentaine de secondes sans ouvrir Mue. La notification `Activity in progress` doit **réapparaître seule**, avec un chronomètre qui compte juste — la durée doit correspondre au temps réel écoulé depuis le démarrage, pause comprise si tu en as fait une.
- [ ] **Le même essai en pause.** Redémarre avec un minuteur en *pause* : la notification doit revenir avec la durée **figée** et le mot `Paused`, sans repartir.
- [ ] **Combien de temps après le déverrouillage ?** La base de Mue est chiffrée par identifiants : le receveur ne peut pas lire avant que tu aies déverrouillé. Dis-moi si l'attente est de quelques secondes ou de plusieurs dizaines — c'est la seule mesure que je ne peux pas prendre.
- [ ] **Force stop, puis redémarrage.** Réglages → Applications → Mue → `Forcer l'arrêt`, avec un minuteur actif, **puis** redémarre le téléphone. Android n'envoie `BOOT_COMPLETED` qu'aux applications qui ne sont pas dans l'état « arrêtée de force » : la notification ne devrait **pas** revenir. Ce n'est pas un bug — mais ouvre Mue ensuite : le minuteur doit être là, intact, à la bonne durée. C'est ça qui compte.

### 5.2 One UI et la mise en veille des applications

Samsung est le constructeur le plus agressif du marché sur ce terrain, et rien de tout cela n'existe sur un émulateur Google.

- [ ] **« Mettre en veille les applis inutilisées ».** Réglages → Batterie → Limites d'utilisation en arrière-plan. Vérifie où Mue est classée. Si elle est en veille profonde (`Applications en veille profonde`), **sors-l'en** et dis-le-moi : ça voudrait dire qu'il faut prévenir l'utilisateur dans l'app.
- [ ] **Veille profonde avec un minuteur actif.** Laisse le téléphone posé, écran éteint, une heure au moins, sans le toucher. Rouvre Mue : la durée doit être **juste à la seconde**. Le calcul repose sur `elapsedRealtime`, qui continue de compter en veille profonde — mais c'est précisément ce que je ne peux pas prouver sur un émulateur toujours réveillé.
- [ ] **L'optimisation de la batterie.** Si One UI propose de « faire passer Mue en veille » pendant que le minuteur tourne, accepte, puis reviens. Rien ne doit être perdu.

### 5.3 La notification, telle que One UI la dessine

- [ ] **Peut-on la balayer ?** Sur Android 13 une notification `ongoing` ne se balaie pas ; à partir d'Android 14 le système l'autorise, et les deux émulateurs le font. Confirme-moi qu'elle **résiste au balayage** sur le A71. Si elle part quand même, dis-le-moi : le code sait déjà la reposter au retour sur l'app, mais je veux savoir laquelle des deux règles s'applique chez toi.
- [ ] **Le rendu One UI.** Samsung redessine complètement la zone de notifications. Regarde : le chronomètre qui défile est-il bien lisible ? Les deux boutons `Pause` et `Finish` tiennent-ils sur une ligne sans être tronqués ? L'icône (cloche qui sonne en marche, cloche simple en pause) se distingue-t-elle dans la barre d'état ?
- [ ] **Écran verrouillé.** La notification est en importance faible et silencieuse. Vérifie ce qu'elle affiche sur l'écran de verrouillage One UI, et si les boutons y sont accessibles.
- [ ] **`Finish` depuis la notification.** Il doit fermer la notification **et** ouvrir Mue directement sur le formulaire prérempli — pas sur le dernier onglet. Vérifie-le téléphone verrouillé aussi.
- [ ] **Le canal.** Réglages → Applications → Mue → Notifications : il doit y avoir un seul canal, nommé `Ongoing activity`, sans son ni vibration.

### 5.4 La permission de notification, pour de vrai

Sur l'émulateur j'ai pu accorder et révoquer la permission en ligne de commande. Le vrai parcours Android 13, lui, ne se simule pas : le système ne montre le dialogue que **deux fois**, puis plus jamais.

- [ ] **Le premier démarrage.** Sur une installation neuve, le tout premier `Start timer` doit : démarrer le minuteur **d'abord**, puis afficher le dialogue Android par-dessus le chronomètre déjà en marche. Le minuteur ne doit jamais attendre la réponse.
- [ ] **Le refus.** Réponds `Refuser`. Le minuteur doit continuer sans broncher — pause, reprise, fin, tout depuis l'app — et le bandeau interne reste ta commande. Seule la notification manque.
- [ ] **Et Mue ne doit plus jamais redemander.** Démarre trois autres minuteurs : aucun dialogue ne doit réapparaître.
- [ ] **La sortie de secours.** Va dans l'onglet `Profile` : une carte `Timer notification` avec un bouton `Open notification settings` doit être apparue sous `Haptic feedback`. Elle doit t'emmener directement sur la page notifications de Mue. *(C'est un manque que j'ai trouvé et corrigé cette nuit — voir plus bas.)*

### 5.8 Une chose à vérifier après une correction

- [ ] **Rouvrir une séance chronométrée pour l'éditer.** Enregistre une séance au minuteur avec des secondes non nulles (`Finish` après une minute et des poussières), puis rouvre-la depuis `Recent activity` et appuie sur `Save changes` sans rien toucher. La durée affichée doit garder ses secondes (`1 min 12 sec`, pas `1 min`), et la séance doit rester accessible par `Start again`. C'est un défaut réel que j'ai trouvé et corrigé — il détruisait les secondes et repassait la séance en saisie manuelle. Je l'ai revérifié sur les deux builds, mais c'est le genre de chose qui mérite un second regard.
- [ ] **Le retour.** Active les notifications depuis cette page, reviens dans Mue : la carte doit disparaître, et la notification du minuteur en cours doit apparaître.

### 5.5 La précision dans le temps long

- [ ] **Une nuit complète, écran éteint.** Démarre un minuteur le soir, note l'heure exacte à la minute, pose le téléphone, et regarde au réveil. Sept ou huit heures plus tard la durée doit correspondre **exactement**. C'est le test le plus important de cette page : c'est le seul qui prouve qu'aucun service de premier plan n'était nécessaire.
- [ ] **Changement d'heure manuel en plein segment.** Minuteur actif, va dans Réglages → Date et heure, coupe l'heure automatique et avance l'horloge d'une heure. Reviens dans Mue. Deux comportements sont corrects : soit la durée est **inchangée** (l'horloge monotone a tenu, c'est le cas normal), soit le minuteur se met **en pause** sur la dernière durée valide en affichant `Check activity time`. Ce qui serait faux, c'est une durée qui bondit d'une heure. Remets l'heure automatique ensuite.
- [ ] **Passage de minuit.** Démarre vers 23 h 55, termine après minuit : la séance enregistrée doit porter la date du **démarrage**, pas celle de la fin.

### 5.6 Les vibrations du minuteur

Le A71 a un moteur ERM sans contrôle d'amplitude ; `MueHaptics` compense par la forme du motif et non par la force. Je ne peux ni sentir ni mesurer ça.

- [ ] **`Start timer`.** Un double battement court, avec les vibrations activées dans le profil.
- [ ] **`Finish`.** Le même.
- [ ] **Sont-ils distinguables du tic de la balance ?** C'est toute la question : le tic de la balance est un temps, les confirmations du minuteur en font deux. Si les deux se ressemblent trop dans la main, dis-le-moi.
- [ ] **Vibrations désactivées.** Coupe `Haptic feedback` dans le profil : plus rien ne doit vibrer, ni au démarrage ni à la fin.

### 5.7 TalkBack sur le minuteur

Même limite que pour Activities : l'arbre est relu, le son ne l'est pas.

- [ ] **L'écran du minuteur.** Le chronomètre doit s'annoncer une fois quand tu le touches (« 4 minutes 23 seconds »), et **surtout pas** se répéter toute seule chaque seconde. C'est le point à écouter en premier.
- [ ] **L'état.** `Active` et `Paused` doivent s'annoncer au changement — c'est la seule zone « live » de l'écran.
- [ ] **Le bandeau.** Depuis un autre onglet, le bandeau doit se lire d'un seul bloc : « Treadmill walk, 00:04:23, Open, bouton ». Il ne doit **pas** faire quatre arrêts.
- [ ] **Les messages du bandeau.** Tente un deuxième minuteur (`Start activity` alors qu'un tourne) : `An activity is already in progress.` doit être **prononcé**, pas seulement affiché. *(C'est le second défaut que j'ai trouvé et corrigé cette nuit — je l'ai vérifié dans l'arbre, mais c'est toi qui peux l'entendre.)*
- [ ] **Les cartes à réviser.** « Treadmill walk, Today, 12:57 AM, 4 min 23 sec, Review this activity, bouton ».

---

## Vérifié cette nuit, plus la peine d'y toucher

**Les 22 critères d'acceptation** de la section 13 du PRD Activity Timer, déroulés un par un sur l'appareil — 20 clos sur émulateur, 2 renvoyés ci-dessus parce qu'ils demandent ton téléphone (TalkBack, et le redémarrage sous One UI).

La **migration v1 → v4 sur un vrai fichier de base** : les poids arrivent convertis en centièmes, les six tables d'Activities et leur catalogue de 17 exercices intacts, les deux tables du minuteur créées et vides, et **aucun index non déclaré** laissé derrière. Une installation d'origine traverse les trois migrations sans rien perdre.

Le **minuteur de bout en bout** : démarrage sur préréglage, chronomètre qui avance à la seconde, pause et reprise depuis l'écran **et** depuis la notification, fin, formulaire prérempli avec la date, l'heure tronquée à la minute et la durée **à la seconde**, correction de cette durée sur trois molettes, enregistrement en `source = timer` avec suppression atomique du brouillon.

**Le temps est recalculé, jamais rattrapé** : minuteur à `00:02:40`, application en arrière-plan 45 secondes, retour — la toute première image affiche `00:03:25`. Elle ne remonte pas en accéléré, elle est juste.

**Un redémarrage complet de l'émulateur** avec un minuteur en marche : notification repostée toute seule, sans ouvrir l'app, avec un chronomètre calé à 400 ms près sur le vrai départ.

**Le processus tué** en pleine marche puis relancé : `00:04:08`, toujours `Active`. Rien n'est remis à zéro.

**Une séance de 7 secondes s'enregistre**, là où la saisie manuelle refuse en dessous d'une minute. Et la saisie manuelle garde bien ses deux molettes heures/minutes, sans secondes.

**Quatre brouillons en attente** : trois cartes puis `+1 more to review`, qui déroule le quatrième sur place. Le brouillon survit à un `Forcer l'arrêt` complet, et sa durée corrigée revient intacte.

**Le bandeau ne bouge pas.** Mesuré au pixel : la barre d'onglets occupe `[0,2179]–[1080,2337]` sur les quatre onglets, avec ou sans minuteur. Le bandeau s'insère au-dessus sans jamais la déplacer, et disparaît sur l'écran du minuteur.

**Le socle et Activities continuent de fonctionner** avec un minuteur en marche : poids enregistré (7000 centigrammes, aucun flottant en base), courbe, profil, export CSV, et une séance passée saisie à la main en `source = manual`.

**La release minifiée exercée en entier** : minuteur, notification et ses boutons, mort du processus, et surtout le **brouillon de révision sérialisé** — une durée corrigée à `2 min 25 sec`, l'application arrêtée de force, rouverte, et la correction est revenue. kotlinx-serialization a survécu à R8. Zéro plantage, debug comme release.

**835 tests unitaires et 441 tests instrumentés, zéro échec. Lint sans erreur.**

### Trois défauts trouvés en pilotant, invisibles aux tests

**Rouvrir une séance chronométrée la démolissait en silence.** C'est le plus grave des trois, et il ne se voyait qu'en pilotant. Tu ouvres une séance venue du minuteur — disons `6 min 25 sec` — juste pour ajouter une note, et tu appuies sur `Save changes` **sans rien changer d'autre**. Elle repartait en base à `6 min 00 sec`, et sa source passait de `timer` à `manual` : les secondes mesurées détruites, et la séance sortie du champ de `Start again`. Deux pertes, aucune alerte, sur une action qui ne modifiait rien.

La cause était un seul champ qui répondait à deux questions différentes. « Est-ce que ce formulaire révise un brouillon en attente ? » — ça, seul un identifiant de brouillon peut le dire, et il disparaît une fois la séance enregistrée. Mais « cette séance a-t-elle été mesurée ? » est un fait sur la **façon dont elle a été enregistrée**, qui lui survit à l'identifiant. Le formulaire retombait donc sur la saisie à la minute et réécrivait `manual`. Corrigé, et la molette des secondes apparaît désormais aussi en édition — vérifié en debug **et** en release minifié.

**Le bandeau parlait dans le vide.** Le bandeau est une seule surface cliquable, donc Compose fusionne tout ce qu'il contient en un seul nœud d'accessibilité — ce qui est juste : un lecteur d'écran doit s'y arrêter une fois, pas quatre. Mais la propriété « zone live », celle qui fait *annoncer* un message, se perd dans cette fusion. Les deux messages du minuteur — `An activity is already in progress.` et `Check activity time` — étaient donc **dessinés et jamais prononcés**. Corrigé en sortant la ligne de message de la fusion, ce qui lui rend sa voix sans lui retirer le tap autour d'elle.

**Il n'y avait aucun moyen de revenir sur un refus de notification.** Le PRD (FR-TIMER-012) demande que le profil propose ensuite un lien `Open notification settings`. Le texte du lien existait, l'intent qui ouvre les réglages existait — et **aucun écran ne les affichait**. Comme l'application ne redemande jamais après un refus, un utilisateur qui avait dit non était enfermé : plus de notification, et plus rien pour la récupérer. La carte est maintenant dans `Profile`, et elle n'apparaît que dans ce cas précis.

---

## Ce qui reste ensuite, hors développement

Une vraie clé de signature — la release est signée avec la clé de debug, parfait pour tester, impossible à publier.

**Et un point à ne pas découvrir au dépôt :** la fiche Play Store et la politique de confidentialité annoncent aujourd'hui une application **sans aucune permission**. Ce n'est plus vrai : le minuteur ajoute `POST_NOTIFICATIONS` et `RECEIVE_BOOT_COMPLETED`. Aucune des deux ne touche à une donnée personnelle, à un capteur ou au réseau, et l'absence d'`INTERNET` — l'argument principal de la fiche — reste intacte. Mais les deux textes doivent être relus avant publication.

Puis la §19 du PRD du socle : disponibilité juridique du nom **Mue**, politique de confidentialité, fiche de boutique et captures.

Et les évolutions déjà cadrées : l'import CSV en V1.1, et Health Connect pour Activities.
