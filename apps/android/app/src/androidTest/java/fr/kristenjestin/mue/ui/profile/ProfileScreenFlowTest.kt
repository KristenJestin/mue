package fr.kristenjestin.mue.ui.profile

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import fr.kristenjestin.mue.ui.advanceToTheQuietButton
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.components.formatBmiValue
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/**
 * The real [ProfileViewModel] behind the real screen, so the paths that only exist once the
 * two are wired together — typing then saving, an error surfacing, the BMI following the
 * height — are covered end to end.
 */
@RunWith(AndroidJUnit4::class)
class ProfileScreenFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val profiles = FakeProfiles()
    private val preferences = FakePreferences()
    private val measurements = FakeMeasurements()
    private val exporter = FakeExporter()
    private val shared = mutableListOf<File>()

    @Test
    fun anOutOfRangeHeightBlocksTheSaveAndKeepsWhatWasTyped() {
        start()

        typeHeight("300")
        composeRule.onNodeWithTag(ProfileTestTags.SAVE_BUTTON).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Height must be between 120 and 230 cm").assertExists()
        composeRule.onNodeWithText("300").assertExists()
        assertEquals(0, profiles.saveCount)
    }

    @Test
    fun aValidFormIsPersistedAndConfirmed() {
        start()

        typeName("Kris")
        typeHeight("180")
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(ProfileTestTags.SAVE_BUTTON).performScrollTo().performClick()
        composeRule.advanceToTheQuietButton()

        assertEquals(UserProfile("Kris", 180, null), profiles.stored)
        composeRule.onNodeWithText(MueSaveConfirmationLabel).assertExists()
    }

    @Test
    fun theBmiAppearsAsSoonAsAHeightIsTypedAndGoesWhenItIsCleared() {
        measurements.set(listOf(Measurement(TODAY, weight(74.5))))
        start()

        composeRule.onNodeWithTag(ProfileTestTags.BMI_READOUT).assertDoesNotExist()

        typeHeight("180")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProfileTestTags.BMI_READOUT).assertExists()
        // No birth date, so the value stands alone with no band named (PRD 15.2).
        composeRule.onNodeWithText(readout(23.0, category = null)).assertExists()

        typeHeight("")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProfileTestTags.BMI_READOUT).assertDoesNotExist()
    }

    @Test
    fun theBandIsNamedForAnAdult() {
        profiles.set(UserProfile(null, 180, LocalDate.of(1992, 4, 16)))
        measurements.set(listOf(Measurement(TODAY, weight(74.5))))
        start()

        composeRule.onNodeWithText(readout(23.0, BmiCategory.HEALTHY_WEIGHT)).assertExists()
    }

    @Test
    fun theHapticsPreferenceIsPersisted() {
        start()

        composeRule.onNodeWithTag(ProfileTestTags.HAPTICS_TOGGLE).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(UserPreferences(hapticsEnabled = false), preferences.stored)
    }

    @Test
    fun aFailedExportShowsAnErrorAndNeverAConfirmation() {
        exporter.failure = IOException("disk full")
        start()

        composeRule.onNodeWithTag(ProfileTestTags.EXPORT_BUTTON).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(ProfileViewModel.EXPORT_ERROR).assertExists()
        assertTrue(shared.isEmpty())
    }

    @Test
    fun aSuccessfulExportHandsOverAFileAndShowsNoError() {
        measurements.set(listOf(Measurement(TODAY, weight(74.5))))
        start()

        composeRule.onNodeWithTag(ProfileTestTags.EXPORT_BUTTON).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, shared.size)
        assertEquals(1, exporter.exported?.size)
        composeRule.onNodeWithText(ProfileViewModel.EXPORT_ERROR).assertDoesNotExist()
    }

    @Test
    fun theStoredProfileFillsTheFormOnFirstDisplay() {
        profiles.set(UserProfile("Kris", 180, LocalDate.of(1992, 4, 16)))
        start()

        composeRule.onNodeWithText("Kris").assertExists()
        composeRule.onNodeWithText("180").assertExists()
        composeRule.onNodeWithText("34 years").assertExists()
    }

    private fun start() {
        val viewModel = ProfileViewModel(
            profileRepository = profiles,
            preferencesRepository = preferences,
            measurementRepository = measurements,
            exporter = exporter,
            savedStateHandle = SavedStateHandle(),
            today = { TODAY },
        )

        composeRule.setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    if (event is ProfileEvent.ShareCsv) shared += event.file
                }
            }
            MueTheme {
                ProfileScreen(
                    state = state,
                    onDisplayNameChange = viewModel::onDisplayNameChange,
                    onHeightChange = viewModel::onHeightChange,
                    onBirthDateChange = viewModel::onBirthDateChange,
                    onSave = viewModel::saveProfile,
                    onSaveConfirmationFinished = viewModel::onSaveConfirmationFinished,
                    onHapticsEnabledChange = viewModel::onHapticsEnabledChange,
                    onExport = viewModel::exportWeightData,
                    today = TODAY,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun typeName(value: String) {
        composeRule
            .onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ProfileTestTags.NAME_FIELD)))
            .performTextReplacement(value)
    }

    private fun typeHeight(value: String) {
        composeRule
            .onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ProfileTestTags.HEIGHT_FIELD)))
            .performTextReplacement(value)
    }

    /** The readout as it reads on screen; the number follows the device's language (BR-010). */
    private fun readout(value: Double, category: BmiCategory?): String =
        "BMI " + formatBmiValue(value, Locale.getDefault()) +
            (category?.let { " · ${it.label}" } ?: "")

    private fun weight(kilograms: Double): Weight =
        requireNotNull(Weight.ofKilogramsOrNull(kilograms))
}

private class FakeProfiles : UserProfileRepository {
    private val state = MutableStateFlow(UserProfile.EMPTY)
    var saveCount: Int = 0
        private set

    val stored: UserProfile get() = state.value

    fun set(profile: UserProfile) {
        state.value = profile
    }

    override val profile: Flow<UserProfile> = state

    override suspend fun save(profile: UserProfile) {
        saveCount++
        state.value = profile
    }
}

private class FakePreferences : UserPreferencesRepository {
    private val state = MutableStateFlow(UserPreferences.DEFAULT)

    val stored: UserPreferences get() = state.value

    override val preferences: Flow<UserPreferences> = state

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        state.value = state.value.copy(hapticsEnabled = enabled)
    }

    override suspend fun setShowEnergy(enabled: Boolean) {
        state.value = state.value.copy(showEnergy = enabled)
    }
}

private class FakeMeasurements : MeasurementRepository {
    private val state = MutableStateFlow<List<Measurement>>(emptyList())

    fun set(measurements: List<Measurement>) {
        state.value = measurements.sortedBy { it.date }
    }

    override fun observeAll(): Flow<List<Measurement>> = state

    override fun observeIn(window: DateWindow): Flow<List<Measurement>> =
        state.map { all -> all.filter { it.date in window } }

    override fun observeLatest(): Flow<Measurement?> =
        state.map { all -> all.maxByOrNull { it.date } }

    override suspend fun getAll(): List<Measurement> = state.value

    override suspend fun findByDate(date: LocalDate): Measurement? =
        state.value.firstOrNull { it.date == date }

    override suspend fun save(measurement: Measurement) {
        set(state.value.filterNot { it.date == measurement.date } + measurement)
    }

    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) {
        set(
            state.value.filterNot { it.date == originalDate || it.date == measurement.date } +
                measurement,
        )
    }

    override suspend fun delete(date: LocalDate) {
        set(state.value.filterNot { it.date == date })
    }
}

private class FakeExporter : WeightDataExporter {
    var failure: Throwable? = null
    var exported: List<Measurement>? = null
        private set

    override suspend fun export(measurements: List<Measurement>, exportDate: LocalDate): File {
        exported = measurements
        failure?.let { throw it }
        return File("mue-weight-$exportDate.csv")
    }
}
