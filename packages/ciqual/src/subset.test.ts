import { describe, expect, test } from "bun:test";

import { fixtureTable } from "./fixtures";
import { findPairs } from "./pairing";
import { parseName } from "./preparation";
import {
  capKey,
  clusterKey,
  genericRank,
  selectSubset,
  subsetOverrides,
  subsetRules,
  withinTolerance,
} from "./subset";
import type { CiqualFood } from "./table";

const table = fixtureTable();
const food = (code: string): CiqualFood => table.foods.get(code) as CiqualFood;

describe("the rules file", () => {
  test("declares everything the pipeline reads", () => {
    expect(subsetRules.subGroupAllowlist.length).toBeGreaterThan(0);
    expect(subsetRules.clustering.relativeTolerance).toBe(0.15);
    expect(subsetRules.clustering.maxPerCluster).toBeGreaterThan(0);
    expect(Object.keys(subsetRules.clusterBy).length).toBeGreaterThan(0);
  });

  test("every exclude pattern compiles", () => {
    for (const entry of subsetRules.excludePatterns) {
      expect(() => new RegExp(entry.pattern)).not.toThrow();
      expect(entry.reason.length).toBeGreaterThan(0);
    }
  });

  test("the brand-parenthesis pattern is case-sensitive, or it eats Ciqual's own averages", () => {
    // Read case-insensitively, `\([A-Z]` matches "(average)" - the generic rows the whole
    // subset works hardest to keep.
    const brand = subsetRules.excludePatterns.find((entry) => entry.pattern === "\\([A-Z]");
    expect(brand?.caseSensitive).toBe(true);
    expect(new RegExp(brand?.pattern ?? "", "i").test("Lentil, dried (average)")).toBe(true);
    expect(new RegExp(brand?.pattern ?? "").test("Lentil, dried (average)")).toBe(false);
    expect(new RegExp(brand?.pattern ?? "").test("Mineral still water (Evian), bottled")).toBe(
      true,
    );
  });

  test("every override names a real Ciqual code and says why", () => {
    for (const entry of [...subsetOverrides.keep, ...subsetOverrides.drop]) {
      expect(entry.code).toMatch(/^\d+$/);
      expect(entry.note.length).toBeGreaterThan(20);
    }
  });
});

describe("withinTolerance", () => {
  test("an unknown on either side cannot testify to a difference", () => {
    expect(withinTolerance(null, 100, 0.15, 1)).toBe(true);
    expect(withinTolerance(100, null, 0.15, 1)).toBe(true);
  });

  test("the floor stops a relative test firing on values the source barely resolves", () => {
    // 15 % of 0,79 is 0,12 g, an order of magnitude under Ciqual's own precision.
    expect(withinTolerance(0.79, 0.9, 0.15, 1)).toBe(true);
    // But a real gap still counts.
    expect(withinTolerance(1, 15, 0.15, 1)).toBe(false);
  });
});

describe("clusterKey", () => {
  test("keeps two foods apart even when their numbers agree", () => {
    // Every vegetable oil is 900 kcal and 100 g of fat; a sub-group-wide 15 % would leave
    // one of them and a search for "olive oil" would return nothing.
    const oil = (name: string): CiqualFood => ({
      ...food("9100"),
      nameEng: name,
      subGroupCode: "0902",
    });
    expect(clusterKey(oil("Olive oil, extra virgin"))).not.toBe(clusterKey(oil("Sunflower oil")));
  });

  test("gathers the qualifications of one food", () => {
    const apple = (name: string): CiqualFood => ({ ...food("13039"), nameEng: name });
    expect(clusterKey(apple("Apple, flesh and skin, raw"))).toBe(
      clusterKey(apple("Apple, Golden variety, raw")),
    );
  });

  test("normalises Ciqual's two spellings of yoghurt", () => {
    const y = (name: string): CiqualFood => ({ ...food("19593"), nameEng: name });
    expect(clusterKey(y("Yogurt or fermented milk, plain"))).toBe(
      clusterKey(y("Yoghurt or fermented milk, plain (average)")),
    );
  });

  test("honours the per-sub-group unit", () => {
    const a: CiqualFood = { ...food("9100"), subGroupCode: "0406", nameEng: "Cod, raw" };
    const b: CiqualFood = { ...food("9100"), subGroupCode: "0406", nameEng: "Megrim, raw" };
    expect(clusterKey(a)).not.toBe(clusterKey(b));
    expect(clusterKey(a, { "0406": "subGroup" })).toBe(clusterKey(b, { "0406": "subGroup" }));
  });
});

describe("capKey", () => {
  test("keeps white and wholegrain rice apart, which PRD_FOOD 9.5 requires", () => {
    // The head noun would not: `Rice` covers white, wholegrain, basmati, red and wild, and
    // a cap counting head nouns throws white rice out of the catalogue.
    const rice = (name: string): CiqualFood => ({ ...food("9100"), nameEng: name });
    expect(capKey(rice("Rice, white, raw"))).not.toBe(capKey(rice("Rice, wholegrain, raw")));
  });

  test("gathers the states and the preservations of one food", () => {
    const peas = (name: string): CiqualFood => ({ ...food("9100"), nameEng: name });
    const key = capKey(peas("Garden peas, raw"));
    expect(capKey(peas("Garden peas, cooked"))).toBe(key);
    expect(capKey(peas("Garden peas, canned, drained"))).toBe(key);
    expect(capKey(peas("Garden peas, frozen, raw"))).toBe(key);
  });
});

describe("genericRank", () => {
  test("prefers the rows Ciqual publishes as generic stand-ins", () => {
    const plain: CiqualFood = { ...food("9100"), nameEng: "Lentil, dried (average)" };
    const specific: CiqualFood = { ...food("9100"), nameEng: "Lentil, pink or red, dried" };
    expect(genericRank(plain)[0]).toBeLessThan(genericRank(specific)[0]);
  });
});

describe("selectSubset", () => {
  const pairs = findPairs(table.foods.values());
  const result = selectSubset(table.foods.values(), pairs);

  test("folds the cooked half of a water-only pair into its reference", () => {
    const kept = new Set(result.selected.map((entry) => entry.food.code));
    expect(kept.has("36017")).toBe(true);
    expect(kept.has("36018")).toBe(false);
    const chicken = result.selected.find((entry) => entry.food.code === "36017");
    expect(chicken?.cookedRatioThousandths).toBe(766);
  });

  test("keeps both halves when the composition changed", () => {
    const kept = new Set(result.selected.map((entry) => entry.food.code));
    expect(kept.has("4008")).toBe(true);
    expect(kept.has("4015")).toBe(true);
    for (const entry of result.selected.filter((one) => ["4008", "4015"].includes(one.food.code))) {
      expect(entry.cookedRatioThousandths).toBeNull();
    }
  });

  test("every drop is accounted for, with a reason and a detail", () => {
    const counted = Object.values(result.droppedByReason).reduce((sum, one) => sum + one, 0);
    expect(counted).toBe(result.dropped.length);
    for (const entry of result.dropped) expect(entry.detail.length).toBeGreaterThan(0);
  });

  test("is deterministic", () => {
    const again = selectSubset(table.foods.values(), pairs);
    expect(again.selected.map((entry) => entry.food.code)).toEqual(
      result.selected.map((entry) => entry.food.code),
    );
  });

  test("marks a drink as millilitres and nothing else (PRD_FOOD 8.6 applies no density)", () => {
    for (const entry of result.selected) {
      const expected = subsetRules.millilitreSubGroups.includes(entry.food.subGroupCode);
      expect(entry.unit).toBe(expected ? "millilitre" : "gram");
    }
  });
});

describe("preparation parsing", () => {
  test("reads a Ciqual name as food plus state", () => {
    expect(parseName("Rice, white, cooked, no added salt")).toMatchObject({
      stem: "Rice, white",
      state: "cooked",
    });
    expect(parseName("Lentil, pink or red, dried")).toMatchObject({
      stem: "Lentil, pink or red",
      state: "reference",
    });
    expect(parseName("Chicken, breast, without skin, grilled/pan-fried")).toMatchObject({
      stem: "Chicken, breast, without skin",
      state: "cooked",
    });
  });

  test("parboiled is a product, not a preparation", () => {
    // Admitting it would collapse `Rice, white, parboiled, raw` onto plain white rice.
    expect(parseName("Rice, white, parboiled, raw").stem).toBe("Rice, white, parboiled");
  });

  test("salting and frying are transformations a ratio cannot model", () => {
    expect(parseName("Cod, salted, boiled/cooked in water").state).toBe("transformed");
    expect(parseName("Potato, sautéed/pan-fried with fat").state).toBe("transformed");
    expect(parseName("Apricot, canned in syrup, drained").state).toBe("transformed");
  });
});
