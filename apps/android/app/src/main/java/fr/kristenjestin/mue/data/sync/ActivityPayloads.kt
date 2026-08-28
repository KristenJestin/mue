package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import kotlinx.serialization.Serializable

/**
 * The wire shape of the two Activity aggregates of PRD 10.1, versioned by
 * [PAYLOAD_SCHEMA_VERSION] exactly as `MeasurementPayload` and the food payloads are.
 *
 * ## A session carries its children, and this is where that becomes true
 *
 * PRD 10.2: *"Une activité ne peut jamais apparaître sans ses enfants obligatoires à cause d'une
 * synchronisation partielle."* Room stores a session across five tables; the wire has one
 * payload, so there is no representation of half a session for a partial synchronisation to
 * produce. `RoomActivityRepository.save` already writes those five tables in one transaction, and
 * this is the same aggregate boundary said a second time, in the format the server reads.
 *
 * ## Why an exercise carries a copy of its definition
 *
 * [StrengthExercisePayload.definition] duplicates a `customExerciseDefinition` aggregate, and
 * that is deliberate rather than an oversight. `strength_exercises.exercise_definition_id` is a
 * foreign key with `ON DELETE RESTRICT`; a session applied on a device that has not yet received
 * the definition would abort the transaction, and `SyncStore.applyPage` writes the cursor in that
 * same transaction — so the phone would stop synchronising, permanently, on a page it could never
 * get past. With the snapshot the receiving side can always materialise what it is missing.
 *
 * It is the same device PRD_FOOD 21.2 uses for an ingredient's `foodName`, applied where the
 * consequence of the missing reference is a *failure* rather than a blank label.
 *
 * ## Nothing here is a float, and nothing absent is a zero
 *
 * Every number is a whole count of its canonical unit — seconds, grams, and the unit each
 * `MetricKind` determines — exactly as the columns hold them. `encodeDefaults` is off in
 * [SyncJson], so an unrecorded repetition count is an absent key and never a `0` the server would
 * store as a claim the phone never made (PRD 9.4: *"un champ non renseigné vaut `null`, jamais
 * `0`"*).
 *
 * ## Why nothing below carries a Kotlin default
 *
 * `Json` does not encode defaults, so a property declared `= null` is *omitted* from the stored
 * payload — and `packages/contracts` says these fields are `.nullable()`, which means present and
 * possibly null, not absent. The two are different states on the wire, and only one of them is a
 * shape the contract accepts here: an absent key would fail to decode into `SyncDto.kt`'s wire DTO
 * and mark the row `sync.invalid_payload`, for a session the user recorded perfectly.
 *
 * The food payloads next door do the opposite, and are right to: their optional fields are
 * `.optional()` in the contract, an unknown nutrient is genuinely an absent key (PRD_FOOD 13.1),
 * and rows in that shape are already journalled on phones. The rule is not "always write nulls" —
 * it is "write what the contract says, field by field", which is why the two files differ.
 */
@Serializable
data class ActivityMetricPayload(
    val kind: String,
    /** Whole units of the canonical unit `kind` determines. The unit is never carried. */
    val value: Int,
    val source: String,
)

/**
 * A piece of gear on a session.
 *
 * There is no `id`, and that is not an omission. `RoomActivityRepository.save` mints a fresh row
 * id for every item on every save — `newRowId()`, per item — so an equipment id is not stable
 * across two writes of the same session and could never be a merge key. Carrying it would be
 * carrying a value whose only possible use is wrong. The stable identity is the type and the
 * folded name, which is what the unique index on `session_equipment` already enforces.
 */
@Serializable
data class SessionEquipmentPayload(
    val equipmentType: String,
    val position: Int,
    val customName: String?,
)

/** The definition an exercise points at, copied into the session. See the file comment. */
@Serializable
data class ExerciseDefinitionPayload(
    val id: String,
    val name: String,
    val trackingMode: String,
    val isCustom: Boolean,
    val equipment: String?,
)

@Serializable
data class StrengthSetPayload(
    val id: String,
    val position: Int,
    val setType: String,
    val repetitions: Int?,
    val loadGrams: Int?,
    val durationSeconds: Int?,
    val perceivedEffort: Int?,
)

@Serializable
data class StrengthExercisePayload(
    val id: String,
    val position: Int,
    val definition: ExerciseDefinitionPayload,
    val sets: List<StrengthSetPayload>,
    val notes: String?,
)

/** PRD 10.2: the session **with** its metrics, its equipment, its exercises and their sets. */
@Serializable
data class ActivitySessionPayload(
    val id: String,
    val movement: String,
    val environment: String,
    val startedOn: String,
    val durationSeconds: Int,
    val source: String,
    val metrics: List<ActivityMetricPayload>,
    val equipment: List<SessionEquipmentPayload>,
    val exercises: List<StrengthExercisePayload>,
    val customMovementName: String?,
    val startedAtTime: String?,
    val perceivedEffort: Int?,
    val notes: String?,
)

/**
 * A personal exercise definition (PRD 10.1).
 *
 * It carries no `isCustom`. PRD 10.1 marks the seventeen definitions Mue ships `Synchronisé: Non`
 * — they are a versioned reference every phone already holds under the same identifiers, not
 * personal data — so a payload able to describe one would be able to rename a shipped exercise on
 * another device. The aggregate type says what these are, and `SyncWire` writes `is_custom = 1`
 * on receive. The snapshot inside a session does carry the flag, because a session may
 * legitimately reference a provided definition.
 */
@Serializable
data class CustomExerciseDefinitionPayload(
    val id: String,
    val name: String,
    val trackingMode: String,
    val equipment: String?,
)

internal fun ActivitySessionDetail.toPayload(): ActivitySessionPayload = ActivitySessionPayload(
    id = session.id.value,
    movement = session.movement.id,
    environment = session.environment.id,
    startedOn = session.startedOn.toString(),
    durationSeconds = session.duration.seconds,
    source = session.source.id,
    metrics = metrics.values.map { metric ->
        ActivityMetricPayload(
            kind = metric.kind.id,
            value = metric.value,
            source = metric.source.id,
        )
    },
    equipment = equipment.map { item ->
        SessionEquipmentPayload(
            equipmentType = item.equipmentType.id,
            position = item.position,
            customName = item.customName,
        )
    },
    exercises = exercises.map { detail ->
        StrengthExercisePayload(
            id = detail.exercise.id.value,
            position = detail.exercise.position,
            definition = detail.definition.toSnapshot(),
            sets = detail.sets.map { set ->
                StrengthSetPayload(
                    id = set.id.value,
                    position = set.position,
                    setType = set.setType.id,
                    repetitions = set.repetitions,
                    loadGrams = set.load?.grams,
                    durationSeconds = set.duration?.seconds,
                    perceivedEffort = set.perceivedEffort?.value,
                )
            },
            notes = detail.exercise.notes,
        )
    },
    customMovementName = session.customMovementName,
    startedAtTime = session.startedAtTime?.let(::toWireTime),
    perceivedEffort = session.perceivedEffort?.value,
    notes = session.notes,
)

internal fun ExerciseDefinition.toSnapshot(): ExerciseDefinitionPayload = ExerciseDefinitionPayload(
    id = id.value,
    name = name,
    trackingMode = trackingMode.id,
    isCustom = isCustom,
    equipment = equipment?.id,
)

internal fun ExerciseDefinition.toPayload(): CustomExerciseDefinitionPayload =
    CustomExerciseDefinitionPayload(
        id = id.value,
        name = name,
        trackingMode = trackingMode.id,
        equipment = equipment?.id,
    )

/**
 * `HH:mm`, and never `HH:mm:ss`.
 *
 * `LocalTime.toString()` omits the seconds only when they are zero, so it has two forms and the
 * contract's `LocalTime` accepts one. Every writer of a start time validates hours and minutes
 * alone, so the second form cannot arise from the app — but a value restored from a backup, or
 * one a future importer writes, would cross the wire as a string the server refuses, and it would
 * refuse it for a session rather than for a field. Formatting explicitly costs nothing and
 * removes the case.
 */
private fun toWireTime(time: java.time.LocalTime): String =
    "%02d:%02d".format(time.hour, time.minute)
