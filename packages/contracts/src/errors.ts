import { z } from "zod";
import { aggregateIdSchema, aggregateTypeSchema, revisionSchema } from "./primitives";

/**
 * The codes V1 defines. Kept sorted, because this list is read far more often than it
 * is edited, and a sorted list makes an accidental duplicate obvious in review.
 */
export const MUE_ERROR_CODES = [
  "auth.forbidden",
  "auth.unauthenticated",
  "http.not_found",
  "server.internal",
  "server.unavailable",
  "sync.aggregate_deleted",
  "sync.invalid_cursor",
  "sync.invalid_payload",
  "sync.missing_required_field",
  "sync.revision_conflict",
  "sync.unknown_aggregate_type",
  "sync.upgrade_required",
] as const;

export type MueErrorCode = (typeof MUE_ERROR_CODES)[number];

/**
 * The wire type of `code` is a pattern, not the enum above.
 *
 * A closed enum would make adding an error code a breaking change for any client whose
 * parser is strict — which is exactly what a hand-written Kotlin `enum class` is. The
 * pattern keeps the shape verifiable while letting a client map an unrecognised code to
 * its own catch-all, so a new code degrades to a generic message instead of a parse
 * failure that hides the real error.
 */
const mueErrorCodeSchema = z
  .string()
  .max(100)
  .regex(/^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9_]*)+$/, "expected a dotted lowercase error code")
  .meta({
    id: "MueErrorCode",
    description: `Stable machine-readable code. Known values: ${MUE_ERROR_CODES.join(", ")}. Treat an unknown code as a non-retryable failure.`,
    examples: [...MUE_ERROR_CODES],
  });

/**
 * FR-SYNC-007's structured, actionable business error.
 *
 * Everything past `retryable` exists so a client can act without guessing:
 * `currentRevision` lets it rebase onto the revision it lost the race to instead of
 * refetching the whole aggregate, and `field` names the missing value that section 14.4
 * says the server must never invent.
 */
export const mueErrorSchema = z
  .object({
    code: mueErrorCodeSchema,
    /** English, and safe to log: no personal data, no credentials, no SQL. */
    message: z.string().min(1).max(2000),
    retryable: z.boolean(),
    aggregateType: aggregateTypeSchema.optional(),
    aggregateId: aggregateIdSchema.optional(),
    /** Dotted path of the offending field, for `sync.missing_required_field`. */
    field: z.string().min(1).max(200).optional(),
    /** The revision the server actually holds, so a client can rebase. */
    currentRevision: revisionSchema.optional(),
  })
  .meta({
    id: "MueError",
    description: "Structured, actionable business error (FR-SYNC-007).",
  });

export type MueError = z.infer<typeof mueErrorSchema>;

/** The single error envelope every non-2xx body uses, so a client parses one shape. */
export const errorResponseSchema = z.object({ error: mueErrorSchema }).meta({
  id: "ErrorResponse",
});

export type ErrorResponse = z.infer<typeof errorResponseSchema>;
