package fr.kristenjestin.mue.domain.model

/**
 * Local user preferences (PRD 11.3).
 *
 * The "reduce animations" setting is deliberately absent: it belongs to Android and
 * is read from the system rather than duplicated here.
 */
data class UserPreferences(
    val hapticsEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT: UserPreferences = UserPreferences()
    }
}
