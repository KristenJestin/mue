package fr.kristenjestin.mue.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history of [MueDatabase]. Every version ever shipped keeps a path forward from here.
 *
 * PRD 16.3 forbids a migration that drops history, and PRD 20.3 forbids
 * `fallbackToDestructiveMigration` in any of its forms, so nothing in this file may ever
 * delete a measurement it cannot first convert.
 */
internal object MueMigrations {

    /**
     * 1 → 2: the weight column changes unit, from tenths to hundredths of a kilogram.
     *
     * The rows are copied rather than altered in place. `ALTER TABLE … RENAME COLUMN` needs
     * SQLite 3.25, which arrives with Android 11, and Mue supports Android 8 (PRD 20.1) — so
     * the only portable rename is a new table, a converting `INSERT … SELECT` and a swap. All
     * four statements run inside the transaction Room opens around a migration, so an
     * interrupted upgrade leaves the old table untouched rather than a half-converted one.
     *
     * `× 10` is exact in integer arithmetic: 745 tenths becomes 7450 hundredths, and every
     * migrated value therefore lands on the 0.05 kg grid of BR-003 with nothing to round.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `measurements_v2` " +
                    "(`date` TEXT NOT NULL, `weight_cg` INTEGER NOT NULL, PRIMARY KEY(`date`))"
            )
            db.execSQL(
                "INSERT INTO `measurements_v2` (`date`, `weight_cg`) " +
                    "SELECT `date`, `weight_dg` * 10 FROM `measurements`"
            )
            db.execSQL("DROP TABLE `measurements`")
            db.execSQL("ALTER TABLE `measurements_v2` RENAME TO `measurements`")
        }
    }

    /**
     * 2 → 3: the six tables of the Activities module (PRD 16.2).
     *
     * Purely additive — `measurements` is not read, not written and not mentioned — so a phone
     * that has been logging weights since version 1 arrives here with its history byte for byte.
     *
     * The statements are the ones Room exports for version 3, kept identical on purpose: a
     * migrated file and a freshly created one have to be the same database, and
     * `MigrationTestHelper` compares them column by column, index by index.
     *
     * The seed at the end is what makes the exercise catalogue exist on a phone that already has
     * Mue. `Callback.onCreate` fires on a fresh install only, so without this line every
     * upgrading device would open the exercise picker on nothing.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `activity_sessions` " +
                    "(`id` TEXT NOT NULL, `movement` TEXT NOT NULL, `custom_movement_name` TEXT, " +
                    "`environment` TEXT NOT NULL, `started_on` TEXT NOT NULL, `started_at_time` TEXT, " +
                    "`duration_seconds` INTEGER NOT NULL, `perceived_effort` INTEGER, `notes` TEXT, " +
                    "`source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_activity_sessions_started_on` " +
                    "ON `activity_sessions` (`started_on`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_definitions` " +
                    "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `name_folded` TEXT NOT NULL, " +
                    "`tracking_mode` TEXT NOT NULL, `equipment` TEXT, `is_custom` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_definitions_name_folded` " +
                    "ON `exercise_definitions` (`name_folded`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `activity_metrics` " +
                    "(`session_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, PRIMARY KEY(`session_id`, `kind`), " +
                    "FOREIGN KEY(`session_id`) REFERENCES `activity_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_equipment` " +
                    "(`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `equipment_type` TEXT NOT NULL, " +
                    "`custom_name` TEXT, `custom_name_folded` TEXT NOT NULL DEFAULT '', " +
                    "`position` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`session_id`) REFERENCES `activity_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_equipment_session_id` " +
                    "ON `session_equipment` (`session_id`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_session_equipment_session_id_equipment_type_custom_name_folded` " +
                    "ON `session_equipment` (`session_id`, `equipment_type`, `custom_name_folded`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `strength_exercises` " +
                    "(`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                    "`exercise_definition_id` TEXT NOT NULL, `position` INTEGER NOT NULL, `notes` TEXT, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`session_id`) REFERENCES `activity_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exercise_definition_id`) REFERENCES `exercise_definitions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_strength_exercises_session_id` " +
                    "ON `strength_exercises` (`session_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_strength_exercises_exercise_definition_id` " +
                    "ON `strength_exercises` (`exercise_definition_id`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `strength_sets` " +
                    "(`id` TEXT NOT NULL, `strength_exercise_id` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `set_type` TEXT NOT NULL DEFAULT 'working', " +
                    "`repetitions` INTEGER, `load_grams` INTEGER, `duration_seconds` INTEGER, " +
                    "`perceived_effort` INTEGER, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`strength_exercise_id`) REFERENCES `strength_exercises`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_strength_sets_strength_exercise_id` " +
                    "ON `strength_sets` (`strength_exercise_id`)"
            )

            ExerciseCatalogSeed.insertInto(db)
        }
    }

    /**
     * 3 → 4: the two tables of the Activity Timer (timer PRD 9).
     *
     * Purely additive again. `measurements` and the six activity tables are neither read, nor
     * written, nor mentioned, so every weight and every session arrives here byte for byte.
     *
     * No catalogue is seeded. [MIGRATION_2_3] had to, because `Callback.onCreate` fires on a
     * fresh install alone and an upgrading phone would otherwise open the exercise picker on
     * nothing; version 4 adds no catalogue and calling the seed here would only be a second
     * chance to double it.
     *
     * `ActivitySource` gaining `timer` belongs to this version and yet changes no schema: the
     * column is `TEXT` and an enum persists by its stable id, so a new value is a new string in
     * a column that already accepts it.
     *
     * The statements are the ones Room exports for version 4, kept identical on purpose — a
     * migrated file and a freshly created one have to be the same database, and
     * `MigrationTestHelper` compares them column by column and index by index.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `timed_activity_drafts` " +
                    "(`id` TEXT NOT NULL, `status` TEXT NOT NULL, `movement` TEXT NOT NULL, " +
                    "`custom_movement_name` TEXT, `environment` TEXT NOT NULL, " +
                    "`started_at_millis` INTEGER NOT NULL, `started_on` TEXT NOT NULL, " +
                    "`started_at_local_time` TEXT NOT NULL, " +
                    "`accumulated_active_seconds` INTEGER NOT NULL, " +
                    "`current_segment_started_at_millis` INTEGER, " +
                    "`current_segment_started_elapsed_realtime_millis` INTEGER, " +
                    "`boot_reference_millis` INTEGER, `finished_at_millis` INTEGER, " +
                    "`review_form_state` TEXT, " +
                    "`review_form_schema_version` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_timed_activity_drafts_status` " +
                    "ON `timed_activity_drafts` (`status`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `timed_draft_equipment` " +
                    "(`id` TEXT NOT NULL, `draft_id` TEXT NOT NULL, " +
                    "`equipment_type` TEXT NOT NULL, `custom_name` TEXT, " +
                    "`custom_name_folded` TEXT NOT NULL DEFAULT '', " +
                    "`position` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`draft_id`) REFERENCES `timed_activity_drafts`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_timed_draft_equipment_draft_id` " +
                    "ON `timed_draft_equipment` (`draft_id`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_timed_draft_equipment_draft_id_equipment_type_custom_name_folded` " +
                    "ON `timed_draft_equipment` (`draft_id`, `equipment_type`, `custom_name_folded`)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
