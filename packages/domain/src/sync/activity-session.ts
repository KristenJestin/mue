import type { ActivitySessionPayloadV1, MutationEnvelope } from "@mue/contracts";
import { type Transaction, appendToJournal, schema } from "@mue/db";
import { and, eq, inArray } from "drizzle-orm";
import {
  type OpaqueState,
  deletedRejection,
  misroutedRejection,
  nextRevision,
  refusesResurrection,
} from "./opaque";
import type { AggregateHandler, ApplyOutcome, SyncContext } from "./types";

const { activitySessions } = schema;

/**
 * One finished activity session, whole: PRD section 10.2's atomic aggregate, under section
 * 13.3's rules as `opaque.ts` reads them.
 *
 * The scalars a list query or an MCP tool filters on are columns; the metrics, the equipment and
 * the exercises with their sets are `jsonb`, replaced together with the row that carries them.
 * That is not a storage shortcut — it is what section 10.2's *"une activité ne peut jamais
 * apparaître sans ses enfants obligatoires à cause d'une synchronisation partielle"* means when
 * you write it down: there is no statement in this file that can leave a session and its children
 * in two different versions, because there is only one statement.
 *
 * This handler replaces `mcp/provisional-activity-write.ts`, which the MCP slice carried while
 * `@mue/domain` had no activity write. PRD section 20.2 allows exactly one implementation of a
 * rule, and this is now it: `create_activity` and a pushed `activitySession` upsert reach the
 * same code, so an agent's session and a phone's session are the same kind of thing.
 */

async function readState(
  tx: Transaction,
  userId: string,
  id: string,
): Promise<OpaqueState | undefined> {
  const rows = await tx
    .select({ revision: activitySessions.revision, deletedAt: activitySessions.deletedAt })
    .from(activitySessions)
    .where(and(eq(activitySessions.userId, userId), eq(activitySessions.id, id)));
  return rows[0];
}

/**
 * The columns a payload becomes.
 *
 * Written out rather than spread, so a field added to the contract and forgotten here fails to
 * compile instead of quietly not being stored.
 */
function columnsOf(payload: ActivitySessionPayloadV1) {
  return {
    movement: payload.movement,
    customMovementName: payload.customMovementName,
    environment: payload.environment,
    startedOn: payload.startedOn,
    startedAtTime: payload.startedAtTime,
    durationSeconds: payload.durationSeconds,
    perceivedEffort: payload.perceivedEffort,
    notes: payload.notes,
    source: payload.source,
    metrics: payload.metrics,
    equipment: payload.equipment,
    exercises: payload.exercises,
  };
}

async function applyUpsert(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "upsert" }>,
  now: Date,
): Promise<ApplyOutcome> {
  if (mutation.aggregateType !== "activitySession") {
    return misroutedRejection("activitySession", mutation.aggregateId);
  }
  const payload: ActivitySessionPayloadV1 = mutation.payload;
  const state = await readState(tx, context.userId, mutation.aggregateId);

  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return deletedRejection("activitySession", mutation.aggregateId, state, "session");
  }

  const revision = nextRevision(state);
  const columns = columnsOf(payload);

  await tx
    .insert(activitySessions)
    .values({
      userId: context.userId,
      id: payload.id,
      ...columns,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [activitySessions.userId, activitySessions.id],
      // `created_at` is absent on purpose: it is the instant of the first accepted version and
      // never moves again. Room keeps the same invariant in `ActivityDao.saveDetail`.
      set: {
        ...columns,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "activitySession",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    // The snapshot, children included. It is what keeps the version this mutation replaced
    // readable for ever (section 13.1: no resolution destroys the audit history), and it is what
    // a pull at this sequence returns rather than whatever the row holds by then.
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

/**
 * A delete never erases: it writes a tombstone (FR-SYNC-005), and the row stays until the
 * documented retention sweep removes it.
 *
 * A delete for a session the server has never seen still writes one, for the two reasons
 * `measurement.ts` gives and that have nothing to do with weights: refusing it would leave the
 * client with a mutation it can never drain, and journalling it with no row would leave nothing
 * to stop a later offline upsert from resurrecting it.
 *
 * A session has a dozen non-nullable columns and the tombstone has to fill them, so it fills them
 * the way `measurement.ts` fills `weight_cg`: with values outside the domain's own bounds, on a
 * row no reader ever sees. `duration_seconds` is `0` where `ActivityDuration` starts at 1, which
 * is the exact analogue of that `0` kilogram. Every read filters `deleted_at is null`, and a
 * delete change carries `payload: null` on the wire, so these values reach no screen and no
 * agent — they exist so the primary key can hold a tombstone at all.
 */
const TOMBSTONE_COLUMNS = {
  movement: "other",
  customMovementName: null,
  environment: "unknown",
  startedAtTime: null,
  /** Outside `ActivityDuration`'s range, so this row cannot be mistaken for a session. */
  durationSeconds: 0,
  perceivedEffort: null,
  notes: null,
  source: "manual",
  metrics: [],
  equipment: [],
  exercises: [],
} as const;

async function applyDelete(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "delete" }>,
  now: Date,
): Promise<ApplyOutcome> {
  const state = await readState(tx, context.userId, mutation.aggregateId);
  const revision = nextRevision(state);

  await tx
    .insert(activitySessions)
    .values({
      userId: context.userId,
      id: mutation.aggregateId,
      ...TOMBSTONE_COLUMNS,
      metrics: [],
      equipment: [],
      exercises: [],
      startedOn: now.toISOString().slice(0, 10),
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: now,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [activitySessions.userId, activitySessions.id],
      // Only the tombstone and its metadata. A session that really existed keeps its own columns,
      // so a restoration based on this tombstone has something to restore.
      set: {
        revision,
        updatedAt: now,
        deletedAt: now,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "activitySession",
    aggregateId: mutation.aggregateId,
    operation: "delete",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload: null,
    deletedAt: now,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

export const activitySessionHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return mutation.op === "upsert"
      ? applyUpsert(tx, context, mutation, now)
      : applyDelete(tx, context, mutation, now);
  },

  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ id: activitySessions.id, createdAt: activitySessions.createdAt })
      .from(activitySessions)
      .where(
        and(
          eq(activitySessions.userId, context.userId),
          inArray(activitySessions.id, [...aggregateIds]),
        ),
      );
    for (const row of rows) found.set(row.id, row.createdAt);
    return found;
  },
};
