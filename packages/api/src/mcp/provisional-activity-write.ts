import type { DatabaseHandle, Transaction } from "@mue/db";
import { appendToJournal, recordMutation, schema } from "@mue/db";
import { eq } from "drizzle-orm";
import type { ActivitySessionView } from "./activity";
import type { CreateActivityCommand, CreateActivityResult } from "./services";

/**
 * SCAFFOLDING. Delete this file the day `@mue/domain` exports the activity write.
 *
 * PLATFORM-CONTRACT section 5 and PRD section 20.2 say a rule has exactly one
 * implementation, in `packages/domain`, called by the Hono routes, the server
 * functions and the MCP tools alike. That package currently exports cursor encoding
 * and nothing else -- the sync agent is writing the mutation pipeline in parallel --
 * so `./domain-bridge.ts` prefers the domain function the moment it appears and falls
 * back to this file until then. Without it the vertical slice could not be run at all,
 * which is the one thing section 24 asks to see before the engine is generalised.
 *
 * It is kept as small as a correct write can be, and it deliberately implements no
 * rule that could disagree with the real one:
 *
 *  - a created session is always a new aggregate, so its revision is 1 and there is no
 *    conflict to resolve, no base revision to compare and no merge to perform;
 *  - the sequence, the journal append and the mutation log are `@mue/db`'s own
 *    primitives, which that package documents as "mechanics, not rules";
 *  - FR-SYNC-006 is enforced by claiming the mutation id before anything is applied,
 *    so a replay returns the stored result rather than writing a second session.
 */

const PAYLOAD_SCHEMA_VERSION = 1;

/** The result shape stored in `mutation_log` and returned verbatim on a replay. */
interface StoredResult {
  readonly activity: ActivitySessionView;
}

function toView(command: CreateActivityCommand, revision: bigint, now: Date): ActivitySessionView {
  return {
    ...command.payload,
    revision: revision.toString(),
    createdAt: now.toISOString(),
    updatedAt: now.toISOString(),
    deletedAt: null,
    originType: "agent",
    originId: command.originId,
    lastMutationId: command.mutationId,
  };
}

async function claimMutation(
  tx: Transaction,
  command: CreateActivityCommand,
  placeholder: StoredResult,
): Promise<{ readonly claimed: boolean; readonly stored: StoredResult }> {
  const outcome = await recordMutation(tx, {
    mutationId: command.mutationId,
    userId: command.userId,
    aggregateType: "activitySession",
    aggregateId: command.payload.id,
    operation: "upsert",
    status: "applied",
    sequence: null,
    revision: null,
    result: placeholder,
  });
  return { claimed: outcome.recorded, stored: outcome.result as StoredResult };
}

export async function provisionalCreateActivitySession(
  database: DatabaseHandle,
  command: CreateActivityCommand,
): Promise<CreateActivityResult> {
  const now = new Date();

  return database.db.transaction(async (tx) => {
    const view = toView(command, 1n, now);

    // The mutation id is claimed before a single row is written. Losing the race
    // means the mutation already ran, and FR-SYNC-006 requires the earlier result
    // back untouched rather than a second session with a second id.
    const claim = await claimMutation(tx, command, { activity: view });
    if (!claim.claimed) return { activity: claim.stored.activity, created: false };

    await tx.insert(schema.activitySessions).values({
      userId: command.userId,
      id: view.id,
      movement: view.movement,
      customMovementName: view.customMovementName,
      environment: view.environment,
      startedOn: view.startedOn,
      startedAtTime: view.startedAtTime,
      durationSeconds: view.durationSeconds,
      perceivedEffort: view.perceivedEffort,
      notes: view.notes,
      source: view.source,
      metrics: view.metrics,
      equipment: view.equipment,
      exercises: view.exercises,
      revision: 1n,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: "agent",
      originId: command.originId,
      lastMutationId: command.mutationId,
      payloadSchemaVersion: PAYLOAD_SCHEMA_VERSION,
    });

    const sequence = await appendToJournal(tx, {
      userId: command.userId,
      aggregateType: "activitySession",
      aggregateId: view.id,
      operation: "upsert",
      revision: 1n,
      payloadSchemaVersion: PAYLOAD_SCHEMA_VERSION,
      payload: command.payload,
      deletedAt: null,
      originType: "agent",
      originId: command.originId,
      mutationId: command.mutationId,
    });

    await tx
      .update(schema.mutationLog)
      .set({ sequence, revision: 1n })
      .where(eq(schema.mutationLog.mutationId, command.mutationId));

    return { activity: view, created: true };
  });
}
