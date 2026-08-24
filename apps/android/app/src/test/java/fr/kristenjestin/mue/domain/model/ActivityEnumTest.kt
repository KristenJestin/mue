package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored ids are asserted one by one on purpose: they are the data, and an accidental
 * rename would silently orphan every row already written (PRD 16.1).
 */
class ActivityEnumTest {

    @Test
    fun `the fifteen movements of PRD 8-2 keep their ids`() {
        assertEquals(
            listOf(
                "walking", "running", "cycling", "swimming", "strength_training", "rowing",
                "elliptical", "hiking", "yoga", "climbing", "dancing", "pilates", "mobility",
                "team_sport", "other",
            ),
            Movement.entries.map { it.id },
        )
    }

    @Test
    fun `the fifteen equipment types of PRD 8-4 keep their ids`() {
        assertEquals(
            listOf(
                "treadmill", "stationary_bike", "bicycle", "rowing_machine", "elliptical_machine",
                "yoga_mat", "resistance_bands", "barbell", "dumbbells", "kettlebell", "machine",
                "bodyweight", "climbing_wall", "pool", "other",
            ),
            EquipmentType.entries.map { it.id },
        )
    }

    @Test
    fun `the four tracking modes of PRD 9-2 keep the ids of the PRD and not of the prototype`() {
        assertEquals(
            listOf("weight_and_reps", "reps_only", "duration", "weight_and_duration"),
            TrackingMode.entries.map { it.id },
        )
    }

    @Test
    fun `the remaining enums keep their ids`() {
        assertEquals(listOf("indoor", "outdoor", "unknown"), ActivityEnvironment.entries.map { it.id })
        assertEquals(listOf("manual", "health_connect"), ActivitySource.entries.map { it.id })
        assertEquals(
            listOf("manual", "equipment", "wearable", "calculated"),
            MetricSource.entries.map { it.id },
        )
        assertEquals(listOf("working", "warmup", "drop"), SetType.entries.map { it.id })
        assertEquals(
            listOf(
                "distance", "reported_speed", "average_speed", "average_pace", "estimated_energy",
                "incline", "steps", "average_heart_rate", "elevation_gain", "power", "cadence",
            ),
            MetricKind.entries.map { it.id },
        )
    }

    @Test
    fun `every id round-trips through its own reader`() {
        Movement.entries.forEach { assertEquals(it, Movement.fromId(it.id)) }
        EquipmentType.entries.forEach { assertEquals(it, EquipmentType.fromId(it.id)) }
        TrackingMode.entries.forEach { assertEquals(it, TrackingMode.fromId(it.id)) }
        ActivityEnvironment.entries.forEach { assertEquals(it, ActivityEnvironment.fromId(it.id)) }
        ActivitySource.entries.forEach { assertEquals(it, ActivitySource.fromId(it.id)) }
        MetricSource.entries.forEach { assertEquals(it, MetricSource.fromId(it.id)) }
        MetricUnit.entries.forEach { assertEquals(it, MetricUnit.fromId(it.id)) }
        SetType.entries.forEach { assertEquals(it, SetType.fromId(it.id)) }
        MetricKind.entries.forEach { assertEquals(it, MetricKind.fromIdOrNull(it.id)) }
    }

    @Test
    fun `an id written by a newer build degrades instead of throwing`() {
        assertEquals(Movement.OTHER, Movement.fromId("padel"))
        assertEquals(EquipmentType.OTHER, EquipmentType.fromId("sled"))
        assertEquals(ActivityEnvironment.UNKNOWN, ActivityEnvironment.fromId("underwater"))
        assertEquals(ActivitySource.MANUAL, ActivitySource.fromId("garmin"))
        assertEquals(MetricSource.MANUAL, MetricSource.fromId("guessed"))
        assertEquals(SetType.WORKING, SetType.fromId("cluster"))
        assertEquals(TrackingMode.WEIGHT_AND_REPS, TrackingMode.fromId("weight_and_distance"))
        assertEquals(MetricUnit.COUNT, MetricUnit.fromId("furlong"))
    }

    @Test
    fun `only the four machines of PRD 11-1 may title a session`() {
        assertEquals(
            listOf(
                EquipmentType.TREADMILL,
                EquipmentType.STATIONARY_BIKE,
                EquipmentType.ROWING_MACHINE,
                EquipmentType.ELLIPTICAL_MACHINE,
            ),
            EquipmentType.entries.filter { it.isTitling },
        )
    }

    @Test
    fun `a tracking mode knows the measure a set must carry and whether a load is offered`() {
        assertEquals(SetMeasure.REPETITIONS, TrackingMode.WEIGHT_AND_REPS.primary)
        assertEquals(SetMeasure.REPETITIONS, TrackingMode.REPS_ONLY.primary)
        assertEquals(SetMeasure.DURATION, TrackingMode.DURATION.primary)
        assertEquals(SetMeasure.DURATION, TrackingMode.WEIGHT_AND_DURATION.primary)

        assertTrue(TrackingMode.WEIGHT_AND_REPS.usesLoad)
        assertFalse(TrackingMode.REPS_ONLY.usesLoad)
        assertFalse(TrackingMode.DURATION.usesLoad)
        assertTrue(TrackingMode.WEIGHT_AND_DURATION.usesLoad)
    }

    @Test
    fun `a set row shows its own effort only where a load does not take the column`() {
        assertFalse(TrackingMode.WEIGHT_AND_REPS.showsSetEffort)
        assertTrue(TrackingMode.REPS_ONLY.showsSetEffort)
        assertTrue(TrackingMode.DURATION.showsSetEffort)
        assertFalse(TrackingMode.WEIGHT_AND_DURATION.showsSetEffort)
    }

    @Test
    fun `an exercise name folds the same way whatever the phone's language`() {
        assertEquals("bench press", ExerciseDefinition.fold("  Bench Press "))
        assertEquals("incline press", ExerciseDefinition.fold("INCLINE PRESS"))
        assertEquals(
            ExerciseDefinition.fold("Bench press"),
            definitionOf(TrackingMode.WEIGHT_AND_REPS, "  bench PRESS ").nameFolded,
        )
    }

    @Test
    fun `an equipment folds its free name and falls back on its own label`() {
        assertEquals("garden rower", equipmentOf(EquipmentType.OTHER, " Garden Rower ").customNameFolded)
        assertEquals("", equipmentOf(EquipmentType.TREADMILL).customNameFolded)
        assertEquals("Treadmill", equipmentOf(EquipmentType.TREADMILL).displayName)
        assertEquals("Garden Rower", equipmentOf(EquipmentType.OTHER, " Garden Rower ").displayName)
    }
}

class ActivityPresetTest {

    @Test
    fun `the six presets of PRD 8-5 are offered, with the treadmill walk preselected`() {
        assertEquals(
            listOf("treadmill_walk", "outdoor_walk", "run", "cycling", "strength_training", "other"),
            ActivityPreset.entries.map { it.id },
        )
        assertEquals(ActivityPreset.TREADMILL_WALK, ActivityPreset.DEFAULT)
        assertEquals(ActivityPreset.DEFAULT, ActivityPreset.fromId("gardening"))
    }

    @Test
    fun `the treadmill preset fixes the axes of PRD FR-ACTIVITY-006`() {
        val preset = ActivityPreset.TREADMILL_WALK
        assertEquals(Movement.WALKING, preset.movement)
        assertEquals(ActivityEnvironment.INDOOR, preset.environment)
        assertEquals(EquipmentType.TREADMILL, preset.equipment)
        assertEquals(
            listOf(
                MetricKind.DISTANCE,
                MetricKind.REPORTED_SPEED,
                MetricKind.ESTIMATED_ENERGY,
                MetricKind.INCLINE,
            ),
            preset.metrics,
        )
    }

    @Test
    fun `only the calories of a machine carry its provenance, never the speed it reports`() {
        assertEquals(
            MetricSource.EQUIPMENT,
            ActivityPreset.TREADMILL_WALK.sourceOf(MetricKind.ESTIMATED_ENERGY),
        )
        assertEquals(
            MetricSource.MANUAL,
            ActivityPreset.TREADMILL_WALK.sourceOf(MetricKind.REPORTED_SPEED),
        )
        assertEquals(
            MetricSource.MANUAL,
            ActivityPreset.OUTDOOR_WALK.sourceOf(MetricKind.ESTIMATED_ENERGY),
        )
    }

    @Test
    fun `a walk and a run offer a pace while cycling offers an average speed`() {
        assertTrue(MetricKind.AVERAGE_PACE in ActivityPreset.OUTDOOR_WALK.metrics)
        assertTrue(MetricKind.AVERAGE_PACE in ActivityPreset.RUN.metrics)
        assertTrue(MetricKind.AVERAGE_SPEED in ActivityPreset.CYCLING.metrics)
        assertFalse(MetricKind.AVERAGE_PACE in ActivityPreset.CYCLING.metrics)
    }

    @Test
    fun `a preset imposes a place only where the PRD gives one`() {
        assertEquals(ActivityEnvironment.OUTDOOR, ActivityPreset.RUN.environment)
        assertEquals(ActivityEnvironment.OUTDOOR, ActivityPreset.OUTDOOR_WALK.environment)
        assertEquals(ActivityEnvironment.UNKNOWN, ActivityPreset.CYCLING.environment)
        assertEquals(ActivityEnvironment.UNKNOWN, ActivityPreset.STRENGTH_TRAINING.environment)
        assertEquals(ActivityEnvironment.UNKNOWN, ActivityPreset.OTHER.environment)
    }

    @Test
    fun `the builder catalogue is exactly the ten activities of PRD FR-ACTIVITY-008`() {
        assertEquals(
            listOf(
                Movement.SWIMMING, Movement.ROWING, Movement.ELLIPTICAL, Movement.HIKING,
                Movement.YOGA, Movement.CLIMBING, Movement.DANCING, Movement.PILATES,
                Movement.MOBILITY, Movement.TEAM_SPORT,
            ),
            ActivityPreset.OTHER_CATALOGUE,
        )
        assertFalse(Movement.OTHER in ActivityPreset.OTHER_CATALOGUE)
    }

    @Test
    fun `an existing session reopens in the form it was written with`() {
        assertEquals(
            ActivityPreset.TREADMILL_WALK,
            ActivityPreset.of(Movement.WALKING, listOf(equipmentOf(EquipmentType.TREADMILL))),
        )
        assertEquals(ActivityPreset.OUTDOOR_WALK, ActivityPreset.of(Movement.WALKING, emptyList()))
        assertEquals(ActivityPreset.RUN, ActivityPreset.of(Movement.RUNNING, emptyList()))
        assertEquals(ActivityPreset.CYCLING, ActivityPreset.of(Movement.CYCLING, emptyList()))
        assertEquals(
            ActivityPreset.STRENGTH_TRAINING,
            ActivityPreset.of(Movement.STRENGTH_TRAINING, emptyList()),
        )
        assertEquals(ActivityPreset.OTHER, ActivityPreset.of(Movement.OTHER, emptyList()))
        assertEquals(ActivityPreset.OTHER, ActivityPreset.of(Movement.YOGA, emptyList()))
    }
}
