// PRD_FOOD 8.6's cooked ratio, derived rather than typed.
//
// Ciqual publishes composition per 100 g, never masses, so `cooked mass / reference
// mass` is not in the table. It is nonetheless recoverable from the very conservation
// PRD_FOOD 8.6 asserts: "la matiere seche est conservee, seule la masse change". If
// 100 g of reference food holds `100 - water_ref` grams of dry matter, and that same
// dry matter later occupies a mass `m` at `water_cooked`, then
//
//     (100 - water_ref) = m * (100 - water_cooked) / 100
//     ratio = m / 100   = (100 - water_ref) / (100 - water_cooked)
//
// Water is read for this and shipped nowhere: PRD_FOOD 9.1 keeps five constituents.
//
// The same arithmetic then answers PRD_FOOD 9.5's question mechanically. Re-scale the
// cooked entry's five kept constituents by the derived ratio and compare them with the
// reference entry's. If they agree, only water moved and the pair is *one* catalogue
// row carrying a `cookedRatio`. If they do not, matter was added or removed - fried,
// canned in syrup - and PRD_FOOD 8.6 forbids a ratio: *two* rows, no ratio.

import { asNumber, SHIPPED_CONSTITUENTS, type CiqualFood, type ShippedConstituent } from "./table";
import { parseName, type ParsedName } from "./preparation";
import { COOKED_RATIO_RANGE, isInRange } from "./units";

/** How close a re-scaled constituent must land to count as unchanged. */
export interface RescaleTolerance {
  /**
   * PRD_FOOD 9.5's own "environ 15 %".
   *
   * Using 9.5's number rather than a stricter one is the whole point of the test:
   * 9.5 says two rows are only justified when the figures differ by more than 15 %, so
   * a re-scaled cooked entry that lands inside 15 % of its reference is, by 9.5's own
   * definition, not a second row.
   */
  readonly relative: number;
  /**
   * A floor in the constituent's own unit, below which a relative test is noise.
   *
   * Ciqual reports raw white rice fat as `0,79`; fifteen per cent of that is 0.12 g,
   * an order of magnitude under what the source itself resolves. Without a floor the
   * test stops measuring "did the composition change" and starts measuring "were the
   * two entries analysed by the same laboratory".
   */
  readonly floor: Readonly<Record<ShippedConstituent, number>>;
  /**
   * The constituents allowed to refuse a pair.
   *
   * Fibre is measured and reported but is deliberately **not** among them, and the
   * evidence for that is in Ciqual 2025 itself. Raw white rice (9100) holds 1,53 g of
   * fibre per 100 g; cooked white rice (9104) holds 1,4 g per 100 g at a derived ratio
   * of 2,26, which puts 3,17 g of fibre back into the 100 g of raw rice it came from.
   * Fibre cannot double under a boil. The two rows were analysed by different sources
   * with different methods, and the disagreement is between two measurements of the
   * same food rather than between two foods.
   *
   * Left gating, that single constituent refuses 13 of the 63 pairs that every other
   * constituent accepts - including white rice, wholegrain rice, egg pasta and green
   * lentils, three of which are PRD_FOOD 8.6's own worked examples. Energy and the
   * three macros that compose it are what actually answer "was matter added or
   * removed", which is the question 8.6 asks; fibre is carried into the report so the
   * disagreement stays visible rather than being silently forgiven.
   */
  readonly gating: readonly ShippedConstituent[];
  /**
   * How far from 1 a ratio has to be before it is worth shipping.
   *
   * Ciqual's raw and cooked rows for a vegetable are two separate analyses of two
   * separate samples, so the water figures disagree a little even where nothing
   * happened: boiled cauliflower comes out at 1.127 and boiled new potato at 1.011, and
   * neither is a real mass change - a boiled cauliflower does not gain 13 % of itself.
   * Shipping those publishes measurement noise as a fact, and PRD_FOOD 13.2 already
   * rounds every displayed macro to a tenth of a gram, so a ratio of 1.01 changes
   * nothing a user can see.
   *
   * The threshold reuses PRD_FOOD 9.5's 15 %, on the same argument: below it, weighing
   * the food raw and weighing it cooked give the same answer as far as this module is
   * concerned. Above it - rice at 2.26, chicken breast at 0.77 - the ratio is the whole
   * reason PRD_FOOD 8.6 exists.
   */
  readonly minimumDeviation: number;
}

export const DEFAULT_TOLERANCE: RescaleTolerance = {
  relative: 0.15,
  floor: { energy: 10, protein: 1, carbs: 1, fat: 1, fibre: 1 },
  gating: ["energy", "protein", "carbs", "fat"],
  minimumDeviation: 0.15,
};

export interface ConstituentComparison {
  readonly constituent: ShippedConstituent;
  readonly reference: number | null;
  readonly cooked: number | null;
  readonly rescaled: number | null;
  readonly deltaRatio: number | null;
  readonly agrees: boolean;
  /** Neither side was determined, so the constituent could not vote either way. */
  readonly undecidable: boolean;
}

export type PairVerdict =
  /** Only water moved: one row, carrying the ratio. */
  | "oneEntry"
  /** Composition changed: two rows, no ratio. */
  | "twoEntries"
  /** Water was not determined on one side, so no ratio exists to test. */
  | "noWater"
  /** The ratio fell outside PRD_FOOD 15's 0.3 to 5. */
  | "ratioOutOfBounds"
  /** Only water moved, but so little of it that shipping the ratio would ship noise. */
  | "ratioNegligible";

export interface Pair {
  readonly stem: string;
  readonly reference: CiqualFood;
  readonly cooked: CiqualFood;
  readonly referenceName: ParsedName;
  readonly cookedName: ParsedName;
  readonly waterReference: number | null;
  readonly waterCooked: number | null;
  readonly ratio: number | null;
  readonly ratioThousandths: number | null;
  readonly comparisons: readonly ConstituentComparison[];
  readonly verdict: PairVerdict;
  /** The gating constituents that refused the pair, for the report. */
  readonly disagreeing: readonly ShippedConstituent[];
  /**
   * Constituents that disagreed but do not gate. Never empty in silence: the report
   * prints these so a non-gating disagreement is reviewed rather than forgotten.
   */
  readonly disagreeingUngated: readonly ShippedConstituent[];
}

export function deriveRatio(waterReference: number, waterCooked: number): number | null {
  const dryCooked = 100 - waterCooked;
  if (dryCooked <= 0) return null;
  const ratio = (100 - waterReference) / dryCooked;
  return Number.isFinite(ratio) && ratio > 0 ? ratio : null;
}

function compare(
  constituent: ShippedConstituent,
  reference: number | null,
  cooked: number | null,
  ratio: number,
  tolerance: RescaleTolerance,
): ConstituentComparison {
  if (reference === null || cooked === null) {
    return {
      constituent,
      reference,
      cooked,
      rescaled: cooked === null ? null : cooked * ratio,
      deltaRatio: null,
      // An unknown on either side cannot testify that the composition changed. It is
      // also not evidence that it did not, which is why `undecidable` is reported.
      agrees: true,
      undecidable: true,
    };
  }
  const rescaled = cooked * ratio;
  const delta = Math.abs(rescaled - reference);
  const allowed = Math.max(
    tolerance.relative * Math.max(Math.abs(reference), Math.abs(rescaled)),
    tolerance.floor[constituent],
  );
  const scale = Math.max(Math.abs(reference), 1e-9);
  return {
    constituent,
    reference,
    cooked,
    rescaled,
    deltaRatio: (rescaled - reference) / scale,
    agrees: delta <= allowed,
    undecidable: false,
  };
}

export function evaluatePair(
  reference: CiqualFood,
  cooked: CiqualFood,
  tolerance: RescaleTolerance = DEFAULT_TOLERANCE,
): Pair {
  const referenceName = parseName(reference.nameEng);
  const cookedName = parseName(cooked.nameEng);
  const waterReference = asNumber(reference, "water");
  const waterCooked = asNumber(cooked, "water");

  const base = {
    stem: referenceName.stem,
    reference,
    cooked,
    referenceName,
    cookedName,
    waterReference,
    waterCooked,
  } as const;

  if (waterReference === null || waterCooked === null) {
    return {
      ...base,
      ratio: null,
      ratioThousandths: null,
      comparisons: [],
      verdict: "noWater",
      disagreeing: [],
      disagreeingUngated: [],
    };
  }

  const ratio = deriveRatio(waterReference, waterCooked);
  const ratioThousandths = ratio === null ? null : Math.round(ratio * 1_000);
  if (
    ratio === null ||
    ratioThousandths === null ||
    !isInRange(ratioThousandths, COOKED_RATIO_RANGE)
  ) {
    return {
      ...base,
      ratio,
      ratioThousandths,
      comparisons: [],
      verdict: "ratioOutOfBounds",
      disagreeing: [],
      disagreeingUngated: [],
    };
  }

  const comparisons = SHIPPED_CONSTITUENTS.map((constituent) =>
    compare(
      constituent,
      asNumber(reference, constituent),
      asNumber(cooked, constituent),
      ratio,
      tolerance,
    ),
  );
  const refused = comparisons.filter((entry) => !entry.agrees).map((entry) => entry.constituent);
  const gating = new Set<ShippedConstituent>(tolerance.gating);
  const disagreeing = refused.filter((constituent) => gating.has(constituent));
  const disagreeingUngated = refused.filter((constituent) => !gating.has(constituent));

  return {
    ...base,
    ratio,
    ratioThousandths,
    comparisons,
    verdict:
      disagreeing.length > 0
        ? "twoEntries"
        : Math.abs(ratio - 1) < tolerance.minimumDeviation
          ? "ratioNegligible"
          : "oneEntry",
    disagreeing,
    disagreeingUngated,
  };
}

/**
 * Every reference/cooked pair Ciqual contains, one cooked entry per reference entry.
 *
 * Where a stem has several cooked states, `cookedRank` picks the plainest - boiled in
 * water before pan-fried - so the choice is a written-down order rather than whichever
 * `alim_code` the iteration happened to reach first.
 */
export function findPairs(
  foods: Iterable<CiqualFood>,
  tolerance: RescaleTolerance = DEFAULT_TOLERANCE,
): Pair[] {
  const references = new Map<string, CiqualFood>();
  const cooked = new Map<string, CiqualFood[]>();

  for (const food of foods) {
    const parsed = parseName(food.nameEng);
    // Scoped to the *group*, not the sub-group, and that distinction is load-bearing.
    // Ciqual files the two halves of a pair in different sub-groups by design: raw meat
    // is 0402 and cooked meat is 0401, raw fish is 0406 and cooked fish is 0405. A
    // sub-group-scoped key therefore never pairs anything in group 04 at all - which
    // silently costs PRD_FOOD 8.6 its own chicken-breast example. The stem is specific
    // enough ("Chicken, breast, without skin") that the group is a safe scope.
    const key = `${food.groupCode}/${parsed.stem.toLowerCase()}`;
    if (parsed.state === "reference") {
      const existing = references.get(key);
      if (existing === undefined || food.code < existing.code) references.set(key, food);
    } else if (parsed.state === "cooked") {
      cooked.set(key, [...(cooked.get(key) ?? []), food]);
    }
  }

  const pairs: Pair[] = [];
  const keys = [...references.keys()].sort();
  for (const key of keys) {
    const reference = references.get(key) as CiqualFood;
    const candidates = cooked.get(key);
    if (candidates === undefined || candidates.length === 0) continue;
    const best = [...candidates].sort((a, b) => {
      const rank = parseName(a.nameEng).cookedRank - parseName(b.nameEng).cookedRank;
      return rank !== 0 ? rank : a.code < b.code ? -1 : 1;
    })[0] as CiqualFood;
    pairs.push(evaluatePair(reference, best, tolerance));
  }
  return pairs;
}
