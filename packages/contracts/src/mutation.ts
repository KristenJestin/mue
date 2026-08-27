import { z } from "zod";
import {
  HEALTH_PROFILE_AGGREGATE_ID,
  HEALTH_PROFILE_PAYLOAD_VERSION_1,
  healthProfilePayloadV1Schema,
} from "./health-profile";
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
 * The upsert half of the envelope, discriminated a second time on `aggregateType`.
 *
 * Two levels are what an upsert needs and a delete does not: `op` says whether a payload is
 * present, `aggregateType` says which schema it follows. Splitting them keeps the delete branch
 * one shape for every aggregate — which is exactly what makes a delete applicable at any schema
 * generation in `pull.ts`.
 */
export const upsertMutationSchema = z
  .discriminatedUnion("aggregateType", [
    measurementUpsertMutationSchema,
    healthProfileUpsertMutationSchema,
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

/** The mirror of `upsertMutationSchema` on the read side, discriminated the same way. */
export const upsertChangeSchema = z
  .discriminatedUnion("aggregateType", [
    measurementUpsertChangeSchema,
    healthProfileUpsertChangeSchema,
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
