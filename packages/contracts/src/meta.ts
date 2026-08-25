import { z } from "zod";
import {
  aggregateIdSchema,
  instantSchema,
  mutationIdSchema,
  originTypeSchema,
  revisionSchema,
} from "./primitives";

/**
 * PRD section 12.1's eight fields, kept flat.
 *
 * Flat rather than a nested `origin` object because this is the shape the stores hold —
 * Android's `sync_aggregate_state` has `origin_type` and `origin_id` columns — and a
 * mapper that has to unpack an object into two columns on one side and pack it back on
 * the other is a mapper that will one day forget one of them. The mutation envelope
 * nests instead, because there the author's identity is a single element of the request.
 */
export const aggregateMetaSchema = z
  .object({
    id: aggregateIdSchema,
    revision: revisionSchema,
    createdAt: instantSchema,
    updatedAt: instantSchema,
    /** Set exactly when the aggregate is a tombstone (FR-SYNC-005). */
    deletedAt: instantSchema.nullable(),
    originType: originTypeSchema,
    originId: z.string().min(1).max(200),
    lastMutationId: mutationIdSchema,
  })
  .meta({
    id: "AggregateMeta",
    description: "Synchronisation metadata carried by every aggregate (PRD section 12.1).",
  });

export type AggregateMeta = z.infer<typeof aggregateMetaSchema>;
