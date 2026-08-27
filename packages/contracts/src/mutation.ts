import { z } from "zod";
import {
  ACTIVITY_SESSION_PAYLOAD_VERSION_1,
  CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1,
  activitySessionPayloadV1Schema,
  customExerciseDefinitionPayloadV1Schema,
} from "./activity";
import { FOOD_PAYLOAD_VERSION_1, foodPayloadV1Schema } from "./food";
import { FOOD_LOG_ENTRY_PAYLOAD_VERSION_1, foodLogEntryPayloadV1Schema } from "./food-log";
import {
  HEALTH_PROFILE_AGGREGATE_ID,
  HEALTH_PROFILE_PAYLOAD_VERSION_1,
  healthProfilePayloadV1Schema,
} from "./health-profile";
import {
  MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1,
  mealPlanAggregateId,
  mealPlanAggregateIdSchema,
  mealPlanEntryPayloadV1Schema,
} from "./meal-plan";
import { MEASUREMENT_PAYLOAD_VERSION_1, measurementPayloadV1Schema } from "./measurement";
import { aggregateMetaSchema } from "./meta";
import {
  aggregateIdSchema,
  aggregateTypeSchema,
  instantSchema,
  localDateSchema,
  mutationIdSchema,
  originSchema,
  payloadSchemaVersionSchema,
  revisionSchema,
  sequenceSchema,
} from "./primitives";
import { RECIPE_PAYLOAD_VERSION_1, recipePayloadV1Schema } from "./recipe";

const envelopeBase = {
  mutationId: mutationIdSchema,
  /**
   * The revision the author believed it was editing, or null when it believed the
   * aggregate did not exist yet (PRD section 12.2: "si elle existe").
   */
  baseRevision: revisionSchema.nullable(),
  origin: originSchema,
  /** The author's own clock. For display and audit; never for ordering (section 12.3). */
  clientOccurredAt: instantSchema,
};

/**
 * The identifier of an aggregate whose identity is a minted UUID rather than a business key.
 *
 * Six of the eight aggregates are in that position — a session, a definition, a food, a recipe
 * and a journal line all have `id` columns Room mints — and for every one of them the payload
 * repeats the identifier, so this shape and the `payload.id === aggregateId` refinement below
 * appear together each time.
 *
 * It is `z.uuid()` and not `mutationIdSchema`: these are `UUID.randomUUID()` values, version 4,
 * minted by Room long before this contract existed. Requiring a v7 here would refuse every row
 * already on a phone, which is the mistake `MutationIds` was written to undo, made again in a
 * place where no re-mint is possible — an aggregate identifier is the aggregate, and changing it
 * would fork the row rather than repair it.
 */
const uuidAggregateIdSchema = z.uuid();

/**
 * `payloadSchemaVersion` is required on a delete too, even though a delete has no
 * payload. Section 12.2 leaves this open. Requiring it keeps one envelope shape for the
 * hand-written Kotlin DTO, and it records which schema generation authored the
 * tombstone, which is the only way to read an old journal entry back correctly.
 */
export const measurementUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("measurement"),
    aggregateId: localDateSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(MEASUREMENT_PAYLOAD_VERSION_1),
    payload: measurementPayloadV1Schema,
  })
  .refine((mutation) => mutation.payload.date === mutation.aggregateId, {
    error: "payload.date must equal aggregateId",
    path: ["payload", "date"],
  })
  .meta({
    id: "MeasurementUpsertMutation",
    description: "Upsert of a weight measurement, payload schema version 1.",
  });

/**
 * Upsert of the health profile (PRD section 13.4).
 *
 * `aggregateId` is a literal and not `aggregateIdSchema`. Section 13.4 gives an account one
 * profile, so the identifier is not data the author supplies — it is a constant every reader
 * already knows. Pinning it here is what makes "a second device updates the row, it never
 * inserts a rival one" a property of the wire rather than a rule the server has to remember:
 * a mutation naming any other identifier does not parse, so it can never reach storage at all.
 *
 * There is deliberately **no delete branch for this aggregate**, and the server refuses one.
 * Section 13.4 describes fields that become empty, not a profile that ceases to exist; a
 * tombstone would claim the latter, and FR-SYNC-005 would then use it to block the profile's
 * own resurrection. Clearing a height is `{ heightCm: null }` on this branch.
 */
export const healthProfileUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("healthProfile"),
    aggregateId: z.literal(HEALTH_PROFILE_AGGREGATE_ID),
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(HEALTH_PROFILE_PAYLOAD_VERSION_1),
    payload: healthProfilePayloadV1Schema,
  })
  .meta({
    id: "HealthProfileUpsertMutation",
    description:
      "Upsert of the single health profile, payload schema version 1. The aggregate identifier is the constant `me`.",
  });

/**
 * Upsert of one finished activity session, with its metrics, equipment, exercises and sets
 * (PRD section 10.2).
 *
 * The whole aggregate travels in one payload and is replaced whole, which is what makes section
 * 10.2's *"une activité ne peut jamais apparaître sans ses enfants obligatoires"* structural: a
 * partial session is not a state this branch can express.
 */
export const activitySessionUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("activitySession"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(ACTIVITY_SESSION_PAYLOAD_VERSION_1),
    payload: activitySessionPayloadV1Schema,
  })
  .refine((mutation) => mutation.payload.id === mutation.aggregateId, {
    error: "payload.id must equal aggregateId",
    path: ["payload", "id"],
  })
  .meta({
    id: "ActivitySessionUpsertMutation",
    description: "Upsert of a whole activity session, payload schema version 1.",
  });

/**
 * Upsert of one personal exercise definition (PRD section 10.1).
 *
 * There is no delete branch and the server refuses one, for the reason
 * `customExerciseDefinitionPayloadV1Schema` gives: PRD_ACTIVITIES 9.2 keeps a definition for ever
 * and `strength_exercises` holds a `RESTRICT` foreign key onto it, so a tombstone would be a
 * change no client could apply.
 */
export const customExerciseDefinitionUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("customExerciseDefinition"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1),
    payload: customExerciseDefinitionPayloadV1Schema,
  })
  .refine((mutation) => mutation.payload.id === mutation.aggregateId, {
    error: "payload.id must equal aggregateId",
    path: ["payload", "id"],
  })
  .meta({
    id: "CustomExerciseDefinitionUpsertMutation",
    description: "Upsert of a personal exercise definition, payload schema version 1.",
  });

export const foodUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("food"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(FOOD_PAYLOAD_VERSION_1),
    payload: foodPayloadV1Schema,
  })
  .refine((mutation) => mutation.payload.id === mutation.aggregateId, {
    error: "payload.id must equal aggregateId",
    path: ["payload", "id"],
  })
  .meta({
    id: "FoodUpsertMutation",
    description: "Upsert of a custom food or a copied product, payload schema version 1.",
  });

export const recipeUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("recipe"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(RECIPE_PAYLOAD_VERSION_1),
    payload: recipePayloadV1Schema,
  })
  .refine((mutation) => mutation.payload.id === mutation.aggregateId, {
    error: "payload.id must equal aggregateId",
    path: ["payload", "id"],
  })
  .meta({
    id: "RecipeUpsertMutation",
    description: "Upsert of a recipe with its ingredients, payload schema version 1.",
  });

export const foodLogEntryUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("foodLogEntry"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(FOOD_LOG_ENTRY_PAYLOAD_VERSION_1),
    payload: foodLogEntryPayloadV1Schema,
  })
  .refine((mutation) => mutation.payload.id === mutation.aggregateId, {
    error: "payload.id must equal aggregateId",
    path: ["payload", "id"],
  })
  .meta({
    id: "FoodLogEntryUpsertMutation",
    description: "Upsert of one journal line with its snapshot, payload schema version 1.",
  });

/**
 * Upsert of one meal proposal.
 *
 * `aggregateId` is `mealPlanAggregateIdSchema` rather than the opaque `aggregateIdSchema`, and
 * the refinement below rebuilds it from the payload. Together they do for `(date, slot)` what
 * `measurementUpsertMutationSchema` does for a date: the identifier is derivable from the
 * aggregate, so an author cannot address one dinner and describe another, and two devices
 * planning the same evening address one row rather than opening a rival to it (PRD_FOOD 21.3).
 */
export const mealPlanEntryUpsertMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: z.literal("mealPlanEntry"),
    aggregateId: mealPlanAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1),
    payload: mealPlanEntryPayloadV1Schema,
  })
  .refine(
    (mutation) =>
      mealPlanAggregateId(mutation.payload.plannedOn, mutation.payload.slot) ===
      mutation.aggregateId,
    {
      error: "aggregateId must be `<payload.plannedOn>:<payload.slot>`",
      path: ["aggregateId"],
    },
  )
  .meta({
    id: "MealPlanEntryUpsertMutation",
    description: "Upsert of one meal proposal, payload schema version 1.",
  });

/**
 * The upsert half of the envelope, discriminated a second time on `aggregateType`.
 *
 * Two levels are what an upsert needs and a delete does not: `op` says whether a payload is
 * present, `aggregateType` says which schema it follows. Splitting them keeps the delete branch
 * one shape for every aggregate — which is exactly what makes a delete applicable at any schema
 * generation in `pull.ts`.
 *
 * The branches are listed in `AGGREGATE_TYPES` order, so "is every synchronised aggregate here"
 * is a question a reader answers by comparing two sorted lists rather than by searching.
 */
export const upsertMutationSchema = z
  .discriminatedUnion("aggregateType", [
    activitySessionUpsertMutationSchema,
    customExerciseDefinitionUpsertMutationSchema,
    foodUpsertMutationSchema,
    foodLogEntryUpsertMutationSchema,
    healthProfileUpsertMutationSchema,
    mealPlanEntryUpsertMutationSchema,
    measurementUpsertMutationSchema,
    recipeUpsertMutationSchema,
  ])
  .meta({
    id: "UpsertMutation",
    description: "An upsert, carrying the complete aggregate for its type (PRD section 12.2).",
  });

export const deleteMutationSchema = z
  .object({
    ...envelopeBase,
    aggregateType: aggregateTypeSchema,
    aggregateId: aggregateIdSchema,
    op: z.literal("delete"),
    payloadSchemaVersion: payloadSchemaVersionSchema,
    payload: z.null(),
  })
  .meta({
    id: "DeleteMutation",
    description: "Deletion of any aggregate. Produces a tombstone, never a hard erase.",
  });

/**
 * One unit of work in the outbox.
 *
 * Discriminated on `op` rather than validated field by field, so "an upsert has a
 * payload and a delete has none" is a shape the type system and the generated
 * specification both enforce. The upsert arm is itself discriminated on `aggregateType`, so
 * "this payload follows the schema its own type declares" is the same kind of shape rather
 * than a lookup someone has to remember to perform.
 */
export const mutationEnvelopeSchema = z
  .discriminatedUnion("op", [upsertMutationSchema, deleteMutationSchema])
  .meta({
    id: "MutationEnvelope",
    description: "One mutation (PRD section 12.2). Replaying it never repeats its effect.",
  });

export type MutationEnvelope = z.infer<typeof mutationEnvelopeSchema>;
export type UpsertMutation = z.infer<typeof upsertMutationSchema>;
export type MeasurementUpsertMutation = z.infer<typeof measurementUpsertMutationSchema>;
export type HealthProfileUpsertMutation = z.infer<typeof healthProfileUpsertMutationSchema>;
export type ActivitySessionUpsertMutation = z.infer<typeof activitySessionUpsertMutationSchema>;
export type CustomExerciseDefinitionUpsertMutation = z.infer<
  typeof customExerciseDefinitionUpsertMutationSchema
>;
export type FoodUpsertMutation = z.infer<typeof foodUpsertMutationSchema>;
export type RecipeUpsertMutation = z.infer<typeof recipeUpsertMutationSchema>;
export type FoodLogEntryUpsertMutation = z.infer<typeof foodLogEntryUpsertMutationSchema>;
export type MealPlanEntryUpsertMutation = z.infer<typeof mealPlanEntryUpsertMutationSchema>;
export type DeleteMutation = z.infer<typeof deleteMutationSchema>;

const changeBase = {
  /** Journal position. The only thing a cursor is built from. */
  sequence: sequenceSchema,
  meta: aggregateMetaSchema,
};

export const measurementUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("measurement"),
    aggregateId: localDateSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(MEASUREMENT_PAYLOAD_VERSION_1),
    payload: measurementPayloadV1Schema,
  })
  .meta({ id: "MeasurementUpsertChange" });

export const healthProfileUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("healthProfile"),
    aggregateId: z.literal(HEALTH_PROFILE_AGGREGATE_ID),
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(HEALTH_PROFILE_PAYLOAD_VERSION_1),
    payload: healthProfilePayloadV1Schema,
  })
  .meta({ id: "HealthProfileUpsertChange" });

export const activitySessionUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("activitySession"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(ACTIVITY_SESSION_PAYLOAD_VERSION_1),
    payload: activitySessionPayloadV1Schema,
  })
  .meta({ id: "ActivitySessionUpsertChange" });

export const customExerciseDefinitionUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("customExerciseDefinition"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1),
    payload: customExerciseDefinitionPayloadV1Schema,
  })
  .meta({ id: "CustomExerciseDefinitionUpsertChange" });

export const foodUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("food"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(FOOD_PAYLOAD_VERSION_1),
    payload: foodPayloadV1Schema,
  })
  .meta({ id: "FoodUpsertChange" });

export const recipeUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("recipe"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(RECIPE_PAYLOAD_VERSION_1),
    payload: recipePayloadV1Schema,
  })
  .meta({ id: "RecipeUpsertChange" });

export const foodLogEntryUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("foodLogEntry"),
    aggregateId: uuidAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(FOOD_LOG_ENTRY_PAYLOAD_VERSION_1),
    payload: foodLogEntryPayloadV1Schema,
  })
  .meta({ id: "FoodLogEntryUpsertChange" });

export const mealPlanEntryUpsertChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: z.literal("mealPlanEntry"),
    aggregateId: mealPlanAggregateIdSchema,
    op: z.literal("upsert"),
    payloadSchemaVersion: z.literal(MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1),
    payload: mealPlanEntryPayloadV1Schema,
  })
  .meta({ id: "MealPlanEntryUpsertChange" });

/** The mirror of `upsertMutationSchema` on the read side, discriminated the same way. */
export const upsertChangeSchema = z
  .discriminatedUnion("aggregateType", [
    activitySessionUpsertChangeSchema,
    customExerciseDefinitionUpsertChangeSchema,
    foodUpsertChangeSchema,
    foodLogEntryUpsertChangeSchema,
    healthProfileUpsertChangeSchema,
    mealPlanEntryUpsertChangeSchema,
    measurementUpsertChangeSchema,
    recipeUpsertChangeSchema,
  ])
  .meta({ id: "UpsertChange" });

export const deleteChangeSchema = z
  .object({
    ...changeBase,
    aggregateType: aggregateTypeSchema,
    aggregateId: aggregateIdSchema,
    op: z.literal("delete"),
    payloadSchemaVersion: payloadSchemaVersionSchema,
    payload: z.null(),
  })
  .meta({ id: "DeleteChange" });

/**
 * A journal entry as the client receives it: the applied result plus the metadata the
 * client needs to store the tombstone or the new revision in the same transaction.
 */
export const syncChangeSchema = z
  .discriminatedUnion("op", [upsertChangeSchema, deleteChangeSchema])
  .meta({
    id: "SyncChange",
    description: "One accepted change from the server journal (PRD section 12.3).",
  });

export type SyncChange = z.infer<typeof syncChangeSchema>;
export type MeasurementUpsertChange = z.infer<typeof measurementUpsertChangeSchema>;
export type HealthProfileUpsertChange = z.infer<typeof healthProfileUpsertChangeSchema>;
export type ActivitySessionUpsertChange = z.infer<typeof activitySessionUpsertChangeSchema>;
export type CustomExerciseDefinitionUpsertChange = z.infer<
  typeof customExerciseDefinitionUpsertChangeSchema
>;
export type FoodUpsertChange = z.infer<typeof foodUpsertChangeSchema>;
export type RecipeUpsertChange = z.infer<typeof recipeUpsertChangeSchema>;
export type FoodLogEntryUpsertChange = z.infer<typeof foodLogEntryUpsertChangeSchema>;
export type MealPlanEntryUpsertChange = z.infer<typeof mealPlanEntryUpsertChangeSchema>;
