import {
  AGGREGATE_TYPES,
  type AggregateType,
  CURRENT_PAYLOAD_SCHEMA_VERSIONS,
  type DateRuleViolation,
  type MueError,
  type MutationEnvelope,
  mutationEnvelopeSchema,
  mutationIdSchema,
  pastEventDay,
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

/**
 * The date policy of `@mue/contracts`, applied to a parsed upsert.
 *
 * This is the second of the two authoring paths, and the one F-02 could not see: every pushed
 * mutation and every MCP write reaches `submitMutation`, so a rule enforced here holds for the
 * phone's outbox and for an agent alike. Enforcing it only in the MCP tools would have left
 * `POST /api/v1/sync/push` accepting the very session `mue.create_activity` had just refused.
 *
 * Only [pastEventDay] is applied, and only it can be. It is the one rule of the three that is
 * **stable under replay**: a day that was not in the future when a row was written is still not
 * in the future when the row is pushed a week later, because time moves one way. `planningWindow`
 * and `lifetimeFloor` both decay, and `push` records a rejection under its `mutationId` and
 * replays it verbatim -- so refusing a mutation on a bound that has since moved would strand a
 * stored row permanently. Those two therefore stay at the point a value is authored.
 *
 * This is emphatically not a schema refinement, for the reason `health-profile.ts` and
 * `meal-plan.ts` both give: `pull` re-parses journalled changes through `syncChangeSchema`, and
 * a clock-relative bound inside a payload schema would eventually stop a cursor dead on data the
 * client already holds. Validation on submission is a different question from validation on
 * replay, and only the first one may read a clock.
 */
function checkDatePolicy(mutation: MutationEnvelope): DateRuleViolation | undefined {
  // A delete carries no payload, and a tombstone for a badly dated row must stay possible.
  if (mutation.op !== "upsert") return undefined;

  switch (mutation.aggregateType) {
    case "measurement":
      // PRD section 11.1, BR-009.
      return pastEventDay("payload.date", mutation.payload.date);
    case "activitySession":
      // PRD_ACTIVITIES FR-ACTIVITY-005: "Interdire les dates futures".
      return pastEventDay("payload.startedOn", mutation.payload.startedOn);
    case "foodLogEntry":
      // PRD_FOOD 15 and 21.5: a journal line is never created in the future.
      return pastEventDay("payload.consumedOn", mutation.payload.consumedOn);
    case "healthProfile":
      // The future half of PRD section 11.2 only. Its 120-year floor moves with the calendar,
      // so it belongs where a profile is authored, not where a stored one is replayed.
      return mutation.payload.birthDate === null
        ? undefined
        : pastEventDay("payload.birthDate", mutation.payload.birthDate);
    case "mealPlanEntry":
    // A proposal is deliberately ahead, and its window is unstable under replay. See
    // `planningWindow`: `mue.plan_meal` is the only place it is checked.
    case "food":
    case "recipe":
    case "customExerciseDefinition":
      // No business date in the payload at all.
      return undefined;
  }
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

  // Last, and only on a payload that has already parsed: the date policy reads fields by name,
  // so it needs the shape to be known good before it can ask anything about a value.
  const violation = checkDatePolicy(parsed.data);
  if (violation !== undefined) {
    return {
      ok: false,
      error: mueError("sync.invalid_payload", violation.message, false, {
        ...context,
        field: violation.field,
      }),
    };
  }

  return { ok: true, mutation: parsed.data };
}
