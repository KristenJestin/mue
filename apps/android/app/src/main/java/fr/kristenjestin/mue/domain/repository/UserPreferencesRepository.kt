package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/** The preferences of PRD 11.3 and of PRD_FOOD FR-FOOD-010. */
interface UserPreferencesRepository {

    /** Emits [UserPreferences.DEFAULT] until something has been saved. */
    val preferences: Flow<UserPreferences>

    suspend fun setHapticsEnabled(enabled: Boolean)

    /**
     * PRD_FOOD FR-FOOD-010: turns every energy and macronutrient figure of the Food module on or
     * off. A setter of its own rather than a whole-object save, exactly like [setHapticsEnabled],
     * so two screens writing two different preferences never overwrite one another's field.
     */
    suspend fun setShowEnergy(enabled: Boolean)
}
