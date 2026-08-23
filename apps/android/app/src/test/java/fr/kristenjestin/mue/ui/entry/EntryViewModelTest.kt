package fr.kristenjestin.mue.ui.entry

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.testing.measurementOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

class EntryViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    // A dot-decimal locale keeps the expected strings readable; the comma is covered separately.
    @get:Rule
    val locale = LocaleRule(Locale.UK)

    private fun viewModel(
        history: List<Measurement> = emptyList(),
        profile: UserProfile = UserProfile.EMPTY,
        preferences: UserPreferences = UserPreferences.DEFAULT,
        savedState: SavedStateHandle = SavedStateHandle(),
        repository: FakeMeasurementRepository = FakeMeasurementRepository(history),
        today: LocalDate = TODAY,
    ) = EntryViewModel(
        measurements = repository,
        profiles = FakeUserProfileRepository(profile),
        preferences = FakeUserPreferencesRepository(preferences),
        savedState = savedState,
        today = { today },
    )

    // --- FR-ENTRY-001, initial value -------------------------------------------------

    @Test
    fun `an empty history starts the scale at 70 kg`() = runTest {
        assertEquals(Weight.DEFAULT, viewModel().uiState.value.weight)
        assertEquals(700, viewModel().uiState.value.weight.tenthsKg)
    }

    @Test
    fun `the scale starts on the most recent measurement`() = runTest {
        val model = viewModel(
            history = listOf(
                measurementOf("2026-08-12", 74.8),
                measurementOf("2026-08-21", 74.2),
                measurementOf("2026-08-18", 74.9),
            ),
        )
        assertEquals(742, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `the measurement is dated today by default`() = runTest {
        val state = viewModel().uiState.value
        assertEquals(TODAY, state.date)
        assertEquals(TODAY, state.today)
        assertTrue(state.isToday)
    }

    @Test
    fun `the value the user set survives a process death`() = runTest {
        val history = listOf(measurementOf("2026-08-20", 80.0))
        val savedState = SavedStateHandle()
        viewModel(history = history, savedState = savedState)
            .onWeightChanged(Weight.ofTenthsClamped(662))

        val restored = viewModel(history = history, savedState = savedState)

        assertEquals(662, restored.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `the date the user chose survives a process death`() = runTest {
        val savedState = SavedStateHandle()
        viewModel(savedState = savedState).onDateSelected(LocalDate.of(2026, 8, 11))

        assertEquals(LocalDate.of(2026, 8, 11), viewModel(savedState = savedState).uiState.value.date)
    }

    // --- FR-ENTRY-002 / FR-ENTRY-003, moving the value -------------------------------

    @Test
    fun `stepping moves the value one tenth at a time`() = runTest {
        val model = viewModel()
        model.onStep(1)
        assertEquals(701, model.uiState.value.weight.tenthsKg)
        model.onStep(-1)
        assertEquals(700, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `stepping stops dead at the lower end stop`() = runTest {
        val model = viewModel()
        model.onWeightChanged(Weight.ofTenthsClamped(Weight.MIN_TENTHS))
        model.onStep(-1)
        assertEquals(Weight.MIN_TENTHS, model.uiState.value.weight.tenthsKg)
        assertTrue(model.uiState.value.isAtLowerStop)
    }

    @Test
    fun `stepping stops dead at the upper end stop`() = runTest {
        val model = viewModel()
        model.onWeightChanged(Weight.ofTenthsClamped(Weight.MAX_TENTHS))
        model.onStep(1)
        assertEquals(Weight.MAX_TENTHS, model.uiState.value.weight.tenthsKg)
        assertTrue(model.uiState.value.isAtUpperStop)
    }

    @Test
    fun `the scale moving does not order the scale to move`() = runTest {
        val model = viewModel()
        val revision = model.uiState.value.weightRevision
        model.onWeightChanged(Weight.ofTenthsClamped(745))

        assertEquals(revision, model.uiState.value.weightRevision)
    }

    @Test
    fun `every source other than the scale asks the scale to follow`() = runTest {
        val model = viewModel()
        val start = model.uiState.value.weightRevision

        model.onStep(1)
        assertEquals(start + 1, model.uiState.value.weightRevision)

        model.onManualEntryOpened()
        model.onManualInputChanged("81.3")
        assertEquals(start + 2, model.uiState.value.weightRevision)
    }

    @Test
    fun `the history seed asks the scale to follow`() = runTest {
        val model = viewModel(history = listOf(measurementOf("2026-08-20", 80.0)))

        assertEquals(800, model.uiState.value.weight.tenthsKg)
        assertTrue(model.uiState.value.weightRevision > 0)
    }

    @Test
    fun `a value the user already chose is not replaced by the history`() = runTest {
        val savedState = SavedStateHandle()
        viewModel(savedState = savedState).onWeightChanged(Weight.ofTenthsClamped(662))

        val restored = viewModel(
            history = listOf(measurementOf("2026-08-20", 80.0)),
            savedState = savedState,
        )

        assertEquals(662, restored.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `the scale never reports a value outside the range`() = runTest {
        val model = viewModel()
        model.onWeightChanged(Weight.ofTenthsClamped(10))
        assertEquals(Weight.MIN_TENTHS, model.uiState.value.weight.tenthsKg)
        model.onWeightChanged(Weight.ofTenthsClamped(99_999))
        assertEquals(Weight.MAX_TENTHS, model.uiState.value.weight.tenthsKg)
    }

    // --- FR-ENTRY-004, manual entry --------------------------------------------------

    @Test
    fun `manual entry opens on the value the scale is showing`() = runTest {
        val model = viewModel()
        model.onWeightChanged(Weight.ofTenthsClamped(745))
        model.onManualEntryOpened()

        val state = model.uiState.value
        assertTrue(state.manualEntry)
        assertEquals("74.5", state.manualInput)
        assertNull(state.manualError)
    }

    @Test
    fun `a comma is accepted as a decimal separator`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("74,5")

        assertEquals(745, model.uiState.value.weight.tenthsKg)
        assertNull(model.uiState.value.manualError)
    }

    @Test
    fun `a dot is accepted as a decimal separator`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("74.5")

        assertEquals(745, model.uiState.value.weight.tenthsKg)
        assertNull(model.uiState.value.manualError)
    }

    @Test
    fun `a second decimal is rounded to the tenth`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("74.55")

        assertEquals(746, model.uiState.value.weight.tenthsKg)
        assertNull(model.uiState.value.manualError)
    }

    @Test
    fun `a value below the range is refused with the exact message`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("12")

        val state = model.uiState.value
        assertEquals("Weight must be between 30.0 and 250.0 kg", state.manualError)
        assertEquals(MueValidation.WEIGHT_ERROR, state.manualError)
        assertEquals(700, state.weight.tenthsKg)
        assertEquals("12", state.manualInput)
    }

    @Test
    fun `a value above the range is refused with the exact message`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("999")

        assertEquals(MueValidation.WEIGHT_ERROR, model.uiState.value.manualError)
        assertEquals("999", model.uiState.value.manualInput)
    }

    @Test
    fun `garbage is refused like an out of range value`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("seventy four")

        assertEquals(MueValidation.WEIGHT_ERROR, model.uiState.value.manualError)
        assertEquals(700, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `clearing the field mid-edit is not an error yet`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("")

        assertNull(model.uiState.value.manualError)
        assertEquals(700, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `both range limits are accepted`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("30")
        assertEquals(Weight.MIN_TENTHS, model.uiState.value.weight.tenthsKg)
        model.onManualInputChanged("250")
        assertEquals(Weight.MAX_TENTHS, model.uiState.value.weight.tenthsKg)
        assertNull(model.uiState.value.manualError)
    }

    @Test
    fun `a value just outside the range is refused`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("29.9")
        assertEquals(MueValidation.WEIGHT_ERROR, model.uiState.value.manualError)
        model.onManualInputChanged("250.1")
        assertEquals(MueValidation.WEIGHT_ERROR, model.uiState.value.manualError)
    }

    @Test
    fun `Done restores the scale on a valid value`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("81,3")

        assertTrue(model.onManualEntryConfirmed())
        assertFalse(model.uiState.value.manualEntry)
        assertEquals(813, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `Done keeps an invalid value on screen for correction`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("999")

        assertFalse(model.onManualEntryConfirmed())
        assertTrue(model.uiState.value.manualEntry)
        assertEquals("999", model.uiState.value.manualInput)
        assertEquals(MueValidation.WEIGHT_ERROR, model.uiState.value.manualError)
    }

    @Test
    fun `Done refuses an empty field`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("")

        assertFalse(model.onManualEntryConfirmed())
        assertEquals(MueValidation.WEIGHT_ERROR, model.uiState.value.manualError)
    }

    @Test
    fun `leaving manual entry keeps the last valid weight`() = runTest {
        val model = viewModel()
        model.onManualEntryOpened()
        model.onManualInputChanged("81.3")
        model.onManualInputChanged("999")
        model.onManualEntryDismissed()

        val state = model.uiState.value
        assertFalse(state.manualEntry)
        assertNull(state.manualError)
        assertEquals(813, state.weight.tenthsKg)
    }

    // --- FR-ENTRY-005, the date ------------------------------------------------------

    @Test
    fun `changing the date never changes the weight`() = runTest {
        val model = viewModel()
        model.onWeightChanged(Weight.ofTenthsClamped(662))
        model.onDateSelected(LocalDate.of(2026, 8, 11))

        assertEquals(662, model.uiState.value.weight.tenthsKg)
        assertEquals(LocalDate.of(2026, 8, 11), model.uiState.value.date)
    }

    @Test
    fun `a date that already holds a measurement still leaves the weight alone`() = runTest {
        val existing = LocalDate.of(2026, 8, 11)
        val model = viewModel(history = listOf(Measurement(existing, Weight.ofTenthsClamped(901))))
        model.onWeightChanged(Weight.ofTenthsClamped(662))
        model.onDateSelected(existing)

        assertEquals(662, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `a date after today is refused`() = runTest {
        val model = viewModel()
        model.onDateSelected(TODAY.plusDays(1))

        assertEquals(TODAY, model.uiState.value.date)
    }

    @Test
    fun `the date picker opens and closes`() = runTest {
        val model = viewModel()
        assertFalse(model.uiState.value.datePickerVisible)
        model.onDatePickerOpened()
        assertTrue(model.uiState.value.datePickerVisible)
        model.onDatePickerDismissed()
        assertFalse(model.uiState.value.datePickerVisible)
    }

    @Test
    fun `confirming a date closes the picker`() = runTest {
        val model = viewModel()
        model.onDatePickerOpened()
        model.onDateSelected(LocalDate.of(2026, 8, 11))

        assertFalse(model.uiState.value.datePickerVisible)
    }

    // --- FR-ENTRY-006, saving --------------------------------------------------------

    @Test
    fun `saving creates the measurement`() = runTest {
        val repository = FakeMeasurementRepository()
        val model = viewModel(repository = repository)
        model.onWeightChanged(Weight.ofTenthsClamped(745))
        model.onSave()

        assertEquals(listOf(Measurement(TODAY, Weight.ofTenthsClamped(745))), repository.stored)
    }

    @Test
    fun `saving replaces the measurement already on that date`() = runTest {
        val repository = FakeMeasurementRepository(listOf(measurementOf("2026-08-23", 80.0)))
        val model = viewModel(repository = repository)
        model.onWeightChanged(Weight.ofTenthsClamped(745))
        model.onSave()

        assertEquals(1, repository.stored.size)
        assertEquals(745, repository.stored.single().weight.tenthsKg)
    }

    @Test
    fun `saving twice never leaves two measurements on one date`() = runTest {
        val repository = FakeMeasurementRepository()
        val model = viewModel(repository = repository)
        model.onSave()
        model.onStep(1)
        model.onSave()

        assertEquals(1, repository.stored.size)
        assertEquals(701, repository.stored.single().weight.tenthsKg)
    }

    @Test
    fun `saving confirms without leaving the screen`() = runTest {
        val model = viewModel()
        model.onSave()

        val state = model.uiState.value
        assertTrue(state.justSaved)
        assertNull(state.saveError)
        assertEquals(1, state.saveFlareCount)
    }

    @Test
    fun `the confirmation clears when its time is up`() = runTest {
        val model = viewModel()
        model.onSave()
        model.onSaveConfirmationFinished()

        assertFalse(model.uiState.value.justSaved)
    }

    @Test
    fun `the value stays put after a save`() = runTest {
        val model = viewModel()
        model.onWeightChanged(Weight.ofTenthsClamped(745))
        model.onSave()

        assertEquals(745, model.uiState.value.weight.tenthsKg)
    }

    @Test
    fun `a failed write shows an error and no confirmation`() = runTest {
        val repository = FakeMeasurementRepository().apply { failWrites = true }
        val model = viewModel(repository = repository)
        model.onSave()

        val state = model.uiState.value
        assertFalse(state.justSaved)
        assertEquals(EntryViewModel.SAVE_ERROR, state.saveError)
        assertEquals(0, state.saveFlareCount)
        assertTrue(repository.stored.isEmpty())
    }

    // --- FR-ENTRY-007, the greeting --------------------------------------------------

    @Test
    fun `a display name produces the greeting`() = runTest {
        val model = viewModel(profile = UserProfile(displayName = "Kris"))

        assertEquals("Hello Kris,", model.uiState.value.greeting)
    }

    @Test
    fun `no display name means no greeting line at all`() = runTest {
        assertNull(viewModel().uiState.value.greeting)
        assertNull(viewModel(profile = UserProfile(displayName = "   ")).uiState.value.greeting)
    }

    // --- Preferences -----------------------------------------------------------------

    @Test
    fun `the haptics preference reaches the screen`() = runTest {
        assertTrue(viewModel().uiState.value.hapticsEnabled)
        assertFalse(
            viewModel(preferences = UserPreferences(hapticsEnabled = false))
                .uiState.value.hapticsEnabled,
        )
    }
}
