package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the module's preferences sheet draws (PRD_FOOD 13.2, FR-FOOD-010). */
@Immutable
data class FoodPreferencesUiState(
    val showEnergy: Boolean = UserPreferences.DEFAULT.showEnergy,
)

/**
 * The one setting PRD_FOOD 13.2 gives the module, read from and written to the device's own
 * preferences (FR-FOOD-010: "la préférence est locale à l'appareil").
 *
 * It goes through [UserPreferencesRepository] rather than through a store of its own, beside
 * `hapticsEnabled` and with a setter of its own — so two screens writing two different
 * preferences can never overwrite one another's field.
 *
 * The initial value is `true`, which is [UserPreferences.DEFAULT]. `rememberTimerHaptics` starts
 * its own flag at `false` on the argument that a phone which buzzes against the owner's wishes is
 * worse than one that misses a buzz; the argument inverts here. Blanking the figures for a frame
 * on every cold start, for someone who never asked for that, would be the module hiding what they
 * opened it to read.
 */
class FoodPreferencesViewModel(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<FoodPreferencesUiState> = preferences.preferences
        .map { FoodPreferencesUiState(showEnergy = it.showEnergy) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = FoodPreferencesUiState(),
        )

    fun onShowEnergyChange(enabled: Boolean) {
        viewModelScope.launch { preferences.setShowEnergy(enabled) }
    }

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The preference lives on [fr.kristenjestin.mue.di.AppContainer] itself and not on its
         * Food container: it is the app's preference file, shared with the haptics flag, and
         * FR-FOOD-010 keeps it out of every synchronised aggregate.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                FoodPreferencesViewModel(app.container.userPreferencesRepository)
            }
        }
    }
}

@Composable
fun foodPreferencesViewModel(): FoodPreferencesViewModel =
    viewModel(factory = FoodPreferencesViewModel.Factory)
