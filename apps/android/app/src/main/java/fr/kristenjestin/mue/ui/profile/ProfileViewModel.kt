package fr.kristenjestin.mue.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.logic.ProfileValidation
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * State holder of the Profile screen: the health profile form, the derived BMI, the
 * preferences and the CSV export (PRD 9.3, 9.4, 9.5).
 *
 * The unsaved form lives in [SavedStateHandle] rather than in a plain flow so a rotation or
 * a process death cannot lose what the user was typing (PRD 16.3).
 */
class ProfileViewModel(
    private val profileRepository: UserProfileRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val measurementRepository: MeasurementRepository,
    private val exporter: WeightDataExporter,
    private val savedStateHandle: SavedStateHandle,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())
    private val eventChannel = Channel<ProfileEvent>(Channel.BUFFERED)

    /** One-shot effects; the screen turns [ProfileEvent.ShareCsv] into an Android intent. */
    val events: Flow<ProfileEvent> = eventChannel.receiveAsFlow()

    private val form: Flow<FormSnapshot> = combine(
        savedStateHandle.getStateFlow(KEY_DISPLAY_NAME, ""),
        savedStateHandle.getStateFlow(KEY_HEIGHT, ""),
        savedStateHandle.getStateFlow(KEY_BIRTH_DATE, ""),
        savedStateHandle.getStateFlow(KEY_HEIGHT_ERROR, ""),
        savedStateHandle.getStateFlow(KEY_BIRTH_DATE_ERROR, ""),
    ) { name, height, birthDate, heightError, birthDateError ->
        FormSnapshot(
            displayName = name,
            heightInput = height,
            birthDate = birthDate.toLocalDateOrNull(),
            heightError = heightError.ifEmpty { null },
            birthDateError = birthDateError.ifEmpty { null },
        )
    }

    val state: StateFlow<ProfileUiState> = combine(
        form,
        measurementRepository.observeLatest(),
        preferencesRepository.preferences,
        transient,
    ) { snapshot, latest, preferences, transientState ->
        val currentDay = today()
        // The card follows the form, not the stored profile: typing a height must move the
        // BMI immediately, and clearing the field must make it disappear (PRD FR-PROFILE-001).
        val height = MueValidation.validateHeightInput(snapshot.heightInput)
        val birthDate = MueValidation.validateBirthDate(snapshot.birthDate, currentDay)

        ProfileUiState(
            displayName = snapshot.displayName,
            heightInput = snapshot.heightInput,
            birthDate = snapshot.birthDate,
            ageYears = UserProfile(birthDate = birthDate.valueOrNull).ageOn(currentDay),
            heightError = snapshot.heightError,
            birthDateError = snapshot.birthDateError,
            bmi = BmiCalculator.calculate(
                weight = latest?.weight,
                heightCm = height.valueOrNull,
                birthDate = birthDate.valueOrNull,
                today = currentDay,
            ),
            hapticsEnabled = preferences.hapticsEnabled,
            profileSaved = transientState.profileSaved,
            saveEchoCount = transientState.saveEchoCount,
            saveError = transientState.saveError,
            export = transientState.export,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = ProfileUiState(),
    )

    /**
     * The form follows the stored profile until the user takes it over, and **only** the user
     * takes it over.
     *
     * It used to read once with `first()` and then set [KEY_FORM_OWNED] itself. That made
     * "seeded" and "typed in" the same state, and on a fresh install the two are hours apart: the
     * owner has to open this screen to reach `Server settings` and pair, so the one read happens
     * *before* the first synchronisation and returns an empty profile. The form was then frozen
     * against the height and birth date the pull applied moments later, and the next save wrote
     * that stale empty snapshot back through `UserProfileRepository.save` — which replaces the
     * row *and* journals a mutation, so the nulls reached `mue_app.health_profile` too and the
     * server merged them as "the user cleared their profile".
     *
     * Collecting instead of reading once is what makes a profile arriving from another device
     * converge here, which is the client half of PRD 13.4. [KEY_FORM_OWNED] keeps its original
     * job — a keystroke must never be overwritten by a store that answers afterwards — and it is
     * now set by [takeOverForm] alone, which is to say by an actual edit.
     */
    init {
        viewModelScope.launch {
            profileRepository.profile.collect { stored ->
                // From the first edit the form is the user's, and no stored value may replace
                // what they are looking at.
                if (isFormOwnedByUser()) return@collect
                savedStateHandle[KEY_DISPLAY_NAME] = stored.displayName.orEmpty()
                savedStateHandle[KEY_HEIGHT] = stored.heightCm?.toString().orEmpty()
                savedStateHandle[KEY_BIRTH_DATE] = stored.birthDate?.toString().orEmpty()
            }
        }
    }

    fun onDisplayNameChange(value: String) {
        takeOverForm()
        savedStateHandle[KEY_DISPLAY_NAME] = value.take(UserProfile.MAX_DISPLAY_NAME_LENGTH)
    }

    /**
     * Centimetres are whole numbers, so anything else is dropped as it is typed; the
     * out-of-range message is then the only failure the user can still hit.
     */
    fun onHeightChange(value: String) {
        takeOverForm()
        savedStateHandle[KEY_HEIGHT] = value.filter(Char::isDigit).take(MAX_HEIGHT_DIGITS)
        savedStateHandle[KEY_HEIGHT_ERROR] = ""
    }

    /** A null [date] clears the optional birth date (PRD FR-PROFILE-002). */
    fun onBirthDateChange(date: LocalDate?) {
        takeOverForm()
        savedStateHandle[KEY_BIRTH_DATE] = date?.toString().orEmpty()
        savedStateHandle[KEY_BIRTH_DATE_ERROR] = ""
    }

    /**
     * PRD FR-PROFILE-003: one invalid field blocks the whole save, the typed values stay,
     * and every failing field states its own message.
     */
    fun saveProfile() {
        // Whatever is on screen is now deliberate, so a first read still in flight must not
        // come back and replace it.
        takeOverForm()
        val validation = MueValidation.validateProfile(
            displayName = savedStateHandle[KEY_DISPLAY_NAME] ?: "",
            heightInput = savedStateHandle[KEY_HEIGHT] ?: "",
            birthDate = (savedStateHandle[KEY_BIRTH_DATE] ?: "").toLocalDateOrNull(),
            today = today(),
        )

        when (validation) {
            is ProfileValidation.Invalid -> {
                savedStateHandle[KEY_HEIGHT_ERROR] = validation.heightError.orEmpty()
                savedStateHandle[KEY_BIRTH_DATE_ERROR] = validation.birthDateError.orEmpty()
                transient.update { it.copy(profileSaved = false, saveError = null) }
            }

            is ProfileValidation.Valid -> {
                savedStateHandle[KEY_HEIGHT_ERROR] = ""
                savedStateHandle[KEY_BIRTH_DATE_ERROR] = ""
                viewModelScope.launch {
                    try {
                        profileRepository.save(validation.profile)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        transient.update { it.copy(profileSaved = false, saveError = SAVE_ERROR) }
                        return@launch
                    }
                    // Echo back what was actually stored, so a trimmed name is not a surprise.
                    savedStateHandle[KEY_DISPLAY_NAME] = validation.profile.displayName.orEmpty()
                    savedStateHandle[KEY_HEIGHT] = validation.profile.heightCm?.toString().orEmpty()
                    transient.update {
                        it.copy(
                            profileSaved = true,
                            saveError = null,
                            saveEchoCount = it.saveEchoCount + 1,
                        )
                    }
                }
            }
        }
    }

    /** Called by the button once its confirmation label has had its second on screen. */
    fun onSaveConfirmationFinished() {
        transient.update { it.copy(profileSaved = false) }
    }

    fun onHapticsEnabledChange(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setHapticsEnabled(enabled) }
    }

    /**
     * Builds the complete history into a finished file, then asks the screen to open the
     * share sheet. Nothing is announced until the file exists (PRD 15.4).
     */
    fun exportWeightData() {
        if (transient.value.export is ExportState.InProgress) return
        transient.update { it.copy(export = ExportState.InProgress) }
        viewModelScope.launch {
            val file = try {
                exporter.export(measurementRepository.getAll(), today())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                transient.update { it.copy(export = ExportState.Failed(EXPORT_ERROR)) }
                return@launch
            }
            transient.update { it.copy(export = ExportState.Idle) }
            eventChannel.send(ProfileEvent.ShareCsv(file))
        }
    }

    /** The file was written but Android refused to share it: still a failed export. */
    fun onShareFailed() {
        transient.update { it.copy(export = ExportState.Failed(EXPORT_ERROR)) }
    }

    private fun isFormOwnedByUser(): Boolean = savedStateHandle[KEY_FORM_OWNED] ?: false

    private fun takeOverForm() {
        savedStateHandle[KEY_FORM_OWNED] = true
        transient.update { it.copy(saveError = null) }
    }

    private data class FormSnapshot(
        val displayName: String,
        val heightInput: String,
        val birthDate: LocalDate?,
        val heightError: String?,
        val birthDateError: String?,
    )

    /** State that is meaningless after a process death and therefore stays out of saved state. */
    private data class TransientState(
        val profileSaved: Boolean = false,
        val saveEchoCount: Int = 0,
        val saveError: String? = null,
        val export: ExportState = ExportState.Idle,
    )

    companion object {
        /** PRD 15.4: an understandable message, and the action stays available for a retry. */
        const val EXPORT_ERROR: String = "Export failed. Please try again."
        const val SAVE_ERROR: String = "Could not save your profile. Please try again."

        private const val MAX_HEIGHT_DIGITS = 3
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        private const val KEY_DISPLAY_NAME = "profile.displayName"
        private const val KEY_HEIGHT = "profile.heightCm"
        private const val KEY_BIRTH_DATE = "profile.birthDate"
        private const val KEY_HEIGHT_ERROR = "profile.heightError"
        private const val KEY_BIRTH_DATE_ERROR = "profile.birthDateError"

        /** True once the form reflects the user rather than a pending first read. */
        private const val KEY_FORM_OWNED = "profile.formOwned"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                val container = app.container
                ProfileViewModel(
                    profileRepository = container.userProfileRepository,
                    preferencesRepository = container.userPreferencesRepository,
                    measurementRepository = container.measurementRepository,
                    exporter = WeightDataExporter { measurements, exportDate ->
                        container.csvExportWriter.write(measurements, exportDate)
                    },
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

private fun String.toLocalDateOrNull(): LocalDate? =
    if (isEmpty()) {
        null
    } else {
        try {
            LocalDate.parse(this)
        } catch (_: DateTimeParseException) {
            null
        }
    }
