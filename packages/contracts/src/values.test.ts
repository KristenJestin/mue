import { describe, expect, test } from "bun:test";
import type { ZodType } from "zod";
import {
  SESSION_MIN_SECONDS,
  SESSION_STORED_MIN_SECONDS,
  activitySessionPayloadV1Schema,
  customExerciseDefinitionPayloadV1Schema,
  exerciseDefinitionSnapshotSchema,
  sessionEquipmentSchema,
  strengthExerciseSchema,
} from "./activity";
import { foodPayloadV1Schema } from "./food";
import { foodLogEntryPayloadV1Schema } from "./food-log";
import { servingsThousandthsSchema } from "./meal-plan";
import { mutationEnvelopeSchema } from "./mutation";
import { aggregateIdSchema } from "./primitives";
import { recipePayloadV1Schema } from "./recipe";

/**
 * The narrowing constraints, each fed a real value.
 *
 * `ContractDrift` compares *shapes*, and every rule below is invisible to it. A UUIDv4 where a
 * v7 was required, an `origin.type` outside its enum and a `weightCg` off the five-centigram step
 * were all the right shape and the wrong content, and each one refused a push before the payload
 * was read — one of them for the whole life of the sync feature. So a constraint that narrows a
 * value earns a test that pushes a real value through the path and asserts what lands, rather
 * than a test that a schema exists.
 *
 * These live in their own file because they are a different kind of test from `contracts.test.ts`:
 * that file asks whether a shape round-trips, this one asks what a *value* is allowed to be.
 */

function expectRoundTrip<T>(schema: ZodType<T>, value: unknown): T {
  const parsed = schema.parse(value);
  const reparsed = schema.parse(JSON.parse(JSON.stringify(parsed)));
  expect(reparsed).toEqual(parsed);
  return parsed;
}

const ORIGIN = { type: "android", id: "device-7f3c1a04" } as const;
const CLIENT_OCCURRED_AT = "2026-09-01T18:22:03.100Z";

function mealPlanUpsert(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    mutationId: "0198f0a4-6f70-7192-9c3d-4e5f60718293",
    aggregateType: "mealPlanEntry",
    aggregateId: "2026-09-01:dinner",
    op: "upsert",
    baseRevision: null,
    payloadSchemaVersion: 1,
    payload: {
      plannedOn: "2026-09-01",
      slot: "dinner",
      recipeId: "b5e8f271-0c3a-4d69-8e12-9f4a7b0c5d38",
      plannedServingsThousandths: 1_500,
    },
    origin: ORIGIN,
    clientOccurredAt: CLIENT_OCCURRED_AT,
    ...overrides,
  };
}

describe("the meal plan identifier", () => {
  test("a colon-joined identifier reaches the payload intact", () => {
    const parsed = mutationEnvelopeSchema.parse(mealPlanUpsert());
    expect(parsed.aggregateId).toBe("2026-09-01:dinner");
    expect(parsed.op === "upsert" && parsed.aggregateType === "mealPlanEntry").toBe(true);
  });

  /**
   * The trap this contract was extended around.
   *
   * Every meal-plan row Android journalled before the separator changed carries a `/`,
   * `aggregateIdSchema` is `[A-Za-z0-9._:-]+`, and the refusal happens at the envelope — before
   * any handler, before any storage, before the payload is even looked at. Nothing downstream
   * could have compensated, which is why the rows already written had to be repaired rather than
   * merely tolerated.
   */
  test("the slash Android used to write is refused, which is why the rows needed repairing", () => {
    const result = mutationEnvelopeSchema.safeParse(
      mealPlanUpsert({ aggregateId: "2026-09-01/dinner" }),
    );
    expect(result.success).toBe(false);
    expect(aggregateIdSchema.safeParse("2026-09-01/dinner").success).toBe(false);
    expect(aggregateIdSchema.safeParse("2026-09-01:dinner").success).toBe(true);
  });

  test("an identifier naming a different evening from its payload is refused", () => {
    expect(
      mutationEnvelopeSchema.safeParse(mealPlanUpsert({ aggregateId: "2026-09-02:dinner" }))
        .success,
    ).toBe(false);
  });
});

describe("serving counts", () => {
  test("a count off the quarter step is refused, though it is well inside the range", () => {
    const offStep = mealPlanUpsert();
    (offStep["payload"] as { plannedServingsThousandths: number }).plannedServingsThousandths =
      1_333;
    expect(mutationEnvelopeSchema.safeParse(offStep).success).toBe(false);
    expect(servingsThousandthsSchema.parse(1_250)).toBe(1_250);
    expect(servingsThousandthsSchema.safeParse(240).success).toBe(false);
    expect(servingsThousandthsSchema.safeParse(10_250).success).toBe(false);
  });

  const line = {
    id: "2c5fa948-7d01-4e30-9589-6a1b4c7d2e05",
    consumedOn: "2026-09-01",
    consumedAt: "20:15",
    slot: "dinner",
    kind: "recipe",
    title: "Skyr bowl",
    estimation: "measured",
    weighedCooked: false,
    quantityThousandths: 1_500,
    quantityUnit: "serving",
  };

  /**
   * The two scales are integers in overlapping ranges, so no shape check can tell them apart.
   * `1333` is an ordinary weight — 1.333 g — and an impossible serving count, and the unit is the
   * only thing that says which is being read.
   */
  test("a quantity is judged on the scale its unit selects", () => {
    expect(foodLogEntryPayloadV1Schema.parse(line).quantityThousandths).toBe(1_500);
    expect(
      foodLogEntryPayloadV1Schema.safeParse({ ...line, quantityThousandths: 1_333 }).success,
    ).toBe(false);
    expect(
      foodLogEntryPayloadV1Schema.parse({
        ...line,
        quantityThousandths: 1_333,
        quantityUnit: "gram",
      }).quantityThousandths,
    ).toBe(1_333);
  });

  test("a quantity without its unit, or a unit without its quantity, is refused", () => {
    const quick = {
      id: "3d60ba59-8e12-4f41-8690-7b2c5d8e3f16",
      consumedOn: "2026-09-01",
      consumedAt: "12:30",
      slot: "lunch",
      kind: "quick",
      title: "Riz",
      estimation: "measured",
      weighedCooked: false,
    };
    expect(foodLogEntryPayloadV1Schema.parse(quick).quantityUnit).toBeUndefined();
    expect(
      foodLogEntryPayloadV1Schema.safeParse({ ...quick, quantityThousandths: 120_000 }).success,
    ).toBe(false);
    expect(foodLogEntryPayloadV1Schema.safeParse({ ...quick, quantityUnit: "gram" }).success).toBe(
      false,
    );
  });

  test("a line logged from a proposal carries that proposal's own slot", () => {
    expect(
      foodLogEntryPayloadV1Schema.safeParse({ ...line, fromPlan: "2026-09-01:lunch" }).success,
    ).toBe(false);
    expect(
      foodLogEntryPayloadV1Schema.parse({ ...line, fromPlan: "2026-09-01:dinner" }).fromPlan,
    ).toBe("2026-09-01:dinner");
  });
});

describe("a food", () => {
  test("keeps an unknown macro absent through a round trip, and never turns it into zero", () => {
    const parsed = expectRoundTrip(foodPayloadV1Schema, {
      id: "0d1e2f30-4a5b-4c60-9d71-8e9f0a1b2c34",
      name: "Cafe noir sans sucre",
      source: "custom",
      referenceUnit: "millilitre",
      rawLabel: "Raw",
      cookedLabel: "Cooked",
      energyMilliKcal: 0,
    });
    expect(Object.hasOwn(parsed, "proteinMilligrams")).toBe(false);
    expect(parsed.energyMilliKcal).toBe(0);
  });

  test("cannot describe a Ciqual entry, which is reference data and not personal data", () => {
    const ciqual = {
      id: "0d1e2f30-4a5b-4c60-9d71-8e9f0a1b2c34",
      name: "Riz blanc cru",
      source: "ciqual",
      referenceUnit: "gram",
      rawLabel: "Raw",
      cookedLabel: "Cooked",
    };
    expect(foodPayloadV1Schema.safeParse(ciqual).success).toBe(false);
    expect(foodPayloadV1Schema.safeParse({ ...ciqual, source: "custom" }).success).toBe(true);
  });

  test("takes a barcode of digits only", () => {
    const base = {
      id: "0d1e2f30-4a5b-4c60-9d71-8e9f0a1b2c34",
      name: "Skyr",
      source: "open_food_facts",
      referenceUnit: "gram",
      rawLabel: "Raw",
      cookedLabel: "Cooked",
    };
    expect(foodPayloadV1Schema.parse({ ...base, barcode: "5701092103246" }).barcode).toBe(
      "5701092103246",
    );
    expect(foodPayloadV1Schema.safeParse({ ...base, barcode: "570109210324X" }).success).toBe(
      false,
    );
    expect(foodPayloadV1Schema.safeParse({ ...base, barcode: "5701" }).success).toBe(false);
  });
});

describe("an activity session", () => {
  const session = {
    id: "3a0f7b26-9c41-4a5e-8d13-6f2b8e04c751",
    movement: "running",
    customMovementName: null,
    environment: "outdoor",
    startedOn: "2026-08-25",
    startedAtTime: "07:15",
    durationSeconds: 2_400,
    perceivedEffort: null,
    notes: null,
    source: "manual",
    metrics: [] as unknown[],
    equipment: [] as unknown[],
    exercises: [] as unknown[],
  };

  /**
   * `SESSION_MIN_SECONDS` is the manual form's floor and copying it onto the wire would have
   * refused every timed session shorter than a minute — a row the phone already holds and that no
   * screen can edit back into range, because the manual form has no seconds field.
   */
  test("may be shorter than the manual form can express, because the timer can write one", () => {
    expect(SESSION_STORED_MIN_SECONDS).toBeLessThan(SESSION_MIN_SECONDS);
    expect(
      activitySessionPayloadV1Schema.parse({ ...session, durationSeconds: 40, source: "timer" })
        .durationSeconds,
    ).toBe(40);
    expect(
      activitySessionPayloadV1Schema.safeParse({ ...session, durationSeconds: 0 }).success,
    ).toBe(false);
    expect(
      activitySessionPayloadV1Schema.safeParse({ ...session, durationSeconds: 359_941 }).success,
    ).toBe(false);
  });

  test("keeps the `agent` source the MCP tool has already journalled", () => {
    expect(activitySessionPayloadV1Schema.parse({ ...session, source: "agent" }).source).toBe(
      "agent",
    );
    expect(activitySessionPayloadV1Schema.safeParse({ ...session, source: "web" }).success).toBe(
      false,
    );
  });

  test("carries a free movement name only on `other`, and always there", () => {
    expect(
      activitySessionPayloadV1Schema.safeParse({ ...session, customMovementName: "Padel" }).success,
    ).toBe(false);
    expect(
      activitySessionPayloadV1Schema.safeParse({ ...session, movement: "other" }).success,
    ).toBe(false);
    expect(
      activitySessionPayloadV1Schema.parse({
        ...session,
        movement: "other",
        customMovementName: "Padel",
      }).customMovementName,
    ).toBe("Padel");
  });

  test("never names two metrics of one kind, which is a row that cannot be written", () => {
    const twice = {
      ...session,
      metrics: [
        { kind: "distance", value: 6_200, source: "manual" },
        { kind: "distance", value: 6_300, source: "wearable" },
      ],
    };
    expect(activitySessionPayloadV1Schema.safeParse(twice).success).toBe(false);
    expect(
      activitySessionPayloadV1Schema.parse({ ...session, metrics: [twice.metrics[0]] }).metrics,
    ).toHaveLength(1);
  });

  test("holds the same equipment once, whatever the case and padding of its name", () => {
    expect(
      activitySessionPayloadV1Schema.safeParse({
        ...session,
        equipment: [
          { equipmentType: "other", customName: "Home rack", position: 0 },
          { equipmentType: "other", customName: "  home RACK ", position: 1 },
        ],
      }).success,
    ).toBe(false);
  });
});

describe("a piece of equipment", () => {
  test("takes a free name on `other` alone, and requires one there", () => {
    expect(
      sessionEquipmentSchema.safeParse({
        equipmentType: "barbell",
        customName: "My bar",
        position: 0,
      }).success,
    ).toBe(false);
    expect(
      sessionEquipmentSchema.safeParse({ equipmentType: "other", customName: null, position: 0 })
        .success,
    ).toBe(false);
    expect(
      sessionEquipmentSchema.parse({ equipmentType: "barbell", customName: null, position: 0 })
        .equipmentType,
    ).toBe("barbell");
  });
});

describe("a strength exercise", () => {
  const exercise = {
    id: "1f6a2d70-4c8b-4e15-9f27-3b6d0a4e8c19",
    position: 0,
    notes: null,
    definition: {
      id: "8b2b1c9a-3a4f-4b1c-9d5e-7f8a0b1c2d3e",
      name: "Plank",
      trackingMode: "duration",
      equipment: "bodyweight",
      isCustom: false,
    },
    sets: [
      {
        id: "2c7b3e81-5d9c-4f26-8a38-4c7e1b5f9d20",
        position: 0,
        setType: "working",
        repetitions: null,
        loadGrams: null,
        durationSeconds: 60,
        perceivedEffort: null,
      },
    ],
  };

  test("refuses a set that does not carry the principal measure of its mode", () => {
    expect(strengthExerciseSchema.parse(exercise).sets).toHaveLength(1);
    expect(
      strengthExerciseSchema.safeParse({
        ...exercise,
        sets: [{ ...exercise.sets[0], durationSeconds: null, repetitions: 10 }],
      }).success,
    ).toBe(false);
  });

  test("refuses an exercise with no sets at all", () => {
    expect(strengthExerciseSchema.safeParse({ ...exercise, sets: [] }).success).toBe(false);
  });

  test("takes a load in grams with no step, because that is what the domain stores", () => {
    const weighted = {
      ...exercise,
      definition: { ...exercise.definition, trackingMode: "weight_and_duration" },
      sets: [{ ...exercise.sets[0], loadGrams: 12_345 }],
    };
    expect(strengthExerciseSchema.parse(weighted).sets[0]?.loadGrams).toBe(12_345);
    expect(
      strengthExerciseSchema.safeParse({
        ...weighted,
        sets: [{ ...weighted.sets[0], loadGrams: 0 }],
      }).success,
    ).toBe(false);
  });
});

describe("a personal exercise definition", () => {
  const definition = {
    id: "d41f6c58-7b90-4e2a-8c31-5a6b7c8d9e0f",
    name: "Bulgarian split squat",
    trackingMode: "weight_and_reps",
    equipment: "dumbbells",
  };

  /**
   * PRD section 10.1 marks the seventeen definitions Mue ships `Synchronisé: Non`, so this
   * payload has no field in which one could be claimed. The session's own snapshot does have one
   * — a session may legitimately reference a provided definition — and the two shapes differ for
   * exactly that reason rather than by accident.
   */
  test("cannot claim to be one of the definitions Mue ships", () => {
    const parsed = customExerciseDefinitionPayloadV1Schema.parse({
      ...definition,
      isCustom: false,
    });
    expect(Object.hasOwn(parsed, "isCustom")).toBe(false);
    expect(
      exerciseDefinitionSnapshotSchema.parse({ ...definition, isCustom: false }).isCustom,
    ).toBe(false);
  });
});

describe("a recipe", () => {
  test("never travels without its ingredients", () => {
    expect(
      recipePayloadV1Schema.safeParse({
        id: "f92cd615-4a7e-4b0d-8256-3d8e1f4a9b72",
        name: "Oeufs durs",
        type: "snack",
        baseServings: 12,
        isFavourite: false,
        ingredients: [],
      }).success,
    ).toBe(false);
  });
});
