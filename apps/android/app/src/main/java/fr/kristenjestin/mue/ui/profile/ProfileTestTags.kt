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
}
