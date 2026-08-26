import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, test } from "bun:test";

import { buildCatalogue, serialiseCatalogue } from "./catalogue";
import { fixtureTable } from "./fixtures";
import { findPairs } from "./pairing";
import { loadPortions } from "./portions";
import { packageRoot } from "./source";
import { selectSubset } from "./subset";

// The generated file has two readers: `CiqualCatalogue.fromJsonOrNull` on the phone and
// nothing else. `CiqualEntry.kt` says so in its own header - "the shape is declared
// **here**, in the domain, so the two sides of the file agree by construction". Nothing
// enforces that across the language boundary, though: Kotlin's `ignoreUnknownKeys = true`
// means a key this generator renamed decodes to the field's default instead of failing,
// which on a phone is a food with 0 kcal rather than an error.
//
// So the contract is checked here, by reading the Kotlin source as text. It is a coarse
// test on purpose: it does not parse Kotlin, it reads the property list out of the two
// data classes and compares it with the keys actually emitted.

const KOTLIN = join(
  packageRoot,
  "../../apps/android/app/src/main/java/fr/kristenjestin/mue/domain/model/CiqualEntry.kt",
);

function propertiesOf(source: string, dataClass: string): Map<string, boolean> {
  const start = source.indexOf(`data class ${dataClass}(`);
  if (start === -1) throw new Error(`${dataClass} not found in CiqualEntry.kt`);
  const open = source.indexOf("(", start);
  let depth = 0;
  let end = open;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === "(") depth += 1;
    else if (source[index] === ")") {
      depth -= 1;
      if (depth === 0) {
        end = index;
        break;
      }
    }
  }
  // Line-oriented rather than a grammar: the constructor list is interleaved with KDoc,
  // and one property per line is a convention this file follows and `oxfmt`'s Kotlin
  // equivalent enforces. A property that stops being found fails the tests below, which
  // is the right way for this to break.
  const properties = new Map<string, boolean>();
  for (const line of source.slice(open + 1, end).split("\n")) {
    const match = line.trim().match(/^va[lr]\s+(\w+)\s*:\s*(.*)$/);
    if (match === null) continue;
    // `= null` or `= ReferenceUnit.GRAM.id` means the phone survives an absent key.
    properties.set(match[1] as string, (match[2] as string).includes("="));
  }
  return properties;
}

const available = existsSync(KOTLIN);
const describeIfPresent = available ? describe : describe.skip;

describeIfPresent("the shape CiqualEntry.kt declares", () => {
  const source = readFileSync(KOTLIN, "utf8");
  const entry = propertiesOf(source, "CiqualEntry");
  const catalogue = propertiesOf(source, "CiqualCatalogue");

  const table = fixtureTable();
  const pairs = findPairs(table.foods.values());
  const subset = selectSubset(table.foods.values(), pairs);
  const built = buildCatalogue("test", subset.selected, loadPortions());
  const parsed = JSON.parse(serialiseCatalogue(built.catalogue)) as {
    version: string;
    entries: Record<string, unknown>[];
  };

  test("the catalogue's own two keys are the two Kotlin declares", () => {
    expect([...catalogue.keys()].sort()).toEqual(["entries", "version"]);
    expect(Object.keys(parsed).sort()).toEqual(["entries", "version"]);
  });

  test("every key Kotlin requires is present on every emitted row", () => {
    const required = [...entry.entries()]
      .filter(([, hasDefault]) => !hasDefault)
      .map(([name]) => name);
    expect(required).toContain("code");
    expect(required).toContain("name");
    for (const row of parsed.entries) {
      for (const key of required) expect(Object.hasOwn(row, key)).toBe(true);
    }
  });

  test("every optional key emitted is one Kotlin knows about", () => {
    // `id` is the documented exception: see the test below.
    for (const row of parsed.entries) {
      for (const key of Object.keys(row)) {
        if (key === "id") continue;
        expect(entry.has(key)).toBe(true);
      }
    }
  });

  test("the fields PRD_FOOD 9.1 keeps are all declared, and water is not", () => {
    for (const key of [
      "energyMilliKcal",
      "proteinMilligrams",
      "carbsMilligrams",
      "fatMilligrams",
      "fibreMilligrams",
      "cookedRatioThousandths",
      "servingLabel",
      "servingThousandths",
      "unit",
    ]) {
      expect(entry.has(key)).toBe(true);
    }
    expect(entry.has("waterMilligrams")).toBe(false);
  });

  test("`id` is emitted and Kotlin does not yet read it", () => {
    // This is a cross-module gap, recorded here rather than fixed here: the asset is in
    // this task's scope and `CiqualEntry.kt` is not.
    //
    // Today `toFoodOrNull(sourceVersion, id = FoodId.random())` mints an identifier on the
    // device, so two installs seeded from the same file hold different ids for the same
    // food - which is exactly what `ExerciseCatalogSeed.kt` writes its ids down to avoid,
    // and what PRD_FOOD 21's synchronised `Food` aggregate cannot reconcile. The id is
    // therefore already in the file, where `ignoreUnknownKeys = true` accepts it harmlessly,
    // and adding `val id: String? = null` to `CiqualEntry` plus passing it to
    // `toFoodOrNull` is a one-line change on the Kotlin side.
    //
    // When that lands, this test flips to asserting the field exists. Until then it fails
    // loudly if someone adds it without telling the generator - or removes the emitted id.
    for (const row of parsed.entries) expect(typeof row["id"]).toBe("string");
    expect(entry.has("id")).toBe(false);
  });
});
