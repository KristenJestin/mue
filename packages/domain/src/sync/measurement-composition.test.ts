import type { MeasurementPayloadV1, MueError, MutationResult, PullResponse } from "@mue/contracts";
import { type DatabaseHandle, createTestDatabase, migrate, schema, seedUser } from "@mue/db";
import { and, eq } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { readChanges } from "./pull";
import { submitMutations } from "./push";
import type { SyncContext } from "./types";

/**
 * PRD_SCALE 22 — the provenance, the impedance and the optional composition a weighing now
 * carries — against the development PostgreSQL rather than a fake.
 *
 * Everything asserted here is a property of what the database actually does: a child row that
 * disappears with its parent, a delete that fires no cascade because it is an `UPDATE`, a
 * journal snapshot a client will apply verbatim. A test double would prove none of it, and the
 * regression this file exists to catch lives precisely in the gap between what a handler
 * *writes* and what it *echoes*.
 */

const { bodyComposition, measurements } = schema;

let handle: DatabaseHandle;
const USER = "user-measurement-composition-test";
const context: SyncContext = { userId: USER };
const PHONE = { type: "android", id: "device-phone" } as const;
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
  // Deleting the account cascades through `measurements` and, from there, through
  // `body_composition` — which is itself the first assertion this file makes about the
  // foreign key, made on every single test.
  await handle.sql`delete from mue_auth."user" where "id" = ${USER}`;
  await seedUser(handle, USER);
});

const DATE = "2026-08-25";

/**
 * The weighing of `@mue/contracts`' own `measurement-v1-valid.json` fixture: 78.45 kg at
 * 171 cm, 27 years old, male, 520 ohm.
 *
 * Its four estimates are not decoration and are not invented. They are what PRD_SCALE 13.2's
 * published equations give for those inputs, which is what makes "read back identically" a
 * meaningful assertion rather than a tautology: the server recalculates this payload on the way
 * in, so a fixture whose numbers were made up would come back *corrected* and every test below
 * would still be green while agreeing with no publication.
 */
const FROM_A_SCALE = {
  date: DATE,
  weightCg: 7_845,
  sourceType: "scale",
  impedanceOhm: 520,
  bodyComposition: {
    formulaId: "mue-foot-to-foot-v1",
    formulaVersion: 1,
    inputWeightCg: 7_845,
    inputHeightCm: 171,
    inputAgeYears: 27,
    inputSex: "male",
    bodyFatDeciPercent: 290,
    fatFreeMassCg: 5_567,
    bodyWaterDeciPercent: 519,
    restingEnergyKcal: 1_723,
  },
} as const satisfies MeasurementPayloadV1;

/** The same date, entered by hand: a complete payload that states none of the three fields. */
const TYPED_BY_HAND = { date: DATE, weightCg: 7_800 } as const satisfies MeasurementPayloadV1;

function upsert(
  payload: MeasurementPayloadV1,
  origin: { type: string; id: string } = PHONE,
  baseRevision: string | null = null,
): unknown {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision,
    origin: { ...origin },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "measurement",
    aggregateId: payload.date,
    op: "upsert",
    payloadSchemaVersion: 1,
    payload,
  };
}

function remove(date: string, baseRevision: string | null): unknown {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision,
    origin: { ...PHONE },
    clientOccurredAt: new Date().toISOString(),
    aggregateType: "measurement",
    aggregateId: date,
    op: "delete",
    payloadSchemaVersion: 1,
    payload: null,
  };
}

async function submit(...mutations: unknown[]): Promise<MutationResult[]> {
  const response = await submitMutations(handle, context, mutations);
  return [...response.results];
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

/** The stored weighing: the three columns PRD_SCALE 22 added, plus what says it is alive. */
async function storedMeasurement(date: string = DATE): Promise<
  | {
      weightCg: number;
      sourceType: string;
      impedanceOhm: number | null;
      deleted: boolean;
    }
  | undefined
> {
  const rows = await handle.db
    .select({
      weightCg: measurements.weightCg,
      sourceType: measurements.sourceType,
      impedanceOhm: measurements.impedanceOhm,
      deletedAt: measurements.deletedAt,
    })
    .from(measurements)
    .where(and(eq(measurements.userId, USER), eq(measurements.date, date)));
  const row = rows[0];
  return row === undefined
    ? undefined
    : {
        weightCg: row.weightCg,
        sourceType: row.sourceType,
        impedanceOhm: row.impedanceOhm,
        deleted: row.deletedAt !== null,
      };
}

/**
 * The stored composition, without the two columns that are its parent's identity.
 *
 * What is left is exactly the ten fields of PRD_SCALE 21.1, in the shape the wire states them,
 * so it can be compared against a payload directly. There is nothing else to strip: this table
 * carries no section 12.1 metadata, and that absence is itself asserted below.
 */
async function storedComposition(date: string = DATE): Promise<unknown> {
  const rows = await handle.db
    .select()
    .from(bodyComposition)
    .where(and(eq(bodyComposition.userId, USER), eq(bodyComposition.date, date)));
  const row = rows[0];
  if (row === undefined) return undefined;
  const { userId: _account, date: _parent, ...composition } = row;
  return composition;
}

function page(response: PullResponse) {
  if (response.status !== "ok") {
    throw new Error(`expected a page, got ${JSON.stringify(response)}`);
  }
  return response;
}

/** Everything this account would receive on a full initial sync (FR-SYNC-003). */
async function pullAll(): Promise<PullResponse> {
  return readChanges(handle, context, {
    cursor: null,
    supportedSchemaVersions: { measurement: [1], healthProfile: [1] },
  });
}

/** The payload of the most recent change, which is what a phone applies last. */
async function lastPayload(): Promise<unknown> {
  const changes = page(await pullAll()).changes;
  const last = changes.at(-1);
  if (last === undefined) throw new Error("no change to read back");
  return last.payload;
}

describe("PRD_SCALE 22 — a weighing carries what the scale measured", () => {
  test("impedance and composition are stored, and read back unchanged", async () => {
    accepted((await submit(upsert(FROM_A_SCALE)))[0]);

    expect(await storedMeasurement()).toEqual({
      weightCg: 7_845,
      sourceType: "scale",
      impedanceOhm: 520,
      deleted: false,
    });
    expect(await storedComposition()).toEqual(FROM_A_SCALE.bodyComposition);
    expect(await lastPayload()).toEqual(FROM_A_SCALE);
  });

  /**
   * FR-BODY-004 and BR-SCALE-008, and the case that matters most.
   *
   * The impedance is the only quantity the scale actually measured that Mue cannot recompute,
   * and the ordinary state of the first weighings is *exactly* this one: a usable impedance and
   * no composition, because the profile has no sex yet. Filing the impedance under the
   * composition would lose it precisely here, and lose it for ever — FR-BODY-006's retroactive
   * calculation would then have nothing to work from on any client.
   */
  test("the impedance survives a weighing that has no composition", async () => {
    const noProfileYet = {
      date: DATE,
      weightCg: 7_845,
      sourceType: "scale",
      impedanceOhm: 520,
    } as const satisfies MeasurementPayloadV1;

    accepted((await submit(upsert(noProfileYet)))[0]);

    expect(await storedMeasurement()).toEqual({
      weightCg: 7_845,
      sourceType: "scale",
      impedanceOhm: 520,
      deleted: false,
    });
    expect(await storedComposition()).toBeUndefined();
    expect(await lastPayload()).toEqual(noProfileYet);
  });

  /**
   * A payload from a build that predates this module is still complete and still valid, which is
   * the whole claim that made extending payload version 1 preferable to minting a version 2.
   * `source_type` back-fills to `manual`, exactly as Android's own migration does.
   */
  test("a payload stating none of the three fields is complete, and reads as manual", async () => {
    accepted((await submit(upsert(TYPED_BY_HAND)))[0]);

    expect(await storedMeasurement()).toEqual({
      weightCg: 7_800,
      sourceType: "manual",
      impedanceOhm: null,
      deleted: false,
    });
    // Absent on the wire, `manual` in the column: the payload comes back as it was sent, with
    // no field the author never stated.
    expect(await lastPayload()).toEqual(TYPED_BY_HAND);
    expect(await lastPayload()).not.toHaveProperty("sourceType");
  });

  /**
   * PRD_SCALE 22: a composition is not an aggregate. It has no revision, no origin and no
   * tombstone of its own, and this is that decision asserted against the actual columns rather
   * than against the schema file — a mirror table that had quietly grown section 12.1 metadata
   * would be a second revision for one aggregate.
   */
  test("the composition row carries no synchronisation metadata of its own", async () => {
    await submit(upsert(FROM_A_SCALE));

    const composition = await storedComposition();
    expect(Object.keys(composition as object).sort()).toEqual(
      Object.keys(FROM_A_SCALE.bodyComposition).sort(),
    );
  });
});

describe("BR-SCALE-007 — a complete payload without a composition removes the stored one", () => {
  test("a manual correction of the same date takes the composition and the impedance with it", async () => {
    const created = accepted((await submit(upsert(FROM_A_SCALE)))[0]);
    expect(await storedComposition()).not.toBeUndefined();

    accepted((await submit(upsert(TYPED_BY_HAND, PHONE, created.revision)))[0]);

    expect(await storedComposition()).toBeUndefined();
    expect(await storedMeasurement()).toEqual({
      weightCg: 7_800,
      sourceType: "manual",
      impedanceOhm: null,
      deleted: false,
    });
    // And the echo says so, so the phone that did not author it converges on the removal.
    expect(await lastPayload()).not.toHaveProperty("bodyComposition");
  });

  test("deleting the weighing deletes its composition", async () => {
    const created = accepted((await submit(upsert(FROM_A_SCALE)))[0]);

    accepted((await submit(remove(DATE, created.revision)))[0]);

    // The measurement survives as a tombstone — FR-SYNC-005 — so no row cascade fires and the
    // composition would have outlived it had the handler relied on one.
    expect(await storedMeasurement()).toMatchObject({ deleted: true });
    expect(await storedComposition()).toBeUndefined();
  });

  test("restoring a deleted date does not bring the old composition back", async () => {
    const created = accepted((await submit(upsert(FROM_A_SCALE)))[0]);
    const deleted = accepted((await submit(remove(DATE, created.revision)))[0]);

    accepted((await submit(upsert(TYPED_BY_HAND, PHONE, deleted.revision)))[0]);

    expect(await storedMeasurement()).toMatchObject({ deleted: false, weightCg: 7_800 });
    expect(await storedComposition()).toBeUndefined();
  });
});

/**
 * PRD_SCALE 22: *"pour une écriture MCP comportant une impédance et les entrées requises, le
 * serveur recalcule les résultats avec la version demandée et rejette toute version inconnue.
 * Les valeurs dérivées fournies par le client ne font pas autorité."*
 *
 * The arbitration `measurement.ts` argues for is asserted here in three parts: the check runs on
 * every write and not only on an agent's; an unknown formula set is the one refusal that costs
 * the whole mutation; and a divergence is corrected rather than refused, because the weight and
 * the impedance are what cannot be recomputed.
 */
describe("PRD_SCALE 22 — the server recalculates and does not take derived values on trust", () => {
  function withComposition(patch: Record<string, unknown>): MeasurementPayloadV1 {
    return {
      ...FROM_A_SCALE,
      bodyComposition: { ...FROM_A_SCALE.bodyComposition, ...patch },
    } as MeasurementPayloadV1;
  }

  test("an unknown formula version is rejected, and nothing at all is written", async () => {
    const error = refused((await submit(upsert(withComposition({ formulaVersion: 2 }))))[0]);

    expect(error.code).toBe("sync.invalid_payload");
    expect(error.retryable).toBe(false);
    expect(error.field).toBe("payload.bodyComposition.formulaId");
    expect(await storedMeasurement()).toBeUndefined();
    expect(await storedComposition()).toBeUndefined();
    expect(page(await pullAll()).changes).toEqual([]);
  });

  test("an unknown formula identifier is rejected the same way", async () => {
    const error = refused(
      (await submit(upsert(withComposition({ formulaId: "vendor-secret-v3" }), AGENT)))[0],
    );

    expect(error.code).toBe("sync.invalid_payload");
    expect(await storedMeasurement()).toBeUndefined();
  });

  /**
   * The refusal names the formula set and nothing else. PRD section 16 keeps complete health
   * payloads out of anything that gets logged, and every number in a composition is one.
   */
  test("the refusal quotes the formula set and no measured value", async () => {
    const error = refused((await submit(upsert(withComposition({ formulaVersion: 9 }))))[0]);

    expect(error.message).toContain("mue-foot-to-foot-v1");
    for (const secret of ["7845", "520", "171", "5567", "290", "1723", DATE]) {
      expect(error.message).not.toContain(secret);
    }
  });

  test("derived values a client got wrong are replaced by the server's own", async () => {
    // A believable lie: 12.0 % body fat rather than 29.0 %, everything else untouched.
    accepted((await submit(upsert(withComposition({ bodyFatDeciPercent: 120 }))))[0]);

    expect(await storedComposition()).toEqual(FROM_A_SCALE.bodyComposition);
    // Journalled as what was accepted, so the author converges on it instead of believing its
    // own number stood — the same mechanism a merged health profile uses.
    expect(await lastPayload()).toEqual(FROM_A_SCALE);
  });

  /**
   * The scope of the arbitration, asserted rather than argued: `origin.type` is a field of the
   * envelope that its author fills in, so a rule that read it would be opt-out by spelling.
   */
  test("the correction applies to a phone's push exactly as to an agent's write", async () => {
    const lying = withComposition({ fatFreeMassCg: 4_000, restingEnergyKcal: 900 });

    accepted((await submit(upsert(lying, PHONE)))[0]);
    expect(await storedComposition()).toEqual(FROM_A_SCALE.bodyComposition);

    await handle.sql`delete from mue_auth."user" where "id" = ${USER}`;
    await seedUser(handle, USER);

    accepted((await submit(upsert(lying, AGENT)))[0]);
    expect(await storedComposition()).toEqual(FROM_A_SCALE.bodyComposition);
  });

  /**
   * FR-BODY-001: when an input is missing or the weighing falls outside the domain the equation
   * was developed in, *"le poids est enregistré normalement et la composition est simplement
   * absente"*. Refusing the mutation instead would cost the weight and the impedance — the two
   * things that were measured — for a fault in a value the server computes itself.
   */
  test("a composition the equations refuse is dropped, and the weighing stands", async () => {
    // A composition whose parent carries no impedance: there is nothing it can be a composition
    // *of* (BR-SCALE-008).
    const orphaned: MeasurementPayloadV1 = {
      date: DATE,
      weightCg: FROM_A_SCALE.weightCg,
      sourceType: "scale",
      bodyComposition: { ...FROM_A_SCALE.bodyComposition },
    };

    accepted((await submit(upsert(orphaned)))[0]);

    expect(await storedMeasurement()).toEqual({
      weightCg: 7_845,
      sourceType: "scale",
      impedanceOhm: null,
      deleted: false,
    });
    expect(await storedComposition()).toBeUndefined();
    expect(await lastPayload()).not.toHaveProperty("bodyComposition");
  });

  test("and the same for a snapshot outside FR-BODY-001's age domain", async () => {
    accepted((await submit(upsert(withComposition({ inputAgeYears: 17 }))))[0]);

    expect(await storedMeasurement()).toMatchObject({ impedanceOhm: 520, weightCg: 7_845 });
    expect(await storedComposition()).toBeUndefined();
  });

  /**
   * FR-BODY-004: the snapshot is testimony about a past day and the server does not rewrite it.
   * Only the four derived values are arithmetic, and only arithmetic is redone.
   */
  test("the snapshot inputs are kept as stated, and only the estimates are recomputed", async () => {
    // A profile that really is 165 cm and female, weighed at the same 78.45 kg and 520 ohm, with
    // the four estimates deliberately wrong.
    const otherProfile = withComposition({
      inputHeightCm: 165,
      inputSex: "female",
      inputAgeYears: 40,
      bodyFatDeciPercent: 111,
      fatFreeMassCg: 4_444,
      bodyWaterDeciPercent: 222,
      restingEnergyKcal: 999,
    });

    accepted((await submit(upsert(otherProfile)))[0]);

    expect(await storedComposition()).toMatchObject({
      inputHeightCm: 165,
      inputSex: "female",
      inputAgeYears: 40,
    });
    // Recomputed from that snapshot, not from the fixture's: a different profile gives different
    // estimates, and none of the four the client stated survives.
    const composition = (await storedComposition()) as Record<string, number>;
    expect(composition["bodyFatDeciPercent"]).not.toBe(111);
    expect(composition["fatFreeMassCg"]).not.toBe(4_444);
    expect(composition["bodyWaterDeciPercent"]).not.toBe(222);
    expect(composition["restingEnergyKcal"]).not.toBe(999);
  });
});

/**
 * The regression this file was written for.
 *
 * `SyncStore.applyMeasurementUpsert` on Android used to carry a provisional rule — a change
 * repeating a weight it already held touched nothing — because the payload was known to be
 * partial and applying BR-SCALE-007 to it would have erased an irreplaceable impedance. Commit
 * a8a5085 put the three fields on the wire and lifted that rule, so a descending payload with no
 * `bodyComposition` now removes the composition of its date, to the letter.
 *
 * That makes the server's echo load-bearing. A handler that persists a weight and drops the rest
 * answers every push with a payload that is *complete and empty*, the phone reads it as an
 * erasure, and the impedance is gone from every device with nothing to restore it from. The two
 * halves go together, and the assertion below is the join: what a device pushes is what comes
 * back down, field for field, and what the server holds is the same thing.
 */
describe("the round trip — what a device pushes comes back identical", () => {
  test("a full weighing survives push, storage and pull with nothing lost", async () => {
    accepted((await submit(upsert(FROM_A_SCALE)))[0]);

    const changes = page(await pullAll()).changes;
    expect(changes).toHaveLength(1);
    const change = changes[0];
    if (change === undefined) throw new Error("no change");

    // What descends is what ascended. Not a subset of it, and not a superset either: a payload
    // that gained a `sourceType` the author never stated would be the same defect in reverse.
    expect(change.payload).toEqual(FROM_A_SCALE);
    expect(change.op).toBe("upsert");
    expect(change.meta.originType).toBe("android");

    // And what the *server* holds is that same weighing, not a weight with the rest discarded.
    // This half is what a journal-only assertion cannot see: the journal echoes a payload
    // verbatim, so it stays green over a handler that writes `weight_cg` and nothing else.
    expect(await storedMeasurement()).toEqual({
      weightCg: FROM_A_SCALE.weightCg,
      sourceType: FROM_A_SCALE.sourceType,
      impedanceOhm: FROM_A_SCALE.impedanceOhm,
      deleted: false,
    });
    expect(await storedComposition()).toEqual(FROM_A_SCALE.bodyComposition);
  });

  /**
   * The same round trip for the shape a second device sends afterwards. A phone that pulls this
   * change applies BR-SCALE-007 to it, so the sequence below is what a real account looks like
   * over two days of use, and every step of it has to mean what it says.
   */
  test("a sequence of weighings replays as the sequence that was sent", async () => {
    const sent: MeasurementPayloadV1[] = [
      FROM_A_SCALE,
      { date: "2026-08-26", weightCg: 7_820, sourceType: "scale", impedanceOhm: 517 },
      { date: "2026-08-27", weightCg: 7_805 },
    ];
    for (const payload of sent) accepted((await submit(upsert(payload)))[0]);

    const changes = page(await pullAll()).changes;
    expect(changes.map((change) => change.payload)).toEqual(sent);
  });
});
