package fr.kristenjestin.mue.ui.profile

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.testing.measurementOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // region loading and saved state

    @Test
    fun `seeds the form from the stored profile`() = runTest {
        val harness = harness(
            profile = UserProfile("Kris", 180, LocalDate.of(1992, 4, 16)),
        )

        val state = harness.state()
        assertEquals("Kris", state.displayName)
        assertEquals("180", state.heightInput)
        assertEquals(LocalDate.of(1992, 4, 16), state.birthDate)
    }

    @Test
    fun `an empty profile leaves every field empty`() = runTest {
        val state = harness().state()

        assertEquals("", state.displayName)
        assertEquals("", state.heightInput)
        assertNull(state.birthDate)
        assertNull(state.ageYears)
    }

    @Test
    fun `restores what was typed instead of re-reading storage`() = runTest {
        val savedState = SavedStateHandle()
        val storage = FakeUserProfileRepository(UserProfile("Kris", 180))
        harness(savedState = savedState, profileRepository = storage).run {
            viewModel.onDisplayNameChange("Alex")
            viewModel.onHeightChange("172")
        }

        // Same saved state, brand new ViewModel: a rotation or a process death.
        val restored = harness(savedState = savedState, profileRepository = storage)

        assertEquals("Alex", restored.state().displayName)
        assertEquals("172", restored.state().heightInput)
    }

    // endregion

    // region saving

    @Test
    fun `saves a valid profile and confirms it`() = runTest {
        val harness = harness()

        harness.viewModel.onDisplayNameChange("Kris")
        harness.viewModel.onHeightChange("180")
        harness.viewModel.onBirthDateChange(LocalDate.of(1992, 4, 16))
        harness.viewModel.saveProfile()

        assertEquals(
            UserProfile("Kris", 180, LocalDate.of(1992, 4, 16)),
            harness.profiles.stored,
        )
        assertTrue(harness.state().profileSaved)
        assertNull(harness.state().saveError)
    }

    @Test
    fun `an empty name and an empty height are valid`() = runTest {
        val harness = harness(profile = UserProfile("Kris", 180))

        harness.viewModel.onDisplayNameChange("")
        harness.viewModel.onHeightChange("")
        harness.viewModel.saveProfile()

        assertEquals(UserProfile(null, null, null), harness.profiles.stored)
        assertTrue(harness.state().profileSaved)
        assertNull(harness.state().heightError)
    }

    @Test
    fun `a blank name never blocks the save`() = runTest {
        val harness = harness()

        harness.viewModel.onDisplayNameChange("   ")
        harness.viewModel.saveProfile()

        assertEquals(1, harness.profiles.saveCount)
        assertNull(harness.profiles.stored.displayName)
    }

    @Test
    fun `a long name is capped at forty characters`() = runTest {
        val harness = harness()

        harness.viewModel.onDisplayNameChange("A".repeat(60))

        assertEquals(40, harness.state().displayName.length)
    }

    @Test
    fun `saving echoes back the name that was stored`() = runTest {
        val harness = harness()

        harness.viewModel.onDisplayNameChange("  Kris  ")
        harness.viewModel.saveProfile()

        assertEquals("Kris", harness.state().displayName)
    }

    @Test
    fun `a height below the range blocks the save with the exact message`() = runTest {
        val harness = harness()

        harness.viewModel.onHeightChange("119")
        harness.viewModel.saveProfile()

        assertEquals("Height must be between 120 and 230 cm", harness.state().heightError)
        assertEquals(0, harness.profiles.saveCount)
        assertFalse(harness.state().profileSaved)
    }

    @Test
    fun `a height above the range blocks the save with the exact message`() = runTest {
        val harness = harness()

        harness.viewModel.onHeightChange("231")
        harness.viewModel.saveProfile()

        assertEquals(MueValidation.HEIGHT_ERROR, harness.state().heightError)
        assertEquals(0, harness.profiles.saveCount)
    }

    @Test
    fun `an invalid height keeps what was typed`() = runTest {
        val harness = harness()

        harness.viewModel.onHeightChange("300")
        harness.viewModel.saveProfile()

        assertEquals("300", harness.state().heightInput)
    }

    @Test
    fun `a future birth date blocks the save with the exact message`() = runTest {
        val harness = harness()

        harness.viewModel.onBirthDateChange(TODAY.plusDays(1))
        harness.viewModel.saveProfile()

        assertEquals("Enter a valid date of birth", harness.state().birthDateError)
        assertEquals(0, harness.profiles.saveCount)
    }

    @Test
    fun `a birth date more than 120 years ago blocks the save`() = runTest {
        val harness = harness()

        harness.viewModel.onBirthDateChange(TODAY.minusYears(120).minusDays(1))
        harness.viewModel.saveProfile()

        assertEquals(MueValidation.BIRTH_DATE_ERROR, harness.state().birthDateError)
        assertEquals(0, harness.profiles.saveCount)
    }

    @Test
    fun `exactly 120 years ago is still valid`() = runTest {
        val harness = harness()

        harness.viewModel.onBirthDateChange(TODAY.minusYears(120))
        harness.viewModel.saveProfile()

        assertNull(harness.state().birthDateError)
        assertEquals(1, harness.profiles.saveCount)
    }

    @Test
    fun `one invalid field blocks the whole save`() = runTest {
        val harness = harness()

        harness.viewModel.onDisplayNameChange("Kris")
        harness.viewModel.onHeightChange("180")
        harness.viewModel.onBirthDateChange(TODAY.plusYears(1))
        harness.viewModel.saveProfile()

        assertEquals(0, harness.profiles.saveCount)
        assertEquals("Kris", harness.state().displayName)
        assertEquals("180", harness.state().heightInput)
        assertNull(harness.state().heightError)
    }

    @Test
    fun `editing a field clears its error`() = runTest {
        val harness = harness()

        harness.viewModel.onHeightChange("50")
        harness.viewModel.saveProfile()
        harness.viewModel.onHeightChange("170")

        assertNull(harness.state().heightError)
    }

    @Test
    fun `a storage failure never reports a success`() = runTest {
        val harness = harness()
        harness.profiles.failOnSave = true

        harness.viewModel.onHeightChange("180")
        harness.viewModel.saveProfile()

        assertFalse(harness.state().profileSaved)
        assertEquals(ProfileViewModel.SAVE_ERROR, harness.state().saveError)
    }

    @Test
    fun `the confirmation is cleared once the button has shown it`() = runTest {
        val harness = harness()

        harness.viewModel.saveProfile()
        assertTrue(harness.state().profileSaved)

        harness.viewModel.onSaveConfirmationFinished()
        assertFalse(harness.state().profileSaved)
    }

    /**
     * The BMI readout hops on the echo counter rather than on [ProfileUiState.profileSaved]:
     * the flag is a state the screen leaves and re-enters, which cannot tell a second save
     * from the first.
     */
    @Test
    fun `every successful save gives the readout something to answer`() = runTest {
        val harness = harness()

        assertEquals(0, harness.state().saveEchoCount)

        harness.viewModel.saveProfile()
        assertEquals(1, harness.state().saveEchoCount)

        harness.viewModel.onSaveConfirmationFinished()
        harness.viewModel.saveProfile()
        assertEquals(2, harness.state().saveEchoCount)
    }

    @Test
    fun `a refused form leaves the readout still`() = runTest {
        val harness = harness()

        harness.viewModel.onHeightChange("300")
        harness.viewModel.saveProfile()

        assertEquals(0, harness.state().saveEchoCount)
    }

    @Test
    fun `the height field keeps digits only and at most three of them`() = runTest {
        val harness = harness()

        harness.viewModel.onHeightChange("1a8b0c5")

        assertEquals("180", harness.state().heightInput)
    }

    // endregion

    // region age

    @Test
    fun `age is one year short the day before the birthday`() = runTest {
        val harness = harness(today = LocalDate.of(2026, 4, 15))

        harness.viewModel.onBirthDateChange(LocalDate.of(1992, 4, 16))

        assertEquals(33, harness.state().ageYears)
    }

    @Test
    fun `age turns on the birthday`() = runTest {
        val harness = harness(today = LocalDate.of(2026, 4, 16))

        harness.viewModel.onBirthDateChange(LocalDate.of(1992, 4, 16))

        assertEquals(34, harness.state().ageYears)
    }

    @Test
    fun `a leap day birthday has not aged on the 28th of February in a common year`() = runTest {
        val harness = harness(today = LocalDate.of(2025, 2, 28))

        harness.viewModel.onBirthDateChange(LocalDate.of(2000, 2, 29))

        assertEquals(24, harness.state().ageYears)
    }

    @Test
    fun `a leap day birthday ages on the 1st of March in a common year`() = runTest {
        val harness = harness(today = LocalDate.of(2025, 3, 1))

        harness.viewModel.onBirthDateChange(LocalDate.of(2000, 2, 29))

        assertEquals(25, harness.state().ageYears)
    }

    @Test
    fun `an invalid birth date shows no age`() = runTest {
        val harness = harness()

        harness.viewModel.onBirthDateChange(TODAY.plusYears(2))

        assertNull(harness.state().ageYears)
    }

    // endregion

    // region BMI

    @Test
    fun `no height means no BMI`() = runTest {
        val harness = harness(measurements = listOf(measurementOf("2026-08-20", 74.5)))

        assertEquals(Bmi.Unavailable, harness.state().bmi)
        assertNull(harness.state().bmiAvailable)
    }

    @Test
    fun `no measurement means no BMI`() = runTest {
        val harness = harness(profile = UserProfile(heightCm = 180))

        assertEquals(Bmi.Unavailable, harness.state().bmi)
    }

    @Test
    fun `no birth date gives a value without a category`() = runTest {
        val harness = harness(
            profile = UserProfile(heightCm = 180),
            measurements = listOf(measurementOf("2026-08-20", 74.5)),
        )

        assertEquals(Bmi.ValueOnly(23.0), harness.state().bmi)
    }

    @Test
    fun `under twenty gives a value without a category`() = runTest {
        val harness = harness(
            profile = UserProfile(heightCm = 180, birthDate = TODAY.minusYears(19)),
            measurements = listOf(measurementOf("2026-08-20", 74.5)),
        )

        assertEquals(Bmi.ValueOnly(23.0), harness.state().bmi)
    }

    @Test
    fun `twenty or older gives a classified BMI`() = runTest {
        val harness = harness(
            profile = UserProfile(heightCm = 180, birthDate = TODAY.minusYears(20)),
            measurements = listOf(measurementOf("2026-08-20", 74.5)),
        )

        assertEquals(Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT), harness.state().bmi)
    }

    @Test
    fun `the BMI uses the most recent measurement`() = runTest {
        val harness = harness(
            profile = UserProfile(heightCm = 180, birthDate = LocalDate.of(1992, 4, 16)),
            measurements = listOf(
                measurementOf("2026-08-20", 74.5),
                measurementOf("2026-08-22", 97.2),
                measurementOf("2026-08-01", 60.0),
            ),
        )

        assertEquals(Bmi.Classified(30.0, BmiCategory.OBESITY), harness.state().bmi)
    }

    @Test
    fun `the BMI follows the height being typed, before any save`() = runTest {
        val harness = harness(measurements = listOf(measurementOf("2026-08-20", 74.5)))

        harness.viewModel.onHeightChange("160")

        assertEquals(Bmi.ValueOnly(29.1), harness.state().bmi)
        assertEquals(0, harness.profiles.saveCount)
    }

    @Test
    fun `clearing the height makes the BMI disappear`() = runTest {
        val harness = harness(
            profile = UserProfile(heightCm = 180),
            measurements = listOf(measurementOf("2026-08-20", 74.5)),
        )

        harness.viewModel.onHeightChange("")

        assertEquals(Bmi.Unavailable, harness.state().bmi)
    }

    @Test
    fun `an out-of-range height gives no BMI`() = runTest {
        val harness = harness(measurements = listOf(measurementOf("2026-08-20", 74.5)))

        harness.viewModel.onHeightChange("300")

        assertEquals(Bmi.Unavailable, harness.state().bmi)
    }

    @Test
    fun `an invalid birth date never unlocks a category`() = runTest {
        val harness = harness(
            profile = UserProfile(heightCm = 180),
            measurements = listOf(measurementOf("2026-08-20", 74.5)),
        )

        harness.viewModel.onBirthDateChange(TODAY.minusYears(200))

        assertEquals(Bmi.ValueOnly(23.0), harness.state().bmi)
    }

    // endregion

    // region preferences

    @Test
    fun `haptics are on by default`() = runTest {
        assertTrue(harness().state().hapticsEnabled)
    }

    @Test
    fun `the haptics toggle is persisted`() = runTest {
        val harness = harness()

        harness.viewModel.onHapticsEnabledChange(false)

        assertEquals(UserPreferences(hapticsEnabled = false), harness.preferences.stored)
        assertFalse(harness.state().hapticsEnabled)

        harness.viewModel.onHapticsEnabledChange(true)

        assertTrue(harness.state().hapticsEnabled)
    }

    // endregion

    // region export

    @Test
    fun `a successful export shares the complete history and then idles`() = runTest {
        val history = listOf(
            measurementOf("2026-08-12", 74.8),
            measurementOf("2026-08-18", 74.9),
            measurementOf("2026-08-23", 74.5),
        )
        val harness = harness(measurements = history)

        harness.viewModel.exportWeightData()

        assertEquals(history, harness.exporter.exportedMeasurements)
        assertEquals(TODAY, harness.exporter.exportedOn)
        assertEquals(ExportState.Idle, harness.state().export)
        assertEquals(
            listOf(ProfileEvent.ShareCsv(File("mue-weight-2026-08-23.csv"))),
            harness.events(),
        )
    }

    @Test
    fun `an empty history still produces a file`() = runTest {
        val harness = harness()

        harness.viewModel.exportWeightData()

        assertEquals(emptyList(), harness.exporter.exportedMeasurements)
        assertEquals(1, harness.events().size)
    }

    @Test
    fun `a failing export reports an error and shares nothing`() = runTest {
        val harness = harness()
        harness.exporter.failure = IOException("disk full")

        harness.viewModel.exportWeightData()

        assertEquals(ExportState.Failed(ProfileViewModel.EXPORT_ERROR), harness.state().export)
        assertTrue(harness.events().isEmpty())
    }

    @Test
    fun `a failing history read reports an error`() = runTest {
        val harness = harness()
        harness.measurements.failOnGetAll = true

        harness.viewModel.exportWeightData()

        assertEquals(ExportState.Failed(ProfileViewModel.EXPORT_ERROR), harness.state().export)
        assertEquals(0, harness.exporter.callCount)
        assertTrue(harness.events().isEmpty())
    }

    @Test
    fun `a share the system refuses is a failed export`() = runTest {
        val harness = harness()

        harness.viewModel.exportWeightData()
        harness.viewModel.onShareFailed()

        assertEquals(ExportState.Failed(ProfileViewModel.EXPORT_ERROR), harness.state().export)
    }

    @Test
    fun `retrying after a failure clears the error`() = runTest {
        val harness = harness()
        harness.exporter.failure = IOException("disk full")
        harness.viewModel.exportWeightData()

        harness.exporter.failure = null
        harness.viewModel.exportWeightData()

        assertEquals(ExportState.Idle, harness.state().export)
        assertEquals(1, harness.events().size)
    }

    // endregion

    private class ProfileHarness(
        val profiles: FakeUserProfileRepository,
        val preferences: FakeUserPreferencesRepository,
        val measurements: FakeMeasurementRepository,
        val exporter: FakeWeightDataExporter,
        val viewModel: ProfileViewModel,
        private val collectedEvents: List<ProfileEvent>,
        private val scope: TestScope,
    ) {
        /** Lets the collectors catch up before the assertion reads the value. */
        fun state(): ProfileUiState {
            scope.runCurrent()
            return viewModel.state.value
        }

        fun events(): List<ProfileEvent> {
            scope.runCurrent()
            return collectedEvents.toList()
        }
    }

    private fun TestScope.harness(
        profile: UserProfile = UserProfile.EMPTY,
        preferences: UserPreferences = UserPreferences.DEFAULT,
        measurements: List<Measurement> = emptyList(),
        savedState: SavedStateHandle = SavedStateHandle(),
        today: LocalDate = TODAY,
        profileRepository: FakeUserProfileRepository = FakeUserProfileRepository(profile),
        measurementRepository: FakeMeasurementRepository = FakeMeasurementRepository(measurements),
        exporter: FakeWeightDataExporter = FakeWeightDataExporter(),
    ): ProfileHarness {
        val preferencesRepository = FakeUserPreferencesRepository(preferences)
        val viewModel = ProfileViewModel(
            profileRepository = profileRepository,
            preferencesRepository = preferencesRepository,
            measurementRepository = measurementRepository,
            exporter = exporter,
            savedStateHandle = savedState,
            today = { today },
        )

        val collected = mutableListOf<ProfileEvent>()
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { viewModel.state.collect {} }
        backgroundScope.launch(eager) { viewModel.events.toList(collected) }
        runCurrent()

        return ProfileHarness(
            profiles = profileRepository,
            preferences = preferencesRepository,
            measurements = measurementRepository,
            exporter = exporter,
            viewModel = viewModel,
            collectedEvents = collected,
            scope = this,
        )
    }
}
