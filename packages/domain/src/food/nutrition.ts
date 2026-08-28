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
 * There are exactly five call sites, and PRD_FOOD 15 bounds all five:
 *
 *  - [referenceWeightThousandthsOrNull]: a weighed quantity (at most 5 000 000) times a
 *    thousand — 5.0e9. Its *result* is what widens the line below;
 *  - [usualServingWeightThousandthsOrNull]: a usual serving size (at most 2 000 000) times a
 *    portion count in thousandths (at most 20 000) — 4.0e10;
 *  - [contribution]: a per-100 value (at most 900 000) times a reference weight in
 *    thousandths. The widest such weight is a usual portion — twenty of a 2 000 g serving,
 *    4.0e7 — ahead of a cooked correction's 1.7e7 and a bare ingredient's 5.0e6, so 3.6e13;
 *  - [perServing]: numerator 1 — at most 2.1e9;
 *  - [recipeLine]: a canonical value (at most 2.1e9) times a serving count in thousandths
 *    (at most 10 000) — 2.1e13.
 *
 * The largest is 3.6e13, and 2^53 is 9.0e15. `nutrition.test.ts` asserts the ceiling
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
 * PRD_FOOD 13.1, line one: `poids de reference = poids pese / cookedRatio si pese cuit`.
 *
 * The TypeScript half of `NutritionMath.referenceWeightOrNull` and
 * `Quantity.toReferenceWeightOrNull`, down to the half-up division — a weight and the
 * nutrients derived from it have to fall on the same side of a thousandth, so the module has
 * one rounding rule and not two.
 *
 * The correction is applied **once**, **before** the per-100 contribution, and **only to the
 * quantity**. A food's per-100 figures already describe its reference state (PRD_FOOD 8.2),
 * so bringing the weight back to that state is the whole of the correction; applying the
 * ratio to the nutrients as well would apply it twice.
 *
 * Three cases collapse to the identity, and Kotlin names all three: the weight was taken in
 * the reference state, which is the ordinary case; the food declares no ratio, so there is no
 * cooked state to convert from and a stored flag is a leftover rather than a reason to invent
 * a divisor; or the ratio is exactly 1.000.
 *
 * The result may legitimately exceed PRD_FOOD 15's ingredient ceiling — 5 000 g of something
 * that lost water at a ratio of 0.3 came from 16 666 g of it — so it is bounded by
 * [CANONICAL_MAX] and by nothing narrower, exactly as `Quantity.ofThousandthsOrNull` is.
 */
export function referenceWeightThousandthsOrNull(
  weighedThousandths: number,
  cookedRatioThousandths: number | undefined,
  weighedCooked: boolean,
): number | null {
  if (!weighedCooked || cookedRatioThousandths === undefined) return weighedThousandths;
  if (cookedRatioThousandths <= 0) return null;
  const reference = scaleOrNull(weighedThousandths, 1_000, cookedRatioThousandths);
  // Zero is not a quantity, and `Quantity.ofThousandthsOrNull` refuses it for the same reason:
  // a weight that rounded away is not a weight of nothing.
  return reference === null || reference < 1 ? null : reference;
}

/**
 * A `FOOD` line of PRD_FOOD 10.2, for a quantity read on a scale: line one of PRD_FOOD 13.1
 * and then line two, in that order and each exactly once.
 *
 * An unrepresentable reference weight yields unknown values rather than a wrong number, which
 * is `NutritionMath.foodContribution`'s decision and the same one [ingredientContribution]
 * makes about a food it cannot find.
 */
export function foodContribution(
  food: FoodPayloadV1,
  weighedThousandths: number,
  weighedCooked: boolean,
): Nutrients {
  const reference = referenceWeightThousandthsOrNull(
    weighedThousandths,
    food.cookedRatioThousandths,
    weighedCooked,
  );
  if (reference === null) return NUTRIENTS_UNKNOWN;
  return contribution(nutrientsOfFood(food), reference);
}

/**
 * PRD_FOOD 8.6: what a count of a food's own usual portions weighs — `1.5 x apple` at 150 g
 * each.
 *
 * Null when the food declares no portion size, because there is then nothing to multiply and
 * no weight to guess at.
 */
export function usualServingWeightThousandthsOrNull(
  food: FoodPayloadV1,
  portionsThousandths: number,
): number | null {
  if (food.servingThousandths === undefined) return null;
  const weight = scaleOrNull(food.servingThousandths, portionsThousandths, 1_000);
  return weight === null || weight < 1 ? null : weight;
}

/**
 * The contribution of a `FOOD` line entered as a count of the food's usual portions.
 *
 * No cooked ratio applies here, and `NutritionMath.usualServingContribution` says why: *"a
 * usual portion is an aid to typing, never a cooked reading"*, so PRD_FOOD 8.6 resolves it to
 * grams of the food as the catalogue already describes it.
 */
export function usualServingContribution(
  food: FoodPayloadV1,
  portionsThousandths: number,
): Nutrients {
  const weight = usualServingWeightThousandthsOrNull(food, portionsThousandths);
  if (weight === null) return NUTRIENTS_UNKNOWN;
  return contribution(nutrientsOfFood(food), weight);
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
 * A `RECIPE` line worked out from the recipe itself: the three formulas above, in order.
 *
 * `NutritionMath.recipeLine(detail, foods, servings)` is already this overload on Android,
 * and this is its half. It exists so that *what one serving of a recipe is worth* has a
 * single definition: a caller composing the three by hand can divide by `baseServings` twice,
 * or forget to divide at all, and both mistakes produce a plausible number.
 */
export function recipeLineFor(
  recipe: RecipePayloadV1,
  foods: ReadonlyMap<string, FoodPayloadV1>,
  servingsThousandths: number,
): Nutrients {
  return recipeLine(
    perServing(recipeTotal(recipe, foods), recipe.baseServings),
    servingsThousandths,
  );
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
