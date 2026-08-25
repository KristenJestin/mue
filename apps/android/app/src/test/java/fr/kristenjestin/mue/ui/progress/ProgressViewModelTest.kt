package fr.kristenjestin.mue.ui.progress

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
private const val EPSILON = 1e-6

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-23T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region periods

    @Test
    fun `opens on the thirty day filter`() = progressTest { viewModel, _ ->
        assertEquals(Period.THIRTY_DAYS, viewModel.state().period)
    }

    @Test
    fun `each period filters the curve, the indicators and the history alike`() = progressTest(
        measurements = listOf(
            measurement(120, 78.0),
            measurement(60, 77.0),
            measurement(20, 76.0),
            measurement(3, 75.0),
            measurement(0, 74.5),
        ),
    ) { viewModel, _ ->
        viewModel.selectPeriod(Period.SEVEN_DAYS)
        advanceUntilIdle()
        assertEquals(listOf(daysAgo(3), TODAY), viewModel.state().chartPoints.map { it.date })
        assertEquals(listOf(TODAY, daysAgo(3)), viewModel.state().history.map { it.date })

        viewModel.selectPeriod(Period.THIRTY_DAYS)
        advanceUntilIdle()
        assertEquals(3, viewModel.state().chartPoints.size)

        viewModel.selectPeriod(Period.THREE_MONTHS)
        advanceUntilIdle()
        assertEquals(4, viewModel.state().chartPoints.size)

        viewModel.selectPeriod(Period.ALL)
        advanceUntilIdle()
        assertEquals(5, viewModel.state().chartPoints.size)
    }

    @Test
    fun `the curve is chronological and the history is not`() = progressTest(
        measurements = listOf(measurement(0, 74.5), measurement(10, 75.0), measurement(5, 74.8)),
    ) { viewModel, _ ->
        assertEquals(
            listOf(daysAgo(10), daysAgo(5), TODAY),
            viewModel.state().chartPoints.map { it.date },
        )
        assertEquals(
            listOf(TODAY, daysAgo(5), daysAgo(10)),
            viewModel.state().history.map { it.date },
        )
    }

    @Test
    fun `the history of a period is not capped`() = progressTest(
        measurements = (0L until 90L).map { measurement(it, 75.0) },
    ) { viewModel, _ ->
        viewModel.selectPeriod(Period.ALL)
        advanceUntilIdle()

        assertEquals(90, viewModel.state().history.size)
    }

    @Test
    fun `indicators use the first and last measurement of the period`() = progressTest(
        measurements = listOf(measurement(14, 76.0), measurement(7, 75.4), measurement(0, 74.6)),
    ) { viewModel, _ ->
        val statistics = viewModel.state().statistics

        assertEquals(74.6, assertNotNull(statistics.currentWeight).kilograms, EPSILON)
        assertEquals(-1.4, assertNotNull(statistics.changeKg), 1e-9)
        assertEquals(-0.7, assertNotNull(statistics.weeklyPaceKg), 1e-9)
    }

    // endregion

    // region empty and single-measurement periods

    @Test
    fun `a period with no measurement never falls back on an older one`() = progressTest(
        measurements = listOf(measurement(40, 80.0), measurement(35, 79.0)),
        profile = adultProfile(),
    ) { viewModel, _ ->
        viewModel.selectPeriod(Period.SEVEN_DAYS)
        advanceUntilIdle()

        val state = viewModel.state()
        assertTrue(state.chartPoints.isEmpty())
        assertTrue(state.history.isEmpty())
        assertNull(state.statistics.currentWeight)
        assertNull(state.statistics.changeKg)
        assertNull(state.statistics.weeklyPaceKg)
        assertEquals(Bmi.Unavailable, state.bmi)
        // The history is not empty overall, so this is not the PRD 15.1 empty state.
        assertTrue(state.hasAnyMeasurement)
        assertFalse(state.showEmptyState)
    }

    @Test
    fun `a single measurement has a current weight but no change and no pace`() = progressTest(
        measurements = listOf(measurement(2, 74.5)),
    ) { viewModel, _ ->
        val statistics = viewModel.state().statistics

        assertEquals(74.5, assertNotNull(statistics.currentWeight).kilograms, EPSILON)
        assertNull(statistics.changeKg)
        assertNull(statistics.weeklyPaceKg)
        assertEquals(1, viewModel.state().chartPoints.size)
    }

    @Test
    fun `an empty history triggers the empty state`() = progressTest { viewModel, _ ->
        assertTrue(viewModel.state().showEmptyState)
        assertFalse(viewModel.state().hasAnyMeasurement)
        assertEquals(Bmi.Unavailable, viewModel.state().bmi)
    }

    // endregion

    // region BMI

    @Test
    fun `the bmi is named only when the domain layer allows it`() = progressTest(
        measurements = listOf(measurement(0, 74.5)),
        profile = adultProfile(),
    ) { viewModel, _ ->
        val bmi = assertIs<Bmi.Classified>(viewModel.state().bmi)

        assertEquals(BmiCategory.HEALTHY_WEIGHT, bmi.category)
        assertEquals(23.0, bmi.value, EPSILON)
    }

    @Test
    fun `without a birth date the bmi keeps its value but loses its band`() = progressTest(
        measurements = listOf(measurement(0, 74.5)),
        profile = UserProfile(heightCm = 180),
    ) { viewModel, _ ->
        assertIs<Bmi.ValueOnly>(viewModel.state().bmi)
    }

    @Test
    fun `without a height there is no bmi at all`() = progressTest(
        measurements = listOf(measurement(0, 74.5)),
        profile = UserProfile(birthDate = LocalDate.of(1990, 1, 1)),
    ) { viewModel, _ ->
        assertEquals(Bmi.Unavailable, viewModel.state().bmi)
    }

    @Test
    fun `the bmi follows the current weight of the period, not of the history`() = progressTest(
        measurements = listOf(measurement(40, 95.0), measurement(2, 74.5)),
        profile = adultProfile(),
    ) { viewModel, _ ->
        viewModel.selectPeriod(Period.SEVEN_DAYS)
        advanceUntilIdle()

        assertEquals(23.0, assertIs<Bmi.Classified>(viewModel.state().bmi).value, EPSILON)
    }

    // endregion

    // region editing

    @Test
    fun `opening a row prefills the panel with its date and weight`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, _ ->
        viewModel.openEditor(measurement(5, 74.9))
        advanceUntilIdle()

        val editor = assertNotNull(viewModel.state().editor)
        assertEquals(daysAgo(5), editor.originalDate)
        assertEquals(daysAgo(5), editor.date)
        assertEquals(ProgressFormat.weight(kilograms(74.9)), editor.weightInput)
        assertNull(editor.weightError)
        assertFalse(editor.datePickerVisible)
        assertFalse(editor.deleteConfirmationVisible)
    }

    @Test
    fun `saving writes the edited weight and closes the panel`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateWeightInput("73.2")
        viewModel.saveEdit()
        advanceUntilIdle()

        assertEquals(listOf(daysAgo(5)), repository.measurements.map { it.date })
        assertEquals(73.2, repository.measurements.single().weight.kilograms, EPSILON)
        assertNull(viewModel.state().editor)
    }

    @Test
    fun `a decimal comma is accepted whatever the phone's language`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateWeightInput("73,2")
        viewModel.saveEdit()
        advanceUntilIdle()

        assertEquals(73.2, repository.measurements.single().weight.kilograms, EPSILON)
    }

    @Test
    fun `an out of range weight keeps the panel open with the Entry message`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateWeightInput("312.0")
        viewModel.saveEdit()
        advanceUntilIdle()

        val editor = assertNotNull(viewModel.state().editor)
        assertEquals(MueValidation.WEIGHT_ERROR, editor.weightError)
        assertEquals("312.0", editor.weightInput)
        assertEquals(74.9, repository.measurements.single().weight.kilograms, EPSILON)
    }

    @Test
    fun `correcting the input clears the message`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, _ ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateWeightInput("312.0")
        viewModel.saveEdit()
        advanceUntilIdle()
        assertNotNull(viewModel.state().editor?.weightError)

        viewModel.updateWeightInput("74.0")
        advanceUntilIdle()
        assertNull(viewModel.state().editor?.weightError)
    }

    @Test
    fun `moving a measurement onto an occupied date replaces it without asking`() = progressTest(
        measurements = listOf(measurement(5, 74.9), measurement(2, 75.4)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateDate(daysAgo(2))
        viewModel.saveEdit()
        advanceUntilIdle()

        assertEquals(listOf(daysAgo(2)), repository.measurements.map { it.date })
        assertEquals(74.9, repository.measurements.single().weight.kilograms, EPSILON)
        assertNull(viewModel.state().editor)
    }

    @Test
    fun `moving a measurement to a free date keeps a single entry`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateDate(daysAgo(1))
        viewModel.saveEdit()
        advanceUntilIdle()

        assertEquals(listOf(daysAgo(1)), repository.measurements.map { it.date })
        assertEquals(74.9, repository.measurements.single().weight.kilograms, EPSILON)
    }

    @Test
    fun `a date after today is refused`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, _ ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateDate(TODAY.plusDays(1))
        advanceUntilIdle()

        assertEquals(daysAgo(5), assertNotNull(viewModel.state().editor).date)
    }

    @Test
    fun `dismissing the panel changes nothing`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.updateWeightInput("60.0")
        viewModel.dismissEditor()
        advanceUntilIdle()

        assertNull(viewModel.state().editor)
        assertEquals(74.9, repository.measurements.single().weight.kilograms, EPSILON)
    }

    // endregion

    // region deletion

    @Test
    fun `deleting waits for a confirmation`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.requestDelete()
        advanceUntilIdle()

        assertTrue(assertNotNull(viewModel.state().editor).deleteConfirmationVisible)
        assertEquals(1, repository.measurements.size)
        assertTrue(repository.deletedDates.isEmpty())
    }

    @Test
    fun `cancelling a deletion keeps the measurement`() = progressTest(
        measurements = listOf(measurement(5, 74.9)),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(5, 74.9))
        viewModel.requestDelete()
        viewModel.cancelDelete()
        advanceUntilIdle()

        assertFalse(assertNotNull(viewModel.state().editor).deleteConfirmationVisible)
        assertEquals(1, repository.measurements.size)
    }

    @Test
    fun `confirming a deletion recomputes every indicator at once`() = progressTest(
        measurements = listOf(measurement(14, 76.0), measurement(7, 75.4), measurement(0, 74.6)),
        profile = adultProfile(),
    ) { viewModel, repository ->
        viewModel.openEditor(measurement(0, 74.6))
        viewModel.requestDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        val state = viewModel.state()
        assertEquals(listOf(TODAY), repository.deletedDates)
        assertEquals(listOf(daysAgo(14), daysAgo(7)), state.chartPoints.map { it.date })
        assertEquals(75.4, assertNotNull(state.statistics.currentWeight).kilograms, EPSILON)
        assertEquals(-0.6, assertNotNull(state.statistics.changeKg), 1e-9)
        assertEquals(-0.6, assertNotNull(state.statistics.weeklyPaceKg), 1e-9)
        assertEquals(23.3, assertIs<Bmi.Classified>(state.bmi).value, EPSILON)
        assertNull(state.editor)
    }

    @Test
    fun `deleting the last measurement brings back the empty state`() = progressTest(
        measurements = listOf(measurement(0, 74.6)),
    ) { viewModel, _ ->
        viewModel.openEditor(measurement(0, 74.6))
        viewModel.requestDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertTrue(viewModel.state().showEmptyState)
        assertEquals(Bmi.Unavailable, viewModel.state().bmi)
    }

    // endregion

    // region saved state

    @Test
    fun `the period survives a process death`() = runTest {
        val handle = SavedStateHandle()
        val repository = FakeMeasurementRepository(listOf(measurement(0, 74.5)))
        val first = ProgressViewModel(repository, FakeUserProfileRepository(), handle, clock)
        collect(first)
        first.selectPeriod(Period.ALL)
        advanceUntilIdle()

        val restored = ProgressViewModel(repository, FakeUserProfileRepository(), handle.copy(), clock)
        collect(restored)
        advanceUntilIdle()

        assertEquals(Period.ALL, restored.state().period)
    }

    @Test
    fun `an open panel and its edits survive a process death`() = runTest {
        val handle = SavedStateHandle()
        val repository = FakeMeasurementRepository(listOf(measurement(5, 74.9)))
        val first = ProgressViewModel(repository, FakeUserProfileRepository(), handle, clock)
        collect(first)
        first.openEditor(measurement(5, 74.9))
        first.updateWeightInput("73.1")
        first.requestDelete()
        advanceUntilIdle()

        val restored = ProgressViewModel(repository, FakeUserProfileRepository(), handle.copy(), clock)
        collect(restored)
        advanceUntilIdle()

        val editor = assertNotNull(restored.state().editor)
        assertEquals(daysAgo(5), editor.originalDate)
        assertEquals("73.1", editor.weightInput)
        assertTrue(editor.deleteConfirmationVisible)
    }

    // endregion

    // region harness

    private fun ProgressViewModel.state(): ProgressUiState = uiState.value

    private fun TestScope.collect(viewModel: ProgressViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    private fun progressTest(
        measurements: List<Measurement> = emptyList(),
        profile: UserProfile = UserProfile.EMPTY,
        body: suspend TestScope.(ProgressViewModel, FakeMeasurementRepository) -> Unit,
    ) = runTest {
        val repository = FakeMeasurementRepository(measurements)
        val viewModel = ProgressViewModel(
            measurementRepository = repository,
            userProfileRepository = FakeUserProfileRepository(profile),
            savedStateHandle = SavedStateHandle(),
            clock = clock,
        )
        collect(viewModel)
        advanceUntilIdle()
        body(viewModel, repository)
    }

    // endregion
}

/** Rebuilds a handle from what the system would have written out and read back. */
private fun SavedStateHandle.copy(): SavedStateHandle =
    SavedStateHandle(keys().associateWith { get<Any?>(it) })

private fun kilograms(value: Double): Weight =
    requireNotNull(Weight.ofKilogramsOrNull(value)) { "$value kg is out of range" }

private fun daysAgo(days: Long): LocalDate = TODAY.minusDays(days)

private fun measurement(daysAgo: Long, kilograms: Double): Measurement =
    Measurement(daysAgo(daysAgo), kilograms(kilograms))

private fun adultProfile(): UserProfile =
    UserProfile(heightCm = 180, birthDate = LocalDate.of(1990, 1, 1))
