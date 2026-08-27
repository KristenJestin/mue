package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.ui.progress.BodyCompositionMetric
import java.util.Locale

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
 *
 * The module lands on four surfaces — `Entry`, `Progress`, `Profile` and the `Scales` screen it
 * creates (PRD_SCALE 8) — and every one of them reads its words from here, including the ones on
 * `Progress`: a body-composition sentence declared next to the card that renders it is a sentence
 * the next screen will write again, slightly differently. That is why this file names
 * [BodyCompositionMetric], which lives in `ui/progress` and already takes its own labels from
 * here. The two packages know each other by design; only one of them decides on the wording.
 *
 * What stays out: the separators and units that are **layout**, not language — `" · "` between two
 * facts of one line, `%`, `kg`, `kcal` after a number. They live beside the formatting they belong
 * to. Their spoken forms, which a screen reader actually says, are here.
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

    /** What the `Scales` row of `Profile` opens, said for the benefit of a screen reader. */
    const val MANAGE_YOUR_SCALES: String = "Manage your scales"

    /**
     * What a paired scale is for, in one line, on `Profile` (PRD_SCALE 8). It describes the
     * gesture and nothing else: stepping on a scale is the whole interaction (PRD_SCALE 7.4).
     */
    const val SCALES_ROW_BODY: String =
        "Pair a Bluetooth scale and your weight arrives on its own when you step on it."

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

    /** The heading above the list, once there is one. The screen is already called `Scales`. */
    const val YOUR_SCALES: String = "Your scales"

    /** What opening a row does, for a screen reader: the row is a card, not a labelled button. */
    const val OPEN_THIS_SCALE: String = "Open this scale"

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

    /**
     * Before the first scan of the screen has run. It says the screen is ready and waiting, not
     * that something failed — the difference matters, because that is also what the line reads
     * for the instant between opening the screen and the scan starting on its own.
     */
    const val SCAN_NOT_STARTED: String = "Ready when you are"

    /** The deliberate tap FR-SCALE-025 puts the permission request behind, and nowhere else. */
    const val ALLOW_BLUETOOTH: String = "Allow Bluetooth"

    /** FR-SCALE-011: this address is already paired, so the row says under which name. */
    fun alreadyPairedAs(name: String): String = "Already paired as $name"

    /** FR-SCALE-001: the hint on the row itself, before any question is asked. */
    fun mightBe(name: String): String = "Might be $name"

    // endregion

    // region Reattaching a scale that changed address (FR-SCALE-001)

    /**
     * FR-SCALE-001: proposed, never silent. Two identical scales in one home must not merge
     * behind their owner's back, and this is the one case where the right answer depends on
     * something the application cannot see.
     */
    const val REATTACH_TITLE: String = "Is this the same scale?"

    /**
     * Both answers are constructive, so the body says what each one keeps. The battery sentence
     * is the one that makes the question answerable: it names the ordinary event that produces
     * a new address (PRD_SCALE 10.1), instead of leaving the reader to guess at a fault.
     */
    fun reattachBody(name: String): String =
        "Mue knows a scale called $name that is no longer answering at the address it had. A " +
            "battery change is enough to do that. Reattaching keeps its name and every " +
            "measurement it produced; adding it separately keeps both scales."

    const val REATTACH: String = "Reattach"
    const val ADD_AS_A_NEW_SCALE: String = "Add as a new scale"

    // endregion

    // region One scale (FR-SCALE-012, FR-SCALE-013, FR-SCALE-014)

    /** FR-SCALE-012: the default name is the model, and it can be replaced. */
    const val SCALE_NAME_LABEL: String = "Name"
    const val RENAME_THIS_SCALE: String = "Rename this scale"
    const val SAVE_NAME: String = "Save name"

    /** The block that groups the model, the last contact and the in-range state. */
    const val ABOUT_THIS_SCALE: String = "About this scale"

    /** FR-SCALE-013: the model a driver recognised, beside the name the user gave. */
    const val MODEL_LABEL: String = "Model"

    /**
     * PRD_SCALE 9.2: what the model reads as once its driver is no longer shipped. A scale paired
     * by an older build may reference a driver that has since been removed; the case has to be
     * readable rather than fatal, and above all the scale must stay in the list — one that cannot
     * be seen is one that cannot be forgotten (FR-SCALE-014).
     */
    const val UNKNOWN_MODEL: String = "Unknown model"

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

    /**
     * FR-SCALE-013 in one sentence: this block is something to read, never something to set. It
     * is written out because the layout alone — no field, no chevron, no touch target — is a
     * claim a screen reader cannot see.
     */
    const val DIAGNOSTICS_NOTE: String =
        "Nothing to change here. These are the values Mue reads when it looks for this scale."
    const val DIAGNOSTICS_ADDRESS: String = "Bluetooth address"
    const val DIAGNOSTICS_ADVERTISED_NAME: String = "Advertised name"
    const val DIAGNOSTICS_DRIVER: String = "Driver"

    /**
     * FR-SCALE-013: what this phone's Android version asks Mue for before it may look for a
     * scale (PRD_SCALE 16.1). Diagnostics, not a setting — nothing here is granted or revoked,
     * and the row has no control of any kind. It earns its place in the block because it is the
     * one line of it nobody can look up for their own device: the list is `BLUETOOTH_SCAN` and
     * `BLUETOOTH_CONNECT` from Android 12 and `ACCESS_FINE_LOCATION` before it, which is also
     * why a bug report that quotes it is worth more than one that quotes the version number.
     */
    const val DIAGNOSTICS_PERMISSIONS: String = "Permissions"

    /**
     * The short names, as Android's own settings screen writes them.
     *
     * `android.permission.` is dropped because it is the same eighteen characters on every line
     * and says nothing: what identifies the permission is what follows it. The full name never
     * appears on screen and is not needed to look one up.
     */
    fun permissionNames(permissions: List<String>): String =
        permissions.joinToString(", ") { it.substringAfterLast('.') }

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

    /**
     * PRD_SCALE 11: the unstable stream drives the readout, and says so.
     *
     * The value is allowed to move on screen — a screen frozen while someone is visibly standing
     * on a scale watching numbers move reads as broken — and this caption is the second half of
     * that sentence, "marquée comme non définitive". BR-SCALE-001 is upheld on the save path, not
     * by hiding the number.
     */
    const val NOT_FINAL_YET: String = "Not final yet"

    /**
     * FR-SCALE-023 and BR-SCALE-001: why `Save measurement` is quiet while frames arrive.
     *
     * A control that goes dark without saying why is read as a fault. The sentence is also a
     * promise: the wait ends on its own, there is nothing to do about it.
     */
    const val WAITING_TO_SETTLE: String = "Waiting for the weight to settle"

    /*
     * `Save measurement` is deliberately NOT declared here.
     *
     * The button predates this module: it is `EntryScreen`'s own `SaveLabel`, and that is the
     * string the user reads. A copy here claiming FR-SCALE-023 as its authority would be a second
     * literal to keep in step with the first — the very thing this file's KDoc says it exists to
     * prevent — and the scale module never renamed that button anyway. FR-SCALE-023 is upheld by
     * `EntryViewModel.onSave` being the only writer, not by owning the word.
     */

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

    // region Entry: the header link chip (PRD_SCALE 11, 19, 20, FR-SCALE-020)

    /*
     * The link state moved into the header, top right, and a corner of a screen is not a line of
     * text: `Looking for your scale` is the right sentence for a caption under the value and far
     * too long for a chip beside the wordmark. So each state gets a *label* here — two words at
     * most — while the sentence it already owns above stays what a screen reader hears
     * (PRD_SCALE 20). Nothing below duplicates a string: a state whose full wording is already
     * short enough, `Connecting` and `Measuring`, keeps that one constant for both jobs.
     */

    /** [SEARCHING] shortened for the chip; the long form is what is spoken. */
    const val LINK_SEARCHING: String = "Searching"

    /**
     * The link is up and the sequence has been sent. The chip states the *link* — it is ready —
     * while [STEP_ON_THE_SCALE] under the value states the gesture, which is PRD_SCALE 7.4's
     * order of importance: the physical act stays the main one, the connection is a detail.
     */
    const val LINK_READY: String = "Ready"

    /** [BLUETOOTH_IS_OFF] shortened for the chip. The chip is still what opens the setting. */
    const val LINK_BLUETOOTH_OFF: String = "Bluetooth off"

    /** [SCALE_NOT_FOUND] shortened for the chip: what is left is the offer, which is the point. */
    const val LINK_TRY_AGAIN: String = "Try again"

    /** [SCALE_UNAVAILABLE] shortened for the chip — a missing permission, or system location. */
    const val LINK_UNAVAILABLE: String = "Unavailable"

    /**
     * PRD_SCALE 19 and 20: what the chip *says* once it has stopped showing a label.
     *
     * The chip goes wordless when the weight lands. Naming the scale there teaches nothing — the
     * reader is standing on it — and the default name is the model, `HB BODY FAT`, which would
     * blow a header chip open; identity belongs to `Profile > Scales` (FR-SCALE-012). None of that
     * applies to a screen reader, which has no colour and no dot to read, so the spoken form stays
     * whole.
     */
    const val LINK_WEIGHT_RECEIVED: String = "Weight received from your scale"

    /**
     * PRD_SCALE 18.2: between sessions the chip is a dot and nothing else, and this is what it
     * reads as. A scale asleep or out of range is the normal state of a bathroom scale, so the
     * words state it flatly — no fault, no apology (PRD_SCALE 7.3).
     */
    const val LINK_IDLE: String = "No scale in range"

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

    /**
     * What opens the detailed caution of `BodyCompositionFormula` (FR-BODY-005, PRD_SCALE 13.3).
     *
     * A question, not a warning: the text behind it explains where the figures come from, it does
     * not caution against anything.
     */
    const val HOW_ESTIMATED: String = "How these are estimated"

    /** FR-BODY-005: no second composition in the period, so there is no change to show. */
    const val NO_VALUE: String = "—"

    /** FR-BODY-005: the main value carries its own date, so it is not read as the last weigh-in. */
    const val MEASURED_ON_LABEL: String = "Measured on"

    /**
     * FR-BODY-005: the date of the displayed value stays visible, without which it would pass for
     * the last weigh-in — which it is not, since weigh-ins without impedance are skipped when
     * choosing it.
     */
    fun measuredOn(date: String): String = "$MEASURED_ON_LABEL $date"

    /** The change, undated, when there is no earlier composition to name. */
    const val CHANGE_LABEL: String = "Change"

    /** The change with what it is measured against: a change without its date says nothing. */
    fun changeSince(date: String): String = "$CHANGE_LABEL since $date"

    /**
     * The units, spoken (PRD_SCALE 20). The written `%`, `kg` and `kcal` stay in
     * `BodyCompositionMetric` beside the number formatting they belong to; these three are read
     * aloud, so they are words the module puts to a reader and belong here.
     *
     * `%` is announced `percent`, not "percent sign" — which is what a screen reader makes of the
     * character on its own.
     */
    const val SPOKEN_PERCENT: String = "percent"
    const val SPOKEN_KILOGRAMS: String = "kilograms"
    const val SPOKEN_KILOCALORIES: String = "kilocalories"

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

    /**
     * PRD_SCALE 18.4: what is missing, by name.
     *
     * Written from [BodyCompositionResult.ProfileInput] rather than hard-coded, so someone who
     * left out only their sex is not asked again for a height they already gave.
     *
     * **This is the only place the sentence exists.** [PROFILE_INCOMPLETE_BODY] is the same
     * function applied to all three inputs, not a second copy of the wording: the PRD quotes the
     * three-input form, and a second literal would be a second thing to keep in step.
     */
    fun profileIncompleteBody(missing: Set<BodyCompositionResult.ProfileInput>): String {
        val named = BodyCompositionResult.ProfileInput.entries
            .filter { it in missing }
            .map(::profileInputPhrase)
        return "Estimates need ${joinWithAnd(named)}. $IMPEDANCE_KEPT"
    }

    /** PRD_SCALE 18.4, word for word: nothing of the profile is filled in yet. */
    val PROFILE_INCOMPLETE_BODY: String =
        profileIncompleteBody(BodyCompositionResult.ProfileInput.entries.toSet())

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

    /**
     * PRD_SCALE 20: the scale state, exposed to accessibility services as a labelled region.
     *
     * Carried as a pane title by the two lines `Entry` gives the scale — the discreet indication
     * and the actionable status — which are never both on screen, so the name is never
     * ambiguous. It labels the region and says nothing about its contents: what is announced,
     * and when, is decided by `EntryScaleAnnouncement` and by nothing here.
     */
    const val SCALE_STATUS_LABEL: String = "Scale status"

    /**
     * PRD_SCALE 20: the main value, read with its unit spelled out and its date.
     *
     * Only the two labels are lowercased, never the date: `Aug 20, 2026` put through `lowercase`
     * becomes `aug 20, 2026`, which speech synthesisers read as a word.
     *
     * Takes the metric rather than its label and spoken unit as two separate strings: they are
     * adjacent parameters of the same type, and an announcement that swaps them is exactly the
     * kind of mistake no test would catch and no reader could unhear.
     */
    fun valueDescription(metric: BodyCompositionMetric, value: String, date: String): String =
        "${metric.label} ${ESTIMATE.lowercase(Locale.ROOT)} $value ${metric.spokenUnit}, " +
            "${MEASURED_ON_LABEL.lowercase(Locale.ROOT)} $date"

    /** PRD_SCALE 20: a period with no composition, said rather than left to a silent dash. */
    fun valueUnavailableDescription(metric: BodyCompositionMetric): String =
        "${metric.label} estimate unavailable for this period"

    /** PRD_SCALE 20: the change, read with what it compares itself to. */
    fun changeDescription(
        metric: BodyCompositionMetric,
        change: String,
        previousDate: String,
    ): String = "${changeSince(previousDate)}, $change ${metric.spokenUnit}"

    /** PRD_SCALE 20: why the change is a dash. A fact, not something missing. */
    const val NO_PREVIOUS_DESCRIPTION: String = "No earlier estimate in this period"

    // endregion

    // region The wording of a sentence, not sentences of their own

    /**
     * The second sentence of [PROFILE_INCOMPLETE_BODY], repeated word for word while the first is
     * made specific by [profileIncompleteBody].
     */
    private const val IMPEDANCE_KEPT: String =
        "Mue kept the impedance it already measured, so past weigh-ins can be completed too."

    /** The name of each missing input, as the `Profile` screen calls it. */
    private fun profileInputPhrase(input: BodyCompositionResult.ProfileInput): String =
        when (input) {
            BodyCompositionResult.ProfileInput.HEIGHT -> "your height"
            BodyCompositionResult.ProfileInput.BIRTH_DATE -> "your date of birth"
            BodyCompositionResult.ProfileInput.SEX -> "your sex"
        }

    /** `a`, `a and b`, `a, b and c` — no comma before the `and`, as everywhere else in Mue. */
    private fun joinWithAnd(items: List<String>): String = when (items.size) {
        0, 1 -> items.joinToString()
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    // endregion
}
