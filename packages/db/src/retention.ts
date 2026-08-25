import { and, isNotNull, lt, sql } from "drizzle-orm";
import type { DatabaseHandle } from "./client";
import { activitySessions, customExercises, measurements, mutationLog } from "./schema/app";

/**
 * FR-SYNC-005: the server keeps a tombstone longer than the longest resume
 * window it supports, and purges only under a documented, tested policy.
 * PLATFORM-CONTRACT decision 6 fixes that window at 180 days *as a
 * configuration value* -- `MUE_RETENTION_DAYS`, read in ./config.ts.
 *
 * `mutation_log` is swept on the same clock. A client offline for longer than
 * the window can no longer prove a mutation was already applied, so its
 * tombstones must not outlive its idempotency keys, nor the reverse.
 *
 * The journal is not swept. It is the audit of replaced versions that sections
 * 13.2 and 13.3 require, and dropping an entry a live cursor still points at
 * would silently skip a change. `health_profile` is single-row and its
 * tombstone is one row per account, so it is not swept either.
 */

export interface PurgeReport {
  readonly cutoff: Date;
  readonly deleted: Readonly<Record<string, number>>;
}

export function retentionCutoff(retentionDays: number, now: Date = new Date()): Date {
  return new Date(now.getTime() - retentionDays * 24 * 60 * 60 * 1000);
}

const one = { one: sql<number>`1` };

/**
 * Delete every tombstone and every mutation-log row older than the cutoff.
 * Idempotent, so it is safe to run from a schedule or by hand.
 */
export async function purgeExpired(
  handle: DatabaseHandle,
  now: Date = new Date(),
): Promise<PurgeReport> {
  const cutoff = retentionCutoff(handle.config.retentionDays, now);
  const { db } = handle;

  const [purgedMeasurements, purgedSessions, purgedExercises, purgedMutations] = await Promise.all([
    db
      .delete(measurements)
      .where(and(isNotNull(measurements.deletedAt), lt(measurements.deletedAt, cutoff)))
      .returning(one),
    db
      .delete(activitySessions)
      .where(and(isNotNull(activitySessions.deletedAt), lt(activitySessions.deletedAt, cutoff)))
      .returning(one),
    db
      .delete(customExercises)
      .where(and(isNotNull(customExercises.deletedAt), lt(customExercises.deletedAt, cutoff)))
      .returning(one),
    db.delete(mutationLog).where(lt(mutationLog.createdAt, cutoff)).returning(one),
  ]);

  return {
    cutoff,
    deleted: {
      measurements: purgedMeasurements.length,
      activity_sessions: purgedSessions.length,
      custom_exercises: purgedExercises.length,
      mutation_log: purgedMutations.length,
    },
  };
}
