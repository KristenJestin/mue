package fr.kristenjestin.mue.ui.profile

/**
 * Handles for the Compose tests. They exist for the parts a test cannot address by their
 * visible text: the live BMI readout, whose whole point is sometimes to be absent, and the
 * fields, whose labels are also words used elsewhere on the screen.
 */
internal object ProfileTestTags {
    const val BMI_READOUT: String = "profile:bmiReadout"
    const val NAME_FIELD: String = "profile:nameField"
    const val HEIGHT_FIELD: String = "profile:heightField"
    const val BIRTH_DATE_FIELD: String = "profile:birthDateField"
    const val SAVE_BUTTON: String = "profile:saveButton"
    const val HAPTICS_TOGGLE: String = "profile:hapticsToggle"
    const val EXPORT_BUTTON: String = "profile:exportButton"

    /** FR-TIMER-012's way back, which exists only while notifications are off. */
    const val NOTIFICATION_SETTINGS: String = "profile:notificationSettings"

    // region `Food preferences` (PRD_FOOD 6.7, 13.2 and FR-FOOD-010)

    /**
     * The three handles that came over from `FoodTestTags` with the screen they name.
     *
     * A tag belongs to whoever draws the node — the rule `MueScaffoldTestTags` states, and the
     * reason that object sits beside the scaffold rather than in a screen's own tag file. No Food
     * screen draws any of these any more, so `food:` would have been a prefix pointing at a
     * module that no longer knows they exist.
     *
     * The *names* still say `Food`, because the setting still is about Food. Only the address
     * changed.
     */
    const val OPEN_FOOD_PREFERENCES: String = "profile:openFoodPreferences"

    const val FOOD_PREFERENCES: String = "profile:foodPreferences"

    /**
     * Reserved as `HIDE_ENERGY_TOGGLE` before the switch was written, and kept under that name.
     *
     * The tag names the *effect* someone comes to the screen for; the control names the state it
     * is in, which is `Show energy`. Renaming it during a move would have made one change look
     * like two in every test that reads it.
     */
    const val HIDE_ENERGY_TOGGLE: String = "profile:hideEnergyToggle"

    // endregion
}
