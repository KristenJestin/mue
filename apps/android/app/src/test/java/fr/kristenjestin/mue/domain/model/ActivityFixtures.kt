package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * Builders for the activity tests. Identifiers are fixed rather than random so two fixtures
 * built the same way compare equal.
 */

fun minutesOf(minutes: Int): ActivityDuration =
    requireNotNull(ActivityDuration.ofHoursAndMinutesOrNull(0, minutes))

fun secondsOf(seconds: Int): ActivityDuration =
    requireNotNull(ActivityDuration.ofSecondsOrNull(seconds))

fun loadOf(kilograms: Double): Load =
    requireNotNull(Load.ofKilogramsOrNull(kilograms)) { "$kilograms kg is out of range" }

fun effortOf(value: Int): PerceivedEffort =
    requireNotNull(PerceivedEffort.ofOrNull(value)) { "$value is out of range" }

fun sessionOf(
    isoDate: String = "2026-08-19",
    movement: Movement = Movement.WALKING,
    minutes: Int = 45,
    environment: ActivityEnvironment = ActivityEnvironment.UNKNOWN,
    customMovementName: String? = null,
    startedAtTime: LocalTime? = null,
    id: String = "session-1",
): ActivitySession = ActivitySession(
    id = ActivityId(id),
    movement = movement,
    startedOn = LocalDate.parse(isoDate),
    duration = minutesOf(minutes),
    customMovementName = customMovementName,
    environment = environment,
    startedAtTime = startedAtTime,
)

fun summaryOf(
    isoDate: String,
    minutes: Int = 45,
    energyKcal: Int? = null,
    label: String = "Treadmill walk",
    movement: Movement = Movement.WALKING,
    id: String = "session-$isoDate-$minutes",
): ActivitySummary = ActivitySummary(
    id = ActivityId(id),
    label = label,
    movement = movement,
    startedOn = LocalDate.parse(isoDate),
    startedAtTime = null,
    duration = minutesOf(minutes),
    estimatedEnergyKcal = energyKcal,
)

fun equipmentOf(type: EquipmentType, customName: String? = null, position: Int = 0) =
    SessionEquipment(equipmentType = type, customName = customName, position = position)

fun definitionOf(
    trackingMode: TrackingMode,
    name: String = "Bench press",
    equipment: EquipmentType? = null,
    id: String = "definition-$name",
): ExerciseDefinition = ExerciseDefinition(
    id = ExerciseDefinitionId(id),
    name = name,
    trackingMode = trackingMode,
    equipment = equipment,
)

fun strengthSetOf(
    position: Int = 0,
    reps: Int? = null,
    loadKg: Double? = null,
    seconds: Int? = null,
    effort: Int? = null,
    setType: SetType = SetType.WORKING,
): StrengthSet = StrengthSet(
    id = StrengthSetId("set-$position"),
    position = position,
    setType = setType,
    repetitions = reps,
    load = loadKg?.let(::loadOf),
    duration = seconds?.let(::secondsOf),
    perceivedEffort = effort?.let(::effortOf),
)

fun exerciseDetailOf(
    trackingMode: TrackingMode,
    vararg sets: StrengthSet,
    name: String = "Bench press",
    position: Int = 0,
): StrengthExerciseDetail = StrengthExerciseDetail(
    exercise = StrengthExercise(id = StrengthExerciseId("exercise-$name"), position = position),
    definition = definitionOf(trackingMode, name),
    sets = sets.toList(),
)
