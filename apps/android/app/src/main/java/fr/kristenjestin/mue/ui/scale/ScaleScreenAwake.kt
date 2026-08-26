package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.model.ScaleSessionState

/**
 * L'écran doit-il rester éveillé ? (FR-SCALE-020)
 *
 * « Android maintient l'écran éveillé uniquement pendant cette session de recherche, afin que
 * l'utilisateur puisse poser le téléphone puis monter sur la balance. Ce maintien cesse dès qu'un
 * poids stable est reçu, que le délai expire ou qu'`Entry` n'est plus visible. »
 *
 * **Pourquoi une dérivation et non un drapeau porté par l'état.** `ScaleSessionState` est un contrat
 * gelé, écrit avant ce module et partagé avec l'écran `Entry` ; y ajouter un booléen mêlerait une
 * décision d'affichage à une description de la liaison, alors que le booléen se lit exactement dans
 * les états déjà présents. Les quatre états ci-dessous sont, mot pour mot, « le scan, la connexion
 * et l'attente que l'utilisateur monte sur la balance », plus le flux instable qui en est la suite
 * immédiate. Les trois conditions d'arrêt tombent alors toutes seules : `Stable` et `Complete` ne
 * sont pas dans la liste, `NotFound` non plus, et un écran qui n'est plus visible a déjà appelé
 * `ScaleSessionSource.stop()`.
 *
 * **Le maintien lui-même appartient à l'écran** : c'est une propriété de la fenêtre Android
 * (`View.keepScreenOn`), et rien dans un repository ne doit tenir une référence de fenêtre. Cette
 * dérivation a d'abord été écrite dans `data/scale/ble` faute de mieux ; elle est ici parce que la
 * règle est une règle d'interface et que rien de la couche de liaison ne la lit.
 *
 * **C'est la seule implémentation de cette règle.** `EntryViewModel` la lit une fois, à l'endroit
 * unique où un état de liaison devient de l'interface, et la pose dans
 * `EntryScaleUiState.keepScreenOn` ; `EntryScreen` recopie ce booléen dans la fenêtre. Aucune
 * branche de l'écran ne décide de l'éveil pour son compte — sans quoi les trois conditions d'arrêt
 * de FR-SCALE-020 seraient à vérifier une par une dans dix endroits, et la dixième serait fausse.
 */
internal val ScaleSessionState.keepsScreenAwake: Boolean
    get() = when (this) {
        ScaleSessionState.Searching,
        ScaleSessionState.Connecting,
        ScaleSessionState.WaitingForStepOn,
        is ScaleSessionState.Measuring,
        -> true

        else -> false
    }
