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
        ScaleEntity::class,
        BodyCompositionEntity::class,
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

    /**
     * Les balances appairées. Sans journal de synchronisation, contrairement à tous les autres
     * DAO métier : PRD_SCALE 22 tient cette collection hors du fil.
     */
    abstract fun scaleDao(): ScaleDao

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
         * 7: le module balance (PRD_SCALE 21.1) — `scale`, `body_composition`, les trois colonnes
         *    de provenance et d'impédance de `measurements`, et `health_profile.sex`. Additive :
         *    aucun poids n'est réécrit, aucune ligne n'est perdue. `scale` est la première table
         *    **jamais synchronisée** du fichier (PRD_SCALE 22), et la seule dont le DAO n'hérite
         *    pas de `SyncJournalDao`.
         */
        const val VERSION = 7

        fun build(context: Context): MueDatabase =
            Room.databaseBuilder(context.applicationContext, MueDatabase::class.java, NAME)
                .addMigrations(*MueMigrations.ALL)
                .addCallback(ExerciseCatalogSeed.CALLBACK)
                .build()
    }
}
