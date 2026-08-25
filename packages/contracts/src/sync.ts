import { z } from "zod";
import { cursorSchema } from "./cursor";
import { mueErrorSchema } from "./errors";
import { mutationEnvelopeSchema, syncChangeSchema } from "./mutation";
import {
  instantSchema,
  mutationIdSchema,
  payloadSchemaVersionSchema,
  revisionSchema,
  sequenceSchema,
} from "./primitives";

/** One request carries at most this many mutations; the outbox drains in batches. */
export const PUSH_MAX_MUTATIONS = 200;

/** Page size bounds for a pull. The default keeps an initial sync's pages small. */
export const PULL_DEFAULT_LIMIT = 100;
export const PULL_MAX_LIMIT = 500;

export const pushRequestSchema = z
  .object({
    mutations: z.array(mutationEnvelopeSchema).min(1).max(PUSH_MAX_MUTATIONS),
  })
  .meta({
    id: "PushRequest",
    description: "A batch of local mutations. Order within the batch is the outbox order.",
  });

export type PushRequest = z.infer<typeof pushRequestSchema>;

const appliedResultSchema = z
  .object({
    mutationId: mutationIdSchema,
    status: z.literal("applied"),
    revision: revisionSchema,
    sequence: sequenceSchema,
  })
  .meta({ id: "MutationApplied" });

/**
 * A replay. It carries the stored result verbatim, which is FR-SYNC-006's guarantee
 * that a lost response costs a round trip and never a duplicate.
 */
const duplicateResultSchema = z
  .object({
    mutationId: mutationIdSchema,
    status: z.literal("duplicate"),
    revision: revisionSchema,
    sequence: sequenceSchema,
  })
  .meta({ id: "MutationDuplicate" });

const rejectedResultSchema = z
  .object({
    mutationId: mutationIdSchema,
    status: z.literal("rejected"),
    error: mueErrorSchema,
  })
  .meta({ id: "MutationRejected" });

/**
 * One result per mutation, so a single invalid mutation cannot block the batch behind
 * it (FR-SYNC-007). The client keeps the rejected one and surfaces `Sync issue`; the
 * rest are acknowledged and dropped from the outbox.
 */
export const mutationResultSchema = z
  .discriminatedUnion("status", [appliedResultSchema, duplicateResultSchema, rejectedResultSchema])
  .meta({ id: "MutationResult" });

export type MutationResult = z.infer<typeof mutationResultSchema>;

export const pushResponseSchema = z
  .object({
    results: z.array(mutationResultSchema),
    serverTime: instantSchema,
  })
  .meta({
    id: "PushResponse",
    description: "Exactly one result per submitted mutation, in submission order.",
  });

export type PushResponse = z.infer<typeof pushResponseSchema>;

/**
 * Payload versions the client can apply, keyed by aggregate type.
 *
 * The key is a free string rather than the `AggregateType` enum on purpose. A private
 * self-hosted deployment has no ordering guarantee between a phone update and a server
 * update, so both an unknown type sent by a newer client and a known type omitted by an
 * older one have to be tolerated here — the version check itself is what rejects, with
 * an actionable code, instead of a validation error nobody can act on.
 */
export const supportedSchemaVersionsSchema = z
  .record(z.string().min(1).max(64), z.array(payloadSchemaVersionSchema).min(1).max(32))
  .meta({
    id: "SupportedSchemaVersions",
    description: "Aggregate type to the payload schema versions the client can apply.",
    examples: [{ measurement: [1] }],
  });

export type SupportedSchemaVersions = z.infer<typeof supportedSchemaVersionsSchema>;

export const pullRequestSchema = z
  .object({
    /** Null on an initial sync: read the journal from the beginning. */
    cursor: cursorSchema.nullable(),
    /**
     * Absent means `PULL_DEFAULT_LIMIT`, applied by the server. Deliberately not a
     * schema-level default: a default makes the parsed input and the described output
     * two different shapes, and the generated specification can only describe one.
     */
    limit: z.int().min(1).max(PULL_MAX_LIMIT).optional(),
    supportedSchemaVersions: supportedSchemaVersionsSchema,
  })
  .meta({
    id: "PullRequest",
    description:
      "Reads the journal after `cursor`. `supportedSchemaVersions` is required: it is what turns PRD section 12.4 into a server-enforced invariant.",
  });

export type PullRequest = z.infer<typeof pullRequestSchema>;

export const pullPageSchema = z
  .object({
    status: z.literal("ok"),
    changes: z.array(syncChangeSchema),
    /** Advance to this only after every change in `changes` is applied locally. */
    nextCursor: cursorSchema,
    hasMore: z.boolean(),
    serverTime: instantSchema,
    /**
     * FR-SYNC-008: the age of the last Android sync the server knows about, so no
     * reader — agent or client — infers a freshness guarantee the server cannot give.
     * Null when no Android device has ever synchronised.
     */
    lastAndroidSyncAt: instantSchema.nullable(),
  })
  .meta({ id: "PullPage", description: "A page of changes." });

export type PullPage = z.infer<typeof pullPageSchema>;

/**
 * The other outcome: the server holds a payload at a version the client did not
 * declare. It carries no `changes` and, deliberately, no `nextCursor` — so a client
 * cannot advance past data it cannot apply even if it ignores `status` entirely. PRD
 * section 18 states this outcome; the absent field is what makes it structural.
 */
export const pullUpgradeRequiredSchema = z
  .object({
    status: z.literal("upgrade_required"),
    error: mueErrorSchema,
    serverTime: instantSchema,
    lastAndroidSyncAt: instantSchema.nullable(),
  })
  .meta({ id: "PullUpgradeRequired" });

export type PullUpgradeRequired = z.infer<typeof pullUpgradeRequiredSchema>;

/**
 * Both outcomes are HTTP 200 with a discriminated body, matching push: a rejected
 * mutation and an unapplicable payload are business results, not transport failures,
 * and a non-2xx would make a default Ktor client throw before the body — and its
 * actionable `MueError` — is ever parsed.
 */
export const pullResponseSchema = z
  .discriminatedUnion("status", [pullPageSchema, pullUpgradeRequiredSchema])
  .meta({ id: "PullResponse" });

export type PullResponse = z.infer<typeof pullResponseSchema>;
