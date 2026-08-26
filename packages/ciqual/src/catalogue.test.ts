import { describe, expect, test } from "bun:test";

import { buildCatalogue, serialiseCatalogue, shortenName, type CiqualEntryJson } from "./catalogue";
import { fixtureTable } from "./fixtures";
import { findPairs } from "./pairing";
import { loadPortions } from "./portions";
import { selectSubset } from "./subset";
import type { SelectedFood } from "./subset";
import {
  COOKED_RATIO_RANGE,
  ENERGY_PER_100_RANGE,
  MACRO_PER_100_RANGE,
  NAME_LENGTH_RANGE,
  USUAL_SERVING_RANGE,
  absentOrIn,
} from "./units";
import { ciqualEntryId } from "./uuid";

const table = fixtureTable();
const pairs = findPairs(table.foods.values());

function selectionOf(codes: readonly string[]): SelectedFood[] {
  return codes.map((code) => ({
    food: table.foods.get(code) as NonNullable<ReturnType<typeof table.foods.get>>,
    cookedRatioThousandths:
      pairs.find((pair) => pair.reference.code === code)?.ratioThousandths ?? null,
    cookedFrom: null,
    unit: "gram" as const,
  }));
}

describe("the emitted shape", () => {
  const built = buildCatalogue("test", selectionOf(["9100", "22000", "36017"]), new Map());
  const byCode = new Map(built.catalogue.entries.map((entry) => [entry.code, entry]));

  test("is exactly what CiqualCatalogue declares", () => {
    expect(Object.keys(built.catalogue).sort()).toEqual(["entries", "version"]);
    expect(built.catalogue.version).toBe("test");
  });

  test("every number is an integer in the canonical unit", () => {
    for (const entry of built.catalogue.entries) {
      for (const key of [
        "energyMilliKcal",
        "proteinMilligrams",
        "carbsMilligrams",
        "fatMilligrams",
        "fibreMilligrams",
        "cookedRatioThousandths",
        "servingThousandths",
      ] as const) {
        const value = entry[key];
        if (value !== undefined) expect(Number.isInteger(value)).toBe(true);
      }
    }
    // Raw white rice is 350 kcal and 7,02 g of protein per 100 g.
    expect(byCode.get("9100")?.energyMilliKcal).toBe(350_000);
    expect(byCode.get("9100")?.proteinMilligrams).toBe(7_020);
  });

  test("water is read at build time and shipped nowhere (PRD_FOOD 9.1 keeps five)", () => {
    for (const entry of built.catalogue.entries) {
      expect(Object.keys(entry)).not.toContain("water");
      expect(Object.keys(entry)).not.toContain("waterGrams");
    }
  });

  test("emits the id rather than leaving the device to derive one", () => {
    expect(byCode.get("9100")?.id).toBe(ciqualEntryId("9100"));
    expect(byCode.get("9100")?.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    const ids = built.catalogue.entries.map((entry) => entry.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe("absent means unknown, 0 means a measured zero", () => {
  // The distinction PRD_FOOD 13.1 turns on, asserted on the serialised text rather than
  // on the object, because it is the *file* that has to carry it.
  test("survives the file", () => {
    const chicken = table.foods.get("36017") as NonNullable<ReturnType<typeof table.foods.get>>;
    const built = buildCatalogue("test", selectionOf(["36017"]), new Map());
    const json = serialiseCatalogue(built.catalogue);
    const reparsed = JSON.parse(json) as { entries: CiqualEntryJson[] };
    const entry = reparsed.entries[0] as CiqualEntryJson;

    // Raw chicken breast has a measured zero for carbohydrate.
    expect(chicken.composition.carbs?.kind).toBe("value");
    expect(entry.carbsMilligrams).toBe(0);
    expect(json).toContain('"carbsMilligrams": 0');
    // And the key exists, which is what tells Kotlin it is a known zero and not a null.
    expect(Object.hasOwn(entry, "carbsMilligrams")).toBe(true);
  });

  test("an undetermined constituent is an absent key, never a JSON null", () => {
    const rice = table.foods.get("9100") as NonNullable<ReturnType<typeof table.foods.get>>;
    const stripped = {
      food: { ...rice, composition: { ...rice.composition, fibre: undefined } },
      cookedRatioThousandths: null,
      cookedFrom: null,
      unit: "gram" as const,
    };
    const json = serialiseCatalogue(buildCatalogue("test", [stripped], new Map()).catalogue);
    expect(json).not.toContain("null");
    expect(json).not.toContain("fibreMilligrams");
  });
});

describe("PRD_FOOD 15's bounds", () => {
  const portions = loadPortions();
  const subset = selectSubset(table.foods.values(), pairs);
  const built = buildCatalogue("test", subset.selected, portions);

  test("every emitted row is inside them", () => {
    expect(built.catalogue.entries.length).toBeGreaterThan(0);
    for (const entry of built.catalogue.entries) {
      expect(entry.name.trim().length).toBeGreaterThanOrEqual(NAME_LENGTH_RANGE.min);
      expect(entry.name.trim().length).toBeLessThanOrEqual(NAME_LENGTH_RANGE.max);
      expect(absentOrIn(entry.energyMilliKcal, ENERGY_PER_100_RANGE)).toBe(true);
      expect(absentOrIn(entry.proteinMilligrams, MACRO_PER_100_RANGE)).toBe(true);
      expect(absentOrIn(entry.carbsMilligrams, MACRO_PER_100_RANGE)).toBe(true);
      expect(absentOrIn(entry.fatMilligrams, MACRO_PER_100_RANGE)).toBe(true);
      expect(absentOrIn(entry.fibreMilligrams, MACRO_PER_100_RANGE)).toBe(true);
      expect(absentOrIn(entry.cookedRatioThousandths, COOKED_RATIO_RANGE)).toBe(true);
      expect(absentOrIn(entry.servingThousandths, USUAL_SERVING_RANGE)).toBe(true);
    }
  });

  test("a usual serving is both halves or neither (PRD_FOOD 8.2)", () => {
    for (const entry of built.catalogue.entries) {
      expect(entry.servingLabel === undefined).toBe(entry.servingThousandths === undefined);
    }
  });

  test("nothing was rejected at emit time", () => {
    // A rejected row is a food that silently does not exist on the phone.
    expect(built.rejected).toEqual([]);
  });
});

describe("shortenName", () => {
  test("leaves a name inside the limit untouched", () => {
    expect(shortenName("Rice, white, raw")).toBe("Rice, white, raw");
  });

  test("drops trailing qualifications, food first", () => {
    const long =
      "Mashed potatoes, made from flakes, reconstituted with semi-skimmed milk and water, no added salt";
    expect(shortenName(long)).toBe("Mashed potatoes, made from flakes");
    expect(shortenName(long).length).toBeLessThanOrEqual(NAME_LENGTH_RANGE.max);
  });

  test("never leaves an unclosed parenthesis", () => {
    const long =
      "Gazelle horn (oriental pastry with almonds, crescent shaped and covered with icing sugar)";
    const short = shortenName(long);
    expect(short.length).toBeLessThanOrEqual(NAME_LENGTH_RANGE.max);
    expect(short.split("(").length).toBe(short.split(")").length);
    expect(short).toBe("Gazelle horn");
  });

  test("always lands inside PRD_FOOD 15, even with no comma to cut on", () => {
    const short = shortenName(`${"a".repeat(200)}`);
    expect(short.length).toBeLessThanOrEqual(NAME_LENGTH_RANGE.max);
    expect(short.length).toBeGreaterThanOrEqual(NAME_LENGTH_RANGE.min);
  });
});

describe("serialisation", () => {
  test("is stable, so a regeneration is a reviewable diff", () => {
    const once = serialiseCatalogue(
      buildCatalogue("v", selectionOf(["9100", "22000"]), new Map()).catalogue,
    );
    const twice = serialiseCatalogue(
      buildCatalogue("v", selectionOf(["9100", "22000"]), new Map()).catalogue,
    );
    expect(once).toBe(twice);
    expect(once.endsWith("\n")).toBe(true);
  });
});
