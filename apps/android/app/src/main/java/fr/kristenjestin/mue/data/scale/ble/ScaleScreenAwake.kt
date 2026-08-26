package fr.kristenjestin.mue.data.scale.ble

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
 * **Le maintien lui-même appartient à l'écran**, pas à la couche data : c'est une propriété de la
 * fenêtre Android (`FLAG_KEEP_SCREEN_ON`, ou `Modifier` équivalent côté Compose), et rien dans un
 * dépôt ne doit tenir une référence de fenêtre. Cette fonction est la moitié qui peut être écrite
 * ici et testée sans Android ; l'autre moitié est une ligne de l'écran `Entry`.
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
