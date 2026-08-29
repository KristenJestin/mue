import {
  divideWorking,
  formatWorking,
  fromInteger,
  multiplyWorking,
  timesInteger,
  toStoredInteger,
} from "./decimal";
import {
  AGE_COEFFICIENT,
  FAT_FREE_MASS_HYDRATION,
  FORMULA_ID,
  FORMULA_VERSION,
  IMPEDANCE_INDEX_COEFFICIENT,
  INTERCEPT,
  ONE_HUNDRED,
  RESTING_ENERGY_AGE_COEFFICIENT,
  RESTING_ENERGY_HEIGHT_COEFFICIENT,
  RESTING_ENERGY_WEIGHT_COEFFICIENT,
  SEX_COEFFICIENT,
  type Sex,
  WEIGHT_COEFFICIENT,
  bmiOrNull,
  isAgeInDomain,
  isBmiInDomain,
  isHeightUsable,
  isImpedanceUsable,
  isKnownFormula,
  restingEnergyOffset,
  sexCoefficient,
} from "./formula";

/**
 * PRD_SCALE 13.2 on the server: pure, deterministic, clockless.
 *
 * This is the TypeScript half of `domain/logic/BodyCompositionCalculator.kt`. It exists
 * because PRD_SCALE 22 has the server recalculate a composition from a payload — for a
 * client that cannot, and to verify one that could — and PRD section 20.2 says a rule is
 * implemented once per environment and never a third time inside a route or an MCP tool.
 * PRD_SCALE 13.2 then makes the two halves testable against each other: the same payload
 * must produce the same stored integers, and
 * `apps/android/app/src/test/resources/bodycomposition/mue-foot-to-foot-v1.json` is the
 * contract both replay.
 *
 * ## Clockless, and that is the point
 *
 * Nothing here reads today's date. The age at the date of the measurement is a parameter,
 * so the same function serves a fresh weigh-in and FR-BODY-006's retroactive
 * recalculation — which must use the age of *each* past date. A function calling
 * `new Date()` would be silently wrong by a year for everyone whose birthday has passed.
 *
 * The measurement date is not a parameter at all: it enters no equation, it is only the
 * identity of the row that stores the result, and the caller that stores it already holds
 * it. Leaving it out keeps this function honest about what it actually depends on.
 *
 * ## Order of the gates
 *
 * Formula version, then impedance, then profile inputs, then age, then BMI, then the
 * calculation, then the output checks. The first refusal wins.
 *
 * Impedance comes before the profile deliberately, and Android documents why: without it
 * there is nothing to compute whatever the profile says, and every manually entered weight
 * lacks one. Were the profile examined first, each manual weight of a profile without a sex
 * would answer "the sex is missing", and a caller counting the past weigh-ins that
 * completing a profile would unlock (FR-BODY-006) would count all of them. With this order,
 * [MissingProfileInput] designates only measurements that really do carry a usable
 * impedance.
 *
 * The formula version comes before all of them because it is not a property of the
 * measurement at all: it says which equations the answer is even about.
 */

/** The three profile inputs a composition needs (FR-BODY-001), in the order they are reported. */
export const PROFILE_INPUTS = ["height", "birthDate", "sex"] as const;

/**
 * A missing profile input.
 *
 * `birthDate` and not "age": it is the birth date the profile lacks and the birth date a
 * screen asks for. The age is only its projection onto the date of the measurement.
 */
export type ProfileInput = (typeof PROFILE_INPUTS)[number];

/** The output checks of PRD_SCALE 13.2, in the order they are applied. */
export const PLAUSIBILITY_CHECKS = [
  // FFM > 0
  "fat-free-mass-not-positive",
  // FFM <= weight
  "fat-free-mass-above-weight",
  // 0 < body fat % < 100
  "body-fat-percent-out-of-range",
  // water <= weight
  "body-water-above-weight",
  // 0 < water % < 100
  "body-water-percent-out-of-range",
  // resting energy > 0
  "resting-energy-not-positive",
] as const;

/** Which output check of PRD_SCALE 13.2 failed. */
export type PlausibilityCheck = (typeof PLAUSIBILITY_CHECKS)[number];

/**
 * The four stored integers of a composition, plus the inputs that produced them
 * (PRD_SCALE 21.1).
 *
 * The inputs are carried with the result rather than left to the caller to remember: a
 * recalculation under a later formula version (FR-BODY-004) has to know what the previous
 * one was fed, and a row that stores only its outputs cannot say.
 */
export interface BodyComposition {
  readonly formulaId: string;
  readonly formulaVersion: number;
  readonly inputWeightCg: number;
  readonly inputHeightCm: number;
  readonly inputAgeYears: number;
  readonly inputSex: Sex;
  /** Tenths of a percent of body fat. */
  readonly bodyFatDeciPercent: number;
  /** Hundredths of a kilogram of fat-free mass. */
  readonly fatFreeMassCg: number;
  /** Tenths of a percent of body water. */
  readonly bodyWaterDeciPercent: number;
  /** Whole kilocalories of resting energy expenditure. */
  readonly restingEnergyKcal: number;
}

/** Every outcome name, shared with the versioned vectors. */
export type BodyCompositionOutcome = BodyCompositionResult["outcome"];

/**
 * What an attempt at a composition produced.
 *
 * **Why not a `BodyComposition | null`.** A silent absence would force every caller to redo
 * the diagnosis itself, with its own thresholds, which would drift. The server has to be
 * able to say *why* there is no composition — a sync response explaining a refusal, an MCP
 * tool answering a question, a log worth reading — and the outcomes call for different
 * answers: record the composition; name the missing profile input; say plainly that
 * estimates are unavailable for this profile **without showing the BMI or the age**
 * (FR-BODY-001); note an aberrant reading. One `null` would merge all of them.
 *
 * In every refusal the weight stays valid and a usable impedance stays recorded on the
 * measurement (FR-BODY-004, BR-SCALE-008): this type decides the fate of the composition
 * and of nothing else.
 *
 * The `outcome` strings are the ones the versioned vectors use, so the JSON is replayed
 * without a translation table that could itself be wrong.
 */
export type BodyCompositionResult =
  | {
      /** The composition was calculated and can be written with its measurement. */
      readonly outcome: "calculated";
      readonly composition: BodyComposition;
    }
  | {
      /** Impedance absent, zero or negative (BR-SCALE-005, FR-BODY-002). */
      readonly outcome: "impedance-unusable";
      readonly impedanceOhm: number | null;
    }
  | {
      /** At least one of the three profile inputs is missing (FR-BODY-001). */
      readonly outcome: "missing-profile-input";
      /** Which ones, in the order of [PROFILE_INPUTS], so a message can name them. */
      readonly missing: readonly ProfileInput[];
    }
  | {
      /** The age at the date of the measurement is outside the domain (FR-BODY-001). */
      readonly outcome: "age-out-of-domain";
      /** Kept for the technical log; FR-BODY-001 does not let a screen show it. */
      readonly ageYears: number;
    }
  | {
      /** The BMI is outside `15.8-43.1`, where the equation was developed (FR-BODY-001). */
      readonly outcome: "bmi-out-of-domain";
      /**
       * The exact, unrounded value the gate compared, as a decimal string. For the
       * technical log only, never for a screen. `null` only when the height cannot produce
       * a BMI at all — unreachable from [calculateBodyComposition], which validates the
       * height first.
       */
      readonly bmi: string | null;
    }
  | {
      /**
       * The calculation succeeded and its result is physically impossible
       * (PRD_SCALE 13.2). Nothing is pulled back inside the bounds; the composition is
       * simply absent.
       */
      readonly outcome: "physically-implausible";
      readonly check: PlausibilityCheck;
    }
  | {
      /** The caller asked for a formula set this build does not implement (PRD_SCALE 22). */
      readonly outcome: "unknown-formula";
      readonly formulaId: string;
      readonly formulaVersion: number;
    };

/** The composition when there is one, `null` otherwise, for callers with no message to write. */
export function compositionOrNull(result: BodyCompositionResult): BodyComposition | null {
  return result.outcome === "calculated" ? result.composition : null;
}

/**
 * The primitive inputs of a calculation.
 *
 * Primitives and not a domain object: this is what arrives over the wire, and the fewer
 * shapes stand between the payload and the equation, the fewer places a unit can be lost.
 */
export interface BodyCompositionInput {
  /** Weight in hundredths of a kilogram, the parent measurement's (BR-SCALE-015). */
  readonly weightCg: number;
  /** Profile height in centimetres; `null`, or outside the profile range, when absent. */
  readonly heightCm: number | null;
  /**
   * Whole age **at the date of the measurement**, `null` without a birth date. Never the
   * age on the day of the calculation (FR-BODY-006).
   */
  readonly ageYears: number | null;
  /** `null` while the profile does not state it (FR-PROFILE-007). */
  readonly sex: Sex | null;
  /**
   * Impedance of the measurement, `null` when the driver reported an impossible reading
   * (BR-SCALE-005) or when the weigh-in was manual.
   */
  readonly impedanceOhm: number | null;
}

/**
 * Rejects a non-integer input before it can produce a different integer than Kotlin does.
 *
 * The wire schema is what normally guarantees this, and it belongs there. But a fractional
 * centigram reaching the equation would not fail: it would return four plausible integers
 * that Android cannot reproduce, and PRD_SCALE 13.2's parity would be broken by a value no
 * test vector describes. Failing loudly is the only outcome that stays honest, and it is a
 * programming error rather than a business refusal — no caller can act on it.
 */
function requireInteger(name: string, value: number): void {
  if (!Number.isSafeInteger(value)) {
    throw new TypeError(`body composition: ${name} must be a safe integer, got ${value}`);
  }
}

/**
 * Calculates a composition with the current formula set, from primitive inputs.
 *
 * Callers that are recalculating on behalf of a client, and therefore have a version to
 * honour, go through [recalculateBodyComposition] instead.
 */
export function calculateBodyComposition(input: BodyCompositionInput): BodyCompositionResult {
  const { weightCg, heightCm, ageYears, sex, impedanceOhm } = input;
  requireInteger("weightCg", weightCg);
  if (heightCm !== null) {
    requireInteger("heightCm", heightCm);
  }
  if (ageYears !== null) {
    requireInteger("ageYears", ageYears);
  }
  if (impedanceOhm !== null) {
    requireInteger("impedanceOhm", impedanceOhm);
  }

  if (!isImpedanceUsable(impedanceOhm)) {
    return { outcome: "impedance-unusable", impedanceOhm };
  }

  const height = isHeightUsable(heightCm) ? heightCm : null;
  if (height === null || ageYears === null || sex === null) {
    // Built in the declaration order of PROFILE_INPUTS, so a message naming two missing
    // inputs names them in the same order on both sides of the wire.
    const missing: ProfileInput[] = [];
    if (height === null) {
      missing.push("height");
    }
    if (ageYears === null) {
      missing.push("birthDate");
    }
    if (sex === null) {
      missing.push("sex");
    }
    return { outcome: "missing-profile-input", missing };
  }

  if (!isAgeInDomain(ageYears)) {
    return { outcome: "age-out-of-domain", ageYears };
  }

  const bmi = bmiOrNull(weightCg, height);
  if (bmi === null || !isBmiInDomain(bmi)) {
    return { outcome: "bmi-out-of-domain", bmi: bmi === null ? null : formatWorking(bmi) };
  }

  const quantities = compute(weightCg, height, ageYears, sex, impedanceOhm);

  const failure = plausibilityFailureOf(quantities);
  if (failure !== null) {
    return { outcome: "physically-implausible", check: failure };
  }

  // The single rounding to whole storage units (PRD_SCALE 21.1).
  const fatFreeMassCg = toStoredInteger(timesInteger(quantities.fatFreeMassKg, 100n));
  const bodyFatDeciPercent = toStoredInteger(timesInteger(quantities.bodyFatPercent, 10n));
  const bodyWaterDeciPercent = toStoredInteger(timesInteger(quantities.bodyWaterPercent, 10n));
  const restingEnergyKcal = toStoredInteger(quantities.restingEnergyKcal);

  const storedFailure = storedFailureOf({
    weightCg,
    fatFreeMassCg,
    bodyFatDeciPercent,
    bodyWaterDeciPercent,
    restingEnergyKcal,
  });
  if (storedFailure !== null) {
    return { outcome: "physically-implausible", check: storedFailure };
  }

  return {
    outcome: "calculated",
    composition: {
      formulaId: FORMULA_ID,
      formulaVersion: FORMULA_VERSION,
      inputWeightCg: weightCg,
      inputHeightCm: height,
      inputAgeYears: ageYears,
      inputSex: sex,
      bodyFatDeciPercent,
      fatFreeMassCg,
      bodyWaterDeciPercent,
      restingEnergyKcal,
    },
  };
}

/**
 * The same calculation, for a caller that names the formula set it wants (PRD_SCALE 22).
 *
 * An unknown identifier or version is refused before anything is computed. Answering with
 * the current set's numbers under the requested version's name would store, under a version
 * a client believes it understands, integers a different equation produced — and no later
 * migration could tell the two apart.
 */
export function recalculateBodyComposition(
  formulaId: string,
  formulaVersion: number,
  input: BodyCompositionInput,
): BodyCompositionResult {
  if (!isKnownFormula(formulaId, formulaVersion)) {
    return { outcome: "unknown-formula", formulaId, formulaVersion };
  }
  return calculateBodyComposition(input);
}

/** The decimal quantities, before any storage rounding. */
interface Quantities {
  readonly weightKg: bigint;
  readonly fatFreeMassKg: bigint;
  readonly bodyFatPercent: bigint;
  readonly bodyWaterKg: bigint;
  readonly bodyWaterPercent: bigint;
  readonly restingEnergyKcal: bigint;
}

/**
 * The five equations of PRD_SCALE 13.2, in the order they follow from one another.
 *
 * Every product and every quotient is brought back to the working scale as soon as it is
 * formed; sums of values already at that scale are exact. That granularity — rounding at
 * each operation rather than at each line — is the part of the rule a port gets wrong
 * silently, which is why this is written operation by operation rather than as one
 * expression per line.
 *
 * The percentages divide `mass × 100` by the weight, and not `mass / weight` then `× 100`:
 * one division, so one rounding, and a rounding that is not then multiplied by a hundred.
 */
function compute(
  weightCg: number,
  heightCm: number,
  ageYears: number,
  sex: Sex,
  impedanceOhm: number,
): Quantities {
  const weightKg = divideWorking(fromInteger(weightCg), ONE_HUNDRED);
  const impedanceIndex = divideWorking(fromInteger(heightCm * heightCm), fromInteger(impedanceOhm));

  const fatFreeMassKg =
    INTERCEPT +
    multiplyWorking(WEIGHT_COEFFICIENT, weightKg) +
    multiplyWorking(IMPEDANCE_INDEX_COEFFICIENT, impedanceIndex) -
    multiplyWorking(AGE_COEFFICIENT, fromInteger(ageYears)) +
    multiplyWorking(SEX_COEFFICIENT, sexCoefficient(sex));

  const fatMassKg = weightKg - fatFreeMassKg;
  const bodyFatPercent = divideWorking(multiplyWorking(fatMassKg, ONE_HUNDRED), weightKg);

  const bodyWaterKg = multiplyWorking(fatFreeMassKg, FAT_FREE_MASS_HYDRATION);
  const bodyWaterPercent = divideWorking(multiplyWorking(bodyWaterKg, ONE_HUNDRED), weightKg);

  const restingEnergyKcal =
    multiplyWorking(RESTING_ENERGY_WEIGHT_COEFFICIENT, weightKg) +
    multiplyWorking(RESTING_ENERGY_HEIGHT_COEFFICIENT, fromInteger(heightCm)) -
    multiplyWorking(RESTING_ENERGY_AGE_COEFFICIENT, fromInteger(ageYears)) +
    restingEnergyOffset(sex);

  return {
    weightKg,
    fatFreeMassKg,
    bodyFatPercent,
    bodyWaterKg,
    bodyWaterPercent,
    restingEnergyKcal,
  };
}

/**
 * PRD_SCALE 13.2's output checks, on the decimal quantities, before any rounding. `null`
 * when they all pass.
 */
function plausibilityFailureOf(q: Quantities): PlausibilityCheck | null {
  if (q.fatFreeMassKg <= 0n) {
    return "fat-free-mass-not-positive";
  }
  if (q.fatFreeMassKg > q.weightKg) {
    return "fat-free-mass-above-weight";
  }
  if (q.bodyFatPercent <= 0n || q.bodyFatPercent >= ONE_HUNDRED) {
    return "body-fat-percent-out-of-range";
  }
  if (q.bodyWaterKg > q.weightKg) {
    return "body-water-above-weight";
  }
  if (q.bodyWaterPercent <= 0n || q.bodyWaterPercent >= ONE_HUNDRED) {
    return "body-water-percent-out-of-range";
  }
  if (q.restingEnergyKcal <= 0n) {
    return "resting-energy-not-positive";
  }
  return null;
}

/**
 * The same checks, replayed on the integers actually stored.
 *
 * Rounding is monotone: it cannot lift a fat-free mass above a weight it was below. But it
 * can land `99.97 %` on `1000` tenths, that is, show `100.0 %` body fat — a value
 * PRD_SCALE 13.2 refuses in decimal and that it would be absurd to accept as an integer on
 * the grounds that a rounding produced it. This second pass guarantees that what is
 * *stored*, and not only what was computed, satisfies the bounds. It pulls nothing back
 * inside them: it refuses.
 */
function storedFailureOf(stored: {
  weightCg: number;
  fatFreeMassCg: number;
  bodyFatDeciPercent: number;
  bodyWaterDeciPercent: number;
  restingEnergyKcal: number;
}): PlausibilityCheck | null {
  if (stored.fatFreeMassCg <= 0) {
    return "fat-free-mass-not-positive";
  }
  if (stored.fatFreeMassCg > stored.weightCg) {
    return "fat-free-mass-above-weight";
  }
  if (stored.bodyFatDeciPercent < 1 || stored.bodyFatDeciPercent > 999) {
    return "body-fat-percent-out-of-range";
  }
  if (stored.bodyWaterDeciPercent < 1 || stored.bodyWaterDeciPercent > 999) {
    return "body-water-percent-out-of-range";
  }
  if (stored.restingEnergyKcal <= 0) {
    return "resting-energy-not-positive";
  }
  return null;
}
