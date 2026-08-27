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
  windowCrossesMidnight,
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

describe("the windows tile the day rather than dotting it", () => {
  test("each window begins exactly where the one before it ended", () => {
    for (let index = 1; index < MEAL_SLOT_WINDOWS.length; index += 1) {
      expect(MEAL_SLOT_WINDOWS[index]!.fromMinutes).toBe(
        MEAL_SLOT_WINDOWS[index - 1]!.untilMinutes,
      );
    }
  });

  test("exactly one window crosses midnight, and it closes the ring", () => {
    const wrapping = MEAL_SLOT_WINDOWS.filter(windowCrossesMidnight);
    expect(wrapping.map((window) => window.slot)).toEqual(["evening_snack"]);
    // The last window ends where the first begins, which is what makes the day a loop
    // with no seam rather than six intervals with gaps between them.
    expect(MEAL_SLOT_WINDOWS.at(-1)!.untilMinutes).toBe(MEAL_SLOT_WINDOWS[0]!.fromMinutes);
  });

  test("each window is non-empty and its bounds are minutes of one day", () => {
    for (const window of MEAL_SLOT_WINDOWS) {
      expect(window.fromMinutes).toBeGreaterThanOrEqual(0);
      expect(window.fromMinutes).toBeLessThan(24 * 60);
      expect(window.untilMinutes).toBeGreaterThan(0);
      expect(window.untilMinutes).toBeLessThanOrEqual(24 * 60);
      // Only the wrapping one may end before it starts; the other five may not.
      if (!windowCrossesMidnight(window)) {
        expect(window.untilMinutes).toBeGreaterThan(window.fromMinutes);
      }
    }
  });

  /**
   * The 1 440-minute walk, on the server's own copy of the rule.
   *
   * The counts are the property, not the totality: two windows that overlap by a minute and two
   * that leave a minute out both still answer at every hour anybody would think to test, and
   * both show up here as a count that is one out.
   */
  test("every one of the 1440 minutes falls in exactly one moment", () => {
    const counts = new Map<string, number>();
    for (let minutes = 0; minutes < 24 * 60; minutes += 1) {
      const time = `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
      const slot = mealSlotForLocalTime(time) as string;
      counts.set(slot, (counts.get(slot) ?? 0) + 1);
    }

    expect(Object.fromEntries(counts)).toEqual({
      breakfast: 5 * 60,
      morning_snack: 2 * 60,
      lunch: 150,
      snack: 4 * 60,
      dinner: 210,
      evening_snack: 7 * 60,
    });
    expect([...counts.values()].reduce((total, count) => total + count, 0)).toBe(24 * 60);
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
    // Still a snack, and now the one that follows breakfast rather than the catch-all.
    expect(mealSlotForLocalTime("10:00")).toBe("morning_snack");
    expect(mealSlotForLocalTime("14:00")).toBe("lunch");
  });

  test("a window is closed at its start and open at its end", () => {
    expect(mealSlotForLocalTime("05:00")).toBe("breakfast");
    expect(mealSlotForLocalTime("09:59")).toBe("breakfast");
    expect(mealSlotForLocalTime("11:59")).toBe("morning_snack");
    expect(mealSlotForLocalTime("12:00")).toBe("lunch");
    expect(mealSlotForLocalTime("14:30")).toBe("snack");
    expect(mealSlotForLocalTime("18:30")).toBe("dinner");
    expect(mealSlotForLocalTime("22:00")).toBe("evening_snack");
  });

  test("the small hours belong to the window that crossed midnight to reach them", () => {
    expect(mealSlotForLocalTime("00:00")).toBe("evening_snack");
    expect(mealSlotForLocalTime("03:00")).toBe("evening_snack");
    expect(mealSlotForLocalTime("04:59")).toBe("evening_snack");
    expect(mealSlotForLocalTime("05:00")).toBe("breakfast");
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
