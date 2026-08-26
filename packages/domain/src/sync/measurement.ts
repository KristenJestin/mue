import { type MeasurementPayloadV1, type MutationEnvelope } from "@mue/contracts";
import { type Transaction, appendToJournal, schema } from "@mue/db";
import { and, eq, inArray } from "drizzle-orm";
import { mueError } from "./errors";
import type { AggregateHandler, ApplyOutcome, SyncContext } from "./types";

const { measurements } = schema;

/**
 * The rules of PRD section 13.2, and the only implementation of them.
 *
 * The business key is the local date, in Room and in `mue_app.measurements`
 * alike, so convergence is structural: two devices recording a weight for the
 * same day address the same row and cannot produce a second measurement for it.
 *
 * There is deliberately no revision conflict on the happy path. Section 13.2
 * says a newly accepted mutation *replaces* the current value and that the last
 * mutation the server accepts becomes the active version -- last-write-wins,
 * with the replaced version left in the journal for audit. `baseRevision` is
 * therefore recorded and, on a live aggregate, never a reason to reject;
 * `sync.revision_conflict` belongs to section 13.3's aggregates, which V1 does
 * not carry.
 */

interface MeasurementState {
  readonly revision: bigint;
  readonly deletedAt: Date | null;
  readonly weightCg: number;
}

async function readState(
  tx: Transaction,
  userId: string,
  date: string,
): Promise<MeasurementState | undefined> {
  const rows = await tx
    .select({
      revision: measurements.revision,
      deletedAt: measurements.deletedAt,
      weightCg: measurements.weightCg,
    })
    .from(measurements)
    .where(and(eq(measurements.userId, userId), eq(measurements.date, date)));
  return rows[0];
}

/**
 * A tombstone the author did not know about. FR-SYNC-005 exists so that an
 * offline copy cannot resurrect a deletion, and section 13.3's closing rule
 * says a restoration must be an explicit mutation based on the *current*
 * tombstone. So an upsert is refused unless its `baseRevision` is the
 * tombstone's revision -- which is precisely the phone having already received
 * the delete and having chosen to undo it.
 */
function refusesResurrection(state: MeasurementState, baseRevision: string | null): boolean {
  return state.deletedAt !== null && baseRevision !== state.revision.toString();
}

async function applyUpsert(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "upsert" }>,
  now: Date,
): Promise<ApplyOutcome> {
  const payload: MeasurementPayloadV1 = mutation.payload;
  const state = await readState(tx, context.userId, mutation.aggregateId);

  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return {
      status: "rejected",
      error: mueError(
        "sync.aggregate_deleted",
        "This measurement was deleted. Restore it with a mutation based on the tombstone revision.",
        false,
        {
          aggregateType: "measurement",
          aggregateId: mutation.aggregateId,
          currentRevision: state.revision.toString(),
        },
      ),
    };
  }

  const revision = (state?.revision ?? 0n) + 1n;
  await tx
    .insert(measurements)
    .values({
      userId: context.userId,
      date: payload.date,
      weightCg: payload.weightCg,
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
      target: [measurements.userId, measurements.date],
      // `created_at` is absent on purpose: it is the instant of the first
      // accepted version and never moves again.
      set: {
        weightCg: payload.weightCg,
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
    aggregateType: "measurement",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    // A snapshot of what was accepted, not a pointer to the current row. This
    // is what lets a pull at sequence N return what N carried, what lets
    // section 12.4 reject an unsupported version for the exact change carrying
    // it, and what keeps the replaced version auditable (sections 13.1, 13.2).
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

/**
 * A delete never erases: it writes a tombstone (FR-SYNC-005), and the row stays
 * until the documented retention sweep removes it.
 *
 * A delete for a date the server has never seen still writes one. The phone's
 * outbox is UUIDv7-ordered so the upsert normally arrives first, but if it does
 * not, refusing the delete would leave the client with a mutation it can never
 * drain, and journalling it without a row would leave nothing to stop a later
 * offline upsert from resurrecting it. `weight_cg` is not nullable, so such a
 * row carries 0 -- a value outside the domain bounds of `Weight`, and one no
 * reader ever sees: every read filters `deleted_at is null`, and a delete
 * change carries `payload: null` on the wire.
 */
async function applyDelete(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "delete" }>,
  now: Date,
): Promise<ApplyOutcome> {
  const state = await readState(tx, context.userId, mutation.aggregateId);
  const revision = (state?.revision ?? 0n) + 1n;

  await tx
    .insert(measurements)
    .values({
      userId: context.userId,
      date: mutation.aggregateId,
      weightCg: state?.weightCg ?? 0,
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
      target: [measurements.userId, measurements.date],
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
    aggregateType: "measurement",
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

export const measurementHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return mutation.op === "upsert"
      ? applyUpsert(tx, context, mutation, now)
      : applyDelete(tx, context, mutation, now);
  },

  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ date: measurements.date, createdAt: measurements.createdAt })
      .from(measurements)
      .where(
        and(eq(measurements.userId, context.userId), inArray(measurements.date, [...aggregateIds])),
      );
    for (const row of rows) found.set(row.date, row.createdAt);
    return found;
  },
};
