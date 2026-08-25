import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { DatabaseHandle } from "./client";
import {
  allocateSequence,
  appendToJournal,
  currentSequence,
  readJournalSince,
  recordMutation,
} from "./journal";
import { migrate } from "./migrate";
import { purgeExpired } from "./retention";
import { measurements, mutationLog, syncJournal } from "./schema/app";
import { createTestDatabase, resetSchemas, seedUser } from "./testing";

let handle: DatabaseHandle;
const USER = "user-journal-test";

beforeAll(async () => {
  handle = createTestDatabase();
  // Section 22.6, first bullet: every migration applied to an empty database.
  await resetSchemas(handle);
  const result = await migrate(handle);
  expect(result.applied.length).toBeGreaterThan(0);
  await seedUser(handle, USER);
});

afterAll(async () => {
  await handle.close();
});

describe("migrations", () => {
  test("re-running applies nothing", async () => {
    const again = await migrate(handle);
    expect(again.applied).toEqual([]);
    expect(again.alreadyApplied.length).toBeGreaterThan(0);
  });

  test("an applied migration is immutable", async () => {
    const folder = await mkdtemp(join(tmpdir(), "mue-mig-"));
    // Same tag as the one already recorded, different content.
    const [tag] = (await migrate(handle)).alreadyApplied;
    expect(tag).toBeDefined();
    await writeFile(join(folder, `${tag}.sql`), "select 1;\n");
    await expect(migrate(handle, folder)).rejects.toThrow(/already applied with a different/);
  });
});

describe("the sequence", () => {
  test("serialises concurrent appends instead of leaving a gap", async () => {
    const before = await currentSequence(handle, USER);
    const order: string[] = [];

    // Two transactions overlap. The first takes the counter row lock and holds
    // it; the second must wait for the commit rather than take the next value
    // and race it to disk, which is exactly the bigserial failure.
    let releaseFirst: () => void = () => {};
    const firstHolds = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const first = handle.db.transaction(async (tx) => {
      const seq = await allocateSequence(tx, USER);
      order.push(`first allocated ${seq}`);
      await firstHolds;
      order.push("first committing");
      return seq;
    });

    // Give the first transaction time to take the lock.
    await Bun.sleep(50);

    const second = handle.db.transaction(async (tx) => {
      const seq = await allocateSequence(tx, USER);
      order.push(`second allocated ${seq}`);
      return seq;
    });

    await Bun.sleep(100);
    // The second is blocked: nothing of it has run yet.
    expect(order).toEqual([`first allocated ${before + 1n}`]);

    releaseFirst();
    const [firstSeq, secondSeq] = await Promise.all([first, second]);

    expect(firstSeq).toBe(before + 1n);
    expect(secondSeq).toBe(before + 2n);
    expect(order).toEqual([
      `first allocated ${before + 1n}`,
      "first committing",
      `second allocated ${before + 2n}`,
    ]);
  });

  test("a change is readable only with every lower sequence", async () => {
    const start = await currentSequence(handle, USER);
    for (const value of [70_00, 71_50, 69_80]) {
      await handle.db.transaction(async (tx) => {
        await appendToJournal(tx, {
          userId: USER,
          aggregateType: "measurement",
          aggregateId: `2026-01-0${value % 7}`,
          operation: "upsert",
          revision: 1n,
          payloadSchemaVersion: 1,
          payload: { weightCg: value },
          deletedAt: null,
          originType: "android",
          originId: "device-1",
          mutationId: crypto.randomUUID(),
        });
      });
    }

    const page = await readJournalSince(handle, USER, start, 10);
    expect(page.map((entry) => entry.sequence)).toEqual([start + 1n, start + 2n, start + 3n]);
    expect(page.map((entry) => (entry.payload as { weightCg: number }).weightCg)).toEqual([
      7000, 7150, 6980,
    ]);

    // A cursor inside the page returns the rest, never a repeat.
    const rest = await readJournalSince(handle, USER, start + 1n, 10);
    expect(rest.map((entry) => entry.sequence)).toEqual([start + 2n, start + 3n]);
  });
});

describe("mutation_log", () => {
  test("a replayed mutation returns the stored result and does not repeat", async () => {
    const mutationId = crypto.randomUUID();
    const first = await handle.db.transaction(async (tx) => {
      const sequence = await appendToJournal(tx, {
        userId: USER,
        aggregateType: "measurement",
        aggregateId: "2026-02-01",
        operation: "upsert",
        revision: 4n,
        payloadSchemaVersion: 1,
        payload: { weightCg: 7210 },
        deletedAt: null,
        originType: "agent",
        originId: "agent-1",
        mutationId,
      });
      return recordMutation(tx, {
        mutationId,
        userId: USER,
        aggregateType: "measurement",
        aggregateId: "2026-02-01",
        operation: "upsert",
        status: "applied",
        sequence,
        revision: 4n,
        result: { status: "applied", revision: "4", sequence: sequence.toString() },
      });
    });

    expect(first.recorded).toBe(true);

    const journalBefore = await handle.db.$count(syncJournal);

    // The same mutation arrives again, as a retried push would send it.
    const replay = await handle.db.transaction((tx) =>
      recordMutation(tx, {
        mutationId,
        userId: USER,
        aggregateType: "measurement",
        aggregateId: "2026-02-01",
        operation: "upsert",
        status: "applied",
        sequence: 999n,
        revision: 99n,
        result: { status: "applied", revision: "99", sequence: "999" },
      }),
    );

    expect(replay.recorded).toBe(false);
    // Verbatim: the second call's own result is discarded.
    expect(replay.result).toEqual(first.result);
    expect(await handle.db.$count(syncJournal)).toBe(journalBefore);
  });

  test("a rejection replays as the same rejection", async () => {
    const mutationId = crypto.randomUUID();
    const rejection = {
      status: "rejected",
      error: { code: "sync.conflict", retryable: false },
    };
    const write = () =>
      handle.db.transaction((tx) =>
        recordMutation(tx, {
          mutationId,
          userId: USER,
          aggregateType: "activitySession",
          aggregateId: "5f0f4f9f-0000-4000-8000-000000000000",
          operation: "upsert",
          status: "rejected",
          sequence: null,
          revision: null,
          result: rejection,
        }),
      );

    expect((await write()).recorded).toBe(true);
    const second = await write();
    expect(second.recorded).toBe(false);
    expect(second.result).toEqual(rejection);
  });
});

describe("retention", () => {
  test("purges tombstones and mutation rows past the window, and nothing else", async () => {
    const days = handle.config.retentionDays;
    expect(days).toBe(180);
    const old = new Date(Date.now() - (days + 1) * 24 * 60 * 60 * 1000);
    const recent = new Date(Date.now() - 24 * 60 * 60 * 1000);

    await handle.db.insert(measurements).values([
      {
        userId: USER,
        date: "2020-01-01",
        weightCg: 7000,
        revision: 2n,
        createdAt: old,
        updatedAt: old,
        deletedAt: old,
        originType: "android",
        originId: "device-1",
        lastMutationId: crypto.randomUUID(),
        payloadSchemaVersion: 1,
      },
      {
        userId: USER,
        date: "2020-01-02",
        weightCg: 7010,
        revision: 2n,
        createdAt: old,
        updatedAt: recent,
        deletedAt: recent,
        originType: "android",
        originId: "device-1",
        lastMutationId: crypto.randomUUID(),
        payloadSchemaVersion: 1,
      },
      {
        userId: USER,
        date: "2020-01-03",
        weightCg: 7020,
        revision: 1n,
        createdAt: old,
        updatedAt: old,
        deletedAt: null,
        originType: "android",
        originId: "device-1",
        lastMutationId: crypto.randomUUID(),
        payloadSchemaVersion: 1,
      },
    ]);

    await handle.db.insert(mutationLog).values({
      mutationId: crypto.randomUUID(),
      userId: USER,
      aggregateType: "measurement",
      aggregateId: "2020-01-01",
      operation: "delete",
      status: "applied",
      sequence: null,
      revision: null,
      result: {},
      createdAt: old,
    });

    const journalBefore = await handle.db.$count(syncJournal);
    const report = await purgeExpired(handle);

    expect(report.deleted.measurements).toBe(1);
    expect(report.deleted.mutation_log).toBeGreaterThanOrEqual(1);
    // A live row and a fresh tombstone survive; so does the journal.
    expect(await handle.db.$count(measurements)).toBe(2);
    expect(await handle.db.$count(syncJournal)).toBe(journalBefore);
  });
});

describe("a populated database", () => {
  test("takes a later migration without losing a row", async () => {
    // Section 22.6, second bullet. A new additive migration is applied by the
    // real runner to the database the tests above filled.
    const before = {
      journal: await handle.db.$count(syncJournal),
      measurements: await handle.db.$count(measurements),
      mutations: await handle.db.$count(mutationLog),
    };
    expect(before.journal).toBeGreaterThan(0);
    expect(before.measurements).toBeGreaterThan(0);

    const folder = await mkdtemp(join(tmpdir(), "mue-mig-"));
    const tag = "9999_additive_probe";
    await writeFile(
      join(folder, `${tag}.sql`),
      'ALTER TABLE "mue_app"."measurements" ADD COLUMN "probe" text;\n',
    );

    const result = await migrate(handle, folder);
    expect(result.applied).toEqual([tag]);

    expect(await handle.db.$count(syncJournal)).toBe(before.journal);
    expect(await handle.db.$count(measurements)).toBe(before.measurements);
    expect(await handle.db.$count(mutationLog)).toBe(before.mutations);

    const probed = await handle.sql<{ probe: string | null }[]>`
      select probe from mue_app.measurements limit 1
    `;
    expect(probed[0]?.probe ?? null).toBeNull();

    // Leave the schema as the committed migrations describe it.
    await handle.sql`alter table mue_app.measurements drop column probe`;
    await handle.sql`delete from mue_app.__mue_migrations where tag = ${tag}`;
  });
});
