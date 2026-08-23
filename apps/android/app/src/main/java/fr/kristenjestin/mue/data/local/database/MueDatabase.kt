package fr.kristenjestin.mue.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room holds the weight history only; the four profile and preference fields live in
 * DataStore instead (PRD 20.2).
 *
 * Destructive migrations are never enabled: PRD 16.3 forbids losing history, and the
 * user has no cloud backup to fall back on.
 */
@Database(
    entities = [MeasurementEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MueDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val NAME = "mue.db"

        fun build(context: Context): MueDatabase =
            Room.databaseBuilder(context.applicationContext, MueDatabase::class.java, NAME)
                .build()
    }
}
