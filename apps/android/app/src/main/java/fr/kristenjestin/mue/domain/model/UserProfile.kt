package fr.kristenjestin.mue.domain.model

import java.time.LocalDate

/**
 * The minimal health profile (PRD 11.2). Every field is optional: the app stays
 * fully usable with an entirely empty profile.
 *
 * Age is never stored, only derived from [birthDate] (PRD BR-005), and it exists
 * for exactly one purpose: deciding whether an adult BMI category may be shown.
 */
data class UserProfile(
    val displayName: String? = null,
    val heightCm: Int? = null,
    val birthDate: LocalDate? = null,
    /**
     * Le sexe, facultatif (PRD_SCALE FR-PROFILE-007).
     *
     * **Un seul usage** : les estimations de composition corporelle de PRD_SCALE 13.2. L'IMC ne
     * s'en sert pas et son affichage n'en dépend en rien ; les catégories adultes de
     * PRD FR-BMI-002 restent identiques pour tous. C'est aussi pourquoi l'interface le présente
     * dans un groupe distinct, jamais aux côtés de [heightCm] et [birthDate] : ces deux-là
     * alimentent l'IMC, celui-ci non, et les réunir suggérerait visuellement un lien que le PRD
     * prend soin de nier.
     *
     * **Où il est stocké.** Room, dans `health_profile`, aux côtés de [heightCm] et [birthDate] —
     * et non DataStore, contrairement à la lettre de PRD_SCALE 21.1. Motif : PRD_SCALE 22 exige
     * qu'il soit synchronisé dans l'agrégat `HealthProfile`, et une donnée synchronisée doit
     * pouvoir être appliquée dans la même transaction que son curseur de synchronisation. C'est
     * exactement la raison pour laquelle le dépôt a déjà déplacé [heightCm] et [birthDate] de
     * DataStore vers Room.
     *
     * `null` est valide et ne bloque jamais l'enregistrement du profil : sans sexe, le poids
     * s'enregistre normalement et la composition est simplement absente (FR-BODY-001).
     */
    val sex: Sex? = null,
) {
    val heightMetres: Double? get() = heightCm?.let { it / 100.0 }

    /** Whole years lived on [today]; null when no birth date is known. */
    fun ageOn(today: LocalDate): Int? =
        birthDate?.let { java.time.Period.between(it, today).years }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH: Int = 40

        /** PRD FR-PROFILE-001. */
        val HEIGHT_RANGE_CM: IntRange = 120..230

        /** PRD FR-PROFILE-002. */
        const val MAX_AGE_YEARS: Long = 120

        /** PRD FR-BMI-002: below this age the V1 shows the BMI value with no category. */
        const val ADULT_AGE_YEARS: Long = 20

        val EMPTY: UserProfile = UserProfile()
    }
}
