package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    @Query("SELECT * FROM measurements ORDER BY date ASC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    /**
     * A null bound means "unbounded", which is how the `All` period is expressed
     * without inventing sentinel dates.
     */
    @Query(
        """
        SELECT * FROM measurements
        WHERE (:start IS NULL OR date >= :start)
          AND (:end IS NULL OR date <= :end)
        ORDER BY date ASC
        """
    )
    fun observeInWindow(start: String?, end: String?): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements ORDER BY date DESC LIMIT 1")
    fun observeLatest(): Flow<MeasurementEntity?>

    @Query("SELECT * FROM measurements ORDER BY date ASC")
    suspend fun getAll(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE date = :date")
    suspend fun findByDate(date: String): MeasurementEntity?

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    /** REPLACE is what makes PRD BR-002 silent: writing an existing date overwrites it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /**
     * Edits a measurement whose date may have moved (PRD FR-PROGRESS-005). Removing
     * the old row and writing the new one must not be observable as two steps
     * (PRD 16.3).
     */
    @Transaction
    suspend fun replace(originalDate: String, entity: MeasurementEntity) {
        if (originalDate != entity.date) {
            deleteByDate(originalDate)
        }
        upsert(entity)
    }
}
