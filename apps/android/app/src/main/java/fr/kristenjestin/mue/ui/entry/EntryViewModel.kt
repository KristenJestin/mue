package fr.kristenjestin.mue.ui.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Holds the weight the user is composing, the date it belongs to, and nothing else.
 *
 * The value is session state, not stored state: it is seeded once from the history at app
 * start and then belongs to the user until the process ends (PRD FR-ENTRY-001). Everything
 * needed to rebuild it goes through [SavedStateHandle], so a rotation *and* a system-killed
 * process both come back to the same screen (PRD 16.3).
 */
class EntryViewModel(
    private val measurements: MeasurementRepository,
    private val profiles: UserProfileRepository,
    private val preferences: UserPreferencesRepository,
    private val savedState: SavedStateHandle,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoredState())
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()

    /** True once the value on screen belongs to the user; the history must no longer overwrite it. */
    private var valueIsUserOwned: Boolean = savedState.contains(KEY_WEIGHT_HUNDREDTHS)

    init {
        if (!valueIsUserOwned) seedFromHistory()
        observeProfile()
        observePreferences()
    }

    // --- The scale ------------------------------------------------------------------

    /**
     * Called on every frame of a drag, so it must stay allocation-cheap and never suspend.
     *
     * An unchanged value returns at once. That is not only an optimisation: the scale echoes
     * its starting position back the moment it appears, and treating that echo as a choice
     * would cancel the seeding of FR-ENTRY-001 before the history had time to answer.
     */
    fun onWeightChanged(weight: Weight) {
        if (_uiState.value.weight == weight) return
        valueIsUserOwned = true
        savedState[KEY_WEIGHT_HUNDREDTHS] = weight.hundredthsKg
        _uiState.update { it.copy(weight = weight) }
    }

    /** [steps] presses of `−` or `+`, 0.05 kg each, clamped at the end stop (PRD FR-ENTRY-003). */
    fun onStep(steps: Int) {
        val current = _uiState.value.weight
        setWeight(Weight.ofHundredthsClamped(RulerPhysics.step(current.hundredthsKg, steps)))
    }

    /** Any source other than the scale itself; the scale is told to follow. */
    private fun setWeight(weight: Weight) {
        valueIsUserOwned = true
        savedState[KEY_WEIGHT_HUNDREDTHS] = weight.hundredthsKg
        _uiState.update { it.copy(weight = weight, weightRevision = it.weightRevision + 1) }
    }

    // --- Manual entry ---------------------------------------------------------------

    fun onManualEntryOpened() {
        val text = EntryFormat.weight(_uiState.value.weight)
        savedState[KEY_MANUAL_ENTRY] = true
        savedState[KEY_MANUAL_INPUT] = text
        _uiState.update { it.copy(manualEntry = true, manualInput = text, manualError = null) }
    }

    /**
     * Commits every keystroke that parses, so the hero readout tracks what is being typed.
     * A blank field is "not finished yet", not an error: the message would otherwise fire
     * the moment the user clears the value in order to retype it.
     */
    fun onManualInputChanged(raw: String) {
        savedState[KEY_MANUAL_INPUT] = raw
        if (raw.isBlank()) {
            _uiState.update { it.copy(manualInput = raw, manualError = null) }
            return
        }
        when (val parsed = MueValidation.validateWeightInput(raw)) {
            is Validated.Valid -> {
                setWeight(parsed.value)
                _uiState.update { it.copy(manualInput = raw, manualError = null) }
            }

            is Validated.Invalid ->
                _uiState.update { it.copy(manualInput = raw, manualError = parsed.message) }
        }
    }

    /**
     * The keyboard's `Done`. Returns true when the scale came back.
     *
     * PRD FR-ENTRY-004 wants `Done` to restore the scale and PRD 15.3 wants an invalid value
     * kept on screen for correction. Both hold only if an invalid value refuses to close.
     */
    fun onManualEntryConfirmed(): Boolean =
        when (val parsed = MueValidation.validateWeightInput(_uiState.value.manualInput)) {
            is Validated.Valid -> {
                setWeight(parsed.value)
                savedState[KEY_MANUAL_ENTRY] = false
                _uiState.update { it.copy(manualEntry = false, manualError = null) }
                true
            }

            is Validated.Invalid -> {
                _uiState.update { it.copy(manualError = parsed.message) }
                false
            }
        }

    /** Leaving manual entry without committing: the scale keeps the last valid weight. */
    fun onManualEntryDismissed() {
        savedState[KEY_MANUAL_ENTRY] = false
        _uiState.update { it.copy(manualEntry = false, manualError = null) }
    }

    // --- Date -----------------------------------------------------------------------

    fun onDatePickerOpened() {
        _uiState.update { it.copy(datePickerVisible = true) }
    }

    fun onDatePickerDismissed() {
        _uiState.update { it.copy(datePickerVisible = false) }
    }

    /**
     * PRD FR-ENTRY-005: a date change never touches the weight, not even when a measurement
     * already exists on the chosen day. The history is deliberately not consulted here.
     */
    fun onDateSelected(date: LocalDate) {
        val currentDay = today()
        if (!MueValidation.isMeasurementDateAllowed(date, currentDay)) return
        savedState[KEY_DATE] = date.toString()
        _uiState.update { it.copy(date = date, today = currentDay, datePickerVisible = false) }
    }

    // --- Saving ---------------------------------------------------------------------

    /** Creates the measurement, or replaces the one already on that date (PRD BR-001, BR-002). */
    fun onSave() {
        val snapshot = _uiState.value
        if (!MueValidation.isMeasurementDateAllowed(snapshot.date, today())) return
        viewModelScope.launch {
            runCatching { measurements.save(Measurement(snapshot.date, snapshot.weight)) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            justSaved = true,
                            saveError = null,
                            saveFlareCount = it.saveFlareCount + 1,
                        )
                    }
                }
                // PRD 15.4: a failed write never shows a confirmation.
                .onFailure {
                    _uiState.update { it.copy(justSaved = false, saveError = SAVE_ERROR) }
                }
        }
    }

    fun onSaveConfirmationFinished() {
        _uiState.update { it.copy(justSaved = false) }
    }

    // --- Wiring ---------------------------------------------------------------------

    private fun restoredState(): EntryUiState {
        val restoredHundredths: Int? = savedState[KEY_WEIGHT_HUNDREDTHS]
        val restoredDate: String? = savedState[KEY_DATE]
        val currentDay = today()
        return EntryUiState(
            weight = restoredHundredths?.let(Weight::ofHundredthsClamped) ?: Weight.DEFAULT,
            date = restoredDate?.let(LocalDate::parse) ?: currentDay,
            today = currentDay,
            manualEntry = savedState[KEY_MANUAL_ENTRY] ?: false,
            manualInput = savedState[KEY_MANUAL_INPUT] ?: "",
        )
    }

    private fun seedFromHistory() {
        viewModelScope.launch {
            val latest = runCatching { measurements.observeLatest().first() }.getOrNull()
            // A drag that started before the database answered wins; the value is the user's.
            if (latest != null && !valueIsUserOwned) {
                _uiState.update {
                    it.copy(weight = latest.weight, weightRevision = it.weightRevision + 1)
                }
            }
        }
    }

    private fun observeProfile() {
        viewModelScope.launch {
            profiles.profile.collect { profile ->
                val name = MueValidation.normalizeDisplayName(profile.displayName)
                _uiState.update { state -> state.copy(greeting = name?.let { "Hello $it," }) }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferences.preferences.collect { prefs ->
                _uiState.update { it.copy(hapticsEnabled = prefs.hapticsEnabled) }
            }
        }
    }

    companion object {
        /** PRD 15.4 asks for a comprehensible message and a retry, not a silent failure. */
        const val SAVE_ERROR: String = "Could not save this measurement. Try again."

        private const val KEY_WEIGHT_HUNDREDTHS = "entry.weightHundredths"
        private const val KEY_DATE = "entry.date"
        private const val KEY_MANUAL_ENTRY = "entry.manualEntry"
        private const val KEY_MANUAL_INPUT = "entry.manualInput"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                EntryViewModel(
                    measurements = app.container.measurementRepository,
                    profiles = app.container.userProfileRepository,
                    preferences = app.container.userPreferencesRepository,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
