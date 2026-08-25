package fr.kristenjestin.mue.ui.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.logic.ProgressStatistics
import fr.kristenjestin.mue.domain.logic.StatisticsCalculator
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

/**
 * State of the Progress screen (PRD 9.2).
 *
 * Everything but [today] and [isLoading] is derived from the selected period, which is the
 * point of FR-PROGRESS-001: one filter governs the curve, the indicators and the history.
 */
data class ProgressUiState(
    val period: Period,
    val today: LocalDate,
    val isLoading: Boolean,
    /** False only when the history is empty overall, which is what triggers PRD 15.1. */
    val hasAnyMeasurement: Boolean,
    /** Measurements of the period, oldest first — the order the curve is drawn in. */
    val chartPoints: List<Measurement>,
    /** The same measurements, most recent first (PRD FR-PROGRESS-004). */
    val history: List<Measurement>,
    val statistics: ProgressStatistics,
    val bmi: Bmi,
    /** Null while the edit panel is closed. */
    val editor: EditorUiState?,
) {
    /** PRD 15.1: an inviting empty state replaces the chart when nothing was ever recorded. */
    val showEmptyState: Boolean get() = !isLoading && !hasAnyMeasurement
}

/** The edit and delete panel of PRD FR-PROGRESS-005 and FR-PROGRESS-006. */
data class EditorUiState(
    /** The date the measurement had when the panel was opened; the row to replace. */
    val originalDate: LocalDate,
    val date: LocalDate,
    val weightInput: String,
    val weightError: String?,
    val datePickerVisible: Boolean,
    val deleteConfirmationVisible: Boolean,
)

class ProgressViewModel(
    private val measurementRepository: MeasurementRepository,
    userProfileRepository: UserProfileRepository,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    /**
     * Only the validation message is kept out of [savedStateHandle]: it is the result of a
     * save attempt, not something the user typed, and re-showing it after a process death
     * would be noise.
     */
    private val weightError = MutableStateFlow<String?>(null)

    private val editorFlow: Flow<EditorUiState?> = combine(
        savedStateHandle.getStateFlow<String?>(KEY_EDIT_ORIGINAL_DATE, null),
        savedStateHandle.getStateFlow<String?>(KEY_EDIT_DATE, null),
        savedStateHandle.getStateFlow<String?>(KEY_EDIT_WEIGHT, null),
        combine(
            savedStateHandle.getStateFlow(KEY_DATE_PICKER_VISIBLE, false),
            savedStateHandle.getStateFlow(KEY_DELETE_CONFIRMATION_VISIBLE, false),
            weightError,
        ) { datePicker, deleteConfirmation, error -> Triple(datePicker, deleteConfirmation, error) },
    ) { original, date, weight, dialogs ->
        original?.let {
            EditorUiState(
                originalDate = LocalDate.parse(it),
                date = LocalDate.parse(date ?: it),
                weightInput = weight.orEmpty(),
                weightError = dialogs.third,
                datePickerVisible = dialogs.first,
                deleteConfirmationVisible = dialogs.second,
            )
        }
    }

    val uiState: StateFlow<ProgressUiState> = combine(
        measurementRepository.observeAll(),
        userProfileRepository.profile,
        savedStateHandle.getStateFlow(KEY_PERIOD, DEFAULT_PERIOD.name),
        editorFlow,
    ) { measurements, profile, periodName, editor ->
        buildState(measurements, profile, periodOf(periodName), editor, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildState(
            measurements = emptyList(),
            profile = UserProfile.EMPTY,
            period = periodOf(savedStateHandle[KEY_PERIOD]),
            editor = null,
            isLoading = true,
        ),
    )

    fun selectPeriod(period: Period) {
        savedStateHandle[KEY_PERIOD] = period.name
    }

    fun openEditor(measurement: Measurement) {
        weightError.value = null
        savedStateHandle[KEY_EDIT_ORIGINAL_DATE] = measurement.date.toString()
        savedStateHandle[KEY_EDIT_DATE] = measurement.date.toString()
        savedStateHandle[KEY_EDIT_WEIGHT] = ProgressFormat.weight(measurement.weight)
        savedStateHandle[KEY_DATE_PICKER_VISIBLE] = false
        savedStateHandle[KEY_DELETE_CONFIRMATION_VISIBLE] = false
    }

    fun dismissEditor() {
        weightError.value = null
        savedStateHandle[KEY_EDIT_ORIGINAL_DATE] = null
        savedStateHandle[KEY_EDIT_DATE] = null
        savedStateHandle[KEY_EDIT_WEIGHT] = null
        savedStateHandle[KEY_DATE_PICKER_VISIBLE] = false
        savedStateHandle[KEY_DELETE_CONFIRMATION_VISIBLE] = false
    }

    fun updateWeightInput(input: String) {
        savedStateHandle[KEY_EDIT_WEIGHT] = input
        // PRD 15.3 keeps the typed value for correction; the message goes as soon as it changes.
        weightError.value = null
    }

    fun openDatePicker() {
        savedStateHandle[KEY_DATE_PICKER_VISIBLE] = true
    }

    fun dismissDatePicker() {
        savedStateHandle[KEY_DATE_PICKER_VISIBLE] = false
    }

    /** Silently ignores a future date (PRD BR-009); the picker already refuses to offer one. */
    fun updateDate(date: LocalDate) {
        savedStateHandle[KEY_DATE_PICKER_VISIBLE] = false
        if (!MueValidation.isMeasurementDateAllowed(date, today())) return
        savedStateHandle[KEY_EDIT_DATE] = date.toString()
    }

    /**
     * PRD FR-PROGRESS-005: the weight follows the Entry rules, and moving the measurement
     * onto an occupied date replaces it with no extra confirmation.
     */
    fun saveEdit() {
        val originalDate = editedOriginalDate() ?: return
        val date = editedDate() ?: return
        if (!MueValidation.isMeasurementDateAllowed(date, today())) return

        when (val validated = MueValidation.validateWeightInput(editedWeightInput())) {
            is Validated.Invalid -> weightError.value = validated.message
            is Validated.Valid -> viewModelScope.launch {
                measurementRepository.replace(originalDate, Measurement(date, validated.value))
                dismissEditor()
            }
        }
    }

    /** PRD BR-006: a deletion always goes through a confirmation. */
    fun requestDelete() {
        savedStateHandle[KEY_DELETE_CONFIRMATION_VISIBLE] = true
    }

    fun cancelDelete() {
        savedStateHandle[KEY_DELETE_CONFIRMATION_VISIBLE] = false
    }

    fun confirmDelete() {
        val originalDate = editedOriginalDate() ?: return
        viewModelScope.launch {
            measurementRepository.delete(originalDate)
            dismissEditor()
        }
    }

    private fun buildState(
        measurements: List<Measurement>,
        profile: UserProfile,
        period: Period,
        editor: EditorUiState?,
        isLoading: Boolean,
    ): ProgressUiState {
        val today = today()
        val window = period.windowEndingOn(today)
        val inPeriod = measurements.filter { it.date in window }.sortedBy { it.date }
        val statistics = StatisticsCalculator.compute(inPeriod)

        return ProgressUiState(
            period = period,
            today = today,
            isLoading = isLoading,
            hasAnyMeasurement = measurements.isNotEmpty(),
            chartPoints = inPeriod,
            history = inPeriod.asReversed(),
            statistics = statistics,
            // BR-004 and FR-PROGRESS-003: the BMI of this screen follows the period's own
            // current weight, so an empty period shows no BMI rather than an older one.
            bmi = BmiCalculator.calculate(statistics.currentWeight, profile, today),
            editor = editor,
        )
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun editedOriginalDate(): LocalDate? =
        savedStateHandle.get<String>(KEY_EDIT_ORIGINAL_DATE)?.let(LocalDate::parse)

    private fun editedDate(): LocalDate? =
        savedStateHandle.get<String>(KEY_EDIT_DATE)?.let(LocalDate::parse)
            ?: editedOriginalDate()

    private fun editedWeightInput(): String =
        savedStateHandle.get<String>(KEY_EDIT_WEIGHT).orEmpty()

    private fun periodOf(name: String?): Period =
        name?.let { runCatching { Period.valueOf(it) }.getOrNull() } ?: DEFAULT_PERIOD

    companion object {
        /** The filter the approved prototype opens on. */
        val DEFAULT_PERIOD: Period = Period.THIRTY_DAYS

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private const val KEY_PERIOD = "progress.period"
        private const val KEY_EDIT_ORIGINAL_DATE = "progress.editor.originalDate"
        private const val KEY_EDIT_DATE = "progress.editor.date"
        private const val KEY_EDIT_WEIGHT = "progress.editor.weight"
        private const val KEY_DATE_PICKER_VISIBLE = "progress.editor.datePicker"
        private const val KEY_DELETE_CONFIRMATION_VISIBLE = "progress.editor.deleteConfirmation"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                ProgressViewModel(
                    measurementRepository = app.container.measurementRepository,
                    userProfileRepository = app.container.userProfileRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}
