import {
  type MueError,
  type MutationEnvelope,
  type MutationResult,
  PUSH_MAX_MUTATIONS,
  type PushResponse,
  mutationResultSchema,
} from "@mue/contracts";
import { type DatabaseHandle, type Transaction, recordMutation, schema } from "@mue/db";
import { and, eq, sql } from "drizzle-orm";
import { invalidRequest, mueError } from "./errors";
import { handlerFor } from "./registry";
import type { SyncContext } from "./types";
import { readMutationId, validateMutation } from "./validate";

const { mutationLog } = schema;

/**
 * Applying submitted mutations: FR-SYNC-006 and FR-SYNC-007 in one place.
 *
 * Both requirements are properties of the *batch* rather than of an aggregate,
 * which is why they live above the handlers: idempotence is keyed by
 * `mutationId` whatever the mutation touches, and one rejection never reaching
 * the next mutation is a consequence of each one owning its own transaction.
 */

/**
 * Serialises everything done for one account for the length of a transaction.
 *
 * `allocateSequence` already serialises the *sequence*, but it is taken after
 * the aggregate has been read, and a read-modify-write of `revision` needs the
 * lock from before the read: otherwise two concurrent upserts of one date both
 * read revision 1 and both write revision 2. The lock is advisory and
 * transaction-scoped, so it is released by commit or rollback and cannot be
 * leaked. Section 6 puts multi-user out of scope, so it costs nothing.
 */
const USER_LOCK_NAMESPACE = 0x4d5545;

/** FNV-1a, folded into a signed 32-bit key. A collision only over-serialises. */
function lockKey(userId: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < userId.length; index += 1) {
    hash ^= userId.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash | 0;
}

async function lockUser(tx: Transaction, userId: string): Promise<void> {
  await tx.execute(sql`select pg_advisory_xact_lock(${USER_LOCK_NAMESPACE}, ${lockKey(userId)})`);
}

/**
 * One clock for every instant a mutation writes.
 *
 * `sync_journal.recorded_at` defaults to `now()`, which PostgreSQL fixes at the
 * start of the transaction. Reading the same function here gives byte-identical
 * instants for the journal entry, the aggregate's `created_at`/`updated_at` and
 * a tombstone's `deleted_at`, so `createdAt <= updatedAt` holds by construction
 * instead of by luck. The process clock is deliberately not used: it is a second
 * clock, and section 12.3 keeps civil clocks out of anything but display.
 */
async function transactionNow(tx: Transaction): Promise<Date> {
  const rows = (await tx.execute(sql`select now() as now`)) as unknown as { now: Date }[];
  const first = rows[0];
  if (first === undefined) throw new Error("select now() returned no row");
  return first.now instanceof Date ? first.now : new Date(String(first.now));
}

/** The result already stored for this mutation, scoped to its own account. */
async function storedResult(
  tx: Transaction,
  userId: string,
  mutationId: string,
): Promise<MutationResult | undefined> {
  const rows = await tx
    .select({ result: mutationLog.result })
    .from(mutationLog)
    .where(and(eq(mutationLog.mutationId, mutationId), eq(mutationLog.userId, userId)));
  const row = rows[0];
  if (row === undefined) return undefined;
  return mutationResultSchema.parse(row.result);
}

/**
 * A replay returns the stored result verbatim, with `status` rewritten to
 * `duplicate` so the client can tell a first application from a repeat. The
 * revision and the sequence are the ones the original produced -- that is the
 * whole of "le rejeu retourne le resultat existant" (section 18), and it is
 * what makes a lost response cost a round trip and never a second write.
 */
function asDuplicate(stored: MutationResult): MutationResult {
  return stored.status === "rejected"
    ? stored
    : {
        mutationId: stored.mutationId,
        status: "duplicate",
        revision: stored.revision,
        sequence: stored.sequence,
      };
}

function rejection(mutationId: string, error: MueError): MutationResult {
  return { mutationId, status: "rejected", error };
}

/**
 * Thrown to roll a transaction back while still answering with a result.
 *
 * It is raised only after the aggregate and the journal have been written and
 * `mutation_log` has then refused the id: the effect must not commit, and
 * `postgres.js` rolls back exactly when the callback throws.
 */
class AbortWithResult extends Error {
  readonly result: MutationResult;

  constructor(result: MutationResult) {
    super("mutation rolled back; a stored result answers it");
    this.name = "AbortWithResult";
    this.result = result;
  }
}

/**
 * Apply one already-validated envelope. Everything it writes -- the aggregate,
 * the journal entry, the sequence and the idempotency record -- commits
 * together or not at all.
 */
async function applyOne(
  handle: DatabaseHandle,
  context: SyncContext,
  mutation: MutationEnvelope,
): Promise<MutationResult> {
  const handler = handlerFor(mutation.aggregateType);
  if (handler === undefined) {
    // Unreachable: `validateMutation` rejects an unknown type first. Kept so
    // that adding an aggregate to the contract without a handler fails loudly.
    return rejection(
      mutation.mutationId,
      mueError("sync.unknown_aggregate_type", `No handler for ${mutation.aggregateType}.`, false, {
        aggregateId: mutation.aggregateId,
      }),
    );
  }

  try {
    return await runApply(handle, context, mutation, handler);
  } catch (error) {
    if (error instanceof AbortWithResult) return error.result;
    throw error;
  }
}

async function runApply(
  handle: DatabaseHandle,
  context: SyncContext,
  mutation: MutationEnvelope,
  handler: NonNullable<ReturnType<typeof handlerFor>>,
): Promise<MutationResult> {
  return handle.db.transaction(async (tx) => {
    await lockUser(tx, context.userId);

    const replay = await storedResult(tx, context.userId, mutation.mutationId);
    if (replay !== undefined) return asDuplicate(replay);

    const now = await transactionNow(tx);
    const outcome = await handler.apply(tx, context, mutation, now);

    const result: MutationResult =
      outcome.status === "applied"
        ? {
            mutationId: mutation.mutationId,
            status: "applied",
            revision: outcome.revision.toString(),
            sequence: outcome.sequence.toString(),
          }
        : rejection(mutation.mutationId, outcome.error);

    // A rejection is recorded too, so replaying a bad mutation replays the same
    // structured error instead of being attempted a second time (section 18).
    const recorded = await recordMutation(tx, {
      mutationId: mutation.mutationId,
      userId: context.userId,
      aggregateType: mutation.aggregateType,
      aggregateId: mutation.aggregateId,
      operation: mutation.op,
      status: outcome.status === "applied" ? "applied" : "rejected",
      sequence: outcome.status === "applied" ? outcome.sequence : null,
      revision: outcome.status === "applied" ? outcome.revision : null,
      result,
    });

    if (recorded.recorded) return result;

    // The authoritative check. The advisory lock makes this unreachable for two
    // concurrent copies of the same mutation on one account, so arriving here
    // means the id was already spent -- possibly by another account, since
    // `mutation_log.mutation_id` is the global primary key. Re-reading scoped to
    // this account is what stops one account replaying another's result.
    const mine = await storedResult(tx, context.userId, mutation.mutationId);
    throw new AbortWithResult(
      mine === undefined
        ? rejection(
            mutation.mutationId,
            mueError("sync.invalid_payload", "This mutationId is already in use.", false, {
              aggregateType: mutation.aggregateType,
              aggregateId: mutation.aggregateId,
            }),
          )
        : asDuplicate(mine),
    );
  });
}

/**
 * Validate and apply one mutation, whatever submitted it: a Hono route, a
 * TanStack Start server function or an MCP tool. Section 20.2 requires them to
 * call the same service, so this is the only entry point that exists.
 */
export async function submitMutation(
  handle: DatabaseHandle,
  context: SyncContext,
  raw: unknown,
): Promise<MutationResult> {
  const mutationId = readMutationId(raw);
  if (mutationId === undefined) {
    throw invalidRequest("Every mutation needs a readable UUIDv7 `mutationId`.");
  }

  const validation = validateMutation(raw);
  if (!validation.ok) {
    // A rejection that never reached an aggregate is still recorded, so that
    // replaying it returns the same error rather than being re-validated
    // against a server that may since have changed its mind.
    return persistRejection(handle, context, raw, mutationId, validation.error);
  }
  return applyOne(handle, context, validation.mutation);
}

/**
 * Store a validation rejection under its `mutationId` and return it. The
 * aggregate columns are best-effort strings: the mutation did not parse, so
 * this is what it claimed rather than what it was.
 */
async function persistRejection(
  handle: DatabaseHandle,
  context: SyncContext,
  raw: unknown,
  mutationId: string,
  error: MueError,
): Promise<MutationResult> {
  const claimed: Record<string, unknown> =
    typeof raw === "object" && raw !== null ? (raw as Record<string, unknown>) : {};
  const result = rejection(mutationId, error);
  return handle.db.transaction(async (tx) => {
    const replay = await storedResult(tx, context.userId, mutationId);
    if (replay !== undefined) return asDuplicate(replay);

    const recorded = await recordMutation(tx, {
      mutationId,
      userId: context.userId,
      aggregateType: typeof claimed["aggregateType"] === "string" ? claimed["aggregateType"] : "",
      aggregateId: typeof claimed["aggregateId"] === "string" ? claimed["aggregateId"] : "",
      // `mutation_log.operation` is constrained to the two known values, so an
      // unparseable one is recorded as an upsert; `status` already says the
      // mutation never ran and `result` carries what actually went wrong.
      operation: claimed["op"] === "delete" ? "delete" : "upsert",
      status: "rejected",
      sequence: null,
      revision: null,
      result,
    });
    if (recorded.recorded) return result;
    const mine = await storedResult(tx, context.userId, mutationId);
    return mine === undefined ? result : asDuplicate(mine);
  });
}

/**
 * A batch, applied in submission order (FR-SYNC-002: the order in the request
 * is the order the outbox queued them).
 *
 * One transaction per mutation, sequentially. One transaction for the batch
 * would make a single rejection roll the whole thing back, which is exactly
 * what FR-SYNC-007 forbids; running them in parallel would let their sequences
 * be assigned in an order the client never asked for.
 */
export async function submitMutations(
  handle: DatabaseHandle,
  context: SyncContext,
  mutations: readonly unknown[],
): Promise<PushResponse> {
  if (mutations.length === 0) {
    throw invalidRequest("A push carries at least one mutation.");
  }
  if (mutations.length > PUSH_MAX_MUTATIONS) {
    throw invalidRequest(`A push carries at most ${PUSH_MAX_MUTATIONS} mutations.`);
  }
  // Every id is read before anything is applied: a batch that cannot be
  // reported on is refused whole, rather than half-applied and unanswerable.
  for (const raw of mutations) {
    if (readMutationId(raw) === undefined) {
      throw invalidRequest("Every mutation needs a readable UUIDv7 `mutationId`.");
    }
  }

  const results: MutationResult[] = [];
  for (const raw of mutations) {
    results.push(await submitMutation(handle, context, raw));
  }
  return { results, serverTime: new Date().toISOString() };
}
