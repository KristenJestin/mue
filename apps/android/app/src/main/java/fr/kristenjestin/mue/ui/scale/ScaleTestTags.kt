package fr.kristenjestin.mue.ui.scale

/**
 * Handles for the Compose tests of the scale module, in the shape `TimerTestTags` already uses.
 *
 * The whole set is reserved before a single screen is written, which is this repository's
 * convention and matters more here than usual: the module lands on four surfaces at once —
 * `Entry`, `Progress`, `Profile` and the `Scales` screen it creates (PRD_SCALE 8) — and those
 * surfaces are written independently. A tag invented late is a tag two screens spell differently.
 *
 * They exist for the parts a test cannot address by their visible text: rows that repeat, states
 * whose whole point is sometimes to be absent, and the several places where the same word — a
 * scale's name, `—` — appears more than once. Fields keyed by an id build their tag from that id.
 */
internal object ScaleTestTags {

    // region Profile (PRD_SCALE 8, FR-SCALE-010, FR-PROFILE-007)

    /** The `Scales` row on `Profile`: the count of paired scales, or its absence. */
    const val PROFILE_SECTION: String = "scale:profileSection"

    /**
     * The optional sex control (FR-PROFILE-007). It sits in a group of its own, never beside
     * the height and the birth date, so a test can assert *where* it is and not only that it is.
     */
    const val SEX_FIELD: String = "scale:sexField"

    /** The group that holds it, named after its single use. */
    const val SEX_SECTION: String = "scale:sexSection"

    // endregion

    // region The scales list (FR-SCALE-010, FR-SCALE-013, PRD_SCALE 18.1)

    const val LIST: String = "scale:list"

    /** PRD_SCALE 18.1: what the screen is when nothing is paired. An invitation, not a report. */
    const val EMPTY_STATE: String = "scale:emptyState"

    const val ADD_SCALE: String = "scale:addScale"

    /** One paired scale, keyed by the id Mue minted at pairing — never by its address. */
    fun row(scaleId: String): String = "scale:row:$scaleId"

    /** `Last seen …` or `Never connected`, plus the in-range state while the screen is open. */
    fun rowStatus(scaleId: String): String = "scale:rowStatus:$scaleId"

    // endregion

    // region The scan (FR-SCALE-011, FR-SCALE-012)

    const val SCAN_SCREEN: String = "scale:scanScreen"

    /** The recognised devices, listed first with the model a driver identified. */
    const val RECOGNISED_DEVICES: String = "scale:recognisedDevices"

    /**
     * The rest, greyed out and not selectable. Listed on purpose (FR-SCALE-011): seeing its own
     * scale in this list is how a user learns Mue found it but cannot speak to it yet.
     */
    const val UNSUPPORTED_DEVICES: String = "scale:unsupportedDevices"

    /** FR-SCALE-011: a sleeping scale is invisible and has to be stepped on to wake up. */
    const val SCAN_HINT: String = "scale:scanHint"

    /** The thirty-second stop of FR-SCALE-011 and the offer to run it again. */
    const val SCAN_AGAIN: String = "scale:scanAgain"

    /**
     * One discovered device, keyed by its Bluetooth address — the only identifier a scan result
     * carries, and the reason this is a function. It never leaves the phone (PRD_SCALE 16.2);
     * it is a test handle here, not a displayed value.
     */
    fun device(address: String): String = "scale:device:$address"

    // endregion

    // region One scale (FR-SCALE-012, FR-SCALE-013, FR-SCALE-014)

    /** The card for a single paired scale: name, model, last contact, and what can be done. */
    const val DETAIL: String = "scale:detail"

    const val RENAME_FIELD: String = "scale:renameField"
    const val RENAME_CONFIRM: String = "scale:renameConfirm"

    const val FORGET: String = "scale:forget"

    /** FR-SCALE-014: forgetting is confirmed, and the safe answer is the one that keeps it. */
    const val FORGET_CONFIRMATION: String = "scale:forgetConfirmation"
    const val CONFIRM_FORGET: String = "scale:confirmForget"
    const val KEEP_SCALE: String = "scale:keepScale"

    /**
     * Address, advertised name and driver, grouped as diagnostics (FR-SCALE-013). Presented as
     * something to read, never as something to set.
     */
    const val DIAGNOSTICS: String = "scale:diagnostics"

    // endregion

    // region Entry (PRD_SCALE 11, FR-SCALE-022, FR-SCALE-024, PRD_SCALE 19)

    /**
     * The search / connect / measure indicator. Discreet by construction (PRD_SCALE 19) and
     * absent entirely when no scale is paired (PRD_SCALE 18.1), which is what a test asserts.
     */
    const val ENTRY_INDICATOR: String = "scale:entryIndicator"

    /**
     * The provenance mark beside the value (FR-SCALE-022). It disappears the moment the user
     * takes the value back, so its absence is as tested as its presence.
     */
    const val SOURCE_MARK: String = "scale:sourceMark"

    /**
     * The one actionable status line of PRD_SCALE 18.5 — `Scale not found · Try again`,
     * `Bluetooth is off · Enable`, `Scale unavailable · Open settings`. One tag for the three,
     * because only one is ever on screen and its text is what tells them apart.
     */
    const val ENTRY_STATUS: String = "scale:entryStatus"

    /** FR-SCALE-024: a stable weigh-in outside `30.0–250.0 kg`, said once and never posted. */
    const val OUT_OF_RANGE_NOTICE: String = "scale:outOfRangeNotice"

    // endregion

    // region Permissions and system state (FR-SCALE-025, PRD_SCALE 18.5)

    /** The one-sentence explanation shown on `Scales` before the first request. */
    const val PERMISSION_EXPLANATION: String = "scale:permissionExplanation"

    /** The way out of a permanent refusal, and nothing else (FR-SCALE-025). */
    const val OPEN_SETTINGS: String = "scale:openSettings"

    /** PRD_SCALE 18.5: `Scales` offers to switch the radio on. */
    const val ENABLE_BLUETOOTH: String = "scale:enableBluetooth"

    /**
     * API ≤ 30 only: system location is a requirement of the platform's scanner, and
     * PRD_SCALE 16.1 wants it explained rather than read as an empty list.
     */
    const val LOCATION_EXPLANATION: String = "scale:locationExplanation"
    const val OPEN_LOCATION_SETTINGS: String = "scale:openLocationSettings"

    // endregion

    // region Body composition on Progress (FR-BODY-005, PRD_SCALE 18.3, 18.4)

    /** The whole section, absent when the history holds no composition at all (18.1). */
    const val COMPOSITION_SECTION: String = "scale:compositionSection"

    const val BODY_FAT_CARD: String = "scale:bodyFatCard"
    const val FAT_FREE_MASS_CARD: String = "scale:fatFreeMassCard"
    const val BODY_WATER_CARD: String = "scale:bodyWaterCard"
    const val RESTING_ENERGY_CARD: String = "scale:restingEnergyCard"

    /**
     * PRD_SCALE 18.3: shown only when the driver explicitly reported an unmeasurable impedance
     * (BR-SCALE-005). A save before the impedance, or a timeout, must leave this absent — which
     * is a test, so it needs a tag.
     */
    const val BAREFOOT_HINT: String = "scale:barefootHint"

    /** PRD_SCALE 18.4: what the estimates still need, and the way to `Profile`. */
    const val INCOMPLETE_PROFILE: String = "scale:incompleteProfile"

    /** FR-BODY-006: the offer to fill in past weigh-ins, never a silent calculation. */
    const val RETROACTIVE_PROPOSAL: String = "scale:retroactiveProposal"
    const val RETROACTIVE_CONFIRM: String = "scale:retroactiveConfirm"

    // endregion
}
