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
    /**
     * Whether the Bluetooth permissions of PRD_SCALE 16.1 have already been asked for
     * (FR-SCALE-025).
     *
     * Not a preference anybody sets: it is what the app remembers about a question it has
     * already put. It is here rather than in a repository of its own because it has to be read
     * from the composition on two screens — `Entry` and `Profile > Scales` — and both already
     * hold the user preferences.
     *
     * **A persisted boolean is the only correct implementation.**
     * `shouldShowRequestPermissionRationale` answers `false` before the very first request and
     * `false` again after a permanent denial, so on its own it cannot tell "never asked" from
     * "asked and refused for good" — which are exactly the two states FR-SCALE-025 treats
     * differently, one leading to the system prompt and the other to the app's settings page.
     *
     * Local to the device, like [hapticsEnabled] and [showEnergy]: it describes this install's
     * history with Android, not the user, so it stays out of the synchronised aggregates.
     * It defaults to `false` — nothing has been asked yet — and no screen reads it at launch,
     * because no permission is requested at launch.
     */
    val scalePermissionRequested: Boolean = false,
) {
    companion object {
        val DEFAULT: UserPreferences = UserPreferences()
    }
}
