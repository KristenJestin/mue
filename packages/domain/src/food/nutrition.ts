import type { FoodPayloadV1, RecipeIngredient, RecipePayloadV1 } from "@mue/contracts";

/**
 * PRD_FOOD 13.1, on the server, once.
 *
 * This is the TypeScript half of `domain/logic/NutritionMath.kt` and
 * `domain/model/Nutrients.kt`. It exists because PRD_FOOD 21.5 asks the server for two
 * computed answers — a day's totals and a recipe's per-serving values — and PRD section
 * 20.2 says a rule is implemented once. The alternative was arithmetic inside an MCP
 * tool, which is a second implementation of PRD_FOOD 13.1 that nothing compares against
 * the first.
 *
 * ## The one rule everything here exists to keep
 *
 * `null` is **unknown**; `0` is a measured zero. No conversion between them is allowed —
 * not on entry, not in a sum, not on the way out. Android states it as a review gate:
 * *"No `?: 0` appears anywhere in the Food domain"*. The same gate applies here, and it
 * is why every operation below is written against a bundle rather than against five loose
 * numbers: with five nullable fields the rule has to be re-applied by hand five times per
 * operation, and the fifth is the one that gets forgotten.
 *
 * Propagation is strict **metric by metric**. A known energy coexists with an unknown
 * protein, and one unknown contribution makes only *its own* metric unknown for the whole
 * total.
 *
 * ## Where this deliberately differs from Kotlin, and where it must not
 *
 * The arithmetic is identical: integers throughout, products taken wide, half-up rounding,
 * and a result narrowed back to [CANONICAL_MAX] or else `null`. What differs is the width
 * of the intermediate — Kotlin has `Long`, JavaScript has `Number.MAX_SAFE_INTEGER` — so
 * [scaleOrNull] guards against 2^53 where `Nutrients.scaleOrNull` guards against 2^63.
 *
 * That difference is unreachable, and it is worth writing down why rather than hoping.
 * There are exactly three call sites, and PRD_FOOD 15 bounds all three:
 *
 *  - [contribution]: a per-100 value (at most 900 000) times a quantity in thousandths (at
 *    most 5 000 000) — 4.5e12;
 *  - [perServing]: numerator 1 — at most 2.1e9;
 *  - [recipeLine]: a canonical value (at most 2.1e9) times a serving count in thousandths
 *    (at most 10 000) — 2.1e13.
 *
 * The largest is 2.1e13, and 2^53 is 9.0e15. `nutrition.test.ts` asserts the ceiling
 * rather than leaving it to this comment.
 */

/**
 * The five metrics of PRD_FOOD 8.2, in canonical integer units, each independently
 * nullable.
 *
 * `null` means nobody measured it. It does not mean zero, and it is not a default.
 */
export interface Nutrients {
  /** Thousandths of a kilocalorie. */
  readonly energyMilliKcal: number | null;
  readonly proteinMilligrams: number | null;
  readonly carbsMilligrams: number | null;
  readonly fatMilligrams: number | null;
  readonly fibreMilligrams: number | null;
}

/**
 * The metric names, in the order PRD_FOOD 8.2 lists them.
 *
 * Exported so a caller that has to walk the five — a total that reports *which* metrics
 * are unknown, for instance — walks a list rather than writing the names out a sixth time.
 */
export const NUTRIENT_METRICS = [
  "energyMilliKcal",
  "proteinMilligrams",
  "carbsMilligrams",
  "fatMilligrams",
  "fibreMilligrams",
] as const;

export type NutrientMetric = (typeof NUTRIENT_METRICS)[number];

/**
 * The widest value any metric can hold, which is `Int.MAX_VALUE` on Android.
 *
 * It is a property of the storage, not of nutrition: `Energy.ofMilliKcalOrNull` and
 * `Macro.ofMilligramsOrNull` both narrow to `Int`, and a total that does not fit is
 * genuinely not known rather than wrapped into a number that would be shown as a fact.
 */
export const CANONICAL_MAX = 2_147_483_647;

/** A per-100 value is quoted per 100 g or 100 ml, which is 100 000 thousandths. */
export const PER_100_THOUSANDTHS = 100_000;

/**
 * Everything unknown — the nominal state of an incomplete Open Food Facts card
 * (PRD_FOOD 9.2), and what a strict sum collapses to as soon as one contribution is
 * missing.
 */
export const NUTRIENTS_UNKNOWN: Nutrients = {
  energyMilliKcal: null,
  proteinMilligrams: null,
  carbsMilligrams: null,
  fatMilligrams: null,
  fibreMilligrams: null,
};

/**
 * Everything known and equal to zero — water, black coffee, an empty day.
 *
 * The identity of [plusNutrients] and therefore the seed of [strictSum]. Emphatically not
 * [NUTRIENTS_UNKNOWN]: summing no lines at all yields a total of zero that is *known*,
 * while summing one unknown line yields a total that is not. Which of the two a caller may
 * present is a separate question, and `dailyNutrition` answers it with `isRecorded`.
 */
export const NUTRIENTS_ZERO: Nutrients = {
  energyMilliKcal: 0,
  proteinMilligrams: 0,
  carbsMilligrams: 0,
  fatMilligrams: 0,
  fibreMilligrams: 0,
};

function sumOrNull(a: number | null, b: number | null): number | null {
  if (a === null || b === null) return null;
  const total = a + b;
  return total > CANONICAL_MAX ? null : total;
}

/**
 * `value × numerator / denominator`, rounded half-up, or null when the product cannot be
 * taken exactly or the result does not fit a metric.
 *
 * The overflow test is a division rather than a try/catch: every operand here is a
 * non-negative integer, which makes the test exact.
 */
function scaleOrNull(value: number, numerator: number, denominator: number): number | null {
  if (numerator === 0) return 0;
  const headroom = (Number.MAX_SAFE_INTEGER - Math.floor(denominator / 2)) / numerator;
  if (value > headroom) return null;
  const scaled = Math.floor((value * numerator + Math.floor(denominator / 2)) / denominator);
  return scaled > CANONICAL_MAX ? null : scaled;
}

function scaleMetric(value: number | null, numerator: number, denominator: number): number | null {
  return value === null ? null : scaleOrNull(value, numerator, denominator);
}

/** PRD_FOOD 13.1's strict addition: null as soon as one side of a metric is unknown. */
export function plusNutrients(a: Nutrients, b: Nutrients): Nutrients {
  return {
    energyMilliKcal: sumOrNull(a.energyMilliKcal, b.energyMilliKcal),
    proteinMilligrams: sumOrNull(a.proteinMilligrams, b.proteinMilligrams),
    carbsMilligrams: sumOrNull(a.carbsMilligrams, b.carbsMilligrams),
    fatMilligrams: sumOrNull(a.fatMilligrams, b.fatMilligrams),
    fibreMilligrams: sumOrNull(a.fibreMilligrams, b.fibreMilligrams),
  };
}

/**
 * PRD_FOOD 13.1's `somme stricte`, over the lines of a recipe, of a moment or of a day.
 *
 * An empty list sums to [NUTRIENTS_ZERO], because a total of nothing is a known nothing; a
 * list containing one unknown metric sums to null *for that metric*, because a single
 * missing contribution is exactly what PRD_FOOD 13.1 says makes the total unknown.
 */
export function strictSum(items: Iterable<Nutrients>): Nutrients {
  let total = NUTRIENTS_ZERO;
  for (const item of items) total = plusNutrients(total, item);
  return total;
}

/** This bundle multiplied by `numerator / denominator`, metric by metric. */
export function scaledNutrients(
  nutrients: Nutrients,
  numerator: number,
  denominator: number,
): Nutrients {
  if (numerator < 0 || denominator <= 0) return NUTRIENTS_UNKNOWN;
  return {
    energyMilliKcal: scaleMetric(nutrients.energyMilliKcal, numerator, denominator),
    proteinMilligrams: scaleMetric(nutrients.proteinMilligrams, numerator, denominator),
    carbsMilligrams: scaleMetric(nutrients.carbsMilligrams, numerator, denominator),
    fatMilligrams: scaleMetric(nutrients.fatMilligrams, numerator, denominator),
    fibreMilligrams: scaleMetric(nutrients.fibreMilligrams, numerator, denominator),
  };
}

/** True when no metric of the bundle is known. */
export function isFullyUnknown(nutrients: Nutrients): boolean {
  return NUTRIENT_METRICS.every((metric) => nutrients[metric] === null);
}

/**
 * The per-100 block of a stored food, as a bundle.
 *
 * The payload states an unknown nutrient as an *absent key* (PRD_FOOD 13.1 and
 * `food.ts`'s own note); this is the one place that absence becomes the `null` the
 * arithmetic propagates, and it is a widening, never a defaulting.
 */
export function nutrientsOfFood(food: FoodPayloadV1): Nutrients {
  return {
    energyMilliKcal: food.energyMilliKcal ?? null,
    proteinMilligrams: food.proteinMilligrams ?? null,
    carbsMilligrams: food.carbsMilligrams ?? null,
    fatMilligrams: food.fatMilligrams ?? null,
    fibreMilligrams: food.fibreMilligrams ?? null,
  };
}

/**
 * PRD_FOOD 13.1, line two: `contribution = poids de reference x valeurPour100 / 100`.
 *
 * [referenceWeightThousandths] is already in the food's reference state. The cooking
 * correction of line one belongs to the *quantity* and is applied before this, never here
 * and never twice.
 */
export function contribution(per100: Nutrients, referenceWeightThousandths: number): Nutrients {
  return scaledNutrients(per100, referenceWeightThousandths, PER_100_THOUSANDTHS);
}

/**
 * One ingredient's contribution, against the foods this account holds.
 *
 * A missing food is not an error to reject. PRD_FOOD 21.2 lets a recipe name a food a
 * client has not received, and the ingredient still renders from its `foodName` snapshot;
 * its contribution is simply unknown. That is the difference between *"this recipe has no
 * protein"* and *"this recipe's protein cannot be worked out from here"*, and only the
 * second is true.
 */
export function ingredientContribution(
  ingredient: RecipeIngredient,
  food: FoodPayloadV1 | undefined,
): Nutrients {
  if (food === undefined) return NUTRIENTS_UNKNOWN;
  return contribution(nutrientsOfFood(food), ingredient.quantityThousandths);
}

/** PRD_FOOD 13.1: `total d'une recette = somme stricte des contributions de ses ingredients`. */
export function recipeTotal(
  recipe: RecipePayloadV1,
  foods: ReadonlyMap<string, FoodPayloadV1>,
): Nutrients {
  return strictSum(
    recipe.ingredients.map((ingredient) =>
      ingredientContribution(ingredient, foods.get(ingredient.foodId)),
    ),
  );
}

/**
 * PRD_FOOD 13.1: `valeur par portion = total de la recette / baseServings`.
 *
 * A non-positive `baseServings` cannot pass PRD_FOOD 15 and yields unknown values rather
 * than a division by zero, if a malformed row ever reaches this far.
 */
export function perServing(total: Nutrients, baseServings: number): Nutrients {
  return scaledNutrients(total, 1, baseServings);
}

/** PRD_FOOD 13.1: `ligne RECIPE = valeur par portion x portions consommees`. */
export function recipeLine(servingValues: Nutrients, servingsThousandths: number): Nutrients {
  return scaledNutrients(servingValues, servingsThousandths, 1_000);
}

/**
 * Which ingredients of a recipe this account cannot resolve, in position order.
 *
 * Returned rather than folded into the total, because *why* a value is unknown is the half
 * an agent can act on: "the recipe's protein is unknown because two of its foods have not
 * reached this server" is a sentence a person can do something about, and "—" is not.
 */
export function unresolvedIngredientIds(
  recipe: RecipePayloadV1,
  foods: ReadonlyMap<string, FoodPayloadV1>,
): readonly string[] {
  return recipe.ingredients
    .filter((ingredient) => !foods.has(ingredient.foodId))
    .map((ingredient) => ingredient.id);
}
