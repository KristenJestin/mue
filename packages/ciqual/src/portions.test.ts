import { describe, expect, test } from "bun:test";

import { loadPortions, portionEntries, PortionValidationError } from "./portions";
import { USUAL_SERVING_RANGE } from "./units";

describe("portions.json", () => {
  const portions = loadPortions();

  test("is a hand-maintained file of the size PRD_FOOD 8.2 justifies", () => {
    // Not a portion for every food. A wrong portion weight is invisible in a way a wrong
    // calorie figure is not, so this file only covers foods that come in a unit a person
    // handles.
    expect(portions.size).toBeGreaterThanOrEqual(40);
    expect(portions.size).toBeLessThanOrEqual(80);
  });

  test("every weight is inside PRD_FOOD 15's 1..2 000 g", () => {
    for (const portion of portions.values()) {
      expect(portion.thousandths).toBeGreaterThanOrEqual(USUAL_SERVING_RANGE.min);
      expect(portion.thousandths).toBeLessThanOrEqual(USUAL_SERVING_RANGE.max);
    }
  });

  test("every label is something a person would recognise", () => {
    for (const portion of portions.values()) {
      expect(portion.label.length).toBeGreaterThan(0);
      expect(portion.label.length).toBeLessThanOrEqual(80);
      expect(portion.label).toBe(portion.label.trim());
    }
  });

  test("every code is a Ciqual alim_code", () => {
    for (const entry of portionEntries) expect(entry.code).toMatch(/^\d+$/);
  });

  test("no food is given two portions", () => {
    expect(new Set(portionEntries.map((entry) => entry.code)).size).toBe(portionEntries.length);
  });
});

describe("validation", () => {
  test("a weight over 2 000 g fails the build rather than being skipped", () => {
    // Skipping it would leave the author believing a portion shipped, and the food quietly
    // losing the affordance they wrote it for.
    expect(() => loadPortions([{ code: "1", label: "1 sack", grams: 4000 }])).toThrow(
      PortionValidationError,
    );
  });

  test("a weight under 1 g fails too", () => {
    expect(() => loadPortions([{ code: "1", label: "1 pinch", grams: 0 }])).toThrow(
      PortionValidationError,
    );
  });

  test("a duplicate code fails", () => {
    expect(() =>
      loadPortions([
        { code: "1", label: "1 egg", grams: 50 },
        { code: "1", label: "1 large egg", grams: 60 },
      ]),
    ).toThrow(PortionValidationError);
  });

  test("the error names every problem at once, not just the first", () => {
    try {
      loadPortions([
        { code: "1", label: "1 sack", grams: 4000 },
        { code: "2", label: "", grams: 50 },
      ]);
      throw new Error("expected a PortionValidationError");
    } catch (error) {
      expect(error).toBeInstanceOf(PortionValidationError);
      expect((error as PortionValidationError).problems.length).toBe(2);
    }
  });
});
