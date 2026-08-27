import { MEAL_SLOTS, mealSlotSchema } from "./meal-plan";
import type { z } from "zod";

/**
 * The clock half of `MealSlot`: which moment a time of day falls in, and which time a
 * moment defaults to.
 *
 * PRD_FOOD 10.3 states both, and until now only Android held them —
 * `MealSlot.forTime` and `MealSlot.defaultTime`, in
 * `domain/model/FoodLogEntry.kt`. The MCP `create_food_log` tool needs the same two
 * facts: PRD_FOOD 8.4 makes `slot` a required field of a journal line, and the moment
 * an agent is told is *"deduced from the time"* has to be deduced by something. Putting
 * it in `@mue/contracts` rather than in the tool is what stops a second, drifting copy
 * of PRD_FOOD 10.3 existing on the server.
 *
 * ## Deliberately not an exhaustive table
 *
 * `MEAL_SLOTS` is the enum, and it grows — the fuller set of moments this file was
 * written in anticipation of has since landed, taking it from four to six and giving
 * every minute of the day a moment of its own. It can grow again. So nothing here is
 * typed `Record<MealSlot, …>`: an addition to the enum must not stop the build of a
 * package that has no opinion about how many moments there are.
 *
 * What it does instead is degrade in the one direction PRD section 14.4 allows. A slot
 * with no window is never *chosen* by [mealSlotForLocalTime] — it can still be stated
 * outright — and a slot with no default time makes [defaultLocalTimeForMealSlot] return
 * `undefined`, which the caller turns into "give me the time" rather than into a time it
 * made up. A moment this file has not been told about therefore costs an agent one extra
 * question, and never a fabricated value.
 *
 * `meal-slot-clock.test.ts` asserts that every member of `MEAL_SLOTS` is covered by both
 * tables. It is the tripwire: adding a moment to the enum turns that test red, in the
 * package the moment was added to, with the two lines that are missing named.
 */

export type MealSlot = z.infer<typeof mealSlotSchema>;

/**
 * One named window, in minutes since local midnight.
 *
 * Half-open — closed at `fromMinutes`, open at `untilMinutes` — and that is not a
 * detail. PRD_FOOD 10.3 writes breakfast as `05:00 – 10:00`, but PRD_FOOD 22 requires
 * that *"une pomme à dix heures"* be a snack; read closed the two statements contradict
 * each other, read half-open they agree, and `14:00` still falls in lunch as the same
 * criterion demands. `MealSlot.forTime` settled it the same way.
 */
export interface MealSlotWindow {
  readonly slot: MealSlot;
  /** Inclusive, minutes since local midnight. */
  readonly fromMinutes: number;
  /**
   * Exclusive, minutes since local midnight.
   *
   * **May be earlier than [fromMinutes]**, and for exactly one window it is: the evening
   * snack runs `22:00 – 05:00` and therefore crosses midnight. That window is the union
   * of its two halves rather than their intersection, which is the whole of the
   * difference — `01:00` is inside it and inside nothing else.
   */
  readonly untilMinutes: number;
}

function at(hours: number, minutes: number): number {
  return hours * 60 + minutes;
}

/** True of the one window whose end is not after its start. */
export function windowCrossesMidnight(window: MealSlotWindow): boolean {
  return window.untilMinutes <= window.fromMinutes;
}

function windowContains(window: MealSlotWindow, minutes: number): boolean {
  return windowCrossesMidnight(window)
    ? minutes >= window.fromMinutes || minutes < window.untilMinutes
    : minutes >= window.fromMinutes && minutes < window.untilMinutes;
}

/**
 * The six windows, transcribed from `MealSlot`'s own constants and **tiling the day**.
 *
 * PRD_FOOD 10.3 named three of them and let everything else fall to `snack`, which left a
 * quarter of the clock — the whole night, the late morning, the end of the afternoon — under one
 * label that meant nothing in particular. The owner's requirement is that no hour be without a
 * moment, so each meal now has its own snack after it and the six windows partition the 1 440
 * minutes exactly: each one ends precisely where the next begins, and the last wraps round to
 * meet the first.
 *
 * That is why [MEAL_SLOT_FALLBACK] below is now unreachable rather than load-bearing.
 */
export const MEAL_SLOT_WINDOWS: readonly MealSlotWindow[] = [
  { slot: "breakfast", fromMinutes: at(5, 0), untilMinutes: at(10, 0) },
  { slot: "morning_snack", fromMinutes: at(10, 0), untilMinutes: at(12, 0) },
  { slot: "lunch", fromMinutes: at(12, 0), untilMinutes: at(14, 30) },
  { slot: "snack", fromMinutes: at(14, 30), untilMinutes: at(18, 30) },
  { slot: "dinner", fromMinutes: at(18, 30), untilMinutes: at(22, 0) },
  // The one that crosses midnight, and the only one whose containment is a union.
  { slot: "evening_snack", fromMinutes: at(22, 0), untilMinutes: at(5, 0) },
];

/**
 * The moment a time no window claims would belong to.
 *
 * It used to be the catch-all PRD_FOOD 10.3 made it, and it is **no longer reachable**: the six
 * windows above cover every minute of the day, so [mealSlotForLocalTime] answers from a window
 * every time. It is kept because it is what makes that function total *by construction* rather
 * than by a proof about the table beside it — a window edited into a gap would be answered here
 * instead of by a thrown error or an invented moment.
 */
export const MEAL_SLOT_FALLBACK: MealSlot = "snack";

/**
 * An hour inside each moment, which PRD_FOOD 10.3 makes the default time of a
 * retroactive entry.
 *
 * It is used in one direction only: when a person named the meal but not the clock
 * ("at lunch yesterday"), the line is written at the moment's own default rather than
 * refused. Going the other way — inventing a *moment* from nothing — is what
 * [mealSlotForLocalTime] exists to make unnecessary.
 *
 * Every one of these falls inside the window of the moment it belongs to, which
 * `meal-slot-clock.test.ts` asserts: otherwise "log it at lunch" would write a line the
 * clock reads back as another moment.
 */
export const MEAL_SLOT_DEFAULT_TIMES: Readonly<Partial<Record<MealSlot, string>>> = {
  breakfast: "08:00",
  morning_snack: "11:00",
  lunch: "13:00",
  snack: "16:30",
  dinner: "20:00",
  evening_snack: "23:00",
};

const LOCAL_TIME = /^([01]\d|2[0-3]):([0-5]\d)$/;

/** Minutes since local midnight, or `undefined` when the string is not an `HH:MM`. */
export function localTimeToMinutes(time: string): number | undefined {
  const match = LOCAL_TIME.exec(time);
  if (match === null) return undefined;
  return at(Number(match[1]), Number(match[2]));
}

/**
 * The moment a local time falls in (PRD_FOOD 10.3).
 *
 * Total for every readable `HH:MM`, and `undefined` for anything else — a caller that
 * has not validated its input learns so rather than being handed a moment for a string
 * that is not a time.
 */
export function mealSlotForLocalTime(time: string): MealSlot | undefined {
  const minutes = localTimeToMinutes(time);
  if (minutes === undefined) return undefined;
  const window = MEAL_SLOT_WINDOWS.find((candidate) => windowContains(candidate, minutes));
  return window?.slot ?? MEAL_SLOT_FALLBACK;
}

/**
 * The default time of a moment, or `undefined` when this build has none for it.
 *
 * `undefined` is a real answer and the caller must treat it as one: PRD section 14.4
 * forbids the server fabricating a mandatory value, and a time is one.
 */
export function defaultLocalTimeForMealSlot(slot: string): string | undefined {
  return (MEAL_SLOT_DEFAULT_TIMES as Record<string, string | undefined>)[slot];
}

/** Every moment this file can place on the clock, for the tripwire test. */
export function mealSlotsWithoutWindow(): readonly string[] {
  const placed = new Set<string>([
    MEAL_SLOT_FALLBACK,
    ...MEAL_SLOT_WINDOWS.map((window) => window.slot),
  ]);
  return MEAL_SLOTS.filter((slot) => !placed.has(slot));
}

/** Every moment this file has no default time for, for the tripwire test. */
export function mealSlotsWithoutDefaultTime(): readonly string[] {
  return MEAL_SLOTS.filter((slot) => defaultLocalTimeForMealSlot(slot) === undefined);
}
