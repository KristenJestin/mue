package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The weight history. One date holds at most one measurement (PRD BR-001) and that
 * uniqueness is enforced by the storage itself, not by this contract.
 */
interface MeasurementRepository {

    /** Every measurement, oldest first. */
    fun observeAll(): Flow<List<Measurement>>

    /** The measurements inside [window], oldest first. */
    fun observeIn(window: DateWindow): Flow<List<Measurement>>

    /** The measurement with the most recent date, or null when the history is empty. */
    fun observeLatest(): Flow<Measurement?>

    suspend fun getAll(): List<Measurement>

    suspend fun findByDate(date: LocalDate): Measurement?

    /** Creates the entry, or silently replaces the one already on that date (PRD BR-002). */
    suspend fun save(measurement: Measurement)

    /**
     * Saves an edited measurement whose date may have moved (PRD FR-PROGRESS-005).
     * Dropping the old date and writing the new one happens in one transaction.
     */
    suspend fun replace(originalDate: LocalDate, measurement: Measurement)

    suspend fun delete(date: LocalDate)
}
