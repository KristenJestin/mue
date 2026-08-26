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

    /**
     * 4 → 5: the three synchronisation tables and the health profile (sync PRD 19).
     *
     * Purely additive once more. Not a measurement, not a session and not a draft is read,
     * written or mentioned, so a phone that has been logging weights since version 1 arrives at
     * version 5 with every row it had.
     *
     * The four tables arrive empty and that is the correct starting state. An empty
     * `sync_mutations` means nothing is waiting to be sent, an empty `sync_state` means no
     * server is paired, and an empty `sync_aggregate_state` means the server has acknowledged
     * nothing — which is exactly true of a phone that has never synchronised.
     *
     * `health_profile` is created empty too, and deliberately not filled here. The height and
     * the birth date are in a Preferences DataStore, and a `SupportSQLiteDatabase` cannot read
     * one: it is a file this connection knows nothing about, in a format SQL cannot parse. The
     * copy is therefore a startup task guarded by `sync_state.profile_seeded`, and putting it
     * here instead would either crash the migration or, worse, silently seed nothing.
     *
     * The statements are the ones Room exports for version 5, kept identical on purpose — a
     * migrated file and a freshly created one have to be the same database, and
     * `MigrationTestHelper` compares them column by column and index by index.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_mutations` " +
                    "(`mutation_id` TEXT NOT NULL, `aggregate_type` TEXT NOT NULL, " +
                    "`aggregate_id` TEXT NOT NULL, `op` TEXT NOT NULL, `base_revision` INTEGER, " +
                    "`payload` TEXT, `payload_schema_version` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`attempt_count` INTEGER NOT NULL, `last_error_code` TEXT, " +
                    "`last_error_message` TEXT, PRIMARY KEY(`mutation_id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_mutations_state_created_at` " +
                    "ON `sync_mutations` (`state`, `created_at`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_aggregate_state` " +
                    "(`aggregate_type` TEXT NOT NULL, `aggregate_id` TEXT NOT NULL, " +
                    "`revision` INTEGER, `server_updated_at` INTEGER, " +
                    "`deleted_at` INTEGER, `last_mutation_id` TEXT, `origin_type` TEXT, " +
                    "`origin_id` TEXT, PRIMARY KEY(`aggregate_type`, `aggregate_id`))"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_state` " +
                    "(`id` INTEGER NOT NULL, `server_url` TEXT, `server_name` TEXT, " +
                    "`account_id` TEXT, `device_id` TEXT, `cursor` TEXT, " +
                    "`last_success_at` INTEGER, `last_error_code` TEXT, " +
                    "`last_error_message` TEXT, `profile_seeded` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_profile` " +
                    "(`id` TEXT NOT NULL, `height_cm` INTEGER, `birth_date` TEXT, " +
                    "PRIMARY KEY(`id`))"
            )
        }
    }

    /**
     * 5 → 6: the five additive tables of the Food module (PRD_FOOD 20).
     *
     * Additive once more, and the strongest case of it so far: not one of the thirteen tables
     * already in the file is read, written or even named. A phone that has been logging weights
     * since version 1 arrives at version 6 with every measurement, every session, every draft and
     * every pending mutation exactly as version 5 left them.
     *
     * **No synchronisation column is created here, and that is the decision this migration
     * exists to record.** PRD_FOOD 20.1 asks each of these five tables to carry the sync PRD's
     * section 12.1 metadata from its first migration — identity, revision, timestamps, tombstone,
     * origin, last mutation. Version 5 already shipped that metadata, once, in
     * `sync_aggregate_state`, keyed by `(aggregate_type, aggregate_id)`; `FoodAggregates` already
     * declares the four type names this module contributes to it. Copying seven columns into five
     * more tables would give one fact six places to disagree, and would put a tombstone on a row
     * that no longer exists — which is exactly what the generic table was built to avoid.
     *
     * The reason 20.1 gives for its own rule is served better this way, not worse. It fears
     * migrating a populated food journal a second time to add sync columns. A journal that never
     * holds sync columns can never need that migration; the sixth aggregate, and the seventh,
     * cost `sync_aggregate_state` nothing at all.
     *
     * `meal_plan_entry` is keyed by `(planned_on, slot)` rather than by a UUID, which is also why
     * PRD_FOOD 20.2's "unicité" on that pair needs no index of its own: SQLite backs the primary
     * key with one. See `MealPlanEntryEntity` for why the composite key and not an id.
     *
     * No catalogue is seeded here. The Ciqual subset is an asset, and a `SupportSQLiteDatabase`
     * cannot open one — `CiqualSeeding` does it at startup, guarded by a version rather than by a
     * lifecycle, so that a fresh install and an upgrading phone install the same catalogue.
     *
     * The statements are the ones Room exports for version 6, kept identical on purpose — a
     * migrated file and a freshly created one have to be the same database, and
     * `MigrationTestHelper` compares them column by column and index by index.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food` " +
                    "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `name_folded` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `reference_unit` TEXT NOT NULL, `brand` TEXT, " +
                    "`brand_folded` TEXT, `barcode` TEXT, `source_id` TEXT, " +
                    "`source_version` TEXT, `serving_label` TEXT, " +
                    "`serving_thousandths` INTEGER, `cooked_ratio_thousandths` INTEGER, " +
                    "`raw_label` TEXT NOT NULL, `cooked_label` TEXT NOT NULL, `image_ref` TEXT, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "`energy_milli_kcal` INTEGER, `protein_milligrams` INTEGER, " +
                    "`carbs_milligrams` INTEGER, `fat_milligrams` INTEGER, " +
                    "`fibre_milligrams` INTEGER, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_name_folded` ON `food` (`name_folded`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_barcode` ON `food` (`barcode`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_source_source_id` " +
                    "ON `food` (`source`, `source_id`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recipe` " +
                    "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `name_folded` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `base_servings` INTEGER NOT NULL, `description` TEXT, " +
                    "`prep_time_minutes` INTEGER, `steps` TEXT NOT NULL, `image_ref` TEXT, " +
                    "`is_favourite` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recipe_name_folded` " +
                    "ON `recipe` (`name_folded`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recipe_ingredient` " +
                    "(`id` TEXT NOT NULL, `recipe_id` TEXT NOT NULL, `food_id` TEXT NOT NULL, " +
                    "`quantity_thousandths` INTEGER NOT NULL, `unit` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `food_name` TEXT, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recipe_ingredient_recipe_id` " +
                    "ON `recipe_ingredient` (`recipe_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recipe_ingredient_food_id` " +
                    "ON `recipe_ingredient` (`food_id`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food_log_entry` " +
                    "(`id` TEXT NOT NULL, `consumed_on` TEXT NOT NULL, " +
                    "`consumed_at` TEXT NOT NULL, `slot` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `estimation` TEXT NOT NULL, `source_ref` TEXT, " +
                    "`amount_label` TEXT, `quantity_thousandths` INTEGER, `quantity_unit` TEXT, " +
                    "`portions_thousandths` INTEGER, `weighed_cooked` INTEGER NOT NULL, " +
                    "`planned_on` TEXT, `plan_slot` TEXT, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, `energy_milli_kcal` INTEGER, " +
                    "`protein_milligrams` INTEGER, `carbs_milligrams` INTEGER, " +
                    "`fat_milligrams` INTEGER, `fibre_milligrams` INTEGER, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_food_log_entry_consumed_on_slot_consumed_at` " +
                    "ON `food_log_entry` (`consumed_on`, `slot`, `consumed_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_log_entry_source_ref` " +
                    "ON `food_log_entry` (`source_ref`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_log_entry_planned_on_plan_slot` " +
                    "ON `food_log_entry` (`planned_on`, `plan_slot`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `meal_plan_entry` " +
                    "(`planned_on` TEXT NOT NULL, `slot` TEXT NOT NULL, " +
                    "`recipe_id` TEXT NOT NULL, " +
                    "`planned_servings_thousandths` INTEGER NOT NULL, " +
                    "`consumed_log_entry_id` TEXT, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`planned_on`, `slot`), " +
                    "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_meal_plan_entry_recipe_id` " +
                    "ON `meal_plan_entry` (`recipe_id`)"
            )
        }
    }

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
