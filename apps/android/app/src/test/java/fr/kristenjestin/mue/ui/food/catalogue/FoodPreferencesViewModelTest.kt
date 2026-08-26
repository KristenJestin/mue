package fr.kristenjestin.mue.ui.food.catalogue

import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.ui.entry.FakeUserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Show energy` (PRD_FOOD 13.2, FR-FOOD-010), the setting that made the §22 criterion reachable.
 *
 * Nothing here is about a screen. It is about the preference existing, defaulting to *shown*, and
 * surviving a write — the three things every Food screen now depends on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodPreferencesViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A preference nobody has touched shows the figures.
     *
     * The permissive default is deliberate, and it is the opposite of `rememberTimerHaptics`,
     * which starts at `false` on the argument that a phone buzzing against its owner's wishes is
     * worse than a missed buzz. Here the harm runs the other way: blanking the numbers of someone
     * who never asked would be the module hiding what they opened it to read.
     */
    @Test
    fun `energy is shown until someone says otherwise`() = runTest(mainDispatcher) {
        val viewModel = FoodPreferencesViewModel(FakeUserPreferencesRepository())

        assertTrue(viewModel.uiState.value.showEnergy, "the very first frame already shows it")

        val collector = launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showEnergy)
        collector.cancel()
    }

    @Test
    fun `the toggle is written through to the preferences`() = runTest(mainDispatcher) {
        val store = FakeUserPreferencesRepository()
        val viewModel = FoodPreferencesViewModel(store)
        val collector = launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        viewModel.onShowEnergyChange(false)
        advanceUntilIdle()

        assertFalse(store.preferences.first().showEnergy)
        assertFalse(viewModel.uiState.value.showEnergy)

        viewModel.onShowEnergyChange(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showEnergy)
        collector.cancel()
    }

    /** A stored refusal is read back, so the sheet opens on what the person actually chose. */
    @Test
    fun `a stored preference reaches the sheet`() = runTest(mainDispatcher) {
        val viewModel = FoodPreferencesViewModel(
            FakeUserPreferencesRepository(UserPreferences(showEnergy = false)),
        )
        val collector = launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showEnergy)
        collector.cancel()
    }

    /** FR-FOOD-010 keeps the two preferences apart: hiding the figures must not silence the phone. */
    @Test
    fun `hiding the energy leaves the haptics alone`() = runTest(mainDispatcher) {
        val store = FakeUserPreferencesRepository()
        val viewModel = FoodPreferencesViewModel(store)
        val collector = launch { viewModel.uiState.collect { } }
        advanceUntilIdle()

        viewModel.onShowEnergyChange(false)
        advanceUntilIdle()

        assertTrue(store.preferences.first().hapticsEnabled)
        collector.cancel()
    }
}
