import type { MueError, MutationEnvelope, Origin } from "@mue/contracts";
import type { DatabaseHandle, Transaction } from "@mue/db";

/**
 * Who the work is done for. The account comes from the authenticated
 * credential and never from the request body: every synchronised table is
 * keyed by `user_id`, so this is the only thing separating two accounts.
 *
 * The *origin* is not here. It travels inside each mutation envelope, because
 * PRD section 12.2 makes it the author's identity rather than the caller's:
 * a phone pushing a batch it queued while offline is still the author of it.
 */
export interface SyncContext {
  readonly userId: string;
}

/** An identity a server-side caller (an MCP tool) authors mutations as. */
export type MutationOrigin = Origin;

/**
 * What applying one mutation to one aggregate produced. `sequence` is the
 * journal position it was appended at; it exists only for the cursor.
 */
export interface AppliedOutcome {
  readonly status: "applied";
  readonly revision: bigint;
  readonly sequence: bigint;
}

export interface RejectedOutcome {
  readonly status: "rejected";
  readonly error: MueError;
}

export type ApplyOutcome = AppliedOutcome | RejectedOutcome;

/**
 * The rules of one aggregate kind. Adding an aggregate to the slice is adding
 * one of these and one entry in the registry -- the push and pull paths never
 * learn a second aggregate's vocabulary.
 */
export interface AggregateHandler {
  apply(
    tx: Transaction,
    context: SyncContext,
    mutation: MutationEnvelope,
    now: Date,
  ): Promise<ApplyOutcome>;
  /**
   * The immutable creation instant of each named aggregate, for the metadata a
   * pull carries. Immutable is what makes reading it from the current row safe
   * next to a journal snapshot: unlike every other field, it cannot have moved
   * since the snapshot was taken.
   */
  createdAtFor(
    handle: DatabaseHandle,
    context: SyncContext,
    aggregateIds: readonly string[],
  ): Promise<ReadonlyMap<string, Date>>;
}
