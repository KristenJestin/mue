import type { MueError, MutationResult, PullResponse } from "@mue/contracts";
import { type DatabaseHandle, createTestDatabase, migrate, schema, seedUser } from "@mue/db";
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { and, eq } from "drizzle-orm";
import { readChanges } from "./pull";
import { submitMutations } from "./push";
import type { SyncContext } from "./types";

/**
 * PRD section 13.4, against the development PostgreSQL rather than a fake.
 *
 * The rules being tested are properties of what the database and the journal actually do
 * — one row per account, a merge read back out of a journal snapshot, a replaced version
 * that survives — and a test double would prove none of them.
 */

const { healthProfile, syncJournal } = schema;

let handle: DatabaseHandle;
const USER = "user-health-profile-test";
const context: SyncContext = { userId: USER };
const PHONE = { type: "android", id: "device-phone" } as const;
const LAPTOP = { type: "android", id: "device-laptop" } as const;
const AGENT = { type: "agent", id: "agent-claude" } as const;

beforeAll(async () => {
  handle = createTestDatabase();
  await migrate(handle);
});

afterAll(async () => {
  await handle.sql`delete from mue_auth."user" where "id" = ${USER}`;
  await handle.close();
});

beforeEach(async () => {
  await handle.sql`delete from mue_auth."user" where "id" = ${USER}`;
  await seedUser(handle, USER);
});

interface Profile {
  heightCm: number | null;
  birthDate: string | null;
}

function profileUpsert(
  payload: Profile,
  baseRevision: string | null,
  origin: { type: string; id: string } = PHONE,
): unknown {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision,
    origin: { ...origin },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "healthProfile",
    aggregateId: "me",
    op: "upsert",
    payloadSchemaVersion: 1,
    payload,
  };
}

function accepted(result: MutationResult | undefined): { revision: string; sequence: string } {
  if (result === undefined) throw new Error("no result");
  if (result.status === "rejected") {
    throw new Error(`expected an accepted result, got ${JSON.stringify(result.error)}`);
  }
  return { revision: result.revision, sequence: result.sequence };
}

function refused(result: MutationResult | undefined): MueError {
  if (result === undefined) throw new Error("no result");
  if (result.status !== "rejected") throw new Error(`expected a rejection, got ${result.status}`);
  return result.error;
}

async function submit(...mutations: unknown[]): Promise<MutationResult[]> {
  const response = await submitMutations(handle, context, mutations);
  return [...response.results];
}

/** The stored aggregate, as a second device would eventually read it. */
async function stored(): Promise<
  { heightCm: number | null; birthDate: string | null; revision: bigint } | undefined
> {
  const rows = await handle.db
    .select({
      heightCm: healthProfile.heightCm,
      birthDate: healthProfile.birthDate,
      revision: healthProfile.revision,
    })
    .from(healthProfile)
    .where(eq(healthProfile.userId, USER));
  return rows[0];
}

/** Every profile snapshot the journal holds, oldest first. This is the audit trail. */
async function auditedVersions(): Promise<unknown[]> {
  const rows = await handle.db
    .select({ revision: syncJournal.revision, payload: syncJournal.payload })
    .from(syncJournal)
    // Scoped to this test's own account, which is not a detail: the development cluster is
    // shared, and this assertion read every account's profile journal until a live run against
    // a real phone put a second one in the table and turned three passing tests red.
    .where(and(eq(syncJournal.userId, USER), eq(syncJournal.aggregateType, "healthProfile")))
    .orderBy(syncJournal.sequence);
  return rows.map((row) => row.payload);
}

function pull(cursor: string | null): Promise<PullResponse> {
  return readChanges(handle, context, {
    cursor,
    supportedSchemaVersions: { healthProfile: [1], measurement: [1] },
  });
}

describe("section 13.4 — the profile is one aggregate", () => {
  test("a second device updates the row rather than opening a rival to it", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert({ heightCm: 172, birthDate: "1998-11-18" }, "1", LAPTOP));

    const rows = await handle.db
      .select({ userId: healthProfile.userId })
      .from(healthProfile)
      .where(eq(healthProfile.userId, USER));
    expect(rows).toHaveLength(1);
    expect(await stored()).toEqual({ heightCm: 172, birthDate: "1998-11-18", revision: 2n });
  });

  /**
   * The rival is refused *before* it reaches storage, by the envelope's literal
   * `aggregateId`. That is the point of pinning it in the contract rather than checking
   * it here: uniqueness cannot be forgotten by a later handler.
   */
  test("refuses a mutation naming any identifier but the constant", async () => {
    const rogue = profileUpsert({ heightCm: 171, birthDate: null }, null) as Record<
      string,
      unknown
    >;
    rogue["aggregateId"] = "me-2";

    expect(refused((await submit(rogue))[0]).code).toBe("sync.invalid_payload");
    expect(await stored()).toBeUndefined();
  });

  /**
   * Section 13.4 gives the profile no deletion. A tombstone would be a state the domain
   * does not have, and FR-SYNC-005 would then use it to refuse the user's own next edit
   * as a resurrection.
   */
  test("refuses a delete and leaves the aggregate exactly as it was", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null));

    const error = refused(
      (
        await submit({
          mutationId: Bun.randomUUIDv7(),
          baseRevision: "1",
          origin: { ...PHONE },
          clientOccurredAt: new Date().toISOString(),
          aggregateType: "healthProfile",
          aggregateId: "me",
          op: "delete",
          payloadSchemaVersion: 1,
          payload: null,
        })
      )[0],
    );

    expect(error.code).toBe("sync.invalid_payload");
    expect(error.retryable).toBe(false);
    expect(await stored()).toEqual({ heightCm: 171, birthDate: "1998-11-18", revision: 1n });
  });

  test("clearing a field is an upsert stating null, and it is applied", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null));
    await submit(profileUpsert({ heightCm: null, birthDate: "1998-11-18" }, "1"));

    expect(await stored()).toEqual({ heightCm: null, birthDate: "1998-11-18", revision: 2n });
  });
});

describe("section 13.4 — independent fields merge separately", () => {
  /**
   * The loss this whole rule exists to prevent.
   *
   * The laptop sets a birth date. The phone, which has been offline since revision 1 and
   * has never seen it, saves a new height — and section 12.2 makes it send the *whole*
   * aggregate, birth date included, as it last knew it: null. Applied wholesale, that
   * erases the laptop's work. Merged field by field, it does not: the phone's birth date
   * equals the one in the version it was editing, so it is not a statement about that
   * field at all.
   */
  test("a field the author did not touch keeps the concurrent change", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: null }, null, PHONE));
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, "1", LAPTOP));

    await submit(profileUpsert({ heightCm: 172, birthDate: null }, "1", PHONE));

    expect(await stored()).toEqual({ heightCm: 172, birthDate: "1998-11-18", revision: 3n });
  });

  test("and the same holds the other way round, for the height", async () => {
    await submit(profileUpsert({ heightCm: null, birthDate: null }, null, PHONE));
    await submit(profileUpsert({ heightCm: 171, birthDate: null }, "1", PHONE));

    // The agent has only ever seen revision 1, where the height was null.
    await submit(profileUpsert({ heightCm: null, birthDate: "1998-11-18" }, "1", AGENT));

    expect(await stored()).toEqual({ heightCm: 171, birthDate: "1998-11-18", revision: 3n });
  });

  /** Both fields moved, in opposite directions, from the same base. Both survive. */
  test("two origins editing different fields of one base both keep their change", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert({ heightCm: 180, birthDate: "1998-11-18" }, "1", PHONE));
    await submit(profileUpsert({ heightCm: 171, birthDate: "1990-04-12" }, "1", AGENT));

    expect(await stored()).toEqual({ heightCm: 180, birthDate: "1990-04-12", revision: 3n });
  });
});

describe("section 13.4 — a conflict on one field follows the last accepted mutation", () => {
  test("the later mutation wins the field, and the replaced version stays audited", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: null }, null, PHONE));
    await submit(profileUpsert({ heightCm: 180, birthDate: null }, "1", PHONE));
    // The agent also moved the height, from the same base it shared with the phone.
    await submit(profileUpsert({ heightCm: 165, birthDate: null }, "1", AGENT));

    expect((await stored())?.heightCm).toBe(165);

    // "reste audité": every version, including the one that lost, is still in the
    // journal — which `retention.ts` never sweeps.
    expect(await auditedVersions()).toEqual([
      { heightCm: 171, birthDate: null },
      { heightCm: 180, birthDate: null },
      { heightCm: 165, birthDate: null },
    ]);
  });

  /**
   * No base to compare against, so nothing can be shown to be untouched. Section 13.4's
   * conflict rule applies to the whole payload: the last accepted mutation stands.
   */
  test("an author that quoted no base states every field, and states them last", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert({ heightCm: 180, birthDate: null }, null, LAPTOP));

    expect(await stored()).toEqual({ heightCm: 180, birthDate: null, revision: 2n });
  });
});

describe("section 12.3 — what the journal carries for a merged profile", () => {
  /**
   * The journal snapshot is the *merged* result, not the submission. It has to be: it is
   * what the submitting device applies on its own next pull, which is how a device whose
   * value was merged away converges instead of believing its version stood.
   */
  test("the change a client pulls back is the merged aggregate", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: null }, null, PHONE));
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, "1", LAPTOP));
    await submit(profileUpsert({ heightCm: 172, birthDate: null }, "1", PHONE));

    const response = await pull(null);
    if (response.status !== "ok") throw new Error("expected a page");

    const last = response.changes.at(-1);
    expect(last?.aggregateType).toBe("healthProfile");
    expect(last?.aggregateId).toBe("me");
    expect(last?.payload).toEqual({ heightCm: 172, birthDate: "1998-11-18" });
    expect(last?.meta.revision).toBe("3");
    expect(last?.meta.deletedAt).toBeNull();
  });

  test("createdAt is the first accepted version and never moves again", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: null }, null));
    const first = await pull(null);
    if (first.status !== "ok") throw new Error("expected a page");
    const createdAt = first.changes[0]?.meta.createdAt ?? "";

    await submit(profileUpsert({ heightCm: 172, birthDate: null }, "1"));
    const second = await pull(null);
    if (second.status !== "ok") throw new Error("expected a page");

    expect(second.changes.map((change) => change.meta.createdAt)).toEqual([createdAt, createdAt]);
  });

  test("a client that did not declare the profile is told to upgrade, and keeps its cursor", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null));

    const response = await readChanges(handle, context, {
      cursor: null,
      supportedSchemaVersions: { measurement: [1] },
    });

    expect(response.status).toBe("upgrade_required");
    if (response.status !== "upgrade_required") throw new Error("expected an upgrade demand");
    expect(response.error.code).toBe("sync.upgrade_required");
    expect(response).not.toHaveProperty("nextCursor");
  });
});

describe("section 16 — the profile is personal data", () => {
  /**
   * "Les journaux techniques n'enregistrent pas les secrets ni les payloads de santé
   * complets par défaut." A `MueError.message` is shown in `Data & sync` and logged by
   * whoever receives it, so it may name a field and never its value. This checks the
   * rejections a health profile can actually produce, with a real height and a real birth
   * date in the mutation that caused them.
   */
  test("no rejection quotes the height or the birth date it refused", async () => {
    const rogue = profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null) as Record<
      string,
      unknown
    >;
    rogue["aggregateId"] = "me-2";

    const outOfRange = profileUpsert({ heightCm: 19, birthDate: "1998-11-18" }, null);
    const missing = profileUpsert({ heightCm: 171 } as unknown as Profile, null);
    const tooNew = profileUpsert({ heightCm: 171, birthDate: "2101-01-01" }, null);

    const results = await submit(rogue, outOfRange, missing, tooNew);
    const messages = results.map((result) => refused(result).message);

    expect(messages).toHaveLength(4);
    for (const message of messages) {
      expect(message).not.toContain("171");
      expect(message).not.toContain("19");
      expect(message).not.toContain("1998-11-18");
      expect(message).not.toContain("2101-01-01");
    }
    // It still names the field that was missing, which section 14.4 requires.
    expect(refused(results[2]).field).toBe("payload.birthDate");
    expect(refused(results[2]).code).toBe("sync.missing_required_field");
  });

  test("nothing is stored when a payload is refused", async () => {
    await submit(profileUpsert({ heightCm: 19, birthDate: "1998-11-18" }, null));
    expect(await stored()).toBeUndefined();
    expect(await auditedVersions()).toEqual([]);
  });
});

describe("FR-SYNC-006 — the profile replays like every other aggregate", () => {
  test("a resent mutation returns the stored result and merges nothing twice", async () => {
    const mutation = profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null);

    const first = accepted((await submit(mutation))[0]);
    const again = (await submit(mutation))[0];

    expect(again?.status).toBe("duplicate");
    expect(accepted(again)).toEqual(first);
    expect((await stored())?.revision).toBe(1n);
    expect(await auditedVersions()).toHaveLength(1);
  });

  /** One batch, both aggregates, one journal: adding a type changed no batching rule. */
  test("a profile and a measurement travel in one push and both apply", async () => {
    const results = await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null), {
      mutationId: Bun.randomUUIDv7(),
      baseRevision: null,
      origin: { ...PHONE },
      clientOccurredAt: new Date().toISOString(),
      aggregateType: "measurement",
      aggregateId: "2026-08-25",
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: { date: "2026-08-25", weightCg: 7845 },
    });

    expect(accepted(results[0]).sequence).toBe("1");
    expect(accepted(results[1]).sequence).toBe("2");
    expect(await stored()).toEqual({ heightCm: 171, birthDate: "1998-11-18", revision: 1n });
  });
});
