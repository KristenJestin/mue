import {
  AGGREGATE_TYPES,
  type AggregateType,
  CURRENT_PAYLOAD_SCHEMA_VERSIONS,
  type MueError,
  type MutationEnvelope,
  mutationEnvelopeSchema,
  mutationIdSchema,
} from "@mue/contracts";
import { mueError } from "./errors";

/**
 * Turning one submitted object into either a validated envelope or an
 * actionable rejection.
 *
 * The order of the checks is the point. `payloadSchemaVersion` is read before
 * the envelope is parsed, because the envelope schema pins version 1 as a
 * literal: parsing first would report a payload from a newer client as a
 * generic `sync.invalid_payload`, and section 12.4 requires an explicit upgrade
 * error the client can act on.
 */

export type MutationValidation =
  | { readonly ok: true; readonly mutation: MutationEnvelope }
  | { readonly ok: false; readonly error: MueError };

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

/**
 * The one failure that cannot be reported per mutation: a `PushResponse`
 * result is keyed by `mutationId`, so a mutation whose id cannot be read has
 * nowhere to carry its own rejection.
 */
export function readMutationId(raw: unknown): string | undefined {
  const record = asRecord(raw);
  if (record === undefined) return undefined;
  const parsed = mutationIdSchema.safeParse(record["mutationId"]);
  return parsed.success ? parsed.data : undefined;
}

function isAggregateType(value: unknown): value is AggregateType {
  return typeof value === "string" && (AGGREGATE_TYPES as readonly string[]).includes(value);
}

/** The payload versions this build can apply, per aggregate type. */
function serverSupports(aggregateType: AggregateType, version: unknown): boolean {
  const supported: readonly number[] = CURRENT_PAYLOAD_SCHEMA_VERSIONS[aggregateType];
  return typeof version === "number" && supported.includes(version);
}

/**
 * A zod failure, mapped onto the two codes a client can act on differently: a
 * field the author left out is nameable, and section 14.4 forbids the server
 * from inventing it, so the client is told which one. Anything else is a
 * malformed payload.
 */
function fromParseFailure(
  issues: readonly { code: string; path: readonly PropertyKey[]; input?: unknown }[],
  context: { aggregateType?: AggregateType | undefined; aggregateId?: string | undefined },
): MueError {
  const missing = issues.find(
    (issue) => issue.code === "invalid_type" && issue.input === undefined,
  );
  if (missing !== undefined) {
    return mueError(
      "sync.missing_required_field",
      "The mutation is missing a required field.",
      false,
      { ...context, field: missing.path.map(String).join(".") },
    );
  }
  const first = issues[0];
  const where = first === undefined || first.path.length === 0 ? "" : ` at ${first.path.join(".")}`;
  return mueError(
    "sync.invalid_payload",
    `The mutation does not match the /api/v1 contract${where}.`,
    false,
    context,
  );
}

export function validateMutation(raw: unknown): MutationValidation {
  const record = asRecord(raw);
  if (record === undefined) {
    return {
      ok: false,
      error: mueError("sync.invalid_payload", "A mutation must be an object.", false),
    };
  }

  const aggregateType = record["aggregateType"];
  if (!isAggregateType(aggregateType)) {
    return {
      ok: false,
      error: mueError(
        "sync.unknown_aggregate_type",
        `This server does not synchronise ${JSON.stringify(aggregateType)}. Known types: ${AGGREGATE_TYPES.join(", ")}.`,
        false,
      ),
    };
  }

  const aggregateId = typeof record["aggregateId"] === "string" ? record["aggregateId"] : undefined;
  const context = { aggregateType, aggregateId };

  const op = record["op"];
  if (op !== "upsert" && op !== "delete") {
    return {
      ok: false,
      error: mueError(
        "sync.invalid_payload",
        `A mutation is an upsert or a delete, not ${JSON.stringify(op)}.`,
        false,
        context,
      ),
    };
  }

  // Only an upsert is gated on the version. A delete carries no payload, so it
  // is applicable at any schema generation; refusing it would strand a client
  // on a tombstone it is perfectly able to apply, with no upgrade to gain.
  if (op === "upsert" && !serverSupports(aggregateType, record["payloadSchemaVersion"])) {
    return {
      ok: false,
      error: mueError(
        "sync.upgrade_required",
        `This server cannot apply ${aggregateType} payload schema version ${String(record["payloadSchemaVersion"])}. Supported: ${CURRENT_PAYLOAD_SCHEMA_VERSIONS[aggregateType].join(", ")}.`,
        false,
        context,
      ),
    };
  }

  const parsed = mutationEnvelopeSchema.safeParse(raw);
  if (!parsed.success) {
    return { ok: false, error: fromParseFailure(parsed.error.issues, context) };
  }
  return { ok: true, mutation: parsed.data };
}
