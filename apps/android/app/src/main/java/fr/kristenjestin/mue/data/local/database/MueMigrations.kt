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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
