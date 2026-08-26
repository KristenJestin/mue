import type { MueError, MutationResult, PullResponse } from "@mue/contracts";
import {
  type DatabaseHandle,
  appendToJournal,
  createTestDatabase,
  migrate,
  schema,
  seedUser,
} from "@mue/db";
import { and, count, eq } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { decodeCursor } from "./cursor";
import { SyncRequestError } from "./errors";
import { readChanges, readLastAndroidSyncAt } from "./pull";
import { submitMutations } from "./push";
import type { SyncContext } from "./types";

/**
 * These run against the development PostgreSQL, not a fake. Idempotence and
 * ordering are properties of what the database does under concurrency, and a
 * test double would prove nothing about either.
 */

const { measurements, mutationLog, syncJournal } = schema;

let handle: DatabaseHandle;
const USER = "user-sync-domain-test";
const context: SyncContext = { userId: USER };
const DEVICE = { type: "android", id: "device-under-test" } as const;

beforeAll(async () => {
  handle = createTestDatabase();
  // Idempotent, and deliberately not a schema reset: another package's tests
  // may be running against the same disposable cluster.
  await migrate(handle);
});

afterAll(async () => {
  await handle.sql`delete from mue_auth."user" where "id" = ${USER}`;
  await handle.close();
});

beforeEach(async () => {
  // Deleting the account cascades through every synchronised table, the
  // journal, the counter and the mutation log, so each test starts at sequence
  // zero without touching anyone else's rows.
  await handle.sql`delete from mue_auth."user" where "id" = ${USER}`;
  await seedUser(handle, USER);
});

interface Envelope {
  mutationId: string;
  baseRevision: string | null;
  origin: { type: string; id: string };
  clientOccurredAt: string;
  aggregateType: string;
  aggregateId: string;
  op: string;
  payloadSchemaVersion: number;
  payload: unknown;
}

function upsert(date: string, weightCg: number, overrides: Partial<Envelope> = {}): Envelope {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision: null,
    origin: { ...DEVICE },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "measurement",
    aggregateId: date,
    op: "upsert",
    payloadSchemaVersion: 1,
    payload: { date, weightCg },
    ...overrides,
  };
}

function remove(date: string, baseRevision: string | null): Envelope {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision,
    origin: { ...DEVICE },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "measurement",
    aggregateId: date,
    op: "delete",
    payloadSchemaVersion: 1,
    payload: null,
  };
}

function at(results: readonly MutationResult[], index: number): MutationResult {
  const result = results[index];
  if (result === undefined) throw new Error(`no result at ${index}`);
  return result;
}

function accepted(result: MutationResult): { revision: string; sequence: string } {
  if (result.status === "rejected") {
    throw new Error(`expected an accepted result, got ${JSON.stringify(result.error)}`);
  }
  return { revision: result.revision, sequence: result.sequence };
}

function refused(result: MutationResult): MueError {
  if (result.status !== "rejected") {
    throw new Error(`expected a rejection, got ${result.status}`);
  }
  return result.error;
}

function page(response: PullResponse) {
  if (response.status !== "ok") {
    throw new Error(`expected a page, got ${JSON.stringify(response.error)}`);
  }
  return response;
}

function pull(cursor: string | null, limit?: number): Promise<PullResponse> {
  return readChanges(handle, context, {
    cursor,
    supportedSchemaVersions: { measurement: [1] },
    ...(limit === undefined ? {} : { limit }),
  });
}

async function rowsFor(date: string): Promise<number> {
  const rows = await handle.db
    .select({ total: count() })
    .from(measurements)
    .where(and(eq(measurements.userId, USER), eq(measurements.date, date)));
  return rows[0]?.total ?? 0;
}

async function journalLength(): Promise<number> {
  const rows = await handle.db
    .select({ total: count() })
    .from(syncJournal)
    .where(eq(syncJournal.userId, USER));
  return rows[0]?.total ?? 0;
}

describe("FR-SYNC-006 — replaying a mutation", () => {
  test("returns the stored result and does not repeat the effect", async () => {
    const mutation = upsert("2026-08-01", 7250);

    const first = await submitMutations(handle, context, [mutation]);
    const second = await submitMutations(handle, context, [mutation]);

    expect(at(first.results, 0).status).toBe("applied");
    expect(at(second.results, 0).status).toBe("duplicate");
    expect(accepted(at(second.results, 0))).toEqual(accepted(at(first.results, 0)));
    expect(await rowsFor("2026-08-01")).toBe(1);
    expect(await journalLength()).toBe(1);
  });

  test("replays a rejection as the same rejection", async () => {
    // 1 cg is below `Weight`'s domain minimum, so the payload never parses.
    const mutation = upsert("2026-08-02", 1);

    const first = await submitMutations(handle, context, [mutation]);
    const second = await submitMutations(handle, context, [mutation]);

    expect(refused(at(first.results, 0)).code).toBe("sync.invalid_payload");
    expect(at(second.results, 0)).toEqual(at(first.results, 0));
    expect(await rowsFor("2026-08-02")).toBe(0);
    expect(await journalLength()).toBe(0);
  });

  test("consumes no sequence for a rejected mutation", async () => {
    const results = await submitMutations(handle, context, [
      upsert("2026-08-03", 7000),
      upsert("2026-08-04", 1),
      upsert("2026-08-05", 7100),
    ]);
    expect(accepted(at(results.results, 0)).sequence).toBe("1");
    expect(accepted(at(results.results, 2)).sequence).toBe("2");
  });
});

describe("FR-SYNC-007 — a partial failure", () => {
  test("rejects one mutation and applies the rest of the batch", async () => {
    const response = await submitMutations(handle, context, [
      upsert("2026-08-10", 7000),
      upsert("2026-08-11", 999_999),
      upsert("2026-08-12", 7100),
      { ...upsert("2026-08-13", 7200), aggregateType: "recipe" },
      upsert("2026-08-14", 7300),
    ]);

    expect(response.results.map((result) => result.status)).toEqual([
      "applied",
      "rejected",
      "applied",
      "rejected",
      "applied",
    ]);
    expect(refused(at(response.results, 1)).code).toBe("sync.invalid_payload");
    expect(refused(at(response.results, 3)).code).toBe("sync.unknown_aggregate_type");
    expect(await journalLength()).toBe(3);
  });

  test("names the missing field rather than inventing it", async () => {
    const mutation = upsert("2026-08-15", 7000);
    const { payload: _dropped, ...withoutPayload } = mutation;
    const response = await submitMutations(handle, context, [withoutPayload]);

    const error = refused(at(response.results, 0));
    expect(error.code).toBe("sync.missing_required_field");
    expect(error.field).toBe("payload");
  });

  test("refuses the whole batch when a mutation carries no readable id", async () => {
    const broken = { ...upsert("2026-08-16", 7000), mutationId: "not-a-uuid" };
    await expect(
      submitMutations(handle, context, [upsert("2026-08-17", 7000), broken]),
    ).rejects.toThrow(SyncRequestError);
    // Nothing was applied: a batch that cannot be reported on is not half done.
    expect(await journalLength()).toBe(0);
  });
});

describe("section 12.4 — a payload version the peer did not declare", () => {
  test("is rejected on push with an explicit upgrade error", async () => {
    const response = await submitMutations(handle, context, [
      upsert("2026-08-20", 7000, { payloadSchemaVersion: 2 }),
    ]);
    const error = refused(at(response.results, 0));
    expect(error.code).toBe("sync.upgrade_required");
    expect(error.retryable).toBe(false);
  });

  test("stops the pull with no changes and no cursor at all", async () => {
    await submitMutations(handle, context, [upsert("2026-08-21", 7000)]);
    const before = page(await pull(null));
    expect(before.changes).toHaveLength(1);

    // A change this build cannot even express, written straight to the journal.
    await handle.db.transaction(async (tx) =>
      appendToJournal(tx, {
        userId: USER,
        aggregateType: "measurement",
        aggregateId: "2026-08-22",
        operation: "upsert",
        revision: 1n,
        payloadSchemaVersion: 2,
        payload: { date: "2026-08-22", weightCg: 7100, bodyFatPercent: 18.2 },
        deletedAt: null,
        originType: "agent",
        originId: "agent-under-test",
        mutationId: Bun.randomUUIDv7(),
      }),
    );

    const blocked = await pull(before.nextCursor);
    expect(blocked.status).toBe("upgrade_required");
    expect("changes" in blocked).toBe(false);
    // The absent field is what makes it structural: a client that ignores
    // `status` still has nothing to advance to.
    expect("nextCursor" in blocked).toBe(false);

    // The cursor is exactly where it was, and asking again says the same thing:
    // nothing about the client's position moved, so no change was skipped.
    expect(decodeCursor(before.nextCursor).seq).toBe("1");
    const askedAgain = await pull(before.nextCursor);
    expect(askedAgain.status).toBe("upgrade_required");
    expect(page(await pull(null, 1)).changes).toHaveLength(1);
  });

  test("still delivers a tombstone, which carries no payload to misread", async () => {
    await submitMutations(handle, context, [upsert("2026-08-23", 7000)]);
    await handle.db.transaction(async (tx) =>
      appendToJournal(tx, {
        userId: USER,
        aggregateType: "measurement",
        aggregateId: "2026-08-23",
        operation: "delete",
        revision: 2n,
        payloadSchemaVersion: 7,
        payload: null,
        deletedAt: new Date(),
        originType: "agent",
        originId: "agent-under-test",
        mutationId: Bun.randomUUIDv7(),
      }),
    );

    const result = page(await pull(null));
    expect(result.changes.map((change) => change.op)).toEqual(["upsert", "delete"]);
  });
});

describe("section 12.3 — the cursor", () => {
  test("pages, reports hasMore and resumes exactly where it stopped", async () => {
    const dates = ["2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05"];
    await submitMutations(
      handle,
      context,
      dates.map((date, index) => upsert(date, 7000 + index * 5)),
    );

    const first = page(await pull(null, 2));
    expect(first.changes.map((change) => change.aggregateId)).toEqual(dates.slice(0, 2));
    expect(first.hasMore).toBe(true);

    const second = page(await pull(first.nextCursor, 2));
    expect(second.changes.map((change) => change.aggregateId)).toEqual(dates.slice(2, 4));
    expect(second.hasMore).toBe(true);

    const third = page(await pull(second.nextCursor, 2));
    expect(third.changes.map((change) => change.aggregateId)).toEqual(dates.slice(4));
    expect(third.hasMore).toBe(false);

    // Re-requesting a page is safe: FR-SYNC-006 makes a pull a pure read.
    const again = page(await pull(first.nextCursor, 2));
    expect(again.changes).toEqual(second.changes);

    const drained = page(await pull(third.nextCursor, 2));
    expect(drained.changes).toEqual([]);
    expect(drained.hasMore).toBe(false);
    expect(drained.nextCursor).toBe(third.nextCursor);
  });

  test("refuses an unreadable cursor instead of restarting from zero", async () => {
    await expect(pull("bm90LWEtY3Vyc29y")).rejects.toThrow(SyncRequestError);
  });

  test("carries the metadata the phone stores, and a payload snapshot", async () => {
    await submitMutations(handle, context, [upsert("2026-09-10", 7000)]);
    await submitMutations(handle, context, [upsert("2026-09-10", 7500)]);

    const result = page(await pull(null));
    expect(result.changes).toHaveLength(2);
    const [older, newer] = result.changes;
    if (older === undefined || newer === undefined) throw new Error("missing changes");

    // The journal keeps what was accepted, not a pointer to today's row: the
    // replaced version stays auditable (sections 13.1 and 13.2).
    expect(older.payload).toEqual({ date: "2026-09-10", weightCg: 7000 });
    expect(newer.payload).toEqual({ date: "2026-09-10", weightCg: 7500 });
    expect(older.meta.revision).toBe("1");
    expect(newer.meta.revision).toBe("2");
    expect(older.meta.createdAt).toBe(newer.meta.createdAt);
    expect(Date.parse(newer.meta.updatedAt)).toBeGreaterThanOrEqual(
      Date.parse(older.meta.updatedAt),
    );
    expect(newer.meta.deletedAt).toBeNull();
    expect(newer.meta.originType).toBe("android");
  });
});

describe("FR-SYNC-005 — deletions", () => {
  test("writes a tombstone and refuses an offline resurrection", async () => {
    const created = accepted(
      at((await submitMutations(handle, context, [upsert("2026-10-01", 7000)])).results, 0),
    );
    const deleted = accepted(
      at(
        (await submitMutations(handle, context, [remove("2026-10-01", created.revision)])).results,
        0,
      ),
    );
    expect(deleted.revision).toBe("2");

    const stale = await submitMutations(handle, context, [upsert("2026-10-01", 6900)]);
    const error = refused(at(stale.results, 0));
    expect(error.code).toBe("sync.aggregate_deleted");
    expect(error.currentRevision).toBe("2");

    // A restoration is an explicit mutation based on the current tombstone.
    const restored = await submitMutations(handle, context, [
      upsert("2026-10-01", 6900, { baseRevision: "2" }),
    ]);
    expect(accepted(at(restored.results, 0)).revision).toBe("3");

    const changes = page(await pull(null)).changes;
    expect(changes.map((change) => change.op)).toEqual(["upsert", "delete", "upsert"]);
    expect(changes[1]?.meta.deletedAt).not.toBeNull();
    expect(changes[2]?.meta.deletedAt).toBeNull();
  });

  test("accepts a delete for a date the server never received", async () => {
    const response = await submitMutations(handle, context, [remove("2026-10-05", null)]);
    expect(accepted(at(response.results, 0)).revision).toBe("1");
    const changes = page(await pull(null)).changes;
    expect(changes).toHaveLength(1);
    expect(changes[0]?.op).toBe("delete");
    // The tombstone carries no payload, so the placeholder the not-null
    // `weight_cg` column needs never reaches a client.
    expect(changes[0]?.payload).toBeNull();
  });
});

describe("section 13.2 — one measurement per date", () => {
  test("replaces the value and keeps the replaced one in the journal", async () => {
    await submitMutations(handle, context, [upsert("2026-11-01", 7000)]);
    await submitMutations(handle, context, [
      upsert("2026-11-01", 7300, { origin: { type: "agent", id: "agent-under-test" } }),
    ]);

    expect(await rowsFor("2026-11-01")).toBe(1);
    const rows = await handle.db
      .select({ weightCg: measurements.weightCg, revision: measurements.revision })
      .from(measurements)
      .where(and(eq(measurements.userId, USER), eq(measurements.date, "2026-11-01")));
    expect(rows[0]).toEqual({ weightCg: 7300, revision: 2n });
    expect(await journalLength()).toBe(2);
  });
});

describe("FR-SYNC-008 — the age of the last Android state", () => {
  test("is null before a phone has ever written, then moves with it", async () => {
    expect(await readLastAndroidSyncAt(handle, context)).toBeNull();

    await submitMutations(handle, context, [
      upsert("2026-12-01", 7000, { origin: { type: "agent", id: "agent-under-test" } }),
    ]);
    // An agent's write is not a phone synchronising, so it must not be read as one.
    expect(await readLastAndroidSyncAt(handle, context)).toBeNull();
    expect(page(await pull(null)).lastAndroidSyncAt).toBeNull();

    await submitMutations(handle, context, [upsert("2026-12-02", 7100)]);
    const seen = page(await pull(null)).lastAndroidSyncAt;
    expect(seen).not.toBeNull();
    expect(Date.parse(String(seen))).toBeLessThanOrEqual(Date.now() + 1000);
  });
});

describe("ordering under concurrency", () => {
  test("assigns consecutive sequences to overlapping pushes", async () => {
    const dates = [
      "2027-01-01",
      "2027-01-02",
      "2027-01-03",
      "2027-01-04",
      "2027-01-05",
      "2027-01-06",
    ];
    const responses = await Promise.all(
      dates.map((date, index) =>
        submitMutations(handle, context, [upsert(date, 7000 + index * 5)]),
      ),
    );

    const sequences = responses
      .map((response) => Number(accepted(at(response.results, 0)).sequence))
      .sort((left, right) => left - right);
    expect(sequences).toEqual([1, 2, 3, 4, 5, 6]);
  });

  test("never shows a sequence before its predecessor is visible", async () => {
    const dates = Array.from({ length: 8 }, (_, index) => `2027-02-0${index + 1}`);

    const holes: string[] = [];
    let reading = true;
    const reader = (async () => {
      while (reading) {
        const seen = page(await pull(null, 100)).changes.map((change) => Number(change.sequence));
        for (const [index, sequence] of seen.entries()) {
          // A bigserial cursor fails exactly here: 101 commits before 100 and a
          // reader sees [1, 2, 4] with 3 still in flight.
          if (sequence !== index + 1) holes.push(`saw ${seen.join(",")}`);
        }
      }
    })();

    await Promise.all(
      dates.map((date, index) =>
        submitMutations(handle, context, [upsert(date, 7000 + index * 5)]),
      ),
    );
    reading = false;
    await reader;

    expect(holes).toEqual([]);
    expect(await journalLength()).toBe(8);
  });

  test("keeps one mutation_log row for a mutation pushed twice at once", async () => {
    const mutation = upsert("2027-03-01", 7000);
    const [left, right] = await Promise.all([
      submitMutations(handle, context, [mutation]),
      submitMutations(handle, context, [mutation]),
    ]);

    const statuses = [at(left.results, 0).status, at(right.results, 0).status].sort();
    expect(statuses).toEqual(["applied", "duplicate"]);
    expect(accepted(at(left.results, 0))).toEqual(accepted(at(right.results, 0)));

    const logged = await handle.db
      .select({ total: count() })
      .from(mutationLog)
      .where(eq(mutationLog.mutationId, mutation.mutationId));
    expect(logged[0]?.total).toBe(1);
    expect(await journalLength()).toBe(1);
  });
});
