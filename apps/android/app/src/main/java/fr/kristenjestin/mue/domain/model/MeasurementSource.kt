package fr.kristenjestin.mue.domain.model

/**
 * D'où vient un poids (PRD_SCALE 21.1, FR-SCALE-020).
 *
 * La provenance est une donnée de la mesure, pas une décoration d'affichage : BR-SCALE-013 exige
 * qu'une retouche manuelle d'un poids reçu retire à la fois la provenance matérielle, l'impédance
 * et la composition. Sans champ explicite, cette règle serait inexprimable.
 *
 * [wireValue] est la forme stockée en base et échangée avec le serveur (PRD_SCALE 22). Elle est
 * figée : la renommer orphelinerait silencieusement toutes les lignes déjà écrites. C'est aussi
 * la raison pour laquelle [MANUAL] vaut `"manual"` — tout l'historique antérieur au module balance
 * est rétro-rempli avec cette valeur par la migration additive de PRD_SCALE 21.1.
 *
 * [AGENT] et [SERVER] ne sont écrits par aucun code de ce module ; ils existent pour que
 * l'écriture par agent MCP et la réconciliation serveur de PRD_SCALE 22 n'imposent pas une
 * seconde migration.
 */
enum class MeasurementSource(val wireValue: String) {
    /** Saisie à la main sur l'écran `Entry`, ou redevenue telle après retouche (BR-SCALE-013). */
    MANUAL("manual"),

    /** Reçue d'une balance appairée, poids stable uniquement (BR-SCALE-001). */
    SCALE("scale"),

    /** Écrite par un agent via MCP (PRD_SCALE 22). */
    AGENT("agent"),

    /** Descendue du serveur lors d'une synchronisation (PRD_SCALE 22). */
    SERVER("server"),
    ;

    companion object {
        private val byWire: Map<String, MeasurementSource> = entries.associateBy { it.wireValue }

        /**
         * Décode une valeur stockée. Renvoie `null` — et non [MANUAL] — sur une entrée inconnue :
         * à ce niveau, une valeur illisible est un fait que l'appelant doit constater et
         * journaliser (PRD_SCALE 18.5), pas une provenance à inventer. C'est la couche de
         * conversion Room qui décide du repli, pas le domaine.
         */
        fun fromWire(value: String): MeasurementSource? = byWire[value]
    }
}
