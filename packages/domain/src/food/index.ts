// PRD_FOOD 13, on the server. The MCP read tools compute a day's totals and a recipe's
// per-serving values through these functions and nowhere else (PRD section 20.2), so
// PRD_FOOD 13.1's strict propagation has one implementation here and one on Android, and
// no third one inside a tool.

export {
  dailyNutrition,
  nutrientsOfLogEntry,
  type DailyNutrition,
  type MealSlotNutrition,
} from "./daily";
export {
  APPROXIMATE_PREFIX,
  ENERGY_UNIT,
  MACRO_UNIT,
  UNKNOWN,
  energyLabel,
  macroLabel,
} from "./labels";
export {
  CANONICAL_MAX,
  NUTRIENTS_UNKNOWN,
  NUTRIENTS_ZERO,
  NUTRIENT_METRICS,
  PER_100_THOUSANDTHS,
  contribution,
  foodContribution,
  ingredientContribution,
  isFullyUnknown,
  nutrientsOfFood,
  perServing,
  plusNutrients,
  recipeLine,
  recipeLineFor,
  recipeTotal,
  referenceWeightThousandthsOrNull,
  scaledNutrients,
  strictSum,
  unresolvedIngredientIds,
  usualServingContribution,
  usualServingWeightThousandthsOrNull,
  type NutrientMetric,
  type Nutrients,
} from "./nutrition";
