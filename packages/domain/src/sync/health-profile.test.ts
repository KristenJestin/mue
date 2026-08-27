import type { MueError, MutationResult, PullResponse } from "@mue/contracts";
import { type DatabaseHandle, createTestDatabase, migrate, schema, seedUser } from "@mue/db";
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
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
   * No base to compare against, so nothing can be shown to be untouched — but a value the
   * author states is still a value the author states. Section 13.4's conflict rule applies
   * to it: the last accepted mutation stands.
   *
   * The birth date is the other half, and it is the rule this file's next block is about:
   * the laptop states null with no base, which is not a conflict because it is not a
   * statement. See "an author with no base cannot empty a field".
   */
  test("an author that quoted no base states every value it has, and states them last", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert({ heightCm: 180, birthDate: null }, null, LAPTOP));

    expect(await stored()).toEqual({ heightCm: 180, birthDate: "1998-11-18", revision: 2n });
  });
});

/**
 * The defect, and the distinction that closes it.
 *
 * `mue_app.sync_journal` held three entries of `{heightCm: null, birthDate: null}` from three
 * different android origins, each minutes after a *clear app data → pair → open Profile →
 * Save*, each replacing a row that held `171 / 1998-11-18`.
 *
 * Section 12.2 requires an upsert to state the complete aggregate — that is what makes the
 * merge above possible and it is not being weakened here — so a freshly-paired phone with an
 * empty local profile sends exactly the payload a person who cleared both fields sends. The
 * payloads are the same. The difference is that a person clears a field *on a profile they
 * are looking at*, which has a revision their client holds and quotes, and a phone that has
 * never received the aggregate has nothing to quote.
 *
 * So `baseRevision` is the discriminator, and it is evidence rather than a claim: on Android
 * it is filled from `sync_aggregate_state.revision`, a column written only by a completed
 * pull or a completed push. `FirstPushBaseRevisionTest` in the Android suite pins the two
 * bodies that arrive here, byte for byte.
 */
describe("section 12.2 — a complete payload from a client that has seen nothing", () => {
  const FROM_A_FRESHLY_PAIRED_PHONE = { heightCm: null, birthDate: null } as const;

  /**
   * The revisions `cleared-height-save.json` quotes as its base, built by applying three real
   * mutations rather than by writing a row. Revision 3's journal snapshot is what the phone
   * pulled and what its clear is an edit of.
   */
  async function profileAsThePhonePulledIt(): Promise<void> {
    await submit(profileUpsert({ heightCm: 180, birthDate: null }, null, PHONE));
    await submit(profileUpsert({ heightCm: 171, birthDate: null }, "1", PHONE));
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, "2", PHONE));
  }

  /**
   * The bytes an Android phone actually produced, as `FirstPushBaseRevisionTest` proves it
   * does — not a re-statement of them here.
   *
   * The path this closes is the whole one: `ProfileScreen` → `DataStoreUserProfileRepository`
   * → `HealthProfileDao.upsertWithMutation` → `sync_mutations` → `SyncWire.toEnvelope` → these
   * bytes → `submitMutations` → `mue_app.health_profile`. Half of it is Kotlin and half is
   * TypeScript, so the join has to be a file: neither suite can be edited into agreement with
   * itself, and a value that changes anywhere along the path turns one of the two red.
   */
  function fromThePhone(name: string): unknown {
    const path = join(import.meta.dir, "../../../../apps/android/app/src/test/resources", name);
    let text: string;
    try {
      text = readFileSync(path, "utf8").trim();
    } catch {
      // Named rather than left as an ENOENT, because the interesting fact is *whose* file it
      // is: the Android suite asserts these bytes, so moving one silently unlinks the two
      // halves of the path this describe block exists to cover.
      throw new Error(
        `${path} is missing. FirstPushBaseRevisionTest in the Android suite asserts these ` +
          "exact bytes; this test submits them. Both must read the same file.",
      );
    }
    return JSON.parse(text);
  }

  test("a device that has never seen the profile cannot empty it", async () => {
    await profileAsThePhonePulledIt();

    // Cleared app data, paired again, opened Profile, saved. A null base, and two nulls.
    const result = accepted(
      (await submit(fromThePhone("first-push/freshly-paired-empty-save.json")))[0],
    );

    expect(result.revision).toBe("4");
    expect(await stored()).toEqual({ heightCm: 171, birthDate: "1998-11-18", revision: 4n });
  });

  /**
   * The other half, and the one a blunt "refuse an empty profile" rule would have broken.
   * The height goes because the author was editing revision 3 and moved it; the birth date
   * stays because the author was editing revision 3 and did not.
   *
   * The same phone, the same screen, the same repository call — one pull apart.
   */
  test("a person who clears a field quotes the version they were clearing", async () => {
    await profileAsThePhonePulledIt();

    await submit(fromThePhone("first-push/cleared-height-save.json"));

    expect(await stored()).toEqual({ heightCm: null, birthDate: "1998-11-18", revision: 4n });
  });

  /** And clearing *both* is expressible too, for the same reason: there is a base. */
  test("a person who clears both fields quotes it too, and both fields go", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert(FROM_A_FRESHLY_PAIRED_PHONE, "1", PHONE));

    expect(await stored()).toEqual({ heightCm: null, birthDate: null, revision: 2n });
  });

  /**
   * What the rule does **not** protect, asserted rather than left to a comment.
   *
   * A client with no base that carries a *wrong* value still overwrites: `heightCm: 180` is
   * an assertion and this server cannot know it came from a stale seed rather than from a
   * person. Telling those apart is a fact about the client — whether a field was ever
   * edited — and no client has a column for it today.
   */
  test("but a wrong value from the same device still stands, because it is stated", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert({ heightCm: 180, birthDate: null }, null, LAPTOP));

    expect(await stored()).toEqual({ heightCm: 180, birthDate: "1998-11-18", revision: 2n });
  });

  /**
   * The first profile an account ever has. There is no stored aggregate, so there is nothing
   * for a null to yield to and nothing to protect: an empty profile is created, exactly as
   * section 13.4's "le profil existe avant d'être rempli" describes.
   */
  test("the first profile of an account may be empty, and is created", async () => {
    await submit(profileUpsert(FROM_A_FRESHLY_PAIRED_PHONE, null, PHONE));

    expect(await stored()).toEqual({ heightCm: null, birthDate: null, revision: 1n });
  });

  /**
   * A `baseRevision` the journal cannot answer is no base — a server restored from a backup
   * whose revisions the phone has outrun, or a snapshot written by a payload version this
   * build cannot parse. It degrades to the same rule and for the same reason: the server has
   * no third version, so it cannot tell an emptying from an absence.
   *
   * The cost is one round trip, and it is worth naming: the person's clear does not take
   * effect on this attempt. Their next pull gives them a base the journal does hold, and the
   * clear they repeat from it is applied.
   */
  test("a base the journal cannot give back is no base, and protects the same way", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert({ heightCm: null, birthDate: null }, "4096", PHONE));

    expect(await stored()).toEqual({ heightCm: 171, birthDate: "1998-11-18", revision: 2n });
  });

  /**
   * "Reste audité" (rule 3) survives the change. Every version is still journalled, including
   * the one the merge kept rather than replaced, and the snapshot is the merged aggregate —
   * so the phone that sent the empty payload pulls the profile back rather than believing its
   * nulls stood.
   */
  test("the refused emptying is journalled as what was kept, and the phone converges", async () => {
    await submit(profileUpsert({ heightCm: 171, birthDate: "1998-11-18" }, null, PHONE));
    await submit(profileUpsert(FROM_A_FRESHLY_PAIRED_PHONE, null, LAPTOP));

    expect(await auditedVersions()).toEqual([
      { heightCm: 171, birthDate: "1998-11-18" },
      { heightCm: 171, birthDate: "1998-11-18" },
    ]);

    const response = await pull(null);
    if (response.status !== "ok") throw new Error("expected a page");
    const last = response.changes.at(-1);
    expect(last?.payload).toEqual({ heightCm: 171, birthDate: "1998-11-18" });
    expect(last?.meta.originId).toBe(LAPTOP.id);
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
