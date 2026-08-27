import { describe, expect, test } from "bun:test";
import { MEAL_SLOTS } from "./meal-plan";
import {
  MEAL_SLOT_FALLBACK,
  MEAL_SLOT_WINDOWS,
  defaultLocalTimeForMealSlot,
  localTimeToMinutes,
  mealSlotForLocalTime,
  mealSlotsWithoutDefaultTime,
  mealSlotsWithoutWindow,
} from "./meal-slot-clock";

/**
 * The tripwire for `meal-slot-clock.ts`.
 *
 * `MEAL_SLOTS` is expected to grow. When it does, the two tests at the top of this file
 * fail and name the moments that have no place on the clock — which is the whole point:
 * a moment the server cannot deduce from a time is a moment an agent would have to be
 * told, and a moment with no default time is one an agent must state the clock for. Both
 * are recoverable; both silently choosing the wrong slot is not.
 *
 * To fix a failure here: add one entry to `MEAL_SLOT_WINDOWS` (or make the moment the
 * fallback) and one entry to `MEAL_SLOT_DEFAULT_TIMES`, both in `meal-slot-clock.ts`.
 */

describe("every moment can be placed on the clock", () => {
  test("each `MEAL_SLOTS` member has a window, or is the catch-all", () => {
    expect(mealSlotsWithoutWindow()).toEqual([]);
  });

  test("each `MEAL_SLOTS` member has the default time of a retroactive entry", () => {
    expect(mealSlotsWithoutDefaultTime()).toEqual([]);
  });

  test("the fallback is a moment that exists", () => {
    expect(MEAL_SLOTS).toContain(MEAL_SLOT_FALLBACK);
  });
});

describe("the windows partition nothing they should not", () => {
  test("no two named windows overlap", () => {
    const sorted = [...MEAL_SLOT_WINDOWS].sort((a, b) => a.fromMinutes - b.fromMinutes);
    for (let index = 1; index < sorted.length; index += 1) {
      expect(sorted[index]!.fromMinutes).toBeGreaterThanOrEqual(sorted[index - 1]!.untilMinutes);
    }
  });

  test("each window is non-empty and inside one day", () => {
    for (const window of MEAL_SLOT_WINDOWS) {
      expect(window.fromMinutes).toBeGreaterThanOrEqual(0);
      expect(window.untilMinutes).toBeGreaterThan(window.fromMinutes);
      expect(window.untilMinutes).toBeLessThanOrEqual(24 * 60);
    }
  });
});

describe("deducing the moment from the time", () => {
  test("answers for every minute of the day, and always with a real moment", () => {
    // Totality is what lets `create_food_log` treat `slot` as optional: there is no
    // time of day for which the server would have to guess or refuse.
    for (let minutes = 0; minutes < 24 * 60; minutes += 1) {
      const time = `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
      const slot = mealSlotForLocalTime(time);
      expect({ time, known: MEAL_SLOTS.includes(slot as never) }).toEqual({ time, known: true });
    }
  });

  test("PRD_FOOD 22, in its own words: an apple at ten is a snack, a dessert at two is lunch", () => {
    expect(mealSlotForLocalTime("10:00")).toBe("snack");
    expect(mealSlotForLocalTime("14:00")).toBe("lunch");
  });

  test("a window is closed at its start and open at its end", () => {
    expect(mealSlotForLocalTime("05:00")).toBe("breakfast");
    expect(mealSlotForLocalTime("09:59")).toBe("breakfast");
    expect(mealSlotForLocalTime("11:29")).toBe("snack");
    expect(mealSlotForLocalTime("11:30")).toBe("lunch");
    expect(mealSlotForLocalTime("18:00")).toBe("dinner");
    expect(mealSlotForLocalTime("22:00")).toBe("snack");
    expect(mealSlotForLocalTime("03:00")).toBe("snack");
  });

  test("refuses a string that is not a time rather than placing it", () => {
    for (const bad of ["", "7:30", "24:00", "18:60", "18:00:00", "evening"]) {
      expect(localTimeToMinutes(bad)).toBeUndefined();
      expect(mealSlotForLocalTime(bad)).toBeUndefined();
    }
  });
});

describe("the default time of a moment", () => {
  test("falls inside the moment it belongs to", () => {
    // Otherwise "log it at lunch" would write a line the clock reads as another moment.
    for (const slot of MEAL_SLOTS) {
      const time = defaultLocalTimeForMealSlot(slot);
      if (time === undefined) continue;
      expect({ slot, deduced: mealSlotForLocalTime(time) }).toEqual({ slot, deduced: slot });
    }
  });

  test("is undefined for a moment this build does not know, never a made-up time", () => {
    expect(defaultLocalTimeForMealSlot("second_breakfast")).toBeUndefined();
  });
});
