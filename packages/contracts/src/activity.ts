import { z } from "zod";
import { localDateSchema } from "./primitives";

/**
 * The `ActivitySession` aggregate of PRD section 10.2: a finished session **with** its metrics,
 * its equipment, its exercises and their sets, carried in one payload and applied atomically.
 *
 * Section 10.2 closes on the sentence this whole file exists to keep: *"Une activité ne peut
 * jamais apparaître sans ses enfants obligatoires à cause d'une synchronisation partielle."* So
 * the children are not four aggregates that happen to reference a fifth — they are fields of it.
 * The wire has no way to express half a session, which is stronger than a server that remembers
 * to write five tables together.
 *
 * ## Every bound here is Android's, transcribed
 *
 * Nothing below is new policy. Each constant names the Kotlin constant it copies, because a
 * bound the wire invents is a bound that refuses a row the phone has already stored — and a row
 * the phone stored and the server refuses is precisely the loss this contract is being extended
 * to stop. Where Android and the PRD disagree, the *stored* range wins: see
 * [SESSION_STORED_MIN_SECONDS].
 *
 * Sources, all under `apps/android/app/src/main/java/fr/kristenjestin/mue/domain/`:
 *   `model/Movement.kt`             — the movement ids
 *   `model/ActivityEnvironment.kt`  — environment, session source and metric source ids
 *   `model/ActivityMetric.kt`       — the metric kinds
 *   `model/EquipmentType.kt`        — the equipment ids
 *   `model/StrengthSession.kt`      — tracking modes, set types, repetition range, name length
 *   `model/ActivitySession.kt`      — custom-name and notes lengths
 *   `model/ActivityUnits.kt`        — durations, loads and the 1-10 effort scale
 *   `logic/ActivityValidation.kt`   — the equipment name length
 *   `ui/activity/StrengthDraftEditor.kt` — the exercise notes length
 */

export const ACTIVITY_SESSION_PAYLOAD_VERSION_1 = 1;

export const ACTIVITY_MOVEMENTS = [
  "walking",
  "running",
  "cycling",
  "swimming",
  "strength_training",
  "rowing",
  "elliptical",
  "hiking",
  "yoga",
  "climbing",
  "dancing",
  "pilates",
  "mobility",
  "team_sport",
  "other",
] as const;

export const ACTIVITY_ENVIRONMENTS = ["indoor", "outdoor", "unknown"] as const;

/**
 * How the session entered Mue.
 *
 * Android's `ActivitySource` declares three ids; `agent` is the fourth, and it is already in the
 * journal — the MCP `create_activity` tool has been writing it since the vertical slice landed.
 * Leaving it out would make `readChanges` fail to re-parse eight entries this database already
 * holds, and a pull that cannot parse an entry it must return is a cursor that stops for good.
 *
 * It is an enum and not the free `z.string().min(1).max(40)` the MCP slice used, because a
 * closed set is what makes "a session says how it was created" a fact a reader can rely on.
 * Android's `ActivitySource.fromId` is total and degrades an unknown id to `MANUAL`, so a client
 * older than a future fifth value survives it; the server is the side that must not invent one.
 */
export const ACTIVITY_SOURCES = ["manual", "timer", "health_connect", "agent"] as const;

export const METRIC_KINDS = [
  "distance",
  "reported_speed",
  "average_speed",
  "average_pace",
  "estimated_energy",
  "incline",
  "steps",
  "average_heart_rate",
  "elevation_gain",
  "power",
  "cadence",
] as const;

export const METRIC_SOURCES = ["manual", "equipment", "wearable", "calculated"] as const;

export const EQUIPMENT_TYPES = [
  "treadmill",
  "stationary_bike",
  "bicycle",
  "rowing_machine",
  "elliptical_machine",
  "yoga_mat",
  "resistance_bands",
  "barbell",
  "dumbbells",
  "kettlebell",
  "machine",
  "bodyweight",
  "climbing_wall",
  "pool",
  "other",
] as const;

export const TRACKING_MODES = [
  "weight_and_reps",
  "reps_only",
  "duration",
  "weight_and_duration",
] as const;

export const SET_TYPES = ["working", "warmup", "drop"] as const;

/** `ActivitySession.MAX_CUSTOM_MOVEMENT_NAME_LENGTH`. */
export const MAX_CUSTOM_MOVEMENT_NAME_LENGTH = 60;
/** `ActivitySession.MAX_NOTES_LENGTH`, and `StrengthDraftEditor.MAX_EXERCISE_NOTES_LENGTH`. */
export const MAX_NOTES_LENGTH = 500;
/** `ActivityValidation.MAX_EQUIPMENT_NAME_LENGTH`. */
export const MAX_EQUIPMENT_NAME_LENGTH = 40;
/** `ExerciseDefinition.MAX_NAME_LENGTH`. */
export const MAX_EXERCISE_NAME_LENGTH = 60;

/**
 * `ActivityDuration.SESSION_MIN_SECONDS`, the floor of the **manual** form alone.
 *
 * It is exported for the MCP tool, which creates sessions nobody timed and therefore holds
 * itself to the hand-entry rule. It is deliberately **not** the floor of this payload.
 */
export const SESSION_MIN_SECONDS = 60;

/**
 * `ActivityDuration.TIMED_MIN_SECONDS`, and the floor a *stored* session actually has.
 *
 * The manual form types hours and minutes and so can never express less than a minute; the timer
 * of `FR-TIMER-006` records the exact second, and `Finish` pressed forty seconds after `Start`
 * writes a forty-second session into Room. A wire that copied the manual floor would refuse a
 * row the phone already holds, for ever, with no way for the user to correct it — the row cannot
 * be edited to a legal value because the form that could edit it has no seconds field.
 *
 * So the payload takes the wider of the two ranges. That is the general rule this file follows:
 * a validation bound on the wire answers *"can this have been stored?"*, never *"would this form
 * have accepted it?"*, because only the first question has an answer that stays true.
 */
export const SESSION_STORED_MIN_SECONDS = 1;

/** `ActivityDuration.SESSION_MAX_SECONDS`, 99 h 59 m, shared by both modes of entry. */
export const SESSION_MAX_SECONDS = 359_940;

/** `PerceivedEffort.MIN` and `MAX`, the 1-to-10 scale of PRD_ACTIVITIES 8.2. */
export const PERCEIVED_EFFORT_MIN = 1;
export const PERCEIVED_EFFORT_MAX = 10;

/** `StrengthSet.REPETITIONS_RANGE`. */
export const REPETITIONS_MIN = 1;
export const REPETITIONS_MAX = 999;

/**
 * `Load.MAX_GRAMS`, and `Load.ofGramsOrNull`'s floor of one gram.
 *
 * There is deliberately no `multipleOf(Load.STEP_GRAMS)`. The 10 g step belongs to
 * `ofKilogramsOrNull`, which *rounds* a typed kilogram value onto it; `ofGramsOrNull` accepts any
 * gram in range, and a session imported or written by an agent may legitimately carry one. A
 * step on the wire would be a rule Android does not enforce at rest, so it would refuse stored
 * rows to gain nothing.
 */
export const LOAD_MIN_GRAMS = 1;
export const LOAD_MAX_GRAMS = 1_000_000;

/**
 * A metric value, in the canonical integer unit its `kind` determines.
 *
 * The unit is never a field: `ActivityMetric.kt` derives it from the kind, precisely so that a
 * distance expressed in kilocalories is unrepresentable. The bound is the width of the `Int` the
 * column and the domain model both are, and the floor is zero rather than one — PRD_ACTIVITIES
 * 8.3 says an absent measurement has no row rather than a zero one, which is a rule about
 * *writing* a metric and not a claim that zero cannot be measured.
 */
export const METRIC_VALUE_MIN = 0;
export const METRIC_VALUE_MAX = 2_147_483_647;

export const activityMovementSchema = z.enum(ACTIVITY_MOVEMENTS).meta({
  id: "ActivityMovement",
  description: "The movement of a session (PRD_ACTIVITIES 8.2).",
});

export const activityEnvironmentSchema = z.enum(ACTIVITY_ENVIRONMENTS).meta({
  id: "ActivityEnvironment",
  description: "Where the session happened, `unknown` when the preset does not impose one.",
});

export const activitySourceSchema = z.enum(ACTIVITY_SOURCES).meta({
  id: "ActivitySource",
  description:
    "How the session entered Mue. `agent` is an MCP creation and is not one of Android's three ids; an unknown id degrades to `manual` on the client.",
});

export const metricKindSchema = z.enum(METRIC_KINDS).meta({
  id: "ActivityMetricKind",
  description:
    "The measured quantity. Its canonical unit is derived from the kind and never carried, so an impossible pair (a distance in kilocalories) cannot be expressed.",
});

export const metricSourceSchema = z.enum(METRIC_SOURCES).meta({
  id: "ActivityMetricSource",
  description: "Where a metric's value came from (PRD_ACTIVITIES 8.3).",
});

export const equipmentTypeSchema = z.enum(EQUIPMENT_TYPES).meta({
  id: "ActivityEquipmentType",
  description: "A known piece of gear, or `other` with a free name (PRD_ACTIVITIES 8.4).",
});

export const trackingModeSchema = z.enum(TRACKING_MODES).meta({
  id: "ExerciseTrackingMode",
  description: "Which fields a set of this exercise carries (PRD_ACTIVITIES 9.2).",
});

export const setTypeSchema = z.enum(SET_TYPES).meta({
  id: "StrengthSetType",
  description: "Working, warm-up or drop set (PRD_ACTIVITIES 9.4).",
});

/**
 * Local wall time at minute precision, exactly as Room stores `started_at_time`.
 *
 * A `time` type would rewrite `07:30` as `07:30:00` and make one instant two strings. There is
 * no zone and no date here on purpose: PRD_ACTIVITIES 8.2 forbids an absolute timestamp so that
 * a change of timezone cannot move a session by a day, and the absence of a time stays distinct
 * from midnight.
 */
export const localTimeSchema = z
  .string()
  .regex(/^([01]\d|2[0-3]):[0-5]\d$/, "expected a 24-hour local time such as 18:00")
  .meta({
    id: "LocalTime",
    description: "ISO-8601 local time, hours and minutes only, no zone.",
    examples: ["18:00"],
  });

export const activityMetricSchema = z
  .object({
    kind: metricKindSchema,
    /** Whole units of the kind's canonical unit. Never a float, and never a unit of its own. */
    value: z.int().min(METRIC_VALUE_MIN).max(METRIC_VALUE_MAX),
    source: metricSourceSchema,
  })
  .meta({
    id: "ActivityMetric",
    description: "One measured quantity of a session, in the integer unit its kind determines.",
  });

export type ActivityMetric = z.infer<typeof activityMetricSchema>;

/**
 * One piece of gear on a session.
 *
 * There is no `id`. Room gives the row one and mints a **fresh** one on every save — see
 * `RoomActivityRepository.save`, which calls `newRowId()` per item — so an equipment id is not
 * stable across two writes of the same session and could never be a merge key. Carrying it would
 * be carrying a value whose only possible use is wrong. The stable identity is
 * `(equipmentType, folded customName)`, which is what the unique index on `session_equipment`
 * already enforces and what [activitySessionPayloadV1Schema] refuses a duplicate of below.
 */
export const sessionEquipmentSchema = z
  .object({
    equipmentType: equipmentTypeSchema,
    customName: z.string().min(1).max(MAX_EQUIPMENT_NAME_LENGTH).nullable(),
    /** Display order, in the order the user added them. Zero-based, as Room renumbers it. */
    position: z.int().min(0).max(1000),
  })
  .refine(
    (equipment) =>
      equipment.equipmentType === "other"
        ? equipment.customName !== null
        : equipment.customName === null,
    {
      error: "a free name belongs to the `other` type alone, and is required there",
      path: ["customName"],
    },
  )
  .meta({
    id: "SessionEquipment",
    description:
      "A piece of gear attached to a session. `customName` is required for `other` and absent for every known type (PRD_ACTIVITIES 8.2 and 8.4).",
  });

export type SessionEquipment = z.infer<typeof sessionEquipmentSchema>;

/**
 * The exercise definition a session's exercise points at, carried **inside** the session.
 *
 * This is a snapshot, and it is the same device that `PRD_FOOD` 21.2 makes of an ingredient's
 * food name: *"Une recette peut référencer un aliment que le client n'a pas encore reçu […] il
 * ne rejette pas l'agrégat."* The reason is sharper here, because Android's schema turns the
 * missing reference into a *failure* rather than a blank label: `strength_exercises
 * .exercise_definition_id` is a foreign key with `ON DELETE RESTRICT`, so applying a session
 * whose definition has not arrived aborts the transaction — and `applyPage` writes the cursor in
 * that same transaction, so the phone would stop synchronising entirely on a page it can
 * never get past.
 *
 * With the snapshot the client can always materialise what it is missing, and PRD_ACTIVITIES 9.2
 * says exactly what to do when the name is already taken by another id: *"un nom déjà présent
 * dans le catalogue, sans distinction de casse ni d'espaces de bordure, réutilise la définition
 * existante"*. So there is no case left in which a session cannot be applied.
 *
 * `isCustom` is here and is deliberately **absent** from
 * [customExerciseDefinitionPayloadV1Schema]: this field describes a definition that may be one
 * of the seventeen Mue ships, which PRD section 10.1 marks `Synchronisé: Non`. That aggregate
 * carries no such field precisely so that a provided definition cannot be pushed as a personal
 * one.
 */
export const exerciseDefinitionSnapshotSchema = z
  .object({
    id: z.uuid(),
    name: z.string().min(1).max(MAX_EXERCISE_NAME_LENGTH),
    trackingMode: trackingModeSchema,
    equipment: equipmentTypeSchema.nullable(),
    /** False for one of the seventeen definitions Mue ships, true for a personal one. */
    isCustom: z.boolean(),
  })
  .meta({
    id: "ExerciseDefinitionSnapshot",
    description:
      "The definition an exercise points at, copied into the session so the session can always be applied (PRD section 10.2).",
  });

export type ExerciseDefinitionSnapshot = z.infer<typeof exerciseDefinitionSnapshotSchema>;

export const strengthSetSchema = z
  .object({
    id: z.uuid(),
    position: z.int().min(0).max(1000),
    setType: setTypeSchema,
    repetitions: z.int().min(REPETITIONS_MIN).max(REPETITIONS_MAX).nullable(),
    /** Whole grams, so the 0.5 kg step and the 1.25 kg plate are both exact. */
    loadGrams: z.int().min(LOAD_MIN_GRAMS).max(LOAD_MAX_GRAMS).nullable(),
    durationSeconds: z.int().min(1).max(SESSION_MAX_SECONDS).nullable(),
    perceivedEffort: z.int().min(PERCEIVED_EFFORT_MIN).max(PERCEIVED_EFFORT_MAX).nullable(),
  })
  .meta({
    id: "StrengthSet",
    description:
      "One set. Every optional number is null when unrecorded and never zero (PRD_ACTIVITIES 9.4).",
  });

export type StrengthSet = z.infer<typeof strengthSetSchema>;

/**
 * The validity rule of PRD_ACTIVITIES 9.4: a set carries the principal measure of its mode.
 *
 * It is applied here, at the exercise, because the mode lives on the definition and a set on its
 * own cannot know it. `StrengthRules.persistableExercises` already drops an invalid set before
 * Room sees one, so this refuses nothing Android stores; it exists so that an agent's write and
 * a future client's write are held to the same rule as the phone's.
 */
function carriesItsPrincipalMeasure(mode: string, set: StrengthSet): boolean {
  return mode === "weight_and_reps" || mode === "reps_only"
    ? set.repetitions !== null
    : set.durationSeconds !== null;
}

export const strengthExerciseSchema = z
  .object({
    id: z.uuid(),
    position: z.int().min(0).max(1000),
    notes: z.string().min(1).max(MAX_NOTES_LENGTH).nullable(),
    definition: exerciseDefinitionSnapshotSchema,
    /**
     * At least one. PRD_ACTIVITIES 9.4 refuses to store a set that carries no measure, and
     * `StrengthRules.persistableExercises` then drops an exercise left with none — so an empty
     * list is a shape Android cannot produce and a reader would have to guess the meaning of.
     */
    sets: z.array(strengthSetSchema).min(1).max(100),
  })
  .refine(
    (exercise) =>
      exercise.sets.every((set) =>
        carriesItsPrincipalMeasure(exercise.definition.trackingMode, set),
      ),
    {
      error: "every set must carry the principal measure of its tracking mode",
      path: ["sets"],
    },
  )
  .meta({
    id: "StrengthExercise",
    description: "One exercise of a session, with its definition snapshot and its sets.",
  });

export type StrengthExercise = z.infer<typeof strengthExerciseSchema>;

/** The folded form the unique index on `session_equipment` compares, transcribed. */
function foldEquipmentName(name: string | null): string {
  return name === null ? "" : name.trim().toLowerCase();
}

function hasDuplicates(keys: readonly string[]): boolean {
  return new Set(keys).size !== keys.length;
}

/**
 * One finished session, whole.
 *
 * `id` repeats the aggregate identifier for the same reason `MeasurementPayloadV1.date` repeats
 * its own: PRD section 12.2 makes an upsert state the complete aggregate, and a payload read
 * back from the journal on its own has to say which session it is.
 */
export const activitySessionPayloadV1Schema = z
  .object({
    id: z.uuid(),
    movement: activityMovementSchema,
    customMovementName: z.string().min(1).max(MAX_CUSTOM_MOVEMENT_NAME_LENGTH).nullable(),
    environment: activityEnvironmentSchema,
    startedOn: localDateSchema,
    startedAtTime: localTimeSchema.nullable(),
    durationSeconds: z.int().min(SESSION_STORED_MIN_SECONDS).max(SESSION_MAX_SECONDS),
    perceivedEffort: z.int().min(PERCEIVED_EFFORT_MIN).max(PERCEIVED_EFFORT_MAX).nullable(),
    notes: z.string().min(1).max(MAX_NOTES_LENGTH).nullable(),
    source: activitySourceSchema,
    metrics: z.array(activityMetricSchema).max(METRIC_KINDS.length),
    equipment: z.array(sessionEquipmentSchema).max(EQUIPMENT_TYPES.length * 4),
    exercises: z.array(strengthExerciseSchema).max(60),
  })
  .refine(
    (session) =>
      session.movement === "other"
        ? session.customMovementName !== null
        : session.customMovementName === null,
    {
      error: "a custom movement name belongs to the `other` movement alone, and is required there",
      path: ["customMovementName"],
    },
  )
  // `activity_metrics` is keyed by `(session_id, kind)`, so two metrics of one kind are not a
  // conflict to resolve but a row that cannot be written. PRD_ACTIVITIES 8.3 states the same
  // rule in words: "une séance ne porte jamais deux mesures du même kind".
  .refine((session) => !hasDuplicates(session.metrics.map((metric) => metric.kind)), {
    error: "a session carries at most one metric of each kind",
    path: ["metrics"],
  })
  .refine(
    (session) =>
      !hasDuplicates(
        session.equipment.map(
          (item) => `${item.equipmentType} ${foldEquipmentName(item.customName)}`,
        ),
      ),
    {
      error: "a session carries the same equipment at most once, whatever the case of its name",
      path: ["equipment"],
    },
  )
  .meta({
    id: "ActivitySessionPayloadV1",
    description:
      "A finished session with its metrics, equipment, exercises and sets, payload schema version 1. The aggregate is atomic: PRD section 10.2 forbids a session appearing without its mandatory children.",
  });

export type ActivitySessionPayloadV1 = z.infer<typeof activitySessionPayloadV1Schema>;

export const CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1 = 1;

/**
 * A personal exercise definition (PRD section 10.1: *"Exercices personnalisés — Oui"*).
 *
 * ## Why there is no `isCustom`
 *
 * The line above it in the same matrix reads *"Catalogue d'exercices fourni par Mue — Non"*: the
 * seventeen definitions Mue ships are a versioned reference, not personal data, and every phone
 * already holds them under the same hardcoded identifiers. An `isCustom: false` payload would
 * therefore be a synchronised copy of something that is not synchronised — and, worse, one that
 * could rename a shipped definition on another device.
 *
 * The field is absent rather than pinned to `true`, so the impossibility is structural: there is
 * no value of this payload that describes a provided definition, and the client sets `is_custom`
 * on receive from the aggregate type alone.
 *
 * ## Why there is no delete
 *
 * PRD_ACTIVITIES 9.2 is explicit: *"Une définition personnalisée est conservée définitivement, y
 * compris lorsqu'aucune séance ne l'utilise plus."* The V1 offers no screen to rename or remove
 * one, and `strength_exercises` holds a `RESTRICT` foreign key onto it, so a tombstone arriving
 * from anywhere would be a change no client could apply. The handler refuses one, exactly as the
 * health profile's does, rather than journalling a state the domain does not have.
 */
export const customExerciseDefinitionPayloadV1Schema = z
  .object({
    id: z.uuid(),
    name: z.string().min(1).max(MAX_EXERCISE_NAME_LENGTH),
    trackingMode: trackingModeSchema,
    equipment: equipmentTypeSchema.nullable(),
  })
  .meta({
    id: "CustomExerciseDefinitionPayloadV1",
    description:
      "A personal exercise definition, payload schema version 1. It carries no `isCustom`: a provided definition is not personal data and cannot be expressed here (PRD section 10.1).",
  });

export type CustomExerciseDefinitionPayloadV1 = z.infer<
  typeof customExerciseDefinitionPayloadV1Schema
>;
