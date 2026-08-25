package fr.kristenjestin.mue.data.local.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.TrackingMode

/**
 * The seventeen provided exercises of PRD 9.2, installed with the database and never removable.
 *
 * Two paths reach this object, and both have to. `Callback.onCreate` runs on a fresh install
 * only; every phone that already holds a version 2 database runs [MueMigrations.MIGRATION_2_3]
 * instead and would otherwise open the exercise picker on an empty list. One seed called from
 * both places is what keeps the two populations identical.
 *
 * The ids are written down rather than generated. `UUID.randomUUID()` would give `Bench press`
 * a different key on every phone, which costs nothing today and everything the day two devices
 * have to agree on what an exercise is. `INSERT OR IGNORE` against `UNIQUE(name_folded)` then
 * makes running the seed twice — a migration followed by a callback on a rebuilt file — a no-op.
 */
internal object ExerciseCatalogSeed {

    val DEFINITIONS: List<ExerciseDefinition> = listOf(
        definition("75ba917d-2f60-4322-b8b2-7f3fb7bd5ec2", "Barbell squat", TrackingMode.WEIGHT_AND_REPS, EquipmentType.BARBELL),
        definition("b4eafd5e-15fb-44eb-994d-0dbce02a680d", "Deadlift", TrackingMode.WEIGHT_AND_REPS, EquipmentType.BARBELL),
        definition("cfa9cf63-a960-4bf2-8999-8cbae4096962", "Bench press", TrackingMode.WEIGHT_AND_REPS, EquipmentType.BARBELL),
        definition("235e82e5-a1d3-44f3-9bb9-6ecc053b7f87", "Overhead press", TrackingMode.WEIGHT_AND_REPS, EquipmentType.BARBELL),
        definition("6ba20405-45ed-4d06-82a8-5edcf6be675a", "Barbell row", TrackingMode.WEIGHT_AND_REPS, EquipmentType.BARBELL),
        definition("f8fa68fb-1dde-4ecc-a1bb-786db643a273", "Dumbbell row", TrackingMode.WEIGHT_AND_REPS, EquipmentType.DUMBBELLS),
        definition("ee8a7f8a-95dd-48c2-b91d-5fa1a286ce2d", "Dumbbell curl", TrackingMode.WEIGHT_AND_REPS, EquipmentType.DUMBBELLS),
        definition("d7b8c2ed-fe43-4ec9-a0b5-f6f117a5091f", "Lateral raise", TrackingMode.WEIGHT_AND_REPS, EquipmentType.DUMBBELLS),
        definition("46658d39-6110-4a04-acb8-5aee78ae9684", "Goblet squat", TrackingMode.WEIGHT_AND_REPS, EquipmentType.KETTLEBELL),
        definition("6eed0227-aff0-4c8b-8a0f-77048223f551", "Lat pulldown", TrackingMode.WEIGHT_AND_REPS, EquipmentType.MACHINE),
        definition("70cfd79b-a7db-4882-98a9-85611ad6c26f", "Leg press", TrackingMode.WEIGHT_AND_REPS, EquipmentType.MACHINE),
        definition("23d8428d-ebf4-402b-9b4a-b31765956a5b", "Leg curl", TrackingMode.WEIGHT_AND_REPS, EquipmentType.MACHINE),
        definition("8effda24-94c5-4e2b-aa6c-2050dc739160", "Chest press", TrackingMode.WEIGHT_AND_REPS, EquipmentType.MACHINE),
        definition("76169b4e-7a5e-49cd-9700-0872846b2d73", "Pull-up", TrackingMode.REPS_ONLY, EquipmentType.BODYWEIGHT),
        definition("c1ab1220-265c-43fc-902e-ac486977bc49", "Push-up", TrackingMode.REPS_ONLY, EquipmentType.BODYWEIGHT),
        definition("4477d000-84c4-4560-8802-2de99abda01d", "Plank", TrackingMode.DURATION, EquipmentType.BODYWEIGHT),
        definition("7cfda4cd-b035-481e-b117-1fcd870800b7", "Weighted plank", TrackingMode.WEIGHT_AND_DURATION, EquipmentType.BODYWEIGHT),
    )

    /** Bound arguments rather than an interpolated string: a name is data, never SQL. */
    private const val INSERT = """
        INSERT OR IGNORE INTO `${ExerciseDefinitionEntity.TABLE_NAME}`
        (`id`, `name`, `name_folded`, `tracking_mode`, `equipment`, `is_custom`)
        VALUES (?, ?, ?, ?, ?, 0)
    """

    fun insertInto(db: SupportSQLiteDatabase) {
        DEFINITIONS.forEach { definition ->
            db.execSQL(
                INSERT,
                arrayOf(
                    definition.id.value,
                    definition.name,
                    definition.nameFolded,
                    definition.trackingMode.id,
                    definition.equipment?.id,
                ),
            )
        }
    }

    /** The fresh-install half of the pair; [MueMigrations.MIGRATION_2_3] is the other. */
    val CALLBACK: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            insertInto(db)
        }
    }

    private fun definition(
        id: String,
        name: String,
        trackingMode: TrackingMode,
        equipment: EquipmentType,
    ): ExerciseDefinition = ExerciseDefinition(
        id = ExerciseDefinitionId(id),
        name = name,
        trackingMode = trackingMode,
        equipment = equipment,
        isCustom = false,
    )
}
