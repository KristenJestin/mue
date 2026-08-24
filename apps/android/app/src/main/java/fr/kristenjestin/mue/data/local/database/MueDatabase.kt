package fr.kristenjestin.mue.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room holds the weight history and the activity history; the four profile and preference fields
 * live in DataStore instead (PRD 20.2).
 *
 * Destructive migrations are never enabled: PRD 16.3 and 20.3 forbid losing history, and the
 * user has no cloud backup to fall back on. Every version bump therefore ships a real
 * [MueMigrations] entry, and a database Room cannot migrate must fail loudly instead of
 * quietly starting empty.
 */
@Database(
    entities = [
        MeasurementEntity::class,
        ActivitySessionEntity::class,
        ActivityMetricEntity::class,
        SessionEquipmentEntity::class,
        ExerciseDefinitionEntity::class,
        StrengthExerciseEntity::class,
        StrengthSetEntity::class,
    ],
    version = MueDatabase.VERSION,
    exportSchema = true,
)
abstract class MueDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    abstract fun activityDao(): ActivityDao

    abstract fun exerciseCatalogDao(): ExerciseCatalogDao

    companion object {
        const val NAME = "mue.db"

        /**
         * 2: the weight column moved from tenths to hundredths of a kilogram (PRD 20.3).
         * 3: the six additive tables of the Activities module (PRD 16.2).
         */
        const val VERSION = 3

        fun build(context: Context): MueDatabase =
            Room.databaseBuilder(context.applicationContext, MueDatabase::class.java, NAME)
                .addMigrations(*MueMigrations.ALL)
                .addCallback(ExerciseCatalogSeed.CALLBACK)
                .build()
    }
}
