import { z } from "zod";
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
 * specification both enforce. The upsert branch is a single member today; a second
 * aggregate type turns it into a nested union on `aggregateType` and changes nothing else.
 */
export const mutationEnvelopeSchema = z
  .discriminatedUnion("op", [measurementUpsertMutationSchema, deleteMutationSchema])
  .meta({
    id: "MutationEnvelope",
    description: "One mutation (PRD section 12.2). Replaying it never repeats its effect.",
  });

export type MutationEnvelope = z.infer<typeof mutationEnvelopeSchema>;
export type MeasurementUpsertMutation = z.infer<typeof measurementUpsertMutationSchema>;
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
  .discriminatedUnion("op", [measurementUpsertChangeSchema, deleteChangeSchema])
  .meta({
    id: "SyncChange",
    description: "One accepted change from the server journal (PRD section 12.3).",
  });

export type SyncChange = z.infer<typeof syncChangeSchema>;
