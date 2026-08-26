package fr.kristenjestin.mue.ui.scale

/**
 * Every word the scale module puts on screen, in one place.
 *
 * Constants rather than resources, as everywhere else in Mue: the app ships in English only
 * (PRD 1) and a string a test can name is a string a test cannot mistype. The accessibility
 * announcements sit here too — PRD_SCALE 20 makes them part of the interface, not a decoration
 * a screen adds afterwards.
 *
 * Lines PRD_SCALE writes out in English are quoted character for character and marked as such;
 * `ScaleMessagesTest` locks them, middle dot included. The rest are authored to the tone
 * PRD_SCALE 7 sets and 18 illustrates, and that tone is a requirement rather than a preference:
 *
 * - **silence is not an error** (7.3) — a scale out of range, asleep or without usable impedance
 *   produces no alert and no red badge, so nothing below apologises for one;
 * - **an estimate presents itself as one** (7.5) — no clinical vocabulary, no category, no
 *   threshold, no judgement passed on the reader;
 * - **the physical gesture stays the main one** (7.4) — the user steps on a scale, they do not
 *   drive a connection, and the words follow.
 */
internal object ScaleMessages {

    // region Profile: the way in (FR-SCALE-010)

    /** PRD_SCALE 8: the `Scales` group on `Profile`, a device setting like `Data & sync`. */
    const val SCALES: String = "Scales"

    /** FR-SCALE-010: the section states how many are paired, or that none is. */
    const val NO_SCALE_PAIRED: String = "No scale paired"

    /** FR-SCALE-010, and the same count read aloud. */
    fun scalesPaired(count: Int): String =
        if (count == 1) "1 scale paired" else "$count scales paired"

    /** FR-SCALE-010, word for word: the action that opens the pairing flow. */
    const val ADD_A_SCALE: String = "Add a scale"

    // endregion

    // region The scales screen, empty (PRD_SCALE 18.1)

    /**
     * PRD_SCALE 18.1 asks the empty state to explain what a scale brings and to offer
     * [ADD_A_SCALE]. It is an invitation, not a report of something missing: nobody without a
     * scale is doing anything wrong, and PRD_SCALE 7.1 promises they will not be told otherwise.
     */
    const val SCALES_EMPTY_TITLE: String = "No scale yet"
    const val SCALES_EMPTY_BODY: String =
        "A paired scale puts your weight on the ruler when you step on it, and can estimate " +
            "body composition. Weighing in by hand works exactly as it does now."

    // endregion

    // region The scan (FR-SCALE-011, FR-SCALE-012)

    /** The heading of the pairing flow, which is the action that opened it. */
    const val SCAN_TITLE: String = "Add a scale"

    /** While the thirty seconds of FR-SCALE-011 run. A statement, not a warning. */
    const val SCANNING: String = "Looking for scales nearby"

    /**
     * FR-SCALE-011: the scan must say explicitly that a sleeping scale is invisible and has to
     * be stepped on to wake up. It is the single most useful sentence of this screen — without
     * it the list looks broken to a user whose scale is right there, switched on and asleep.
     */
    const val SCAN_WAKE_HINT: String = "A sleeping scale stays invisible. Step on yours to wake it up."

    /** FR-SCALE-011: the recognised devices come first, with the model that was identified. */
    const val RECOGNISED_HEADING: String = "Scales Mue can read"

    /** FR-SCALE-011: the rest, listed and greyed out rather than hidden. */
    const val UNSUPPORTED_HEADING: String = "Other Bluetooth devices"
    const val UNSUPPORTED_BADGE: String = "Not supported"

    /**
     * Why they are on screen at all (FR-SCALE-011). The sentence has one job: turn "Bluetooth is
     * broken" into "Mue does not know this model yet", which is the truth and is also the thing
     * a user can act on.
     */
    const val UNSUPPORTED_NOTE: String =
        "Mue can see these but does not know how to read them yet."

    /** FR-SCALE-011: the scan stops after thirty seconds and offers to start again. */
    const val SCAN_FINISHED: String = "Scan finished"
    const val SCAN_AGAIN: String = "Scan again"

    /** Nothing recognised in those thirty seconds. Still not an error (PRD_SCALE 7.3). */
    const val SCAN_FOUND_NOTHING: String =
        "No scale found. Step on yours to wake it up, then scan again."

    // endregion

    // region One scale (FR-SCALE-012, FR-SCALE-013, FR-SCALE-014)

    /** FR-SCALE-012: the default name is the model, and it can be replaced. */
    const val SCALE_NAME_LABEL: String = "Name"
    const val RENAME_THIS_SCALE: String = "Rename this scale"
    const val SAVE_NAME: String = "Save name"

    /** FR-SCALE-013: the model a driver recognised, beside the name the user gave. */
    const val MODEL_LABEL: String = "Model"

    /** FR-SCALE-013: the date of the last successful contact. */
    const val LAST_SEEN_LABEL: String = "Last seen"

    /** FR-SCALE-013, word for word: what stands in for that date before there is one. */
    const val NEVER_CONNECTED: String = "Never connected"

    /**
     * FR-SCALE-013: the in-range state, while the screen is open. Out of range is the normal
     * state of a scale that is asleep (PRD_SCALE 18.2), so it is stated flatly and never styled
     * as a fault.
     */
    const val IN_RANGE: String = "In range"
    const val NOT_IN_RANGE: String = "Not in range"

    /** FR-SCALE-013: the technical block, grouped and presented as diagnostics. */
    const val DIAGNOSTICS_TITLE: String = "Technical details"
    const val DIAGNOSTICS_ADDRESS: String = "Bluetooth address"
    const val DIAGNOSTICS_ADVERTISED_NAME: String = "Advertised name"
    const val DIAGNOSTICS_DRIVER: String = "Driver"

    /** FR-SCALE-014, word for word. */
    const val FORGET_THIS_SCALE: String = "Forget this scale"

    /**
     * FR-SCALE-014: the confirmation, and the promise that comes with it. The second sentence is
     * BR-SCALE-010 in words — a measurement belongs to the user, not to the device that produced
     * it — and it is the whole reason this confirmation is safe to accept.
     */
    const val FORGET_CONFIRMATION_TITLE: String = "Forget this scale?"
    const val FORGET_CONFIRMATION_BODY: String =
        "Mue will stop looking for it. Every measurement it produced stays in your history."

    /** The two answers. `Keep scale` is the safe one, as `Keep timer` is for the timer. */
    const val KEEP_SCALE: String = "Keep scale"
    const val FORGET: String = "Forget"

    // endregion

    // region Entry: the life of a measurement (PRD_SCALE 11, FR-SCALE-022)

    /** PRD_SCALE 11: the discreet search indication, while `Entry` is visible. */
    const val SEARCHING: String = "Looking for your scale"

    /** PRD_SCALE 11: a candidate was found and the link is opening. */
    const val CONNECTING: String = "Connecting"

    /** PRD_SCALE 11, word for word: the link is up and the sequence has been sent. */
    const val STEP_ON_THE_SCALE: String = "Step on the scale"

    /** PRD_SCALE 11: unstable frames. The value follows them and commits to nothing. */
    const val MEASURING: String = "Measuring"

    /** FR-SCALE-022 and PRD_SCALE 19: the provenance mark, beside the value, never on it. */
    const val FROM_YOUR_SCALE: String = "From your scale"

    /** FR-SCALE-023, word for word: receiving a weight saves nothing; this does. */
    const val SAVE_MEASUREMENT: String = "Save measurement"

    // endregion

    // region Entry: the three actionable states (FR-SCALE-020, FR-SCALE-025, PRD_SCALE 18.5)

    /**
     * FR-SCALE-020 and PRD_SCALE 18.5, word for word — middle dot `·` included. The two-minute
     * session ended without a stable weight; tapping it starts another. Nothing is blocked in
     * the meantime, and no scan restarts on its own.
     */
    const val SCALE_NOT_FOUND: String = "Scale not found · Try again"

    /**
     * FR-SCALE-025 and PRD_SCALE 18.5, word for word — middle dot `·` included. Shown on `Entry`
     * only when a scale is already paired, and it never blocks typing a weight by hand.
     */
    const val BLUETOOTH_IS_OFF: String = "Bluetooth is off · Enable"

    /**
     * FR-SCALE-025 and PRD_SCALE 18.5, word for word — middle dot `·` included. Missing or
     * revoked permission, shown once per appearance of the screen and opening no dialog.
     */
    const val SCALE_UNAVAILABLE: String = "Scale unavailable · Open settings"

    // endregion

    // region Out of range (FR-SCALE-024)

    /**
     * FR-SCALE-024, word for word. A stable weigh-in outside `30.0–250.0 kg` (PRD BR-003) is
     * never posted on the ruler. It is a statement of what Mue records, not a verdict on what
     * was on the scale — the case is real, a hand pressed on the plate reads about 18 kg.
     */
    const val MEASUREMENT_OUT_OF_RANGE: String =
        "This measurement is outside the range Mue records"

    // endregion

    // region Permissions and system state (FR-SCALE-025, PRD_SCALE 16.1, 18.5)

    /**
     * The one-sentence explanation FR-SCALE-025 asks for, shown in the pairing context where the
     * reason is obvious. It states the limit of the permission as well as its use, because
     * `neverForLocation` in the manifest is a claim Mue owes the reader in plain words too.
     */
    const val PERMISSION_EXPLANATION: String =
        "Mue uses Bluetooth to find your scale and read what it measures. Nothing else, and no " +
            "location."

    /**
     * PRD_SCALE 18.5: the same fact once the answer was no. One sentence, then the way to
     * settings — Mue does not ask a second time (FR-SCALE-025).
     */
    const val PERMISSION_DENIED_EXPLANATION: String =
        "Without Bluetooth permission Mue cannot reach your scale. Everything else in Mue works " +
            "as before."

    const val OPEN_SETTINGS: String = "Open settings"

    /** PRD_SCALE 18.5: `Scales` offers to switch the radio on rather than reporting it off. */
    const val BLUETOOTH_OFF_EXPLANATION: String =
        "Bluetooth is off, so Mue cannot reach your scale."

    const val ENABLE_BLUETOOTH: String = "Enable Bluetooth"

    /**
     * PRD_SCALE 16.1 and 18.5, API ≤ 30 only. A system requirement of the platform's Bluetooth
     * scanner, to be explained rather than left to look like a scale that never appears — and
     * the second sentence is the one that matters, since being asked for location by a weight
     * app deserves an answer.
     */
    const val SYSTEM_LOCATION_EXPLANATION: String =
        "On this version of Android, location has to be switched on before any app can search " +
            "for Bluetooth devices. Mue never uses your position."

    const val OPEN_LOCATION_SETTINGS: String = "Open location settings"

    // endregion

    // region Body composition on Progress (FR-BODY-003, FR-BODY-005)

    /** FR-BODY-005: the section beside the BMI, following the selected period. */
    const val BODY_COMPOSITION: String = "Body composition"

    /** FR-BODY-003, word for word: the four estimates of the V1, and no others. */
    const val BODY_FAT: String = "Body fat"
    const val FAT_FREE_MASS: String = "Fat-free mass"
    const val BODY_WATER: String = "Body water"
    const val RESTING_ENERGY: String = "Resting energy"

    /** FR-BODY-003: every derived figure says it is one. No category, no threshold, no colour. */
    const val ESTIMATE: String = "Estimate"

    /**
     * FR-BODY-005's note of caution, in the spirit of the BMI's. It names the limit of the
     * method — one measurement, between two feet — without turning it into a warning, and it
     * points at the only comparison PRD_SCALE allows: the change since last time.
     */
    const val ESTIMATES_CAUTION: String =
        "These are estimates from a foot-to-foot measurement. The change over time says more " +
            "than any single figure."

    /** FR-BODY-005: no second composition in the period, so there is no change to show. */
    const val NO_VALUE: String = "—"

    /** FR-BODY-005: the main value carries its own date, so it is not read as the last weigh-in. */
    const val MEASURED_ON_LABEL: String = "Measured on"

    // endregion

    // region When there is no composition (PRD_SCALE 18.3, 18.4, FR-BODY-001)

    /**
     * FR-BODY-002 and PRD_SCALE 18.3: shown **only** when the driver explicitly reported that
     * impedance could not be measured (BR-SCALE-005) — socks, shoes, dry or partial contact. A
     * weight saved before the impedance arrived, or a timeout, must not show it. Said once,
     * without insistence, and with the reassurance that the weigh-in itself is fine.
     */
    const val BAREFOOT_HINT: String =
        "Body composition needs bare feet on the scale. Your weight was measured normally."

    /**
     * PRD_SCALE 18.4: what the estimates are still missing, and the offer to go and add it. The
     * last clause is FR-BODY-006 in advance — the impedance already measured is kept, so
     * completing the profile is not only about future weigh-ins.
     */
    const val PROFILE_INCOMPLETE_TITLE: String = "Body composition needs your profile"
    const val PROFILE_INCOMPLETE_BODY: String =
        "Estimates need your height, your date of birth and your sex. Mue kept the impedance it " +
            "already measured, so past weigh-ins can be completed too."

    const val OPEN_PROFILE: String = "Open profile"

    /**
     * FR-BODY-001 and PRD_SCALE 18.4, word for word. The profile is complete but the age or the
     * BMI sits outside the domain the foot-to-foot equation was developed in. Neither figure is
     * shown back as a judgement, and no change to the data is suggested.
     */
    const val ESTIMATES_UNAVAILABLE: String =
        "Body composition estimates are not available for this profile"

    // endregion

    // region Filling in the past (FR-BODY-006)

    /** PRD_SCALE 18.4: how many past weigh-ins can be completed. Without any, nothing is shown. */
    fun pastWeighInsToComplete(count: Int): String =
        if (count == 1) {
            "1 past weigh-in can be completed"
        } else {
            "$count past weigh-ins can be completed"
        }

    /**
     * FR-BODY-006: the calculation is proposed, never silent, because it creates health data for
     * past dates. The approximation is stated rather than hidden — Mue keeps no history of the
     * profile, so today's height and sex are what it has, while the age comes from each
     * weigh-in's own date.
     */
    const val RETROACTIVE_EXPLANATION: String =
        "Mue can estimate body composition for those dates from the impedance it kept. Your " +
            "height and sex as they are today will be used; the age comes from each weigh-in's " +
            "own date."

    const val COMPLETE_PAST_WEIGH_INS: String = "Complete past weigh-ins"

    // endregion

    // region The sex field (FR-PROFILE-007, PRD_SCALE 20)

    /**
     * FR-PROFILE-007: the label states what the field is for. A field of this kind without a
     * visible justification reads as collection for its own sake, which is exactly what the
     * placement rule of that requirement is written to prevent.
     */
    const val SEX_LABEL: String = "Sex, used for body composition estimates"

    /**
     * The group it sits in, named after its single use and kept away from the height and the
     * birth date (FR-PROFILE-007). The second sentence closes the door the layout already
     * closed: the BMI does not use this field and does not change because of it.
     */
    const val SEX_SECTION_TITLE: String = "Body composition"
    const val SEX_SECTION_BODY: String =
        "Used only to estimate body composition. Leaving it empty is fine, and BMI never uses it."

    /** FR-PROFILE-007, word for word: the two values offered. */
    const val FEMALE: String = "Female"
    const val MALE: String = "Male"

    /** FR-PROFILE-007: the third state, which is valid and blocks nothing. */
    const val SEX_NOT_SET: String = "Not set"

    // endregion

    // region Accessibility (PRD_SCALE 20)

    /**
     * PRD_SCALE 20: a stable measurement is announced with its value. Announced when the state
     * changes meaningfully and never on every frame received, which is why this is one sentence
     * and not a running commentary. [formattedWeight] arrives already formatted by the screen,
     * so the announcement and the visible value can never disagree.
     */
    fun measurementReceived(formattedWeight: String): String =
        "$formattedWeight received from your scale"

    /** PRD_SCALE 20: the other change worth announcing — the scale became unusable. */
    const val UNAVAILABLE_ANNOUNCEMENT: String =
        "Your scale is unavailable. You can still enter your weight."

    /** PRD_SCALE 20: the scale state, exposed to accessibility services as a labelled region. */
    const val SCALE_STATUS_LABEL: String = "Scale status"

    // endregion
}
