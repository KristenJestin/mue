package fr.kristenjestin.mue.domain.model

/**
 * Local user preferences (PRD 11.3, PRD_FOOD 13.2 and FR-FOOD-010).
 *
 * The "reduce animations" setting is deliberately absent: it belongs to Android and
 * is read from the system rather than duplicated here.
 */
data class UserPreferences(
    val hapticsEnabled: Boolean = true,
    /**
     * PRD_FOOD 13.2 and FR-FOOD-010: `Show energy`. When false, every energy and macronutrient
     * figure of the Food module is withheld and the rest of the module keeps working.
     *
     * It is phrased positively — "show", not "hide" — because that is the name PRD_FOOD 13.2
     * gives it, and because the default has to be the permissive one: a preference nobody has
     * touched must not silently blank the numbers a person opened the module to read. PRD_FOOD 7
     * keeps it in the preferences sheet, with no permanent control on any screen.
     *
     * Local to the device, like [hapticsEnabled]: FR-FOOD-010 says so, so it stays out of the
     * synchronised aggregates of PRD_FOOD 21.1.
     */
    val showEnergy: Boolean = true,
) {
    companion object {
        val DEFAULT: UserPreferences = UserPreferences()
    }
}
