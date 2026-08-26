package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.ui.scale.ScaleMessages

/**
 * Ce que l'écran `Entry` dessine **en plus** lorsqu'une balance est appairée.
 *
 * Un objet séparé de [EntryUiState] plutôt que six champs de plus, pour une raison qui est une
 * exigence et non un goût : PRD_SCALE 18.1 veut qu'`Entry` soit *strictement* celui du PRD socle
 * tant qu'aucune balance n'est enregistrée. [ABSENT] est cette garantie sous une forme qu'un test
 * peut nommer — un seul `assertEquals(EntryScaleUiState.ABSENT, state.scale)` ferme la porte à
 * tout élément ajouté, tout badge et toute invite, là où six booléens à `false` se seraient
 * vérifiés un par un et auraient laissé passer le septième.
 *
 * Rien ici ne porte de poids « reçu ». La valeur reçue vit dans [EntryUiState.weight] comme
 * n'importe quelle autre : c'est ce qui rend vraie, par construction, la promesse de FR-SCALE-022
 * qu'une mesure reçue reste entièrement modifiable au doigt, aux boutons et au clavier. Ce type ne
 * dit que *d'où* elle vient et ce que l'écran a le droit d'en déduire.
 *
 * @property paired Au moins une balance est enregistrée (PRD_SCALE 11 : tout sauf `Absent`).
 *   Gouverne la seule réservation de place de l'écran ; sans balance, aucune ligne n'est réservée.
 * @property indicator L'indication discrète de recherche, de connexion ou de mesure, ou `null`.
 * @property liveHundredths Le flux instable, en centièmes de kilogramme, pendant
 *   [EntryScaleIndicator.MEASURING]. **Jamais posé sur la règle** (BR-SCALE-001) : il est affiché
 *   dans la ligne d'indication, où il ne peut pas devenir la valeur qu'un appui sur
 *   `Save measurement` enregistrerait.
 * @property status La ligne actionnable de PRD_SCALE 18.5, ou `null`. Elle ne bloque jamais la
 *   saisie manuelle (BR-SCALE-011).
 * @property fromScale La valeur affichée vient de la balance et personne n'y a touché depuis
 *   (FR-SCALE-022). C'est aussi la condition pour joindre une impédance à l'enregistrement : une
 *   fois `false`, l'impédance reçue ou attendue est invalidée (BR-SCALE-013).
 * @property arrivalRevision [EntryUiState.weightRevision] au moment où la valeur a été posée par
 *   la balance. L'écran s'en sert pour distinguer la révision qu'il doit *rejoindre en glissant*
 *   (PRD_SCALE 19) de celles qu'il rejoint d'un saut — le seed historique, `−` / `+`, le clavier.
 * @property outOfRange Une mesure **stable** hors de `30.0–250.0 kg` vient d'arriver
 *   (FR-SCALE-024). L'écran l'annonce et ne change rien d'autre.
 * @property barefootHint Le pilote a explicitement signalé une impédance non mesurable
 *   (BR-SCALE-005). Jamais après un enregistrement anticipé ni après un délai écoulé
 *   (PRD_SCALE 18.3).
 * @property announcement Le seul changement à annoncer aux services d'accessibilité, ou `null`
 *   (PRD_SCALE 20). Volontairement pas une chaîne : le texte se compose avec le poids **formaté
 *   par l'écran**, pour que l'annonce et la valeur visible ne puissent jamais diverger.
 * @property keepScreenOn La session de recherche court : l'écran reste éveillé le temps que
 *   l'utilisateur pose son téléphone et monte sur la balance (FR-SCALE-020).
 */
@Immutable
data class EntryScaleUiState(
    val paired: Boolean = false,
    val indicator: EntryScaleIndicator? = null,
    val liveHundredths: Int? = null,
    val status: EntryScaleStatus? = null,
    val fromScale: Boolean = false,
    val arrivalRevision: Int = 0,
    val outOfRange: Boolean = false,
    val barefootHint: Boolean = false,
    val announcement: EntryScaleAnnouncement? = null,
    val keepScreenOn: Boolean = false,
) {
    companion object {
        /**
         * Aucune balance enregistrée : l'écran est exactement celui du PRD socle (PRD_SCALE 18.1).
         *
         * C'est aussi l'état d'une application dont la couche de liaison n'est pas câblée, ce qui
         * en fait le défaut de [EntryUiState] et non un cas particulier.
         */
        val ABSENT: EntryScaleUiState = EntryScaleUiState()
    }
}

/**
 * L'indication discrète de PRD_SCALE 11, réduite à ce que l'écran en montre.
 *
 * Quatre états du domaine et quatre libellés, alors que `Searching` et `Connecting` pourraient se
 * confondre : les distinguer ne coûte rien ici et le mot exact est celui de PRD_SCALE 11.
 *
 * **Discret par construction** (PRD_SCALE 19) : c'est une ligne de texte, jamais une carte, jamais
 * une couleur d'accent, jamais un élément qui pourrait concurrencer la valeur du poids.
 */
enum class EntryScaleIndicator(val message: String) {
    SEARCHING(ScaleMessages.SEARCHING),
    CONNECTING(ScaleMessages.CONNECTING),
    STEP_ON(ScaleMessages.STEP_ON_THE_SCALE),
    MEASURING(ScaleMessages.MEASURING),
}

/**
 * Les trois lignes actionnables de PRD_SCALE 18.5, plus le cas de la localisation système.
 *
 * Une seule est jamais à l'écran, ce qui est la raison pour laquelle `ScaleTestTags.ENTRY_STATUS`
 * en couvre trois : c'est le texte qui les distingue.
 *
 * [SYSTEM_LOCATION_OFF] réemploie délibérément le libellé de [PERMISSION_MISSING]. PRD_SCALE 18.5
 * ne réserve que trois phrases pour `Entry` et en inventer une quatrième les ferait diverger de
 * `ScaleMessages`, qui est verrouillé mot pour mot ; l'écran des balances, lui, garde l'explication
 * complète de PRD_SCALE 16.1. Ce qui change ici est la destination du geste — le réglage de
 * localisation plutôt que la fiche de l'application —, pas la phrase.
 *
 * Aucune de ces valeurs n'ouvre quoi que ce soit toute seule : chacune décrit un geste que
 * l'utilisateur peut faire, jamais un écran système que Mue ouvrirait de lui-même (FR-SCALE-025).
 */
enum class EntryScaleStatus(val message: String) {
    /** Les deux minutes se sont écoulées. Le geste relance une session (FR-SCALE-020). */
    NOT_FOUND(ScaleMessages.SCALE_NOT_FOUND),

    /** La radio est éteinte. Le geste ouvre la demande d'activation du système. */
    BLUETOOTH_OFF(ScaleMessages.BLUETOOTH_IS_OFF),

    /** Permission absente ou révoquée. Le geste ouvre la fiche de l'application. */
    PERMISSION_MISSING(ScaleMessages.SCALE_UNAVAILABLE),

    /** API ≤ 30 : le scanner de la plateforme exige la localisation système. */
    SYSTEM_LOCATION_OFF(ScaleMessages.SCALE_UNAVAILABLE),
}

/**
 * Ce que les services d'accessibilité doivent entendre, et rien de plus (PRD_SCALE 20).
 *
 * Deux événements seulement — l'arrivée d'une mesure et l'indisponibilité —, **jamais une trame**.
 * Une région active branchée sur l'indicateur parlerait à chaque poids instable reçu, c'est-à-dire
 * plusieurs fois par seconde pendant qu'on monte sur la balance.
 */
enum class EntryScaleAnnouncement {
    /** Une mesure stable vient d'être posée sur la règle. Annoncée **avec sa valeur**. */
    MEASUREMENT_RECEIVED,

    /** La balance est devenue inutilisable. Le reste de l'écran fonctionne (BR-SCALE-011). */
    UNAVAILABLE,
}
