import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  type BodyCompositionInput,
  type BodyCompositionResult,
  calculateBodyComposition,
  compositionOrNull,
  recalculateBodyComposition,
} from "./calculator";
import {
  WORKING_SCALE,
  formatWorking,
  fromDecimal,
  multiplyWorking,
  roundHalfUp,
  toStoredInteger,
} from "./decimal";
import { FORMULA_ID, FORMULA_VERSION, type Sex, isSex } from "./formula";

/**
 * PRD_SCALE 13.2's acceptance criterion, executed rather than asserted.
 *
 * > The two required implementations -- Kotlin on Android and TypeScript on the server --
 * > use the same versioned test vectors. The same payload must produce the same stored
 * > integers in both environments.
 *
 * The only way that sentence can be *tested* is for the two suites to read one file. So
 * this reads `apps/android/app/src/test/resources/bodycomposition/mue-foot-to-foot-v1.json`
 * where it lives, in the Android source tree, and `BodyCompositionCalculatorTest` replays
 * the same bytes. Neither suite can be edited into agreement with itself: a coefficient, a
 * gate or a rounding that moves on one side turns the other red, and a vector deleted to
 * make a failure go away fails [REQUIRED_CASES] below.
 *
 * The file is the contract, and it is not editable evidence. A disagreement between this
 * implementation and a vector is a bug here or a genuine problem in the rounding rule --
 * never a reason to change the expected integer.
 */

const VECTORS_PATH = join(
  import.meta.dir,
  "../../../../apps/android/app/src/test/resources/bodycomposition/mue-foot-to-foot-v1.json",
);

interface VectorCase {
  readonly id: string;
  readonly why: string;
  readonly input: {
    readonly weightCg: number;
    readonly heightCm: number | null;
    readonly ageYears: number | null;
    readonly sex: string | null;
    readonly impedanceOhm: number | null;
  };
  readonly expect: {
    readonly outcome: string;
    readonly bodyFatDeciPercent?: number;
    readonly fatFreeMassCg?: number;
    readonly bodyWaterDeciPercent?: number;
    readonly restingEnergyKcal?: number;
    readonly missing?: readonly string[];
    readonly check?: string;
  };
}

interface VectorFile {
  readonly formulaId: string;
  readonly formulaVersion: number;
  readonly workingScale: number;
  readonly rounding: string;
  readonly cases: readonly VectorCase[];
}

function readVectors(): VectorFile {
  let text: string;
  try {
    text = readFileSync(VECTORS_PATH, "utf8");
  } catch {
    // Named rather than left as an ENOENT, because the interesting fact is *whose* file it
    // is: the Android suite replays these vectors, and moving the file silently unlinks the
    // two halves of the parity PRD_SCALE 23 asks for.
    throw new Error(
      `${VECTORS_PATH} is missing. BodyCompositionCalculatorTest in the Android suite ` +
        "replays these vectors; this suite replays the same file. Both must read it.",
    );
  }
  return JSON.parse(text) as VectorFile;
}

const vectors = readVectors();

/**
 * The vectors this suite is required to exercise, by name.
 *
 * Not decoration: without it, deleting the case that fails is a way of making a
 * disagreement disappear, and the suite would still report twenty green tests over
 * nineteen cases. Each name is a class of failure the file exists to catch -- the two age
 * bounds and the two years outside them, the two BMI bounds and the two valid weight steps
 * outside them, a midpoint on each of the four stored outputs, an aberrant impedance
 * refused rather than clamped, the three shapes of unusable impedance, and an incomplete
 * profile.
 */
const REQUIRED_CASES = [
  "hardware-female",
  "hardware-male",
  "age-lower-bound-20",
  "age-just-below-19",
  "age-upper-bound-75",
  "age-just-above-76",
  "bmi-lower-bound-15.8",
  "bmi-just-below-15.7875",
  "bmi-upper-bound-43.1",
  "bmi-just-above-43.1125",
  "half-up-fat-free-mass",
  "half-up-body-fat",
  "half-up-body-water",
  "half-up-resting-energy",
  "implausible-fat-free-mass-above-weight",
  "impedance-unusable-zero",
  "impedance-unusable-absent",
  "impedance-unusable-negative",
  "missing-sex",
  "missing-height-and-birth-date",
] as const;

function sexOf(vector: VectorCase): Sex | null {
  const value = vector.input.sex;
  if (value === null) {
    return null;
  }
  if (!isSex(value)) {
    throw new Error(`${vector.id}: "${value}" is not a sex this implementation knows`);
  }
  return value;
}

function inputOf(vector: VectorCase): BodyCompositionInput {
  return {
    weightCg: vector.input.weightCg,
    heightCm: vector.input.heightCm,
    ageYears: vector.input.ageYears,
    sex: sexOf(vector),
    impedanceOhm: vector.input.impedanceOhm,
  };
}

/**
 * A result reduced to the shape the vectors describe.
 *
 * Compared with `toStrictEqual` against the whole `expect` object rather than field by
 * field, so a vector that carries four integers is checked on four integers. A test that
 * only compared the outcome would pass on an implementation that computes nothing right,
 * which is the failure mode this whole file exists to rule out.
 */
function wireOf(result: BodyCompositionResult): Record<string, unknown> {
  switch (result.outcome) {
    case "calculated":
      return {
        outcome: result.outcome,
        bodyFatDeciPercent: result.composition.bodyFatDeciPercent,
        fatFreeMassCg: result.composition.fatFreeMassCg,
        bodyWaterDeciPercent: result.composition.bodyWaterDeciPercent,
        restingEnergyKcal: result.composition.restingEnergyKcal,
      };
    case "missing-profile-input":
      return { outcome: result.outcome, missing: [...result.missing] };
    case "physically-implausible":
      return { outcome: result.outcome, check: result.check };
    default:
      return { outcome: result.outcome };
  }
}

describe("the versioned vectors, replayed from the file Android replays", () => {
  test("the file still describes this formula set and this arithmetic", () => {
    expect(vectors.formulaId).toBe(FORMULA_ID);
    expect(vectors.formulaVersion).toBe(FORMULA_VERSION);
    expect(vectors.workingScale).toBe(WORKING_SCALE);
    expect(vectors.rounding).toBe("HALF_UP");
  });

  test("every case this suite is required to cover is still in the file", () => {
    const ids = vectors.cases.map((vector) => vector.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const required of REQUIRED_CASES) {
      expect(ids).toContain(required);
    }
  });

  test("the file exercises every outcome the implementation can produce", () => {
    // `unknown-formula` is deliberately absent: no vector carries a formula version, and
    // PRD_SCALE 22's refusal is a server-only concern, covered on its own below.
    expect(new Set(vectors.cases.map((vector) => vector.expect.outcome))).toEqual(
      new Set([
        "calculated",
        "impedance-unusable",
        "missing-profile-input",
        "age-out-of-domain",
        "bmi-out-of-domain",
        "physically-implausible",
      ]),
    );
  });

  for (const vector of vectors.cases) {
    test(vector.id, () => {
      expect(wireOf(calculateBodyComposition(inputOf(vector)))).toStrictEqual(vector.expect);
    });
  }
});

describe("the decimal rule that makes the two implementations agree", () => {
  test("a midpoint goes away from zero, which is where half-even would differ", () => {
    // 5/2 is 2.5: half-up says 3, half-even says 2. The four `half-up-*` vectors above
    // fail on exactly this difference, one per stored output.
    expect(roundHalfUp(5n, 2n)).toBe(3n);
    expect(roundHalfUp(-5n, 2n)).toBe(-3n);
    expect(roundHalfUp(7n, 2n)).toBe(4n);
    expect(roundHalfUp(-7n, 2n)).toBe(-4n);
  });

  test("anything short of the midpoint stays put, in both directions", () => {
    expect(roundHalfUp(4n, 3n)).toBe(1n);
    expect(roundHalfUp(-4n, 3n)).toBe(-1n);
    expect(roundHalfUp(5n, 3n)).toBe(2n);
    expect(roundHalfUp(-5n, 3n)).toBe(-2n);
  });

  test("a product is rounded to the working scale as soon as it is formed", () => {
    // 1.5 x 10^-12 has one digit more than the working scale holds, and it is a midpoint:
    // it must become 2 x 10^-12 there and then, not survive into the next operation.
    expect(multiplyWorking(fromDecimal("1.5"), fromDecimal("0.000000000001"))).toBe(2n);
  });

  test("a coefficient too precise to represent is refused rather than truncated", () => {
    expect(() => fromDecimal("0.0000000000001")).toThrow(RangeError);
    expect(() => fromDecimal("13,055")).toThrow(RangeError);
    expect(() => fromDecimal("")).toThrow(RangeError);
    expect(fromDecimal("-161")).toBe(-161n * 10n ** 12n);
  });

  test("dividing by zero is a programming error, not a business outcome", () => {
    expect(() => roundHalfUp(1n, 0n)).toThrow(RangeError);
  });

  test("the storage rounding is half-up too, and guards its width", () => {
    expect(toStoredInteger(fromDecimal("2.5"))).toBe(3);
    expect(toStoredInteger(fromDecimal("-2.5"))).toBe(-3);
    expect(toStoredInteger(fromDecimal("2.4999999999"))).toBe(2);
    expect(() => toStoredInteger(fromDecimal("2147483648"))).toThrow(RangeError);
  });

  test("a working quantity reads back as the decimal it holds", () => {
    expect(formatWorking(fromDecimal("15.7875"))).toBe("15.7875");
    expect(formatWorking(fromDecimal("-161"))).toBe("-161");
    expect(formatWorking(0n)).toBe("0");
  });
});

describe("the gates, and the order the vectors fix", () => {
  const usable: BodyCompositionInput = {
    weightCg: 8575,
    heightCm: 178,
    ageYears: 34,
    sex: "female",
    impedanceOhm: 545,
  };

  test("a manual weigh-in on an empty profile reads as unusable impedance, not as a gap", () => {
    // The reason impedance is examined first: were the profile examined first, every
    // manually entered weight of an incomplete profile would answer "the sex is missing",
    // and FR-BODY-006's count of weigh-ins a completed profile would unlock would count
    // them all.
    const result = calculateBodyComposition({
      weightCg: 8575,
      heightCm: null,
      ageYears: null,
      sex: null,
      impedanceOhm: null,
    });
    expect(result.outcome).toBe("impedance-unusable");
  });

  test("a height outside the profile range asks for a height rather than refusing a domain", () => {
    const result = calculateBodyComposition({ ...usable, heightCm: 300 });
    expect(wireOf(result)).toStrictEqual({
      outcome: "missing-profile-input",
      missing: ["height"],
    });
  });

  test("age is decided before BMI, so a teenager is never told about their BMI", () => {
    // 40 kg for 178 cm is a BMI of 12.6, out of domain as well. FR-BODY-001 refuses to let
    // a validity limit read as a judgement, and the age gate is the one that ran.
    const result = calculateBodyComposition({ ...usable, ageYears: 19, weightCg: 4000 });
    expect(result).toStrictEqual({ outcome: "age-out-of-domain", ageYears: 19 });
  });

  test("the BMI a refusal reports is the exact one, not the rounded one", () => {
    // 63.15 kg for 200 cm: 15.7875, which reads 15.8 once rounded to one decimal. Rounding
    // before the gate would widen the published domain.
    const result = calculateBodyComposition({
      weightCg: 6315,
      heightCm: 200,
      ageYears: 30,
      sex: "male",
      impedanceOhm: 700,
    });
    expect(result).toStrictEqual({ outcome: "bmi-out-of-domain", bmi: "15.7875" });
  });

  test("an aberrant reading is refused, and nothing is pulled back inside the bounds", () => {
    const result = calculateBodyComposition({
      weightCg: 6000,
      heightCm: 175,
      ageYears: 30,
      sex: "male",
      impedanceOhm: 150,
    });
    expect(result).toStrictEqual({
      outcome: "physically-implausible",
      check: "fat-free-mass-above-weight",
    });
    expect(compositionOrNull(result)).toBeNull();
  });

  test("a calculated composition carries the formula that produced it and its inputs", () => {
    const composition = compositionOrNull(calculateBodyComposition(usable));
    expect(composition).toStrictEqual({
      formulaId: FORMULA_ID,
      formulaVersion: FORMULA_VERSION,
      inputWeightCg: 8575,
      inputHeightCm: 178,
      inputAgeYears: 34,
      inputSex: "female",
      bodyFatDeciPercent: 431,
      fatFreeMassCg: 4883,
      bodyWaterDeciPercent: 417,
      restingEnergyKcal: 1639,
    });
  });

  test("a fractional input is a programming error, because parity cannot survive it", () => {
    expect(() => calculateBodyComposition({ ...usable, weightCg: 8575.5 })).toThrow(TypeError);
    expect(() => calculateBodyComposition({ ...usable, heightCm: 178.2 })).toThrow(TypeError);
    expect(() => calculateBodyComposition({ ...usable, ageYears: 34.5 })).toThrow(TypeError);
    expect(() => calculateBodyComposition({ ...usable, impedanceOhm: 545.5 })).toThrow(TypeError);
  });
});

describe("PRD_SCALE 22: the server recalculates with the requested version", () => {
  const usable: BodyCompositionInput = {
    weightCg: 8575,
    heightCm: 178,
    ageYears: 34,
    sex: "male",
    impedanceOhm: 545,
  };

  test("the known version computes exactly what the current set computes", () => {
    expect(recalculateBodyComposition(FORMULA_ID, FORMULA_VERSION, usable)).toStrictEqual(
      calculateBodyComposition(usable),
    );
  });

  test("an unknown version is refused rather than answered with this one's numbers", () => {
    expect(recalculateBodyComposition(FORMULA_ID, 2, usable)).toStrictEqual({
      outcome: "unknown-formula",
      formulaId: FORMULA_ID,
      formulaVersion: 2,
    });
  });

  test("an unknown identifier is refused the same way", () => {
    expect(recalculateBodyComposition("mue-hand-to-hand-v1", 1, usable)).toStrictEqual({
      outcome: "unknown-formula",
      formulaId: "mue-hand-to-hand-v1",
      formulaVersion: 1,
    });
  });

  test("the version is settled before the measurement is even looked at", () => {
    // Otherwise a request naming an unknown version would come back "unusable impedance",
    // and a client would fix the wrong thing.
    const result = recalculateBodyComposition(FORMULA_ID, 99, {
      ...usable,
      impedanceOhm: null,
    });
    expect(result.outcome).toBe("unknown-formula");
  });
});
