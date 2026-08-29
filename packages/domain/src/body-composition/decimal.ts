/**
 * The fixed-point decimal arithmetic that makes the server and Android agree digit for
 * digit (PRD_SCALE 13.2).
 *
 * ## Why this file exists at all
 *
 * PRD_SCALE 13.2 accepts `mue-foot-to-foot-v1` only if "the same payload produces the same
 * stored integers" in both implementations, and PRD_SCALE 23 restates it as a release
 * criterion. That is a stronger claim than "both use IEEE 754". Kotlin and JavaScript do
 * share binary64, but the equations below all end at a value that has to be rounded to a
 * whole storage unit, and a quantity mathematically equal to `x.xx5` lands just above or
 * just below the midpoint depending on the order the products were accumulated in. Two
 * honest ports then differ by one stored unit, on a value neither of them can show is
 * wrong. The four `half-up-*` vectors in
 * `apps/android/app/src/test/resources/bodycomposition/mue-foot-to-foot-v1.json` sit
 * exactly on that midpoint, one per output, precisely so that this failure is loud.
 *
 * So the rule is decimal, and stated so it can be re-implemented anywhere. Android states
 * it on `BodyCompositionFormula.WORKING_SCALE` and implements it with `BigDecimal`; this
 * file is the other half:
 *
 * 1. every multiplication and every division is rounded **immediately** to
 *    [WORKING_SCALE] decimals, half-up;
 * 2. additions and subtractions of values already at that scale are exact and re-round
 *    nothing;
 * 3. the rounding to whole storage units happens **once**, at the very end
 *    (PRD_SCALE 21.1).
 *
 * ## Why `bigint` and no dependency
 *
 * A quantity is carried as an integer number of `10^-12` units, so a `BigDecimal` of scale
 * 12 and a `bigint` here hold the same integer. `bigint` is native, exact and unbounded,
 * which removes both the dependency a decimal library would add (forbidden here) and the
 * overflow question a `number` would raise. What it cannot do is round for us, so
 * [roundHalfUp] is written out: for a positive quotient, `floor((2n + d) / 2d)` is half-up
 * in one line, and it is the identity `BodyCompositionFormula.WORKING_SCALE` names.
 *
 * Nothing here is body-composition specific; it stays out of `formula.ts` and
 * `calculator.ts` so the rule can be read, and tested, without reading the equations.
 */

/** Decimals kept by every intermediate quantity. A contract clause, not a tuning knob. */
export const WORKING_SCALE = 12;

/** `10^WORKING_SCALE`: the integer that one unit of a quantity is scaled by. */
export const WORKING_UNIT: bigint = 10n ** BigInt(WORKING_SCALE);

/**
 * `numerator / denominator`, rounded half-up to a whole integer — ties go **away from
 * zero**, matching `RoundingMode.HALF_UP`.
 *
 * `bigint` division truncates toward zero, which is floor only for non-negative operands,
 * so the sign is taken out first and re-applied afterwards. Doing it the other way round
 * would round `-0.5` to `0` and break the symmetry `HALF_UP` promises — a subtraction can
 * legitimately produce a negative fat mass on the aberrant-impedance vector, and it is a
 * plausibility check that must reject it, not an arithmetic accident.
 *
 * Half-up is what Android picked over half-even, on the ground that it survives a port:
 * one `floor` and no parity test.
 *
 * @throws RangeError when [denominator] is zero. Every call site divides by a quantity a
 *   domain gate has already proven non-zero (a positive impedance, a weight a BMI gate
 *   accepted), so this is a programming error and never a business outcome.
 */
export function roundHalfUp(numerator: bigint, denominator: bigint): bigint {
  if (denominator === 0n) {
    throw new RangeError("body composition: division by zero in the working arithmetic");
  }
  // The quotient is negative when exactly one operand is.
  const negative = numerator < 0n !== denominator < 0n;
  const n = numerator < 0n ? -numerator : numerator;
  const d = denominator < 0n ? -denominator : denominator;
  const magnitude = (2n * n + d) / (2n * d);
  return negative ? -magnitude : magnitude;
}

/** A whole number of units, as a working quantity. Exact: nothing is rounded. */
export function fromInteger(value: number | bigint): bigint {
  return BigInt(value) * WORKING_UNIT;
}

/**
 * A decimal literal — `"13.055"`, `"-161"` — as a working quantity.
 *
 * The published coefficients are written in the source exactly as PRD_SCALE 13.2 writes
 * them, rather than pre-scaled into an integer nobody can proof-read against the PRD. The
 * conversion is exact and rejects anything it could not represent, so a coefficient gaining
 * a thirteenth decimal fails at load rather than being quietly truncated.
 *
 * @throws RangeError on a malformed literal or on more than [WORKING_SCALE] decimals.
 */
export function fromDecimal(literal: string): bigint {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(literal);
  if (match === null) {
    throw new RangeError(`body composition: "${literal}" is not a decimal literal`);
  }
  const sign = match[1] ?? "";
  const whole = match[2] ?? "";
  const fraction = match[3] ?? "";
  if (fraction.length > WORKING_SCALE) {
    throw new RangeError(`body composition: "${literal}" has more than ${WORKING_SCALE} decimals`);
  }
  const magnitude = BigInt(`${whole}${fraction.padEnd(WORKING_SCALE, "0")}`);
  return sign === "-" ? -magnitude : magnitude;
}

/** The product of two working quantities, rounded back to [WORKING_SCALE] as it is formed. */
export function multiplyWorking(a: bigint, b: bigint): bigint {
  return roundHalfUp(a * b, WORKING_UNIT);
}

/** The quotient of two working quantities, rounded to [WORKING_SCALE] as it is formed. */
export function divideWorking(a: bigint, b: bigint): bigint {
  return roundHalfUp(a * WORKING_UNIT, b);
}

/**
 * A working quantity multiplied by a whole number, **exactly**.
 *
 * Scaling a value by ten or a hundred to reach its storage unit is not one of the rounded
 * operations: it introduces no digit the value did not already have. Rounding it as a
 * product would apply the storage rounding twice — once at scale 12 and once at scale 0 —
 * and double rounding is exactly how `x.xx45` becomes `x.xx5` becomes `x.xx+1`.
 */
export function timesInteger(value: bigint, factor: bigint): bigint {
  return value * factor;
}

/**
 * The one and only rounding to a whole storage unit (PRD_SCALE 21.1).
 *
 * Mirrors `BigDecimal.setScale(0, HALF_UP).intValueExact()`: Android chose `intValueExact`
 * so that an overflow explodes in a test rather than writing a truncated number into
 * health data, and the same intent needs the same guard here, against the same 32-bit
 * range its `Int` columns hold. The plausibility checks run before this and already bound
 * every quantity far inside it.
 *
 * @throws RangeError when the rounded value does not fit a 32-bit signed integer.
 */
export function toStoredInteger(value: bigint): number {
  const rounded = roundHalfUp(value, WORKING_UNIT);
  if (rounded < -2147483648n || rounded > 2147483647n) {
    throw new RangeError(`body composition: ${rounded} does not fit a stored integer`);
  }
  return Number(rounded);
}

/**
 * A working quantity as a decimal string, trailing zeros removed.
 *
 * For diagnostics only — the BMI a domain refusal reports, so a server log says `15.7875`
 * rather than a scaled integer nobody can read. It is never a display value and never
 * re-parsed into a decision: the gate that produced it has already decided.
 */
export function formatWorking(value: bigint): string {
  const negative = value < 0n;
  const magnitude = negative ? -value : value;
  const whole = magnitude / WORKING_UNIT;
  const fraction = (magnitude % WORKING_UNIT).toString().padStart(WORKING_SCALE, "0");
  const trimmed = fraction.replace(/0+$/, "");
  return `${negative ? "-" : ""}${whole}${trimmed === "" ? "" : `.${trimmed}`}`;
}
