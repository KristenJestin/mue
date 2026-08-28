/**
 * PRD_FOOD 13.2 on the server: how a computed nutritional value is written down.
 *
 * The TypeScript half of `domain/logic/FoodLabels.kt`, and it ships for one reason. An MCP
 * tool hands an agent a number; the agent hands a person a sentence. Between those two
 * steps every rule of PRD_FOOD 13.2 can be lost — the `≈` that says a figure was computed,
 * the `—` that says nobody measured it, the rounding that stops `1849.6` being read out to
 * the tenth of a kilocalorie. Sending the rendered string *next to* the number is what
 * makes the rule survive the trip: an agent that simply quotes [ENERGY] or [macro] is
 * correct by construction, and one that formats the integer itself at least had the
 * correct rendering in front of it.
 *
 * ## Two rules, and they are not decoration
 *
 * **An unknown value is [UNKNOWN], never `0`.** A known zero and an unknown are different
 * facts about the world — black coffee really has no energy, an incomplete Open Food Facts
 * card simply does not say — and nothing here can produce one from the other, because no
 * function takes a fallback.
 *
 * **A computed value carries [APPROXIMATE_PREFIX].** PRD_FOOD 13.2: *"Toute valeur issue
 * d'un calcul ou d'une source externe est précédée de `≈`"*. A day's total is an addition
 * and a per-serving value is a division, so both are computed, always, and the prefix is
 * not conditional on how good the inputs were.
 *
 * Every number is assembled from its canonical integer, digit by digit, with no
 * `Intl.NumberFormat` and no locale, exactly as the Kotlin does. A decimal separator that
 * followed a region would make the same total read `133.5` in one place and `133,5` in
 * another, and PRD_FOOD 13.2 asks for tabular figures, not for a locale.
 */

/** PRD_FOOD 13.2: *"Une valeur inconnue est affichée `—`, jamais `0`."* */
export const UNKNOWN = "—";

/** PRD_FOOD 13.2: the marker every computed or externally sourced value carries. */
export const APPROXIMATE_PREFIX = "≈ ";

export const ENERGY_UNIT = "kcal";
export const MACRO_UNIT = "g";

/** PRD_FOOD 13.2: energy is rounded to the unit. */
const ENERGY_DECIMALS = 0;

/** PRD_FOOD 13.2: a macronutrient to the tenth of a gram, and the tenth is always shown. */
const MACRO_DECIMALS = 1;

const MILLI_PER_KCAL = 1_000;
const MILLIGRAMS_PER_GRAM = 1_000;

/**
 * `canonical / scale` written with `decimals` decimal places, rounded half-up.
 *
 * Integer arithmetic end to end: every canonical unit of the module is a whole thousandth
 * of its display unit, so nothing drifts to `0.0000001` and no rounding depends on a
 * floating-point mode.
 */
function decimal(canonical: number, scale: number, decimals: number): string {
  const factor = 10 ** decimals;
  const scaled = Math.floor((canonical * factor + Math.floor(scale / 2)) / scale);
  const whole = Math.floor(scaled / factor);
  if (decimals === 0) return String(whole);
  const fraction = String(scaled % factor).padStart(decimals, "0");
  return `${whole}.${fraction}`;
}

function prefixed(text: string, approximate: boolean): string {
  return approximate ? `${APPROXIMATE_PREFIX}${text}` : text;
}

/**
 * An energy in thousandths of a kilocalorie, or [UNKNOWN].
 *
 * `approximate` defaults to true because almost every energy the Food module shows came out
 * of a calculation or an external table, which PRD_FOOD 13.2 marks `≈` in both cases. A
 * caller displaying a figure a person typed unchanged passes false.
 */
export function energyLabel(milliKcal: number | null, approximate = true): string {
  if (milliKcal === null) return UNKNOWN;
  return prefixed(
    `${decimal(milliKcal, MILLI_PER_KCAL, ENERGY_DECIMALS)} ${ENERGY_UNIT}`,
    approximate,
  );
}

/** A macronutrient in milligrams, to the tenth of a gram, or [UNKNOWN]. */
export function macroLabel(milligrams: number | null, approximate = true): string {
  if (milligrams === null) return UNKNOWN;
  return prefixed(
    `${decimal(milligrams, MILLIGRAMS_PER_GRAM, MACRO_DECIMALS)} ${MACRO_UNIT}`,
    approximate,
  );
}
