import { describe, expect, test } from "bun:test";
import {
  ENERGY_PER_100_MAX_MILLI_KCAL,
  INGREDIENT_QUANTITY_MAX_THOUSANDTHS,
  SERVINGS_MAX_THOUSANDTHS,
  type FoodLogEntryPayloadV1,
  type FoodPayloadV1,
  type RecipePayloadV1,
} from "@mue/contracts";
import { dailyNutrition } from "./daily";
import { UNKNOWN, energyLabel, macroLabel } from "./labels";
import {
  CANONICAL_MAX,
  NUTRIENTS_UNKNOWN,
  NUTRIENTS_ZERO,
  contribution,
  ingredientContribution,
  nutrientsOfFood,
  perServing,
  plusNutrients,
  recipeLine,
  recipeTotal,
  strictSum,
  unresolvedIngredientIds,
  type Nutrients,
} from "./nutrition";

/**
 * The server's half of PRD_FOOD 13.1, against the arithmetic Android already implements.
 *
 * The numbers below are not invented for the test: each one is a formula from PRD_FOOD 13.1
 * worked through by hand, so a divergence between this file and `NutritionMathTest.kt` shows
 * up as a wrong *value* rather than as a difference of style.
 */

function food(over: Partial<FoodPayloadV1> = {}): FoodPayloadV1 {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    name: "Test food",
    source: "custom",
    referenceUnit: "gram",
    rawLabel: "Raw",
    cookedLabel: "Cooked",
    ...over,
  };
}

function line(over: Partial<FoodLogEntryPayloadV1> = {}): FoodLogEntryPayloadV1 {
  return {
    id: "22222222-2222-4222-8222-222222222222",
    consumedOn: "2026-06-12",
    consumedAt: "08:10",
    slot: "breakfast",
    kind: "quick",
    title: "Test line",
    estimation: "approximate",
    weighedCooked: false,
    ...over,
  };
}

describe("unknown is not zero, in the arithmetic", () => {
  test("an empty strict sum is a known zero, not an unknown", () => {
    // The distinction the whole module rests on. `NUTRIENTS_ZERO` says "nothing was eaten";
    // `NUTRIENTS_UNKNOWN` says "nobody knows what was". A day screen may show the first.
    expect(strictSum([])).toEqual(NUTRIENTS_ZERO);
    expect(strictSum([])).not.toEqual(NUTRIENTS_UNKNOWN);
  });

  test("one unknown metric makes only that metric unknown for the whole total", () => {
    const known: Nutrients = {
      energyMilliKcal: 100_000,
      proteinMilligrams: 5_000,
      carbsMilligrams: 20_000,
      fatMilligrams: 1_000,
      fibreMilligrams: 2_000,
    };
    const partial: Nutrients = { ...known, proteinMilligrams: null };

    expect(strictSum([known, partial])).toEqual({
      energyMilliKcal: 200_000,
      // PRD_FOOD 13.1: a single unknown contribution makes this metric null. Not 5000,
      // which is what dropping the unknown term would produce, and not 0.
      proteinMilligrams: null,
      carbsMilligrams: 40_000,
      fatMilligrams: 2_000,
      fibreMilligrams: 4_000,
    });
  });

  test("a known zero added to a known value is that value, and stays known", () => {
    // Black coffee. The difference between this and the case above is the entire point.
    const coffee: Nutrients = { ...NUTRIENTS_ZERO };
    const toast: Nutrients = {
      energyMilliKcal: 250_000,
      proteinMilligrams: 8_000,
      carbsMilligrams: 45_000,
      fatMilligrams: 3_000,
      fibreMilligrams: 2_500,
    };
    expect(strictSum([coffee, toast])).toEqual(toast);
  });

  test("addition is commutative and associative across an unknown", () => {
    const a: Nutrients = { ...NUTRIENTS_ZERO, energyMilliKcal: 1_000 };
    const b: Nutrients = { ...NUTRIENTS_ZERO, energyMilliKcal: null };
    const c: Nutrients = { ...NUTRIENTS_ZERO, energyMilliKcal: 2_000 };
    expect(plusNutrients(plusNutrients(a, b), c)).toEqual(plusNutrients(a, plusNutrients(b, c)));
    expect(strictSum([a, b, c])).toEqual(strictSum([c, b, a]));
  });

  test("a sum that no longer fits a metric is unknown, never a wrapped number", () => {
    const huge: Nutrients = { ...NUTRIENTS_ZERO, energyMilliKcal: CANONICAL_MAX };
    expect(plusNutrients(huge, huge).energyMilliKcal).toBeNull();
  });

  test("an absent key on a stored food widens to null, and never to zero", () => {
    // `foodPayloadV1Schema` states an unknown nutrient as an absent key. This is the one
    // place that absence becomes the null the arithmetic propagates.
    expect(nutrientsOfFood(food({ energyMilliKcal: 63_000 }))).toEqual({
      energyMilliKcal: 63_000,
      proteinMilligrams: null,
      carbsMilligrams: null,
      fatMilligrams: null,
      fibreMilligrams: null,
    });
  });
});

describe("PRD_FOOD 13.1's formulas, worked through by hand", () => {
  test("contribution = reference weight x per-100 / 100", () => {
    // 150 g of a food at 52 kcal and 0.3 g protein per 100 g.
    const per100 = nutrientsOfFood(food({ energyMilliKcal: 52_000, proteinMilligrams: 300 }));
    expect(contribution(per100, 150_000)).toEqual({
      energyMilliKcal: 78_000, // 52.000 x 1.5
      proteinMilligrams: 450, // 0.300 x 1.5
      carbsMilligrams: null,
      fatMilligrams: null,
      fibreMilligrams: null,
    });
  });

  test("rounding is half-up on the canonical integer, as Kotlin's roundedDiv is", () => {
    // 1 g of a food at 0.005 g protein per 100 g is 0.00005 g, which is 0.05 mg and rounds
    // to 0 mg -- a *known* zero, because the input was known.
    expect(contribution(nutrientsOfFood(food({ proteinMilligrams: 5 })), 1_000)).toMatchObject({
      proteinMilligrams: 0,
    });
    // 10 g of the same food is 0.5 mg, which rounds up rather than to even.
    expect(contribution(nutrientsOfFood(food({ proteinMilligrams: 5 })), 10_000)).toMatchObject({
      proteinMilligrams: 1,
    });
  });

  test("recipe total, per serving and a recipe line", () => {
    const oats = food({ id: "aaaaaaaa-1111-4111-8111-111111111111", energyMilliKcal: 380_000 });
    const milk = food({ id: "bbbbbbbb-1111-4111-8111-111111111111", energyMilliKcal: 46_000 });
    const recipe: RecipePayloadV1 = {
      id: "cccccccc-1111-4111-8111-111111111111",
      name: "Porridge",
      type: "breakfast",
      baseServings: 2,
      isFavourite: false,
      ingredients: [
        {
          id: "dddddddd-1111-4111-8111-111111111111",
          foodId: oats.id,
          quantityThousandths: 100_000,
          unit: "gram",
          position: 0,
        },
        {
          id: "eeeeeeee-1111-4111-8111-111111111111",
          foodId: milk.id,
          quantityThousandths: 300_000,
          unit: "millilitre",
          position: 1,
        },
      ],
    };
    const foods = new Map([
      [oats.id, oats],
      [milk.id, milk],
    ]);

    // 380 kcal + (46 x 3) = 380 + 138 = 518 kcal for the whole recipe.
    expect(recipeTotal(recipe, foods).energyMilliKcal).toBe(518_000);
    // Per serving, for two servings.
    expect(perServing(recipeTotal(recipe, foods), 2).energyMilliKcal).toBe(259_000);
    // One and a half servings eaten.
    expect(recipeLine(perServing(recipeTotal(recipe, foods), 2), 1_500).energyMilliKcal).toBe(
      388_500,
    );
  });

  test("an ingredient whose food this server has not got makes the total unknown, not smaller", () => {
    // PRD_FOOD 21.2: a recipe may name a food the client has not received. Dropping the
    // term would report a recipe that is lighter than it is, which is worse than `—`.
    const known = food({ id: "aaaaaaaa-1111-4111-8111-111111111111", energyMilliKcal: 380_000 });
    const recipe: RecipePayloadV1 = {
      id: "cccccccc-1111-4111-8111-111111111111",
      name: "Half known",
      type: "main",
      baseServings: 1,
      isFavourite: false,
      ingredients: [
        {
          id: "dddddddd-1111-4111-8111-111111111111",
          foodId: known.id,
          quantityThousandths: 100_000,
          unit: "gram",
          position: 0,
        },
        {
          id: "eeeeeeee-1111-4111-8111-111111111111",
          foodId: "ffffffff-1111-4111-8111-111111111111",
          quantityThousandths: 50_000,
          unit: "gram",
          position: 1,
        },
      ],
    };
    const foods = new Map([[known.id, known]]);

    expect(recipeTotal(recipe, foods).energyMilliKcal).toBeNull();
    expect(unresolvedIngredientIds(recipe, foods)).toEqual([
      "eeeeeeee-1111-4111-8111-111111111111",
    ]);
    expect(ingredientContribution(recipe.ingredients[1]!, undefined)).toEqual(NUTRIENTS_UNKNOWN);
  });

  test("a recipe written for no servings yields unknown rather than a division by zero", () => {
    expect(perServing({ ...NUTRIENTS_ZERO, energyMilliKcal: 100 }, 0)).toEqual(NUTRIENTS_UNKNOWN);
  });
});

describe("the intermediate product stays exact", () => {
  test("the widest product any call site can produce is below Number.MAX_SAFE_INTEGER", () => {
    // The claim `nutrition.ts` makes in prose, asserted. Kotlin guards against 2^63 and
    // this guards against 2^53; the difference only matters if a call site could reach
    // between them, and PRD_FOOD 15's own bounds say none can.
    const widest = Math.max(
      // contribution: a per-100 energy times an ingredient quantity in thousandths.
      ENERGY_PER_100_MAX_MILLI_KCAL * INGREDIENT_QUANTITY_MAX_THOUSANDTHS,
      // recipeLine: a canonical value times a consumed serving count in thousandths.
      CANONICAL_MAX * SERVINGS_MAX_THOUSANDTHS,
    );
    expect(widest).toBeLessThan(Number.MAX_SAFE_INTEGER);
  });
});

describe("PRD_FOOD 13.2's two renderings, which must never be confused", () => {
  test("an unknown value reads as a dash and a known zero reads as zero", () => {
    expect(energyLabel(null)).toBe(UNKNOWN);
    expect(energyLabel(0)).toBe("≈ 0 kcal");
    expect(macroLabel(null)).toBe(UNKNOWN);
    expect(macroLabel(0)).toBe("≈ 0.0 g");
  });

  test("a computed value carries the approximation marker", () => {
    expect(energyLabel(1_849_600)).toBe("≈ 1850 kcal");
    expect(macroLabel(133_450)).toBe("≈ 133.5 g");
  });

  test("a value a person typed unchanged carries no marker", () => {
    expect(energyLabel(310_000, false)).toBe("310 kcal");
  });

  test("no decimal separator follows a locale", () => {
    // Assembled digit by digit from the canonical integer, so the same total reads the
    // same everywhere. A locale-aware formatter would write `133,5` in France.
    expect(macroLabel(133_500)).toContain(".");
    expect(macroLabel(133_500)).not.toContain(",");
  });
});

describe("a day of the journal", () => {
  test("a day with no line is a known zero that is nevertheless not recorded", () => {
    const day = dailyNutrition("2026-06-12", []);
    expect(day.isRecorded).toBe(false);
    expect(day.entryCount).toBe(0);
    expect(day.slots).toEqual([]);
    // The total is a known zero -- and `isRecorded` is what stops it being presented as
    // "you ate nothing" when the truth is "nothing was written down".
    expect(day.total).toEqual(NUTRIENTS_ZERO);
  });

  test("a day with one line whose protein is unknown is recorded, and its protein is unknown", () => {
    const day = dailyNutrition("2026-06-12", [
      line({ id: "aaaaaaaa-2222-4222-8222-222222222222", energyMilliKcal: 310_000 }),
      line({
        id: "bbbbbbbb-2222-4222-8222-222222222222",
        slot: "lunch",
        energyMilliKcal: 500_000,
        proteinMilligrams: 30_000,
      }),
    ]);

    expect(day.isRecorded).toBe(true);
    expect(day.total.energyMilliKcal).toBe(810_000);
    expect(day.total.proteinMilligrams).toBeNull();
    // And the day says *which* line left it unknown, which is the half an agent can act on.
    expect(day.unknownFrom.proteinMilligrams).toEqual(["aaaaaaaa-2222-4222-8222-222222222222"]);
    expect(day.unknownFrom.energyMilliKcal).toEqual([]);
  });

  test("moments come back in the contract's order and only when they hold a line", () => {
    const day = dailyNutrition("2026-06-12", [
      line({ id: "aaaaaaaa-2222-4222-8222-222222222222", slot: "dinner" }),
      line({ id: "bbbbbbbb-2222-4222-8222-222222222222", slot: "breakfast" }),
    ]);
    // `MEAL_SLOTS` order, not arrival order -- and no empty moment invented (PRD_FOOD 10.1).
    expect(day.slots.map((slot) => slot.slot)).toEqual(["breakfast", "dinner"]);
  });
});
