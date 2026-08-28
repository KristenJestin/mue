import {
  ACTIVITY_SESSION_PAYLOAD_VERSION_1,
  type ActivitySessionPayloadV1,
  type AggregateType,
  MEASUREMENT_PAYLOAD_VERSION_1,
  type MueError,
  type MutationEnvelope,
  type MutationOp,
  type MutationResult,
  type Origin,
  type OriginType,
} from "@mue/contracts";
import { type DatabaseHandle, schema } from "@mue/db";
import { and, asc, eq, gte, isNull, lte } from "drizzle-orm";
import { SyncRequestError } from "./errors";
import { submitMutation } from "./push";
import type { SyncContext } from "./types";

const { activitySessions, measurements } = schema;

/**
 * Authoring mutations from the server side.
 *
 * An MCP tool has no outbox, so it mints the envelope here and hands it to the
 * same `submitMutation` a pushed batch goes through. That is what section 20.2
 * asks for in practice: an agent's write is a journal entry with `originType:
 * "agent"` and nothing else about it is special, which is also what makes
 * FR-SYNC-004 true -- the phone receives it on its next pull as ordinary data.
 */

export interface AuthoredMeasurement {
  readonly date: string;
  readonly weightCg: number;
  /** The revision the author believed it was editing; null for a creation. */
  readonly baseRevision?: string | null;
  /** Supplied when the caller has its own idempotency key; minted otherwise. */
  readonly mutationId?: string;
  readonly occurredAt?: Date;
}

function envelope(
  origin: Origin,
  mutationId: string | undefined,
  occurredAt: Date | undefined,
  baseRevision: string | null | undefined,
) {
  return {
    // UUIDv7 rather than v4: `mutationIdSchema` requires it, so an identifier
    // sorts by creation time and a replayed batch drains in the order it was
    // authored.
    mutationId: mutationId ?? Bun.randomUUIDv7(),
    baseRevision: baseRevision ?? null,
    origin,
    clientOccurredAt: (occurredAt ?? new Date()).toISOString(),
  };
}

export function buildMeasurementUpsert(
  origin: Origin,
  input: AuthoredMeasurement,
): MutationEnvelope {
  return {
    ...envelope(origin, input.mutationId, input.occurredAt, input.baseRevision),
    aggregateType: "measurement",
    aggregateId: input.date,
    op: "upsert",
    payloadSchemaVersion: MEASUREMENT_PAYLOAD_VERSION_1,
    payload: { date: input.date, weightCg: input.weightCg },
  };
}

export function buildMeasurementDelete(
  origin: Origin,
  input: { date: string; baseRevision?: string | null; mutationId?: string; occurredAt?: Date },
): MutationEnvelope {
  return {
    ...envelope(origin, input.mutationId, input.occurredAt, input.baseRevision),
    aggregateType: "measurement",
    aggregateId: input.date,
    op: "delete",
    payloadSchemaVersion: MEASUREMENT_PAYLOAD_VERSION_1,
    payload: null,
  };
}

/** Record one weight as `origin`. Same validation, same journal, same replay. */
export async function upsertMeasurement(
  handle: DatabaseHandle,
  context: SyncContext,
  origin: Origin,
  input: AuthoredMeasurement,
): Promise<MutationResult> {
  return submitMutation(handle, context, buildMeasurementUpsert(origin, input));
}

export async function deleteMeasurement(
  handle: DatabaseHandle,
  context: SyncContext,
  origin: Origin,
  input: { date: string; baseRevision?: string | null; mutationId?: string; occurredAt?: Date },
): Promise<MutationResult> {
  return submitMutation(handle, context, buildMeasurementDelete(origin, input));
}

export interface MeasurementView {
  readonly date: string;
  readonly weightCg: number;
  readonly revision: string;
}

/**
 * The live measurements of an account, tombstones excluded.
 *
 * The exclusion is the rule, not a convenience: FR-SYNC-005 keeps a deleted
 * row until retention sweeps it, so every reader that is not the journal must
 * filter it out, and that filter exists once.
 */
export async function listMeasurements(
  handle: DatabaseHandle,
  context: SyncContext,
  range: { from?: string; to?: string; limit?: number } = {},
): Promise<MeasurementView[]> {
  const rows = await handle.db
    .select({
      date: measurements.date,
      weightCg: measurements.weightCg,
      revision: measurements.revision,
    })
    .from(measurements)
    .where(
      and(
        eq(measurements.userId, context.userId),
        isNull(measurements.deletedAt),
        range.from === undefined ? undefined : gte(measurements.date, range.from),
        range.to === undefined ? undefined : lte(measurements.date, range.to),
      ),
    )
    .orderBy(asc(measurements.date))
    .limit(range.limit ?? 500);
  return rows.map((row) => ({
    date: row.date,
    weightCg: row.weightCg,
    revision: row.revision.toString(),
  }));
}

export interface MeasurementRevision {
  readonly revision: string;
  readonly deleted: boolean;
}

/**
 * The revision an author must quote to edit or restore one date. A tombstone
 * is reported rather than hidden: restoring it needs a mutation whose
 * `baseRevision` is exactly this one (section 13.3, closing rule).
 */
export async function readMeasurementRevision(
  handle: DatabaseHandle,
  context: SyncContext,
  date: string,
): Promise<MeasurementRevision | undefined> {
  const rows = await handle.db
    .select({ revision: measurements.revision, deletedAt: measurements.deletedAt })
    .from(measurements)
    .where(and(eq(measurements.userId, context.userId), eq(measurements.date, date)));
  const row = rows[0];
  return row === undefined
    ? undefined
    : { revision: row.revision.toString(), deleted: row.deletedAt !== null };
}

/**
 * An activity session authored from the server side, as `create_activity` submits one.
 *
 * This is the export `packages/api/src/mcp/domain-bridge.ts` was written to wait for, and its
 * arrival is what retires `provisional-activity-write.ts`. PRD section 20.2 allows a rule exactly
 * one implementation: with this in place, an agent creating a session and a phone pushing one
 * reach the same handler, take the same revision, land in the same journal and are returned to
 * the phone by the same pull. Nothing about an agent session is special except its `originType`,
 * which is FR-SYNC-004 in one sentence.
 */
export interface CreateActivitySessionCommand {
  readonly userId: string;
  /** FR-SYNC-006 and section 14.6: replaying this id repeats no effect. */
  readonly mutationId: string;
  readonly originId: string;
  readonly payload: ActivitySessionPayloadV1;
  /** The agent's own clock, for display and audit only (section 12.3). */
  readonly clientOccurredAt: string;
}

/** The stored aggregate plus the section 12.1 metadata a read tool reports. */
export interface ActivitySessionView extends ActivitySessionPayloadV1 {
  readonly revision: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly deletedAt: string | null;
  readonly originType: OriginType;
  readonly originId: string | null;
  readonly lastMutationId: string;
}

export interface CreateActivitySessionResult {
  readonly activity: ActivitySessionView;
  /** False when the mutation id had already been applied and this is the stored aggregate. */
  readonly created: boolean;
}

export function buildActivitySessionUpsert(
  origin: Origin,
  input: {
    payload: ActivitySessionPayloadV1;
    baseRevision?: string | null;
    mutationId?: string;
    occurredAt?: Date;
  },
): MutationEnvelope {
  return {
    ...envelope(origin, input.mutationId, input.occurredAt, input.baseRevision),
    aggregateType: "activitySession",
    aggregateId: input.payload.id,
    op: "upsert",
    payloadSchemaVersion: ACTIVITY_SESSION_PAYLOAD_VERSION_1,
    payload: input.payload,
  };
}

/** `origin_type` is a text column; anything unrecognised is reported as `server`. */
function asOriginType(value: string): OriginType {
  return value === "android" || value === "agent" ? value : "server";
}

/**
 * The stored session, read back as the view a tool returns.
 *
 * It is read from the row rather than echoed from the submitted payload, and that matters on a
 * replay: FR-SYNC-006 returns the *stored* result for a repeated `mutationId`, and the stored
 * aggregate may since have been edited by the phone. Echoing the submission would tell the agent
 * its own version stood when a later one is active.
 */
async function readActivitySessionView(
  handle: DatabaseHandle,
  userId: string,
  id: string,
): Promise<ActivitySessionView | undefined> {
  const rows = await handle.db
    .select()
    .from(activitySessions)
    .where(and(eq(activitySessions.userId, userId), eq(activitySessions.id, id)));
  const row = rows[0];
  if (row === undefined) return undefined;
  return {
    id: row.id,
    movement: row.movement as ActivitySessionPayloadV1["movement"],
    customMovementName: row.customMovementName,
    environment: row.environment as ActivitySessionPayloadV1["environment"],
    startedOn: row.startedOn,
    startedAtTime: row.startedAtTime,
    durationSeconds: row.durationSeconds,
    perceivedEffort: row.perceivedEffort,
    notes: row.notes,
    source: row.source as ActivitySessionPayloadV1["source"],
    metrics: row.metrics as ActivitySessionPayloadV1["metrics"],
    equipment: row.equipment as ActivitySessionPayloadV1["equipment"],
    exercises: row.exercises as ActivitySessionPayloadV1["exercises"],
    revision: row.revision.toString(),
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
    deletedAt: row.deletedAt?.toISOString() ?? null,
    originType: asOriginType(row.originType),
    originId: row.originId,
    lastMutationId: row.lastMutationId,
  };
}

/**
 * Create one activity session as an agent. Same validation, same journal, same replay.
 *
 * A rejection is raised rather than returned, because a tool has one envelope for both outcomes
 * and `SyncRequestError` already carries the `MueError` that envelope needs. `status: 400`
 * because every rejection this path can produce is a fault in what the caller supplied.
 */
export async function createActivitySession(
  handle: DatabaseHandle,
  command: CreateActivitySessionCommand,
): Promise<CreateActivitySessionResult> {
  const origin: Origin = { type: "agent", id: command.originId };
  const result = await submitMutation(
    handle,
    { userId: command.userId },
    buildActivitySessionUpsert(origin, {
      payload: command.payload,
      mutationId: command.mutationId,
      occurredAt: new Date(command.clientOccurredAt),
    }),
  );

  if (result.status === "rejected") throw new SyncRequestError(result.error, 400);

  /*
   * On a replay, the session to report is the one the *first* call created — not the one this
   * call would have created.
   *
   * `create_activity` mints a fresh `payload.id` on every invocation and carries the caller's
   * idempotency key in `mutationId`, so a retried call describes a session with a new identifier
   * that was never written. FR-SYNC-006 requires the earlier result back untouched, so the
   * identifier is read from `mutation_log`, which recorded what the first call actually applied.
   * Reading `command.payload.id` here would look for a row that does not exist and fail a retry
   * that the protocol guarantees is safe.
   */
  const aggregateId =
    result.status === "applied"
      ? command.payload.id
      : ((await readMutationAggregateId(handle, command.userId, command.mutationId)) ??
        command.payload.id);

  const activity = await readActivitySessionView(handle, command.userId, aggregateId);
  if (activity === undefined) {
    throw new Error(`activity_sessions lost ${aggregateId} between apply and read`);
  }
  return { activity, created: result.status === "applied" };
}

/** The aggregate a recorded mutation touched, scoped to its own account. */
async function readMutationAggregateId(
  handle: DatabaseHandle,
  userId: string,
  mutationId: string,
): Promise<string | undefined> {
  const rows = await handle.db
    .select({ aggregateId: schema.mutationLog.aggregateId })
    .from(schema.mutationLog)
    .where(
      and(eq(schema.mutationLog.mutationId, mutationId), eq(schema.mutationLog.userId, userId)),
    );
  return rows[0]?.aggregateId;
}

/**
 * One mutation authored from the server side, for any aggregate.
 *
 * `createActivitySession` above is this function with an activity's payload and an
 * activity's read-back. Twenty-six MCP tools do not need twenty-six copies of it: the envelope
 * they build differs only in `aggregateType`, `aggregateId` and `payload`, and everything that
 * follows — validation, the advisory lock, the revision, the journal entry, the sequence, the
 * idempotency record — is `submitMutation`'s and is the same for all of them. So a tool's write
 * path is *this* function, and PRD section 20.2's "one implementation of a rule" survives the
 * catalogue growing by an order of magnitude.
 *
 * It is deliberately un-opinionated about the payload. Every aggregate's shape is already stated
 * once, in `mutationEnvelopeSchema`, and `validateMutation` applies it before any handler runs;
 * a second opinion here would be a second place for the two to disagree.
 */
export interface AuthoredMutation {
  readonly userId: string;
  /** FR-SYNC-006 and section 14.6: replaying this id repeats no effect. */
  readonly mutationId: string;
  readonly origin: Origin;
  readonly aggregateType: AggregateType;
  readonly aggregateId: string;
  readonly op: MutationOp;
  readonly payloadSchemaVersion: number;
  /** The complete aggregate for an upsert; null for a delete (section 12.2). */
  readonly payload: unknown;
  /** The revision the author believed it was editing, when it knows one (section 14.6). */
  readonly baseRevision?: string | null;
  /** The author's own clock, for display and audit only (section 12.3). */
  readonly occurredAt?: Date;
}

export interface AuthoredMutationOutcome {
  /** `duplicate` is a replay: the effect happened once, and this is the stored result. */
  readonly status: "applied" | "duplicate" | "rejected";
  /**
   * The aggregate the mutation actually touched.
   *
   * On a replay this is read back from `mutation_log` rather than echoed from the submission,
   * for the reason `createActivitySession` gives: a creation tool mints a fresh identifier on
   * every invocation, so a retry describes an aggregate that was never written. The identifier
   * of the row the *first* call created is the only useful answer.
   */
  readonly aggregateId: string;
  readonly revision: string | null;
  readonly sequence: string | null;
  readonly error: MueError | null;
}

export async function authorMutation(
  handle: DatabaseHandle,
  input: AuthoredMutation,
): Promise<AuthoredMutationOutcome> {
  const result = await submitMutation(
    handle,
    { userId: input.userId },
    {
      ...envelope(input.origin, input.mutationId, input.occurredAt, input.baseRevision),
      aggregateType: input.aggregateType,
      aggregateId: input.aggregateId,
      op: input.op,
      payloadSchemaVersion: input.payloadSchemaVersion,
      payload: input.payload,
    },
  );

  if (result.status === "rejected") {
    return {
      status: "rejected",
      aggregateId: input.aggregateId,
      revision: null,
      sequence: null,
      error: result.error,
    };
  }

  const aggregateId =
    result.status === "applied"
      ? input.aggregateId
      : ((await readMutationAggregateId(handle, input.userId, input.mutationId)) ??
        input.aggregateId);

  return {
    status: result.status,
    aggregateId,
    revision: result.revision,
    sequence: result.sequence,
    error: null,
  };
}
