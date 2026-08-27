import type { PullResponse } from "@mue/contracts";
// The committed instances themselves, not a copy of them: `packages/contracts` is the only place
// an aggregate is described, and a server test that invented its own payload would prove the
// server consistent with itself rather than with the contract Android reads.
import { CONTRACT_FIXTURES } from "@mue/contracts/fixtures";
import { type DatabaseHandle, createTestDatabase, migrate, schema, seedUser } from "@mue/db";
import { eq } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { readChanges } from "./pull";
import { submitMutations } from "./push";
import type { SyncContext } from "./types";

/**
 * The six aggregates PRD section 10.1 marks synchronised and that never reached the server, one
 * end to the other: pushed, stored in their own table, journalled, and pulled back.
 *
 * The payloads are the **committed contract fixtures**, not values written here. That is the
 * point of the file: a fixture is what Zod emitted, what Kotlin round-trips offline, and what the
 * Android drift detector reads, so pushing the same bytes through the server ties the three sides
 * to one instance of each aggregate. A payload invented for a server test would prove the server
 * consistent with itself.
 *
 * Every one of these tests fails on `main`: `AGGREGATE_TYPES` names two types, so
 * `validateMutation` answers `sync.unknown_aggregate_type` for the other six before any handler
 * exists to be wrong.
 */

const { activitySessions, customExercises, foods, foodLogEntries, mealPlanEntries, recipes } =
  schema;

let handle: DatabaseHandle;
const USER = "user-aggregates-domain-test";
const context: SyncContext = { userId: USER };
const DEVICE = { type: "android", id: "device-under-test" } as const;

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

/** The instance `packages/contracts` emitted for a fixture file, by name. */
function fixture(file: string): Record<string, unknown> {
  const found = CONTRACT_FIXTURES.find((candidate) => candidate.file === file);
  if (found === undefined) throw new Error(`no contract fixture named ${file}`);
  return found.value as Record<string, unknown>;
}

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

function upsert(
  aggregateType: string,
  aggregateId: string,
  payload: unknown,
  overrides: Partial<Envelope> = {},
): Envelope {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision: null,
    origin: { ...DEVICE },
    clientOccurredAt: new Date().toISOString(),
    aggregateType,
    aggregateId,
    op: "upsert",
    payloadSchemaVersion: 1,
    payload,
    ...overrides,
  };
}

function remove(aggregateType: string, aggregateId: string, baseRevision: string | null): Envelope {
  return {
    mutationId: Bun.randomUUIDv7(),
    baseRevision,
    origin: { ...DEVICE },
    clientOccurredAt: new Date().toISOString(),
    aggregateType,
    aggregateId,
    op: "delete",
    payloadSchemaVersion: 1,
    payload: null,
  };
}

async function pushOne(envelope: Envelope) {
  const response = await submitMutations(handle, context, [envelope]);
  const result = response.results[0];
  if (result === undefined) throw new Error("a push of one mutation answered nothing");
  return result;
}

async function pullAll(): Promise<PullResponse> {
  return readChanges(handle, context, {
    cursor: null,
    supportedSchemaVersions: {
      activitySession: [1],
      customExerciseDefinition: [1],
      food: [1],
      foodLogEntry: [1],
      healthProfile: [1],
      mealPlanEntry: [1],
      measurement: [1],
      recipe: [1],
    },
  });
}

/**
 * One aggregate, pushed and pulled, with the payload compared byte for byte.
 *
 * The comparison is against the *submitted* payload rather than against the stored row, and that
 * is what makes it a journal test rather than a storage test: section 12.3 requires a pull at a
 * sequence to return what that sequence carried, and the journal holds a snapshot for exactly
 * that reason. An unknown nutrient that was absent when it was accepted is still absent here.
 */
async function expectRoundTrip(aggregateType: string, aggregateId: string, payload: unknown) {
  const result = await pushOne(upsert(aggregateType, aggregateId, payload));
  expect([aggregateType, result.status]).toEqual([aggregateType, "applied"]);

  const page = await pullAll();
  expect(page.status).toBe("ok");
  if (page.status !== "ok") return;

  const change = page.changes.find((candidate) => candidate.aggregateType === aggregateType);
  expect([aggregateType, change?.aggregateId]).toEqual([aggregateType, aggregateId]);
  expect(change?.op).toBe("upsert");
  // Compared against the *submitted* payload, cast because `SyncChange` narrows `payload` per
  // branch and this helper is deliberately generic over all six.
  expect(change?.payload as unknown).toEqual(payload as never);
  expect(change?.meta.revision).toBe("1");
  expect(change?.meta.originType).toBe("android");
  expect(change?.meta.deletedAt).toBeNull();
}

describe("an activity session", () => {
  const payload = fixture("activity-session-v1-valid.json");
  const id = payload["id"] as string;

  test("reaches PostgreSQL whole, children and all, and comes back from the journal", async () => {
    await expectRoundTrip("activitySession", id, payload);

    const rows = await handle.db
      .select()
      .from(activitySessions)
      .where(eq(activitySessions.userId, USER));
    const row = rows[0];
    expect(row?.id).toBe(id);
    expect(row?.movement).toBe("strength_training");
    expect(row?.durationSeconds).toBe(3_600);
    // Section 10.2: a session never appears without its mandatory children, and the row is the
    // only place they could have been dropped.
    expect((row?.metrics as unknown[] | undefined)?.length).toBe(1);
    expect((row?.equipment as unknown[] | undefined)?.length).toBe(2);
    expect((row?.exercises as unknown[] | undefined)?.length).toBe(2);
    const exercises = (row?.exercises ?? []) as { sets: unknown[] }[];
    expect(exercises.flatMap((exercise) => exercise.sets)).toHaveLength(4);
  });

  test("takes the timed session the manual form could not have typed", async () => {
    const edge = fixture("activity-session-v1-edge.json");
    await expectRoundTrip("activitySession", edge["id"] as string, edge);
    const rows = await handle.db
      .select()
      .from(activitySessions)
      .where(eq(activitySessions.userId, USER));
    expect(rows[0]?.durationSeconds).toBe(40);
  });

  test("is replaced whole by the next accepted mutation, and both stay in the journal", async () => {
    await pushOne(upsert("activitySession", id, payload));
    const edited = { ...payload, notes: "Second version.", perceivedEffort: 9 };
    const second = await pushOne(upsert("activitySession", id, edited, { baseRevision: "1" }));
    expect(second.status).toBe("applied");

    const rows = await handle.db
      .select()
      .from(activitySessions)
      .where(eq(activitySessions.userId, USER));
    expect(rows).toHaveLength(1);
    expect(rows[0]?.notes).toBe("Second version.");
    expect(rows[0]?.revision).toBe(2n);

    // Section 13.1: no resolution destroys the audit history, and `retention.ts` never sweeps
    // the journal, so "reste audité" is true without a horizon.
    const page = await pullAll();
    expect(page.status === "ok" && page.changes).toHaveLength(2);
    if (page.status === "ok") {
      expect((page.changes[0]?.payload as { notes: string } | undefined)?.notes).toBe(
        "Felt strong on the second set.",
      );
      expect((page.changes[1]?.payload as { notes: string } | undefined)?.notes).toBe(
        "Second version.",
      );
    }
  });

  test("keeps a tombstone rather than erasing, and refuses a stale resurrection", async () => {
    await pushOne(upsert("activitySession", id, payload));
    const deleted = await pushOne(remove("activitySession", id, "1"));
    expect(deleted.status).toBe("applied");

    const rows = await handle.db
      .select()
      .from(activitySessions)
      .where(eq(activitySessions.userId, USER));
    expect(rows).toHaveLength(1);
    expect(rows[0]?.deletedAt).not.toBeNull();
    // Its own columns survive, so a restoration based on the tombstone has something to restore.
    expect(rows[0]?.movement).toBe("strength_training");

    // FR-SYNC-005: an offline copy cannot undo the deletion.
    const stale = await pushOne(upsert("activitySession", id, payload, { baseRevision: "1" }));
    expect(stale.status).toBe("rejected");
    expect(stale.status === "rejected" && stale.error.code).toBe("sync.aggregate_deleted");

    // Section 13.3's closing rule: a restoration quotes the *current* tombstone.
    const restored = await pushOne(upsert("activitySession", id, payload, { baseRevision: "2" }));
    expect(restored.status).toBe("applied");
    const after = await handle.db
      .select()
      .from(activitySessions)
      .where(eq(activitySessions.userId, USER));
    expect(after[0]?.deletedAt).toBeNull();
  });
});

describe("a personal exercise definition", () => {
  const payload = fixture("custom-exercise-definition-v1-valid.json");
  const id = payload["id"] as string;

  test("reaches PostgreSQL and comes back from the journal", async () => {
    await expectRoundTrip("customExerciseDefinition", id, payload);
    const rows = await handle.db
      .select()
      .from(customExercises)
      .where(eq(customExercises.userId, USER));
    expect(rows[0]?.name).toBe("Bulgarian split squat");
    // The fold is computed here rather than carried, so there is one place for it to be wrong.
    expect(rows[0]?.nameFolded).toBe("bulgarian split squat");
  });

  test("yields the name rather than colliding, when two devices typed the same one", async () => {
    await pushOne(upsert("customExerciseDefinition", id, payload));

    const rival = "d41f6c58-7b90-4e2a-8c31-000000000000";
    const result = await pushOne(
      upsert("customExerciseDefinition", rival, {
        ...payload,
        id: rival,
        // Same exercise by PRD_ACTIVITIES 9.2: case and padding do not distinguish two names.
        name: "  BULGARIAN Split Squat ",
      }),
    );

    expect(result.status).toBe("applied");
    const rows = await handle.db
      .select()
      .from(customExercises)
      .where(eq(customExercises.userId, USER));
    expect(rows).toHaveLength(2);
    // Nothing was deleted (section 13.1), and the live folded name is unique.
    const folded = rows.map((row) => row.nameFolded).sort();
    expect(folded).toEqual([`bulgarian split squat#${id}`, "bulgarian split squat"].sort());
  });

  test("has no deletion, because the definition is kept for ever", async () => {
    await pushOne(upsert("customExerciseDefinition", id, payload));
    const result = await pushOne(remove("customExerciseDefinition", id, "1"));
    expect(result.status).toBe("rejected");
    expect(result.status === "rejected" && result.error.code).toBe("sync.invalid_payload");
    const rows = await handle.db
      .select()
      .from(customExercises)
      .where(eq(customExercises.userId, USER));
    expect(rows[0]?.deletedAt).toBeNull();
  });
});

describe("a food", () => {
  const payload = fixture("food-v1-valid.json");
  const id = payload["id"] as string;

  test("reaches PostgreSQL and comes back from the journal", async () => {
    await expectRoundTrip("food", id, payload);
    const rows = await handle.db.select().from(foods).where(eq(foods.userId, USER));
    expect(rows[0]?.name).toBe("Skyr nature");
    expect(rows[0]?.barcode).toBe("5701092103246");
    expect(rows[0]?.energyMilliKcal).toBe(63_000);
  });

  /**
   * PRD_FOOD 13.1, through the whole path.
   *
   * The payload has no `proteinMilligrams` key; the column is NULL, not 0; and the pull returns a
   * payload that still has no key. A `0` anywhere on that path would be the server handing back a
   * claim the phone never made.
   */
  test("keeps an unknown nutrient unknown in the column and in the journal alike", async () => {
    const edge = fixture("food-v1-edge.json");
    await expectRoundTrip("food", edge["id"] as string, edge);

    const rows = await handle.db.select().from(foods).where(eq(foods.userId, USER));
    expect(rows[0]?.energyMilliKcal).toBe(0);
    expect(rows[0]?.proteinMilligrams).toBeNull();

    const page = await pullAll();
    if (page.status !== "ok") throw new Error("expected a page");
    const payloadBack = page.changes[0]?.payload as Record<string, unknown>;
    expect(Object.hasOwn(payloadBack, "proteinMilligrams")).toBe(false);
    expect(payloadBack["energyMilliKcal"]).toBe(0);
  });

  test("tombstones rather than erasing", async () => {
    await pushOne(upsert("food", id, payload));
    expect((await pushOne(remove("food", id, "1"))).status).toBe("applied");
    const rows = await handle.db.select().from(foods).where(eq(foods.userId, USER));
    expect(rows).toHaveLength(1);
    expect(rows[0]?.deletedAt).not.toBeNull();
  });
});

describe("a recipe", () => {
  const payload = fixture("recipe-v1-valid.json");
  const id = payload["id"] as string;

  test("never reaches PostgreSQL without its ingredients", async () => {
    await expectRoundTrip("recipe", id, payload);
    const rows = await handle.db.select().from(recipes).where(eq(recipes.userId, USER));
    expect((rows[0]?.ingredients as unknown[] | undefined)?.length).toBe(2);
    expect((rows[0]?.steps as unknown[] | undefined)?.length).toBe(2);
    expect(rows[0]?.isFavourite).toBe(true);
  });

  test("replaces the whole list rather than merging it line by line", async () => {
    await pushOne(upsert("recipe", id, payload));
    const ingredients = payload["ingredients"] as unknown[];
    const shortened = { ...payload, ingredients: [ingredients[0]] };
    expect((await pushOne(upsert("recipe", id, shortened, { baseRevision: "1" }))).status).toBe(
      "applied",
    );

    const rows = await handle.db.select().from(recipes).where(eq(recipes.userId, USER));
    // PRD_FOOD 21.3: the ingredients are not merged, so the removed one is gone from the row —
    // and still in the journal, where the replaced version stays.
    expect((rows[0]?.ingredients as unknown[] | undefined)?.length).toBe(1);
  });

  test("takes the stepless recipe whose `steps` key is absent", async () => {
    const edge = fixture("recipe-v1-edge.json");
    await expectRoundTrip("recipe", edge["id"] as string, edge);
    const rows = await handle.db.select().from(recipes).where(eq(recipes.userId, USER));
    expect(rows[0]?.steps).toEqual([]);
  });
});

describe("a journal line", () => {
  const payload = fixture("food-log-entry-v1-valid.json");
  const id = payload["id"] as string;

  test("reaches PostgreSQL with its own snapshot and comes back from the journal", async () => {
    await expectRoundTrip("foodLogEntry", id, payload);
    const rows = await handle.db
      .select()
      .from(foodLogEntries)
      .where(eq(foodLogEntries.userId, USER));
    expect(rows[0]?.title).toBe("Skyr bowl");
    expect(rows[0]?.consumedAt).toBe("20:15");
    expect(rows[0]?.energyMilliKcal).toBe(284_000);
    // The colon-joined meal plan identifier, stored as the trace of provenance it is.
    expect(rows[0]?.fromPlan).toBe("2026-09-01:dinner");
  });

  /**
   * PRD_FOOD 21.3: two lines created separately coexist and never merge. The identifier is a
   * minted UUID for that reason — two lines really can describe the same food at the same minute.
   */
  test("coexists with a second line of the same food at the same minute", async () => {
    const other = { ...payload, id: "3d60ba59-8e12-4f41-8690-000000000000" };
    await pushOne(upsert("foodLogEntry", id, payload));
    await pushOne(upsert("foodLogEntry", other.id, other));
    const rows = await handle.db
      .select()
      .from(foodLogEntries)
      .where(eq(foodLogEntries.userId, USER));
    expect(rows).toHaveLength(2);
  });

  test("takes a quick add with no food, no recipe and no quantity", async () => {
    const edge = fixture("food-log-entry-v1-edge.json");
    await expectRoundTrip("foodLogEntry", edge["id"] as string, edge);
    const rows = await handle.db
      .select()
      .from(foodLogEntries)
      .where(eq(foodLogEntries.userId, USER));
    expect(rows[0]?.quantityThousandths).toBeNull();
    expect(rows[0]?.quantityUnit).toBeNull();
    expect(rows[0]?.sourceRef).toBeNull();
  });
});

describe("a meal proposal", () => {
  const payload = fixture("meal-plan-entry-v1-valid.json");
  const id = "2026-09-01:dinner";

  test("reaches PostgreSQL under its business key and comes back from the journal", async () => {
    await expectRoundTrip("mealPlanEntry", id, payload);
    const rows = await handle.db
      .select()
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, USER));
    expect(rows[0]?.plannedOn).toBe("2026-09-01");
    expect(rows[0]?.slot).toBe("dinner");
    expect(rows[0]?.plannedServingsThousandths).toBe(1_500);
  });

  /**
   * PRD_FOOD 21.3: *"la précédente est remplacée, jamais dupliquée"*. The primary key is
   * `(user_id, planned_on, slot)`, so this is a property of the table rather than of the handler.
   */
  test("is replaced and never duplicated on the same date and moment", async () => {
    await pushOne(upsert("mealPlanEntry", id, payload));
    const other = {
      ...payload,
      recipeId: "f92cd615-4a7e-4b0d-8256-3d8e1f4a9b72",
      plannedServingsThousandths: 2_000,
    };
    expect((await pushOne(upsert("mealPlanEntry", id, other, { baseRevision: "1" }))).status).toBe(
      "applied",
    );
    const rows = await handle.db
      .select()
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, USER));
    expect(rows).toHaveLength(1);
    expect(rows[0]?.plannedServingsThousandths).toBe(2_000);
    expect(rows[0]?.revision).toBe(2n);
  });

  test("refuses an identifier that names a different evening from its payload", async () => {
    const result = await pushOne(upsert("mealPlanEntry", "2026-09-02:dinner", payload));
    expect(result.status).toBe("rejected");
    expect(result.status === "rejected" && result.error.code).toBe("sync.invalid_payload");
  });

  /**
   * The separator, at the one place a stored row is finally judged.
   *
   * Every meal-plan row Android journalled before this change spells its identifier with a `/`,
   * which `aggregateIdSchema` has never accepted. This is the rejection those rows would have met
   * on the day the aggregate joined the contract, and the reason `MealPlanIdRepair` exists.
   */
  test("refuses the slash Android used to write, which is why the rows were repaired", async () => {
    const result = await pushOne(upsert("mealPlanEntry", "2026-09-01/dinner", payload));
    expect(result.status).toBe("rejected");
    expect(result.status === "rejected" && result.error.code).toBe("sync.invalid_payload");
    const rows = await handle.db
      .select()
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, USER));
    expect(rows).toHaveLength(0);
  });

  test("tombstones under its business key", async () => {
    await pushOne(upsert("mealPlanEntry", id, payload));
    expect((await pushOne(remove("mealPlanEntry", id, "1"))).status).toBe("applied");
    const rows = await handle.db
      .select()
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, USER));
    expect(rows).toHaveLength(1);
    expect(rows[0]?.deletedAt).not.toBeNull();
  });
});

describe("all six together", () => {
  /**
   * The whole point, in one assertion: a batch carrying every aggregate PRD section 10.1 marks
   * synchronised is accepted, and every one of them comes back.
   *
   * On `main` this batch produces six `sync.unknown_aggregate_type` rejections and two
   * applications.
   */
  test("one batch carries every synchronised aggregate, and every one is applied", async () => {
    const activity = fixture("activity-session-v1-valid.json");
    const definition = fixture("custom-exercise-definition-v1-valid.json");
    const food = fixture("food-v1-valid.json");
    const recipe = fixture("recipe-v1-valid.json");
    const line = fixture("food-log-entry-v1-valid.json");
    const plan = fixture("meal-plan-entry-v1-valid.json");

    const response = await submitMutations(handle, context, [
      upsert("measurement", "2026-09-01", { date: "2026-09-01", weightCg: 7_845 }),
      upsert("healthProfile", "me", { heightCm: 171, birthDate: "1998-11-18" }),
      upsert("activitySession", activity["id"] as string, activity),
      upsert("customExerciseDefinition", definition["id"] as string, definition),
      upsert("food", food["id"] as string, food),
      upsert("recipe", recipe["id"] as string, recipe),
      upsert("foodLogEntry", line["id"] as string, line),
      upsert("mealPlanEntry", "2026-09-01:dinner", plan),
    ]);

    expect(response.results.map((result) => result.status)).toEqual([
      "applied",
      "applied",
      "applied",
      "applied",
      "applied",
      "applied",
      "applied",
      "applied",
    ]);

    const page = await pullAll();
    if (page.status !== "ok") throw new Error("expected a page");
    expect(page.changes.map((change) => change.aggregateType).sort()).toEqual([
      "activitySession",
      "customExerciseDefinition",
      "food",
      "foodLogEntry",
      "healthProfile",
      "mealPlanEntry",
      "measurement",
      "recipe",
    ]);
  });
});

/**
 * The two moments the six-moment split adds, pushed all the way to PostgreSQL and pulled back.
 *
 * A new enum member is exactly the change `ContractDrift` cannot see: `slot` is a `text` column
 * before and after, the payload is the same shape before and after, and every schema still parses.
 * The only thing that moves is which *values* survive the trip, and there are three independent
 * gates on the way — `mealSlotSchema`, `mealPlanAggregateIdSchema`'s pattern, and the refinement
 * that makes the identifier agree with the payload. A moment that clears two of them and not the
 * third is a push marked `failed` with `sync.invalid_payload` before any handler runs, which is
 * how the `/` separator lost a week of meal plans.
 *
 * So this walks a real `morning_snack` proposal and a real `evening_snack` line through the whole
 * path, and reads the stored row rather than the response.
 */
describe("the moments the six-moment split adds", () => {
  test("a proposal on the morning snack reaches its own row and comes back", async () => {
    const payload = {
      ...fixture("meal-plan-entry-v1-valid.json"),
      plannedOn: "2026-09-01",
      slot: "morning_snack",
    };
    await expectRoundTrip("mealPlanEntry", "2026-09-01:morning_snack", payload);

    const rows = await handle.db
      .select()
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, USER));
    expect(rows[0]?.slot).toBe("morning_snack");
  });

  /**
   * The business key is `(user_id, planned_on, slot)`, so two snacks on one day are two rows.
   * Under four moments they were one, and the second silently replaced the first.
   */
  test("two snacks on the same day are two proposals, not one overwriting the other", async () => {
    const base = fixture("meal-plan-entry-v1-valid.json");
    for (const slot of ["morning_snack", "snack", "evening_snack"]) {
      const result = await pushOne(
        upsert("mealPlanEntry", `2026-09-01:${slot}`, {
          ...base,
          plannedOn: "2026-09-01",
          slot,
        }),
      );
      expect([slot, result.status]).toEqual([slot, "applied"]);
    }

    const rows = await handle.db
      .select()
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, USER));
    expect(rows.map((row) => row.slot).sort()).toEqual(["evening_snack", "morning_snack", "snack"]);
  });

  test("a line eaten after midnight is an evening snack, and stores as one", async () => {
    const base = fixture("food-log-entry-v1-edge.json");
    const id = base["id"] as string;
    const payload = {
      ...base,
      consumedOn: "2026-09-01",
      // The hour that used to have no moment of its own: one in the morning is the far end of
      // the only window that crosses midnight.
      consumedAt: "01:00",
      slot: "evening_snack",
    };
    await expectRoundTrip("foodLogEntry", id, payload);

    const rows = await handle.db
      .select()
      .from(foodLogEntries)
      .where(eq(foodLogEntries.userId, USER));
    expect(rows[0]?.slot).toBe("evening_snack");
    expect(rows[0]?.consumedAt).toBe("01:00");
  });

  test("a moment the contract has never heard of is still refused at the envelope", async () => {
    const result = await pushOne(
      upsert("mealPlanEntry", "2026-09-01:brunch", {
        ...fixture("meal-plan-entry-v1-valid.json"),
        plannedOn: "2026-09-01",
        slot: "brunch",
      }),
    );
    expect(result.status).toBe("rejected");
    expect(result.status === "rejected" && result.error.code).toBe("sync.invalid_payload");
  });
});
