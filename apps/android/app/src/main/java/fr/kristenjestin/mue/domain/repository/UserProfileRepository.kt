package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

/** The health profile of PRD 11.2. Never leaves the device (PRD 16.1). */
interface UserProfileRepository {

    /** Emits [UserProfile.EMPTY] until something has been saved. */
    val profile: Flow<UserProfile>

    suspend fun save(profile: UserProfile)
}
