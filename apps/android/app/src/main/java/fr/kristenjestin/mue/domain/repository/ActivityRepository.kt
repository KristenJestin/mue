package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.LastPerformance
import kotlinx.coroutines.flow.Flow

/**
 * The activity history (PRD 16.1).
 *
 * Reads are flows so no screen ever blocks the main thread (PRD 16.4); writes are suspending
 * and atomic. Ordering is fixed by the storage and is the same everywhere the app lists
 * sessions: most recent day first, timed sessions before untimed ones on a shared day, and a
 * stable tiebreak so two identical sessions never swap places between two reads.
 */
interface ActivityRepository {

    /** The dashboard's five most recent sessions (PRD FR-ACTIVITY-002). */
    fun observeRecentSummaries(limit: Int): Flow<List<ActivitySummary>>

    /** Every session, most recent first and with no limit at all (PRD FR-ACTIVITY-012). */
    fun observeAllSummaries(): Flow<List<ActivitySummary>>

    /** The sessions inside [window]; what the weekly aggregate of PRD FR-ACTIVITY-001 reads. */
    fun observeSummariesIn(window: DateWindow): Flow<List<ActivitySummary>>

    /**
     * How many sessions exist at all. The dashboard needs it to tell an empty history
     * (PRD 13.1) from a quiet week (PRD 13.2) and to hide `See all` at five or fewer.
     */
    fun observeSessionCount(): Flow<Int>

    /** Everything the editor reopens: the session, its metrics, its equipment and its sets. */
    suspend fun findDetail(id: ActivityId): ActivitySessionDetail?

    /**
     * Creates or replaces a whole session in one transaction (PRD 16.1): all of it, or none.
     * Several sessions may share a date, so this never merges by day the way a weight does.
     */
    suspend fun save(detail: ActivitySessionDetail)

    /** PRD FR-ACTIVITY-011: metrics, equipment, exercises and sets go with it. */
    suspend fun delete(id: ActivityId)

    /**
     * PRD 11.4: the last valid set of the most recent session that used [exercise], with
     * [excludingSession] left out so the session being edited never quotes itself.
     */
    suspend fun findLastPerformance(
        exercise: ExerciseDefinitionId,
        excludingSession: ActivityId? = null,
    ): LastPerformance?
}
