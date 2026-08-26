package fr.kristenjestin.mue.domain.model

/**
 * Ce qui empêche une session de balance d'exister, et que l'utilisateur peut corriger.
 *
 * Ces trois causes ont en commun d'être **actionnables** : chacune ouvre un réglage. Elles se
 * distinguent en cela d'une balance simplement endormie ou hors de portée, qui n'est pas une
 * erreur et ne produit aucun message (PRD_SCALE 7.3, 18.2).
 *
 * [SYSTEM_LOCATION_OFF] n'existe qu'avant l'API 31, où le système exige que la localisation soit
 * activée pour autoriser un scan BLE. C'est une exigence de la plateforme et non un usage de la
 * position par Mue ; elle doit être expliquée plutôt que constatée comme une absence de résultats
 * (PRD_SCALE 16.1, 18.5).
 */
enum class ScaleUnavailableReason {
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    SYSTEM_LOCATION_OFF,
}

/**
 * L'état d'une tentative de pesée, tel que l'écran `Entry` l'observe.
 *
 * Ces états sont ceux du cycle de vie de PRD_SCALE 11, à une nuance près : le PRD y décrit ce que
 * voit l'utilisateur, alors que ce type décrit ce que sait la couche de liaison. L'écran reste
 * libre de présenter plusieurs de ces états de la même façon — c'est même attendu, la recherche et
 * la connexion partageant la même indication discrète.
 *
 * Deux règles gouvernent la lecture de ce type et ne doivent jamais être contournées par
 * l'appelant :
 *
 * - **Une valeur instable n'est jamais enregistrable** (BR-SCALE-001). Seuls [Stable] et
 *   [Complete] portent une [ScaleReading] ; [Measuring] ne porte qu'un entier, délibérément nu,
 *   pour qu'il soit impossible de le confondre avec une mesure et de l'enregistrer.
 * - **Le poids stable suffit** (PRD_SCALE 3.11). [Stable] est déjà enregistrable ; attendre
 *   [Complete] n'est jamais une obligation.
 *
 * Le [ScaleReading.sessionId] porté par [Stable] et [Complete] est ce qui permet à l'écran de
 * refuser une trame tardive appartenant à une liaison précédente (PRD_SCALE 9.4, 21.2).
 */
sealed interface ScaleSessionState {

    /** Aucune balance enregistrée. L'écran `Entry` est strictement celui du PRD socle (18.1). */
    data object Absent : ScaleSessionState

    /** Au moins une balance enregistrée, mais l'écran `Entry` n'est pas visible. Aucun scan. */
    data object Idle : ScaleSessionState

    /** Scan en cours. Indication discrète, jamais un blocage (FR-SCALE-020). */
    data object Searching : ScaleSessionState

    /** Une balance candidate a répondu, la liaison s'établit. Les autres sont ignorées (FR-SCALE-015). */
    data object Connecting : ScaleSessionState

    /** Liaison établie et séquence envoyée : la balance attend qu'on monte dessus. */
    data object WaitingForStepOn : ScaleSessionState

    /**
     * Flux de trames instables. [hundredthsKg] suit la balance et n'est **jamais** enregistrable.
     *
     * Les valeurs hors bornes sont filtrées silencieusement pendant cette phase (FR-SCALE-024) :
     * cet état ne les porte donc pas.
     */
    data class Measuring(val hundredthsKg: Int) : ScaleSessionState

    /**
     * Poids stable reçu et dans le domaine de Mue. La valeur se pose sur la règle et
     * l'enregistrement est immédiatement possible.
     */
    data class Stable(val reading: ScaleReading) : ScaleSessionState

    /**
     * La session est terminée : l'impédance est arrivée, ou les dix secondes qui suivent le poids
     * stable se sont écoulées sans elle (PRD_SCALE 14.3).
     *
     * [ScaleReading.impedanceOhm] vaut `null` quand la balance a signalé une mesure impossible ou
     * quand le délai a expiré. Ces deux cas se distinguent par [impedanceRefused], parce que le
     * conseil « pieds nus » ne doit être affiché que dans le premier (FR-BODY-002, 18.3).
     */
    data class Complete(
        val reading: ScaleReading,
        val impedanceRefused: Boolean = false,
    ) : ScaleSessionState

    /**
     * Un poids **stable** hors de `30.0–250.0 kg` a été reçu.
     *
     * Il n'est jamais posé sur la règle et laisse l'écran inchangé, avec son message (FR-SCALE-024).
     * Ce n'est pas un cas théorique : des appuis à la main ont produit des mesures stables entre
     * 14 et 21 kg pendant la validation du protocole.
     */
    data class OutOfRange(val hundredthsKg: Int) : ScaleSessionState

    /**
     * Les deux minutes de la session se sont écoulées sans mesure (FR-SCALE-020).
     *
     * Aucun scan ne redémarre en boucle ; seul [ScaleSessionSource.retry] ou une réouverture
     * d'`Entry` ouvre une nouvelle session.
     */
    data object NotFound : ScaleSessionState

    /** Bluetooth éteint, permission absente, ou localisation système désactivée avant l'API 31. */
    data class Unavailable(val reason: ScaleUnavailableReason) : ScaleSessionState

    /**
     * La valeur reçue est-elle enregistrable en l'état ?
     *
     * Utilisé par l'écran pour décider s'il affiche la marque de provenance et s'il joint une
     * impédance à l'enregistrement. Volontairement porté par le domaine plutôt que recopié dans
     * chaque `when` de l'interface.
     */
    val stableReading: ScaleReading?
        get() = when (this) {
            is Stable -> this.reading
            is Complete -> this.reading
            else -> null
        }
}
