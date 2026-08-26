package fr.kristenjestin.mue.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The single health profile row. Every query names the row rather than trusting the table to
 * hold exactly one, so a second row could never answer a read by accident. `'me'` is
 * [HealthProfileEntity.ROW_ID].
 */
@Dao
interface HealthProfileDao {

    @Query("SELECT * FROM health_profile WHERE id = 'me'")
    fun observe(): Flow<HealthProfileEntity?>

    @Query("SELECT * FROM health_profile WHERE id = 'me'")
    suspend fun get(): HealthProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HealthProfileEntity)

    /** Used by the one-shot seeding, which must not overwrite a profile already in Room. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: HealthProfileEntity)
}
