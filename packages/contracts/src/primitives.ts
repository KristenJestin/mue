import { z } from "zod";

/**
 * The canonical decimal form of a 64-bit counter, with no leading zero so that one
 * number has exactly one wire representation.
 *
 * Counters cross the wire as strings, not JSON numbers: a JSON number gives Kotlin no
 * precision guarantee past 2^53, and a number invites arithmetic on a value the sync
 * protocol treats as opaque.
 */
export const counterStringSchema = z
  .string()
  .regex(/^(?:0|[1-9]\d*)$/, "expected a canonical decimal string")
  .max(20)
  .meta({
    id: "CounterString",
    description: "A 64-bit counter in canonical decimal form, carried as a string.",
    examples: ["0", "1", "18446744073709551615"],
  });

/**
 * Per-aggregate counter, incremented on each accepted mutation for that aggregate.
 * It exists for optimistic concurrency (PRD section 13.3) and for nothing else.
 */
export const revisionSchema = counterStringSchema.meta({
  id: "Revision",
  description:
    "Per-aggregate revision. Compared for optimistic concurrency; never used as a cursor.",
  examples: ["1", "42"],
});

/**
 * Per-user journal counter, assigned when a change is appended (PRD section 12.3).
 * It is the cursor and nothing else. It is deliberately not `revision`: conflating
 * the two makes a client that has read aggregate revision 3 believe it has read
 * journal position 3, and every change in between is skipped.
 */
export const sequenceSchema = counterStringSchema.meta({
  id: "Sequence",
  description:
    "Global per-user journal position. Strictly increasing. Ordering only; never compared to a revision.",
  examples: ["1", "9007199254740993"],
});

/** UUIDv7, so an identifier sorts by creation time and the outbox drains in order. */
export const mutationIdSchema = z.uuidv7().meta({
  id: "MutationId",
  description: "UUIDv7 identifying one mutation, globally unique and creation-ordered.",
  examples: ["0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6"],
});

/**
 * An aggregate identifier: a UUID where the aggregate has no natural key, the natural
 * key itself where it has one.
 *
 * PRD section 12.1 calls this a UUID, but a `Measurement` has no UUID anywhere in Mue —
 * its primary key is its local date, both in Room and in `mue_app.measurements`. Minting
 * a UUID per device would let two devices create two aggregates for one date and break
 * the one-measurement-per-date rule that section 13.2 keeps. So the identifier is the
 * business key, which makes convergence structural instead of a merge heuristic.
 *
 * The health profile takes the same rule to its limit. Section 13.4 gives an account exactly
 * one profile, so its business key is a constant — `HEALTH_PROFILE_AGGREGATE_ID` — and a
 * second device addresses the row the first one wrote instead of opening a rival to it.
 */
export const aggregateIdSchema = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9._:-]+$/, "expected an opaque aggregate identifier")
  .meta({
    id: "AggregateId",
    description:
      "Stable aggregate identifier. A UUID when the aggregate has no natural key, the natural key when it has one (a Measurement is identified by its local date).",
    examples: ["2026-08-25"],
  });

/**
 * The aggregate kinds this contract can express, from PRD section 10.2's list.
 *
 * Widening it is additive for every reader, which is why it lives in one place — and why
 * widening it is also the only thing that lets a journalled Android mutation be *sent*:
 * `SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES` is derived from this list, so a type absent here is
 * a type whose outbox rows stay pending for ever. `healthProfile` spent exactly that time
 * outside it while section 13.4 already called it synchronised.
 *
 * Sorted, so an addition lands in an obvious place rather than at whichever end the author
 * happened to type.
 */
export const AGGREGATE_TYPES = ["healthProfile", "measurement"] as const;

export const aggregateTypeSchema = z.enum(AGGREGATE_TYPES).meta({
  id: "AggregateType",
  description: "The synchronised aggregate kinds (PRD section 10.2).",
});

export type AggregateType = z.infer<typeof aggregateTypeSchema>;

export const opSchema = z.enum(["upsert", "delete"]).meta({
  id: "MutationOp",
  description: "An upsert carries the full aggregate; a delete carries no payload.",
});

export type MutationOp = z.infer<typeof opSchema>;

export const originTypeSchema = z.enum(["android", "agent", "server"]).meta({
  id: "OriginType",
  description: "Who authored a change (PRD section 12.1).",
});

export type OriginType = z.infer<typeof originTypeSchema>;

export const originSchema = z
  .object({
    type: originTypeSchema,
    /** Device, agent or process identifier. Opaque, and never a display name. */
    id: z.string().min(1).max(200),
  })
  .meta({
    id: "Origin",
    description: "The identity that authored a mutation, kept for audit (PRD section 14.7).",
  });

export type Origin = z.infer<typeof originSchema>;

/**
 * Payload schema version, per aggregate type (PRD section 12.4). Bounded so a hostile
 * value cannot become an unbounded lookup key.
 */
export const payloadSchemaVersionSchema = z
  .int()
  .min(1)
  .max(1000)
  .meta({
    id: "PayloadSchemaVersion",
    description: "Version of the payload schema for one aggregate type.",
    examples: [1],
  });

/**
 * An instant in UTC with a `Z` suffix, which is what `java.time.Instant.parse` reads
 * without a formatter. Offsets are rejected so one instant has one representation.
 */
export const instantSchema = z.iso.datetime().meta({
  id: "Instant",
  description: "ISO-8601 instant in UTC, always suffixed with Z.",
  examples: ["2026-08-25T09:41:00.000Z"],
});

/**
 * A local calendar date with no time and no zone, which makes a timezone-induced
 * off-by-one-day impossible. It is also the business key of a measurement.
 */
export const localDateSchema = z.iso.date().meta({
  id: "LocalDate",
  description: "ISO-8601 calendar date, no time and no zone.",
  examples: ["2026-08-25"],
});
