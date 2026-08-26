package fr.kristenjestin.mue.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room holds the weight history, the activity history and — since version 5 — the two health
 * profile fields the server synchronises; the display name and the interface preferences stay in
 * DataStore (PRD 20.2, sync PRD 10.1).
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
        TimedActivityDraftEntity::class,
        TimedDraftEquipmentEntity::class,
        SyncMutationEntity::class,
        SyncAggregateStateEntity::class,
        SyncStateEntity::class,
        HealthProfileEntity::class,
        FoodEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        FoodLogEntryEntity::class,
        MealPlanEntryEntity::class,
    ],
    version = MueDatabase.VERSION,
    exportSchema = true,
)
abstract class MueDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    abstract fun activityDao(): ActivityDao

    abstract fun exerciseCatalogDao(): ExerciseCatalogDao

    abstract fun timerDao(): TimerDao

    abstract fun syncDao(): SyncDao

    abstract fun healthProfileDao(): HealthProfileDao

    abstract fun foodDao(): FoodDao

    abstract fun recipeDao(): RecipeDao

    abstract fun foodLogDao(): FoodLogDao

    abstract fun mealPlanDao(): MealPlanDao

    companion object {
        const val NAME = "mue.db"

        /**
         * 2: the weight column moved from tenths to hundredths of a kilogram (PRD 20.3).
         * 3: the six additive tables of the Activities module (PRD 16.2).
         * 4: the two additive tables of the Activity Timer (timer PRD 9).
         * 5: the three synchronisation tables and the health profile (sync PRD 19).
         * 6: the five additive tables of the Food module (PRD_FOOD 20), carrying no
         *    synchronisation column of their own — `sync_aggregate_state` already keys that
         *    metadata by aggregate type, and PRD_FOOD 20.1's own reason for asking is served
         *    better by a table that never has to be migrated again.
         */
        const val VERSION = 6

        fun build(context: Context): MueDatabase =
            Room.databaseBuilder(context.applicationContext, MueDatabase::class.java, NAME)
                .addMigrations(*MueMigrations.ALL)
                .addCallback(ExerciseCatalogSeed.CALLBACK)
                .build()
    }
}
