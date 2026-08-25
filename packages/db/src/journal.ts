import { and, asc, eq, gt, sql } from "drizzle-orm";
import type { DatabaseHandle } from "./client";
import { mutationLog, syncCounter, syncJournal } from "./schema/app";

/**
 * Storage primitives for the sync journal.
 *
 * These are mechanics, not rules: what a mutation means, whether it is
 * accepted and what revision it produces belong to `packages/domain`, which is
 * the single implementation of every rule. What lives here is the ordering
 * guarantee, because it is a property of the storage and of nothing else.
 */

export type SyncOperation = "upsert" | "delete";
export type OriginType = "android" | "agent" | "server";

/** A Drizzle transaction handle, as `db.transaction` hands it to its callback. */
export type Transaction = Parameters<Parameters<DatabaseHandle["db"]["transaction"]>[0]>[0];

export interface JournalAppend {
  readonly userId: string;
  readonly aggregateType: string;
  readonly aggregateId: string;
  readonly operation: SyncOperation;
  readonly revision: bigint;
  readonly payloadSchemaVersion: number;
  /** The accepted state. Null for a delete: the tombstone is the change. */
  readonly payload: unknown;
  readonly deletedAt: Date | null;
  readonly originType: OriginType;
  readonly originId: string | null;
  readonly mutationId: string;
}

/**
 * Allocate the next sequence for a user.
 *
 * Must run inside the transaction that writes the change. The `on conflict do
 * update` takes a row lock on the counter that is held until that transaction
 * commits, so two concurrent mutations for one user cannot interleave: the
 * second waits, and a client that sees sequence N has necessarily already been
 * able to see every sequence below it.
 *
 * That is the whole reason a sequence generator is not used here. A
 * `bigserial` hands out 100 and 101 to two transactions and lets 101 commit
 * first; a client pulling in between advances its cursor past 100, which
 * commits a millisecond later and is never delivered. Nothing fails, and a
 * change is lost -- exactly what section 12.3 forbids.
 */
export async function allocateSequence(tx: Transaction, userId: string): Promise<bigint> {
  const rows = await tx
    .insert(syncCounter)
    .values({ userId, seq: 1n })
    .onConflictDoUpdate({
      target: syncCounter.userId,
      set: { seq: sql`${syncCounter.seq} + 1` },
    })
    .returning({ seq: syncCounter.seq });
  const row = rows[0];
  if (row === undefined) throw new Error(`sync_counter returned no sequence for user ${userId}`);
  return row.seq;
}

/** Append one accepted change and return the sequence it was given. */
export async function appendToJournal(tx: Transaction, change: JournalAppend): Promise<bigint> {
  const sequence = await allocateSequence(tx, change.userId);
  await tx.insert(syncJournal).values({
    userId: change.userId,
    sequence,
    aggregateType: change.aggregateType,
    aggregateId: change.aggregateId,
    operation: change.operation,
    revision: change.revision,
    payloadSchemaVersion: change.payloadSchemaVersion,
    payload: change.payload,
    deletedAt: change.deletedAt,
    originType: change.originType,
    originId: change.originId,
    mutationId: change.mutationId,
  });
  return sequence;
}

export type JournalEntry = typeof syncJournal.$inferSelect;

/**
 * Every change after `afterSequence`, in sequence order. Pass `0n` for a first
 * synchronisation: sequences start at 1.
 *
 * `limit` is a page size, not a time window. Section 21 requires an agent to
 * walk the whole history with no imposed window, and the cursor is the only
 * thing that bounds a page.
 */
export async function readJournalSince(
  handle: DatabaseHandle,
  userId: string,
  afterSequence: bigint,
  limit: number,
): Promise<JournalEntry[]> {
  return handle.db
    .select()
    .from(syncJournal)
    .where(and(eq(syncJournal.userId, userId), gt(syncJournal.sequence, afterSequence)))
    .orderBy(asc(syncJournal.sequence))
    .limit(limit);
}

/** The highest sequence assigned to a user, or 0 when nothing was ever written. */
export async function currentSequence(handle: DatabaseHandle, userId: string): Promise<bigint> {
  const rows = await handle.db
    .select({ seq: syncCounter.seq })
    .from(syncCounter)
    .where(eq(syncCounter.userId, userId));
  return rows[0]?.seq ?? 0n;
}

export interface MutationRecord {
  readonly mutationId: string;
  readonly userId: string;
  readonly aggregateType: string;
  readonly aggregateId: string;
  readonly operation: SyncOperation;
  readonly status: "applied" | "rejected";
  readonly sequence: bigint | null;
  readonly revision: bigint | null;
  readonly result: unknown;
}

export interface MutationOutcome {
  /** False when this mutation had already been recorded. */
  readonly recorded: boolean;
  /** The stored result: the new one, or the earlier one returned verbatim. */
  readonly result: unknown;
}

/**
 * FR-SYNC-006, the whole of it.
 *
 * The insert is attempted once with `on conflict (mutation_id) do nothing
 * returning *`. Rows come back only when this call is the one that recorded
 * the mutation. When nothing comes back the mutation already ran, so the
 * stored result is read and returned unchanged -- "le rejeu retourne le
 * resultat existant" -- and the effect is not repeated.
 *
 * Call this inside the same transaction as the change itself, so a lost
 * response can never leave a change applied without its log entry.
 */
export async function recordMutation(
  tx: Transaction,
  record: MutationRecord,
): Promise<MutationOutcome> {
  const inserted = await tx
    .insert(mutationLog)
    .values({
      mutationId: record.mutationId,
      userId: record.userId,
      aggregateType: record.aggregateType,
      aggregateId: record.aggregateId,
      operation: record.operation,
      status: record.status,
      sequence: record.sequence,
      revision: record.revision,
      result: record.result,
    })
    .onConflictDoNothing({ target: mutationLog.mutationId })
    .returning();

  const row = inserted[0];
  if (row !== undefined) return { recorded: true, result: row.result };

  const existing = await tx
    .select({ result: mutationLog.result })
    .from(mutationLog)
    .where(eq(mutationLog.mutationId, record.mutationId));
  const stored = existing[0];
  if (stored === undefined) {
    throw new Error(`mutation_log lost ${record.mutationId} between the insert and the read`);
  }
  return { recorded: false, result: stored.result };
}

/** The result stored for a mutation, or undefined if it was never seen. */
export async function findMutationResult(
  handle: DatabaseHandle,
  mutationId: string,
): Promise<unknown | undefined> {
  const rows = await handle.db
    .select({ result: mutationLog.result })
    .from(mutationLog)
    .where(eq(mutationLog.mutationId, mutationId));
  return rows[0]?.result;
}
