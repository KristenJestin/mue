import { divideWorking, fromDecimal, fromInteger } from "./decimal";

/**
 * The identity, the coefficients and the domain of validity of the `mue-foot-to-foot-v1`
 * formula set (PRD_SCALE 13.2), on the server.
 *
 * This is the TypeScript half of `domain/logic/BodyCompositionFormula.kt`, and it is split
 * from the calculation for the same reason Android splits it: three things with unrelated
 * lifetimes live here. What is written into every stored row ([FORMULA_ID],
 * [FORMULA_VERSION]); what decides whether a calculation is allowed to exist at all
 * ([MIN_AGE_YEARS]–[MAX_AGE_YEARS], [MIN_BMI]–[MAX_BMI]); and the published coefficients.
 * Changing a coefficient forces a new version and a recalculation migration
 * (FR-BODY-004); nothing else here forces anything.
 *
 * ## What is deliberately *not* ported
 *
 * Android's `DETAILED_CAUTION` and the provenance constants that comment it are interface
 * text for the `Progress` screen. They are not copied here. A second copy of a caution
 * paragraph is a copy nothing keeps in phase — Android already deleted three constants for
 * exactly that reason — and the server renders none of it. The server's obligation under
 * PRD_SCALE 13.2 is the arithmetic and the domain, and that is what this file holds.
 */

/**
 * The stable identifier of the formula set (PRD_SCALE 13.2), copied into every stored
 * composition. A different set — different coefficients, different hydration factor, or
 * merely a different rounding order — carries another identifier or another
 * [FORMULA_VERSION].
 */
export const FORMULA_ID = "mue-foot-to-foot-v1";

/** The integer version of the formula set (PRD_SCALE 13.2). */
export const FORMULA_VERSION = 1;

// --------------------------------------------------------------------- domain of validity

/**
 * Lowest age accepted, **inclusive**, taken at the date of the measurement and never at the
 * date of the calculation (FR-BODY-001, FR-BODY-006).
 *
 * The equation was validated from 16, but FR-BODY-001 only authorises it from 20 — the age
 * at which PRD FR-BMI-002 already agrees to name a BMI category. The PRD settles this
 * explicitly in favour of the narrower product domain.
 */
export const MIN_AGE_YEARS = 20;

/** Highest age accepted, **inclusive** (FR-BODY-001). */
export const MAX_AGE_YEARS = 75;

/** Lowest BMI of the equation's development domain, inclusive (FR-BODY-001). */
export const MIN_BMI: bigint = fromDecimal("15.8");

/** Highest BMI of the equation's development domain, inclusive (FR-BODY-001). */
export const MAX_BMI: bigint = fromDecimal("43.1");

/**
 * The height range the profile screen accepts (PRD FR-PROFILE-001), inclusive.
 *
 * A height outside it cannot come from the `Profile` screen, so treating it as a *missing*
 * input rather than as a domain refusal gives the only useful answer: enter a height.
 */
export const MIN_HEIGHT_CM = 120;

/** Highest height the profile screen accepts, inclusive (PRD FR-PROFILE-001). */
export const MAX_HEIGHT_CM = 230;

// --------------------------------------------------------------------- published coefficients

/** Intercept of the foot-to-foot fat-free mass equation (PRD_SCALE 13.2). */
export const INTERCEPT: bigint = fromDecimal("13.055");

/** Coefficient of the weight in kilograms. */
export const WEIGHT_COEFFICIENT: bigint = fromDecimal("0.204");

/** Coefficient of the impedance index `height² / impedance`, in cm²/Ω. */
export const IMPEDANCE_INDEX_COEFFICIENT: bigint = fromDecimal("0.394");

/** Coefficient of the age, **subtracted** in the equation. */
export const AGE_COEFFICIENT: bigint = fromDecimal("0.136");

/** Coefficient of the sex term, added for `"male"` only. */
export const SEX_COEFFICIENT: bigint = fromDecimal("8.125");

/**
 * Mean hydration of fat-free mass (Wang et al., 1999).
 *
 * A physiological average, not a measurement: the scale never measures water. Body water
 * is fat-free mass times this fixed factor, so it never moves independently of it.
 */
export const FAT_FREE_MASS_HYDRATION: bigint = fromDecimal("0.732");

/** Weight coefficient of Mifflin-St Jeor, in kcal/kg. */
export const RESTING_ENERGY_WEIGHT_COEFFICIENT: bigint = fromDecimal("10");

/** Height coefficient of Mifflin-St Jeor, in kcal/cm. */
export const RESTING_ENERGY_HEIGHT_COEFFICIENT: bigint = fromDecimal("6.25");

/** Age coefficient of Mifflin-St Jeor, **subtracted**. */
export const RESTING_ENERGY_AGE_COEFFICIENT: bigint = fromDecimal("5");

/** The `-161` offset of Mifflin-St Jeor, for `"female"`. */
export const RESTING_ENERGY_FEMALE_OFFSET: bigint = fromDecimal("-161");

/** The `+5` offset of Mifflin-St Jeor, for `"male"`. */
export const RESTING_ENERGY_MALE_OFFSET: bigint = fromDecimal("5");

/** One hundred, as a working quantity: the percentage scale, and an upper bound. */
export const ONE_HUNDRED: bigint = fromInteger(100);

// --------------------------------------------------------------------- the gates, as functions

/**
 * The sex of a profile, on the wire.
 *
 * The two literals are the `wireValue`s of Android's `Sex` enum and of the versioned test
 * vectors, so the same JSON drives both implementations without a translation step in
 * between.
 */
export type Sex = "female" | "male";

/** `true` when [value] is one of the two wire values of [Sex]. */
export function isSex(value: unknown): value is Sex {
  return value === "female" || value === "male";
}

/**
 * `true` when [ageYears] — the age **at the date of the measurement** — is inside
 * FR-BODY-001's domain, both bounds included.
 *
 * The age is never the age today: FR-BODY-006 recalculates old measurements, and each
 * snapshot must carry the age the person had on its own day.
 */
export function isAgeInDomain(ageYears: number): boolean {
  return ageYears >= MIN_AGE_YEARS && ageYears <= MAX_AGE_YEARS;
}

/**
 * The exact BMI the domain gate uses, or `null` when [heightCm] cannot produce one.
 *
 * Deliberately **not rounded**, unlike the BMI a screen displays. Both rules are right in
 * their own place: a displayed BMI must match the category announced next to it, while
 * this one decides whether an equation was ever validated for this morphology. Rounding
 * first would let a BMI of `43.14` in on the grounds that it reads `43.1` — that is,
 * widen the published domain, which is exactly what PRD_SCALE 13.2 forbids when it refuses
 * to pull a result back inside its bounds.
 *
 * One single division, `weightCg × 100 / heightCm²`, rather than a detour through metres:
 * two successive divisions would introduce two roundings where the ratio is a quotient of
 * integers.
 */
export function bmiOrNull(weightCg: number, heightCm: number): bigint | null {
  if (heightCm <= 0) {
    return null;
  }
  return divideWorking(fromInteger(weightCg * 100), fromInteger(heightCm * heightCm));
}

/** `true` when [bmi] lies in `[MIN_BMI, MAX_BMI]`, both bounds included (FR-BODY-001). */
export function isBmiInDomain(bmi: bigint): boolean {
  return bmi >= MIN_BMI && bmi <= MAX_BMI;
}

/** `true` when [heightCm] can feed the equation (PRD FR-PROFILE-001). */
export function isHeightUsable(heightCm: number | null): heightCm is number {
  return heightCm !== null && heightCm >= MIN_HEIGHT_CM && heightCm <= MAX_HEIGHT_CM;
}

/**
 * `true` when [impedanceOhm] is usable (BR-SCALE-005).
 *
 * Absent, zero or negative: nothing to compute. A device's own "not measurable" marker —
 * `0xFFFF` on the reference scale — has been turned into `null` by the driver long before
 * anything reaches the server, because its numeric value belongs to a protocol and the
 * domain knows no protocols. An impedance that is present but aberrant is *not* filtered
 * here either: it produces an absurd fat-free mass, and it is PRD_SCALE 13.2's output
 * check that refuses it, with a reason naming what actually failed.
 */
export function isImpedanceUsable(impedanceOhm: number | null): impedanceOhm is number {
  return impedanceOhm !== null && impedanceOhm > 0;
}

/** The `sexCoefficient` term: `0` for `"female"`, `1` for `"male"`. */
export function sexCoefficient(sex: Sex): bigint {
  return sex === "male" ? fromInteger(1) : fromInteger(0);
}

/** The Mifflin-St Jeor offset: `-161` for `"female"`, `+5` for `"male"`. */
export function restingEnergyOffset(sex: Sex): bigint {
  return sex === "male" ? RESTING_ENERGY_MALE_OFFSET : RESTING_ENERGY_FEMALE_OFFSET;
}

/**
 * `true` when this build knows the formula set a caller asks to be recalculated with.
 *
 * PRD_SCALE 22 requires the server to "recalculate the results with the requested version
 * and reject any unknown version". It matters that this is a *rejection* and not a silent
 * fallback onto the current set: a client asking for version 2 and being answered with
 * version 1 numbers would store, under a version it believes it understands, integers a
 * different equation produced — and no later migration could tell the two apart.
 *
 * A build that ships two formula sets replaces this equality with a lookup; the calculator
 * does not change, because it is the caller's requested version that is being validated,
 * not the calculator's own.
 */
export function isKnownFormula(formulaId: string, formulaVersion: number): boolean {
  return formulaId === FORMULA_ID && formulaVersion === FORMULA_VERSION;
}
