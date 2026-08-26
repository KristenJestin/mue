import {
  MEASUREMENT_PAYLOAD_VERSION_1,
  type MutationEnvelope,
  type MutationResult,
  type Origin,
} from "@mue/contracts";
import { type DatabaseHandle, schema } from "@mue/db";
import { and, asc, eq, gte, isNull, lte } from "drizzle-orm";
import { submitMutation } from "./push";
import type { SyncContext } from "./types";

const { measurements } = schema;

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
