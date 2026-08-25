package fr.kristenjestin.mue.ui.activity

/**
 * Handles for the Compose tests of the module. They exist for the parts a test cannot address
 * by their visible text: lists that scroll, rows that repeat, and the weekly bars, whose whole
 * job is sometimes to be empty.
 *
 * Fields keyed by an id — a metric, an exercise, a set — build their tag from that id so a test
 * can name one row among many without counting positions.
 */
internal object ActivityTestTags {

    const val DASHBOARD: String = "activity:dashboard"
    const val WEEKLY_BARS: String = "activity:weeklyBars"
    const val RECENT_LIST: String = "activity:recentList"
    const val SEE_ALL: String = "activity:seeAll"

    /*
     * The dashboard's own action has no tag here any more. PRD_ACTIVITY_TIMER 17 replaced the
     * single `Log activity` with `Start activity` and `Log past activity`, and both of them —
     * like the review block and the `Start again` shortcut beside them — belong to the timer's
     * vocabulary and are named in `TimerTestTags`.
     */

    const val HISTORY_LIST: String = "activity:historyList"

    const val PRESET_ROW: String = "activity:presetRow"
    const val DATE_FIELD: String = "activity:dateField"
    const val START_TIME_FIELD: String = "activity:startTimeField"
    const val START_TIME_PICKER: String = "activity:startTimePicker"
    const val CLEAR_START_TIME: String = "activity:clearStartTime"
    const val CONFIRM_START_TIME: String = "activity:confirmStartTime"
    const val DURATION_HOURS_FIELD: String = "activity:durationHours"
    const val DURATION_MINUTES_FIELD: String = "activity:durationMinutes"
    const val EFFORT_SLIDER: String = "activity:effortSlider"
    const val NOTES_FIELD: String = "activity:notesField"
    const val MOVEMENT_PICKER: String = "activity:movementPicker"
    const val ENVIRONMENT_PICKER: String = "activity:environmentPicker"
    const val EQUIPMENT_PICKER: String = "activity:equipmentPicker"
    /** The whole pinned band: its ramp, the save action, and what sits beside it. */
    const val SAVE_AREA: String = "activity:saveArea"
    const val SAVE_BUTTON: String = "activity:saveButton"
    const val DELETE_BUTTON: String = "activity:deleteButton"

    const val QUICK_LOG: String = "activity:quickLog"
    const val DETAILED_LOG: String = "activity:detailedLog"
    const val EXERCISE_LIST: String = "activity:exerciseList"
    const val EXERCISE_PICKER: String = "activity:exercisePicker"
    const val EXERCISE_SEARCH: String = "activity:exerciseSearch"
    const val ADD_EXERCISE: String = "activity:addExercise"

    fun preset(presetId: String): String = "activity:preset:$presetId"

    fun metricField(kindId: String): String = "activity:metric:$kindId"

    /** One row of the movement or equipment catalogue; the chip it adds carries the same name. */
    fun catalogEntry(entryId: String): String = "activity:catalogEntry:$entryId"

    fun equipmentChip(index: Int): String = "activity:equipment:$index"

    fun exercise(index: Int): String = "activity:exercise:$index"

    fun set(exerciseIndex: Int, setIndex: Int): String = "activity:set:$exerciseIndex:$setIndex"

    fun addSet(exerciseIndex: Int): String = "activity:addSet:$exerciseIndex"

    fun duplicateSet(exerciseIndex: Int): String = "activity:duplicateSet:$exerciseIndex"

    fun weeklyBar(dayIndex: Int): String = "activity:weeklyBar:$dayIndex"

    fun sessionCard(sessionId: String): String = "activity:session:$sessionId"
}
