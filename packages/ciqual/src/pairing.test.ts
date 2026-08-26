import { describe, expect, test } from "bun:test";

import { fixtureTable } from "./fixtures";
import { deriveRatio, evaluatePair, findPairs, DEFAULT_TOLERANCE } from "./pairing";
import { parseName } from "./preparation";
import { asNumber, type CiqualFood } from "./table";

const table = fixtureTable();
const food = (code: string): CiqualFood => {
  const entry = table.foods.get(code);
  if (entry === undefined) throw new Error(`fixture is missing alim_code ${code}`);
  return entry;
};

describe("deriveRatio", () => {
  test("is the dry-matter conservation PRD_FOOD 8.6 asserts", () => {
    // 100 g of raw rice holds 87.4 g of dry matter; at 61.4 % water that dry matter
    // occupies 87.4 / 0.386 = 226.4 g.
    expect(deriveRatio(12.6, 61.4)).toBeCloseTo(2.2642, 4);
    // Losing water is the same equation in the other direction, which is why PRD_FOOD 8.6
    // needs one ratio and not two.
    expect(deriveRatio(75.1, 67.5)).toBeCloseTo(0.7662, 4);
  });

  test("refuses a cooked entry that is all water", () => {
    expect(deriveRatio(10, 100)).toBeNull();
    expect(deriveRatio(10, 101)).toBeNull();
  });
});

describe("PRD_FOOD 8.6's four ratios", () => {
  // These are the regression test the module is allowed to fail on. The expected values
  // are what Ciqual 2025 yields, not what PRD_FOOD 8.6 prints, and the difference between
  // the two is asserted below rather than smoothed over.
  const CASES = [
    { label: "white rice", reference: "9100", cooked: "9104", derived: 2.264, prd: 2.8 },
    { label: "chicken breast", reference: "36017", cooked: "36018", derived: 0.766, prd: 0.72 },
    { label: "dry pasta", reference: "9810", cooked: "9811", derived: 2.192, prd: 2.3 },
    { label: "red lentils", reference: "20535", cooked: "20589", derived: 2.613, prd: 2.4 },
  ] as const;

  for (const one of CASES) {
    test(`${one.label} derives ${one.derived} from Ciqual 2025`, () => {
      const pair = evaluatePair(food(one.reference), food(one.cooked));
      expect(pair.ratio).not.toBeNull();
      // Tight, so that a change in the derivation or in the source moves it and the build
      // stops. This is the "fail on drift" half of the requirement.
      expect(pair.ratio as number).toBeCloseTo(one.derived, 3);
      expect(pair.verdict).toBe("oneEntry");
    });
  }

  test("three of the four agree with PRD_FOOD 8.6 within 10 %", () => {
    const drift = (one: (typeof CASES)[number]) => {
      const pair = evaluatePair(food(one.reference), food(one.cooked));
      return ((pair.ratio as number) - one.prd) / one.prd;
    };
    for (const one of CASES.filter((entry) => entry.label !== "white rice")) {
      expect(Math.abs(drift(one))).toBeLessThan(0.1);
    }
  });

  test("white rice does NOT agree, and that disagreement is the finding", () => {
    // PRD_FOOD 8.6 prints 2.8. Ciqual 2025 gives 2.264 from the only honest pairing:
    // `Rice, white, raw` (12.6 % water) against `Rice, white, cooked, no added salt`
    // (61.4 % water). The gap is -19 %, far outside anything the other three show.
    //
    // 2.8 is reachable from this table in exactly one way: pairing raw white rice against
    // `Rice, white, pre-cooked or instant, cooked` (68 % water), which yields 2.73. That
    // is a different product, and taking it would be the "pairing picked the wrong cooked
    // entry" failure. 2.8 is instead the absorption-method figure a cooking guide gives -
    // one part rice to two parts water, all of it absorbed - whereas Ciqual's plain boiled
    // white rice is a firmer, drained preparation at 61.4 % water.
    //
    // So: the PRD's number comes from a cooking guide, not from Ciqual. This test asserts
    // the disagreement so that it cannot be silently "fixed" - if a later Ciqual release
    // or a corrected PRD makes them agree, this fails and forces someone to re-read both.
    const pair = evaluatePair(food("9100"), food("9104"));
    const drift = ((pair.ratio as number) - 2.8) / 2.8;
    expect(drift).toBeLessThan(-0.15);
    expect(drift).toBeGreaterThan(-0.25);
  });
});

describe("one entry or two", () => {
  test("only water moved: one entry, carrying the ratio", () => {
    const pair = evaluatePair(food("36017"), food("36018"));
    expect(pair.verdict).toBe("oneEntry");
    expect(pair.disagreeing).toEqual([]);
    expect(pair.ratioThousandths).toBe(766);
  });

  test("fat was added: two entries, no ratio", () => {
    // `Potato, peeled, raw` against `Potato, sautéed/pan-fried with fat`. PRD_FOOD 8.6:
    // "ce qu'un ratio ne peut pas modéliser, c'est ce qui est ajouté ou retiré".
    const pair = evaluatePair(food("4008"), food("4015"));
    expect(pair.verdict).toBe("twoEntries");
    expect(pair.disagreeing).toContain("fat");
  });

  test("fibre disagrees on white rice, and is reported rather than allowed to refuse", () => {
    // Raw white rice holds 1.53 g of fibre per 100 g; the cooked row's 1.4 g, re-scaled by
    // 2.264, puts 3.17 g back into the raw 100 g. Fibre cannot double under a boil, so the
    // two rows disagree as measurements and not as foods.
    const pair = evaluatePair(food("9100"), food("9104"));
    expect(pair.verdict).toBe("oneEntry");
    expect(pair.disagreeingUngated).toContain("fibre");
    const fibre = pair.comparisons.find((entry) => entry.constituent === "fibre");
    expect(fibre?.rescaled).toBeCloseTo(3.17, 2);
  });

  test("a ratio within 15 % of 1 is not shipped", () => {
    const raw = food("36017");
    const barelyCooked: CiqualFood = { ...food("36018"), composition: raw.composition };
    const pair = evaluatePair(raw, barelyCooked);
    expect(pair.verdict).toBe("ratioNegligible");
  });

  test("water not determined means no ratio at all, never a guess", () => {
    const raw = food("9100");
    const noWater: CiqualFood = {
      ...food("9104"),
      composition: { ...food("9104").composition, water: undefined },
    };
    expect(evaluatePair(raw, noWater).verdict).toBe("noWater");
    expect(evaluatePair(raw, noWater).ratio).toBeNull();
  });
});

describe("findPairs", () => {
  test("pairs across Ciqual's raw and cooked sub-groups", () => {
    // Ciqual files raw meat in 0402 and cooked meat in 0401 by design. A sub-group-scoped
    // key would find nothing in group 04 at all, and PRD_FOOD 8.6's chicken breast would
    // silently have no ratio.
    expect(food("36017").subGroupCode).not.toBe(food("36018").subGroupCode);
    const pairs = findPairs(table.foods.values());
    const chicken = pairs.find((pair) => pair.reference.code === "36017");
    expect(chicken?.cooked.code).toBe("36018");
  });

  test("is deterministic and reference-keyed", () => {
    const once = findPairs(table.foods.values()).map((pair) => pair.reference.code);
    const twice = findPairs(table.foods.values()).map((pair) => pair.reference.code);
    expect(once).toEqual(twice);
    expect(new Set(once).size).toBe(once.length);
  });

  test("never pairs a transformed entry", () => {
    for (const pair of findPairs(table.foods.values())) {
      expect(parseName(pair.cooked.nameEng).state).toBe("cooked");
      expect(parseName(pair.reference.nameEng).state).toBe("reference");
    }
  });
});

describe("water", () => {
  test("is read at build time and is not one of PRD_FOOD 9.1's five constituents", () => {
    expect(asNumber(food("9100"), "water")).toBeCloseTo(12.6, 2);
    // The proof that it is not shipped lives in catalogue.test.ts, which asserts the
    // emitted keys.
    expect(DEFAULT_TOLERANCE.gating).not.toContain("water" as never);
  });
});
