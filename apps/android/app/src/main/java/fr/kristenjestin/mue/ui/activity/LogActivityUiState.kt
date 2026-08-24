package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.MetricSource
import fr.kristenjestin.mue.domain.model.Movement
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Everything the `Log activity` form draws (PRD FR-ACTIVITY-004 to 008, 010 and 011).
 *
 * The numeric fields are the raw strings of [ActivityDraft] rather than parsed values: a
 * half-typed `7,` has to stay on screen exactly as typed, and PRD 12 forbids showing an absent
 * optional value as a zero. Parsing happens once, on save.
 */
@Immutable
data class LogActivityUiState(
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val preset: ActivityPreset = ActivityPreset.DEFAULT,
    val today: LocalDate = LocalDate.now(),
    val date: LocalDate = LocalDate.now(),
    /** PRD 8.2: the optional start time. Null is no time at all, and is not midnight. */
    val startTime: LocalTime? = null,
    val hours: String = "",
    val minutes: String = "",
    /** PRD FR-TIMER-006: the seconds of a measured duration; blank for a hand-typed one. */
    val seconds: String = "",
    /** FR-TIMER-005: this form is reviewing a timer, which is what puts seconds on screen. */
    val isTimedReview: Boolean = false,
    val perceivedEffort: Int? = null,
    val notes: String = "",
    /** PRD 9.1: the reversible `Quick log` / `Detailed log` choice. */
    val detailed: Boolean = false,
    val exerciseCount: Int = 0,
    /** How many exercises the stored session had when it was opened; drives the warning. */
    val storedExerciseCount: Int = 0,
    val metrics: List<MetricFieldState> = emptyList(),
    /** The `Other` builder's catalogue choice, null until one is made. */
    val movement: Movement? = null,
    val customMovementName: String = "",
    val environment: ActivityEnvironment = ActivityEnvironment.UNKNOWN,
    val equipment: List<EquipmentChipState> = emptyList(),
    val dateError: String? = null,
    val startTimeError: String? = null,
    val durationError: String? = null,
    val movementError: String? = null,
    /** PRD 12: the same message again beside the save action, for accessibility. */
    val formError: String? = null,
    /** PRD 13.4: the write failed and nothing was lost. */
    val saveError: String? = null,
    val isSaving: Boolean = false,
    val datePickerVisible: Boolean = false,
    val timePickerVisible: Boolean = false,
    /** FR-TIMER-006: the three-field correction, which manual entry never opens. */
    val durationPickerVisible: Boolean = false,
    val picker: CatalogPickerState? = null,
    val deleteConfirmationVisible: Boolean = false,
    val quickLogConfirmationVisible: Boolean = false,
    /** Drives the button's discharge; the return to the dashboard follows it (contract 8). */
    val justSaved: Boolean = false,
    val justDeleted: Boolean = false,
    val hapticsEnabled: Boolean = true,
) {
    val screenTitle: String
        get() = if (isEditing) LogActivityMessages.EDIT_TITLE else LogActivityMessages.SCREEN_TITLE

    val saveLabel: String
        get() = if (isEditing) LogActivityMessages.SAVE_CHANGES else LogActivityMessages.SAVE_ACTIVITY

    /** PRD 8.5: only the builder asks which activity this was. */
    val showsBuilder: Boolean get() = preset == ActivityPreset.OTHER

    /** PRD 9.1 and FR-ACTIVITY-008: the builder *and* the quick strength log collect equipment. */
    val showsEquipment: Boolean get() = preset.choosesEquipment

    val showsStrengthDetail: Boolean get() = preset.offersStrengthDetail

    /** The free name wins, exactly as it does on a stored session (PRD FR-ACTIVITY-008). */
    val mainActivityLabel: String
        get() = when {
            movement == Movement.OTHER && customMovementName.isNotBlank() -> customMovementName.trim()
            movement != null && movement != Movement.OTHER -> movement.displayName
            else -> LogActivityMessages.CHOOSE_FROM_CATALOGUE
        }

    val hasMainActivity: Boolean
        get() = movement != null &&
            (movement != Movement.OTHER || customMovementName.isNotBlank())
}

/** One measurement field of the active preset (PRD FR-ACTIVITY-006 and 007). */
@Immutable
data class MetricFieldState(
    val kind: MetricKind,
    val input: String,
    val error: String? = null,
    /** PRD 11.3: a treadmill's calorie readout keeps the machine's provenance. */
    val source: MetricSource = MetricSource.MANUAL,
) {
    /** A pace is one stored value typed in two boxes (`7:10 /km`). */
    val isPace: Boolean get() = kind == MetricKind.AVERAGE_PACE

    val paceMinutes: String get() = LogActivityFormat.splitClock(input).first

    val paceSeconds: String get() = LogActivityFormat.splitClock(input).second
}

/** One removable equipment tag of the builder (PRD FR-ACTIVITY-008). */
@Immutable
data class EquipmentChipState(val index: Int, val label: String)

/** Which catalogue the bottom sheet is showing (PRD FR-ACTIVITY-008). */
enum class CatalogTarget { MOVEMENT, EQUIPMENT }

/** One row of the catalogue sheet. */
@Immutable
data class CatalogEntry(
    val id: String,
    val name: String,
    val meta: String,
    val selected: Boolean = false,
)

/**
 * The searchable catalogue of PRD FR-ACTIVITY-008, with the rows already filtered: a form
 * never filters a list in composition.
 */
@Immutable
data class CatalogPickerState(
    val target: CatalogTarget,
    val query: String = "",
    val results: List<CatalogEntry> = emptyList(),
    /** A short line under the search: a refused duplicate, or a name that will not do. */
    val notice: String? = null,
) {
    val trimmedQuery: String get() = query.trim()

    /** `Create` needs something to name; the prototype disables it on an empty search. */
    val canCreate: Boolean get() = trimmedQuery.isNotEmpty()
}

/**
 * The words of the screen. Constants rather than resources, as everywhere else in Mue: the app
 * ships in English only and the tests assert them character for character.
 */
object LogActivityMessages {

    const val SCREEN_TITLE: String = "Log activity"
    const val EDIT_TITLE: String = "Edit activity"
    const val EYEBROW: String = "What did you do?"
    const val TITLE: String = "Make it yours."

    const val DATE_LABEL: String = "Date"
    const val CHANGE_DATE: String = "Change"
    const val START_TIME_LABEL: String = "Start time · optional"

    /** What the start-time field reads before one is picked; never a plausible `00:00`. */
    const val NO_START_TIME: String = "Not set"
    const val CHANGE_START_TIME: String = "Change"

    const val DATE_SHEET_TITLE: String = "Activity date"
    const val TIME_SHEET_TITLE: String = "Start time"
    const val CLOSE_DATE_SHEET: String = "Close the date picker"
    const val CLOSE_TIME_SHEET: String = "Close the start time picker"
    const val USE_THIS_DATE: String = "Use this date"
    const val USE_THIS_TIME: String = "Use this time"

    /**
     * The correction PRD FR-TIMER-006 opens from the duration summary, worded exactly as the
     * date and start-time panels beside it are: the timer borrows the form's own furniture.
     */
    const val DURATION_SHEET_TITLE: String = "Activity duration"
    const val CLOSE_DURATION_SHEET: String = "Close the duration picker"
    const val USE_THIS_DURATION: String = "Use this duration"

    /** PRD 8.2 keeps the start time optional, so the sheet has to be able to take it back off. */
    const val CLEAR_START_TIME: String = "Clear"

    const val DURATION_LABEL: String = "Duration"
    const val HOURS_SUFFIX: String = "h"
    const val MINUTES_SUFFIX: String = "min"

    /** The caption under each duration wheel, and the name a screen reader gives it. */
    const val HOURS_UNIT: String = "hours"
    const val MINUTES_UNIT: String = "minutes"
    const val DURATION_HOURS_LABEL: String = "Duration in hours"
    const val DURATION_MINUTES_LABEL: String = "Duration in minutes"
    const val NOTES_LABEL: String = "Notes"

    const val OPTIONAL_BADGE: String = "OPTIONAL"
    const val FROM_EQUIPMENT: String = "From equipment"

    const val BUILDER_TITLE: String = "Build your activity"
    const val BUILDER_SUBTITLE: String = "Start with the catalogue. Create only if needed."
    const val MAIN_ACTIVITY_LABEL: String = "Main activity"
    const val CHOOSE_FROM_CATALOGUE: String = "Choose from catalogue"
    const val ENVIRONMENT_LABEL: String = "Environment"
    const val EQUIPMENT_LABEL: String = "Equipment · optional"
    const val CHOOSE_EQUIPMENT: String = "Choose equipment"
    const val ADD_ANOTHER_EQUIPMENT: String = "Add another equipment"

    const val DETAIL_TITLE: String = "How much detail?"
    const val DETAIL_SUBTITLE: String =
        "A quick summary is enough. Sets are there when you want them."
    const val QUICK_LOG: String = "Quick log"
    const val QUICK_LOG_DESCRIPTION: String = "Duration, effort and energy"
    const val DETAILED_LOG: String = "Detailed"
    const val DETAILED_LOG_DESCRIPTION: String = "Exercises, sets and reps"

    const val SAVE_ACTIVITY: String = "Save activity"
    const val SAVE_CHANGES: String = "Save changes"
    const val DELETE_ACTIVITY: String = "Delete activity"
    const val ACTIVITY_DELETED: String = "Activity deleted"

    /** PRD 13.4, word for word. */
    const val SAVE_FAILED: String = "Couldn’t save. Your activity is still here."

    /** The same promise on the other write path: nothing was lost, try again. */
    const val DELETE_FAILED: String = "Couldn’t delete. Your activity is still here."
    const val TRY_AGAIN: String = "Try again"

    /** PRD FR-ACTIVITY-009: a detailed session is not saved without one complete set. */
    const val NO_VALID_SET: String = "Add at least one complete set, or switch to Quick log"

    /** PRD 8.2: the optional start time is a minute of the day, or nothing at all. */
    const val START_TIME_ERROR: String = "Enter a time between 00:00 and 23:59"

    /** PRD FR-ACTIVITY-008: the builder needs an activity before anything can be written. */
    const val MOVEMENT_REQUIRED: String = "Choose an activity, or create one"

    const val ALREADY_ADDED: String = "Already on this activity"

    const val SEARCH_ACTIVITY_PLACEHOLDER: String = "Search yoga, hiking, rowing…"
    const val SEARCH_EQUIPMENT_PLACEHOLDER: String = "Search mat, bands, machine…"

    /** What TalkBack — and a Compose test — calls each search line. */
    const val SEARCH_ACTIVITY_LABEL: String = "Search activities"
    const val SEARCH_EQUIPMENT_LABEL: String = "Search equipment"

    const val ACTIVITY_PICKER_TITLE: String = "Choose an activity"
    const val ACTIVITY_PICKER_EYEBROW: String = "What best describes it?"
    const val EQUIPMENT_PICKER_TITLE: String = "Choose equipment"
    const val EQUIPMENT_PICKER_EYEBROW: String = "Select one or more"
    const val NO_CATALOGUE_MATCH: String = "No catalogue match."
    const val COMMON_CHOICES: String = "Common choices"
    const val RESULTS: String = "Results"
    const val CREATE_HINT: String = "Search before creating a custom item"

    /** `Estimated energy · optional` — how the strength editor names the same field. */
    fun optional(label: String): String = "$label · optional"

    /**
     * What a duration wheel says out loud as it moves. The unit is spoken with the number
     * because the wheel only draws the digits (PRD_ACTIVITIES 15).
     */
    fun spokenHours(hours: Int): String = if (hours == 1) "1 hour" else "$hours hours"

    fun spokenMinutes(minutes: Int): String =
        if (minutes == 1) "1 minute" else "$minutes minutes"

    fun resultCount(count: Int): String = if (count == 1) "1 result" else "$count results"

    fun create(name: String): String = "Create “$name”"

    /** What the header of the per-preset block says, e.g. `Treadmill details`. */
    fun detailsTitle(preset: ActivityPreset): String = when (preset) {
        ActivityPreset.TREADMILL_WALK -> "Treadmill details"
        ActivityPreset.OUTDOOR_WALK -> "Walk details"
        ActivityPreset.RUN -> "Run details"
        ActivityPreset.CYCLING -> "Cycling details"
        ActivityPreset.STRENGTH_TRAINING, ActivityPreset.OTHER -> "Activity details"
    }

    /** The count the detailed choice carries once exercises exist. */
    fun exerciseCount(count: Int): String =
        if (count == 1) "1 exercise" else "$count exercises"
}

/**
 * Display formatting for the form.
 *
 * PRD 12 is two opposite trips: parsing accepts `.` and `,` whatever the phone's language is —
 * which `ActivityValidation` does — and what is shown back follows that language, which is
 * what happens here. Both halves matter; doing only one is the likeliest locale bug.
 */
object LogActivityFormat {

    /** `HH:mm`, the shape PRD 16.3 stores an optional start time in. */
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

    /**
     * The draft keeps the start time as the `HH:mm` PRD 16.3 stores, so a half-written draft
     * survives a process death as text. Anything the app did not write reads as no time.
     */
    fun timeOrNull(raw: String?): LocalTime? = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalTime.parse(it, TIME) }.getOrNull() }

    /** What the start-time field shows: the phone's own clock convention, or `Not set`. */
    fun startTime(time: LocalTime?, locale: Locale = Locale.getDefault()): String =
        time?.let { ActivityFormat.time(it, locale) } ?: LogActivityMessages.NO_START_TIME

    /**
     * A value typed in two boxes is kept joined by a colon, so a preset draft holds one string
     * per metric whatever the field looks like. A blank pair joins to nothing at all, which is
     * what keeps an untouched optional field out of the database.
     */
    fun splitClock(raw: String?): Pair<String, String> {
        val value = raw.orEmpty()
        if (!value.contains(':')) return value to ""
        return value.substringBefore(':') to value.substringAfter(':')
    }

    fun joinClock(first: String, second: String): String =
        if (first.isBlank() && second.isBlank()) "" else "$first:$second"

    /**
     * A stored measurement, back in the display unit and the phone's language (PRD 12).
     *
     * The field renders every decimal the kind can hold and no more, so re-opening a session
     * and saving it again writes back the value that was there: `2950 m` reads `2.95`, and
     * `2.95` parses to `2950 m`. Trailing zeros are dropped — a round `3 km` reads `3`, not
     * `3.00`, exactly as a round load reads `60` — because they carry nothing either way.
     */
    fun metricInput(
        kind: MetricKind,
        canonical: Int,
        locale: Locale = Locale.getDefault(),
    ): String = if (kind == MetricKind.AVERAGE_PACE) {
        clock(canonical, locale)
    } else {
        number(kind.toDisplayValue(canonical), kind.displayDecimals, locale)
    }

    /**
     * Grouping is off on purpose: a `12,345` handed back to `ActivityValidation.parseDecimal`
     * — which treats a comma as the decimal mark, whatever the phone's language — would come
     * back as a different number entirely.
     */
    private fun number(value: Double, decimals: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = decimals
        }.format(value)

    /** `m:ss`, which is how both a pace and a set duration are typed and read back. */
    fun clock(seconds: Int, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d:%02d", seconds / 60, seconds % 60)

    /**
     * Kilograms with no more decimals than the load actually carries: `60`, `62.5`, `62.55`.
     * A trailing `.00` on every round weight reads like a precision nobody claimed.
     */
    fun loadInput(load: Load, locale: Locale = Locale.getDefault()): String =
        if (load.grams % Load.GRAMS_PER_KILOGRAM == 0) {
            String.format(locale, "%d", load.grams / Load.GRAMS_PER_KILOGRAM)
        } else {
            String.format(locale, "%.2f", load.kilograms).trimEnd('0')
        }

    /** The one-line description under a catalogue entry, as in the prototype's sheet. */
    fun meta(movement: Movement): String = when (movement) {
        Movement.SWIMMING -> "Pool or open water"
        Movement.ROWING -> "Indoor or outdoor"
        Movement.ELLIPTICAL -> "Cardio · usually indoor"
        Movement.HIKING -> "Walking · usually outdoor"
        Movement.YOGA -> "Mobility & mind-body"
        Movement.CLIMBING -> "Bouldering, wall or outdoor"
        Movement.DANCING -> "Dance & movement"
        Movement.PILATES -> "Core & mobility"
        Movement.MOBILITY -> "Stretching & recovery"
        Movement.TEAM_SPORT -> "Football, basketball…"
        Movement.WALKING, Movement.RUNNING, Movement.CYCLING,
        Movement.STRENGTH_TRAINING, Movement.OTHER,
        -> "Activity"
    }

    fun meta(equipment: EquipmentType): String = when (equipment) {
        EquipmentType.TREADMILL, EquipmentType.STATIONARY_BIKE,
        EquipmentType.ROWING_MACHINE, EquipmentType.ELLIPTICAL_MACHINE,
        -> "Cardio machine"
        EquipmentType.MACHINE -> "Gym machine"
        EquipmentType.BARBELL, EquipmentType.DUMBBELLS, EquipmentType.KETTLEBELL -> "Free weights"
        EquipmentType.RESISTANCE_BANDS -> "Strength equipment"
        EquipmentType.YOGA_MAT -> "Floor equipment"
        EquipmentType.BODYWEIGHT -> "No equipment"
        EquipmentType.CLIMBING_WALL -> "Climbing equipment"
        EquipmentType.BICYCLE -> "Outdoor equipment"
        EquipmentType.POOL -> "Swimming environment"
        EquipmentType.OTHER -> "Custom"
    }
}
