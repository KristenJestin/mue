package fr.kristenjestin.mue.domain.model

/**
 * Le sexe du profil santé, facultatif (PRD_SCALE FR-PROFILE-007).
 *
 * Ce champ n'a **qu'un seul usage** : c'est le terme `sexCoefficient` de l'équation pied-pied de
 * PRD_SCALE 13.2 et le décalage `−161 / +5` de Mifflin–St Jeor. Il n'entre dans aucun autre calcul
 * et n'affecte en rien l'IMC : PRD_SCALE FR-PROFILE-007 rappelle explicitement que les catégories
 * adultes de PRD FR-BMI-002 restent identiques pour tout le monde.
 *
 * L'énumération ne porte volontairement que deux valeurs, celles que l'équation publiée accepte.
 * L'état « non renseigné » est modélisé par l'absence — `Sex?` valant `null` — et non par une
 * troisième constante : un `UNKNOWN` stocké se propagerait dans `BodyComposition.inputSex`, où
 * l'instantané de calcul de FR-BODY-004 doit refléter une entrée réellement utilisée par la
 * formule. Un profil sans sexe n'a tout simplement pas de composition (FR-BODY-001).
 *
 * [wireValue] est la forme stockée et synchronisée (PRD_SCALE 21.1 ligne `inputSex`, PRD_SCALE 22).
 */
enum class Sex(val wireValue: String) {
    FEMALE("female"),
    MALE("male"),
    ;

    companion object {
        private val byWire: Map<String, Sex> = entries.associateBy { it.wireValue }

        /**
         * Décode une valeur stockée facultative. Accepte `null` en entrée parce que la colonne
         * est nullable de bout en bout, et renvoie `null` aussi bien pour l'absence que pour une
         * valeur illisible : dans les deux cas le profil est incomplet au sens de FR-BODY-001 et
         * le comportement attendu est identique — le poids s'enregistre, la composition est
         * simplement absente, sans message d'erreur.
         */
        fun fromWire(value: String?): Sex? = value?.let(byWire::get)
    }
}
