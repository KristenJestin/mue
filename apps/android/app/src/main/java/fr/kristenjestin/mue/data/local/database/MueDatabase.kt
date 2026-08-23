package fr.kristenjestin.mue.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room holds the weight history only; the four profile and preference fields live in
 * DataStore instead (PRD 20.2).
 *
 * Destructive migrations are never enabled: PRD 16.3 and 20.3 forbid losing history, and the
 * user has no cloud backup to fall back on. Every version bump therefore ships a real
 * [MueMigrations] entry, and a database Room cannot migrate must fail loudly instead of
 * quietly starting empty.
 */
@Database(
    entities = [MeasurementEntity::class],
    version = MueDatabase.VERSION,
    exportSchema = true,
)
abstract class MueDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    companion object {
        const val NAME = "mue.db"

        /** 2: the weight column moved from tenths to hundredths of a kilogram (PRD 20.3). */
        const val VERSION = 2

        fun build(context: Context): MueDatabase =
            Room.databaseBuilder(context.applicationContext, MueDatabase::class.java, NAME)
                .addMigrations(*MueMigrations.ALL)
                .build()
    }
}
