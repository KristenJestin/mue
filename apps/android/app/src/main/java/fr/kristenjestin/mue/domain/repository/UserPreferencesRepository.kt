package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/** The preferences of PRD 11.3. */
interface UserPreferencesRepository {

    /** Emits [UserPreferences.DEFAULT] until something has been saved. */
    val preferences: Flow<UserPreferences>

    suspend fun setHapticsEnabled(enabled: Boolean)
}
