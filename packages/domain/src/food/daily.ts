import { MEAL_SLOTS, type FoodLogEntryPayloadV1 } from "@mue/contracts";
import { NUTRIENT_METRICS, type NutrientMetric, type Nutrients, strictSum } from "./nutrition";

/**
 * What one day of the journal is worth: PRD_FOOD 10.1 and 13.1, as the server answers them.
 *
 * The TypeScript half of `domain/logic/DailyNutritionSummary.kt`, and it keeps that file's
 * central distinction, which is the one an agent will otherwise get wrong.
 *
 * Four states matter, and only the first two are usually noticed:
 *
 *  1. **not recorded** — no line at all. PRD_FOOD 10.4: the day *"reste vide"* rather than
 *     showing a zero. The strict sum of no lines is nevertheless a *known* zero, so the
 *     total alone cannot tell this state from the next one — [DailyNutrition.isRecorded]
 *     is what does.
 *  2. **recorded, energy known** — the ordinary case.
 *  3. **recorded, energy unknown** — one line carried no energy, so PRD_FOOD 13.1 makes the
 *     whole day's energy null. The day *is* recorded; its other metrics may well be known.
 *     Confusing this with state 1 is the mistake this shape exists to prevent.
 *  4. **recorded, some metrics known and others not** — the metric-by-metric case: a known
 *     energy beside an unknown protein.
 *
 * ## Why the unknowns are named
 *
 * [DailyNutrition.unknownFrom] lists, per metric, the journal lines that carry no figure
 * for it. It is not decoration: it turns *"protein: —"* into *"protein is unknown because
 * the apple and the soup have no protein figure"*, which is a sentence a person can act on
 * — by correcting one line — where a dash is one they can only accept. Section 14.4 asks
 * the server not to invent a value; naming what is missing is the constructive half of the
 * same rule.
 */

/** One moment of the day that actually holds something (PRD_FOOD 10.1). */
export interface MealSlotNutrition {
  readonly slot: string;
  readonly total: Nutrients;
  readonly entryCount: number;
  readonly entryIds: readonly string[];
  readonly unknownFrom: Readonly<Record<NutrientMetric, readonly string[]>>;
}

export interface DailyNutrition {
  readonly date: string;
  /** Only the moments that carry a line, in `MEAL_SLOTS` order (PRD_FOOD 10.1). */
  readonly slots: readonly MealSlotNutrition[];
  readonly total: Nutrients;
  readonly entryCount: number;
  /** PRD_FOOD 10.4: a day with no line is not a day worth zero. */
  readonly isRecorded: boolean;
  readonly unknownFrom: Readonly<Record<NutrientMetric, readonly string[]>>;
}

/**
 * The bundle a stored journal line contributes.
 *
 * A line is frozen (PRD_FOOD 8.4): these are the numbers computed when it was written, and
 * nothing here reopens the food or the recipe it came from. Absent keys become `null`,
 * which is a widening and never a defaulting.
 */
export function nutrientsOfLogEntry(entry: FoodLogEntryPayloadV1): Nutrients {
  return {
    energyMilliKcal: entry.energyMilliKcal ?? null,
    proteinMilligrams: entry.proteinMilligrams ?? null,
    carbsMilligrams: entry.carbsMilligrams ?? null,
    fatMilligrams: entry.fatMilligrams ?? null,
    fibreMilligrams: entry.fibreMilligrams ?? null,
  };
}

function unknownFrom(
  entries: readonly FoodLogEntryPayloadV1[],
): Readonly<Record<NutrientMetric, readonly string[]>> {
  const found = {} as Record<NutrientMetric, string[]>;
  for (const metric of NUTRIENT_METRICS) found[metric] = [];
  for (const entry of entries) {
    const nutrients = nutrientsOfLogEntry(entry);
    for (const metric of NUTRIENT_METRICS) {
      if (nutrients[metric] === null) found[metric].push(entry.id);
    }
  }
  return found;
}

/**
 * The moments a day's lines fall into, in the contract's own order.
 *
 * `MEAL_SLOTS` is walked rather than the entries grouped, so the order is the enum's and
 * not the order rows happened to arrive in — and so a moment added to `MealSlot` is picked
 * up here without an edit. Nothing in this file names a slot.
 *
 * A slot a line carries that `MEAL_SLOTS` does not hold cannot occur through the contract,
 * but it would be silently dropped if it did; so anything left over is appended, in the
 * order encountered, rather than lost from a total that claims to be the day's.
 */
function slotsOf(entries: readonly FoodLogEntryPayloadV1[]): readonly string[] {
  const present = new Set(entries.map((entry) => entry.slot));
  const known = MEAL_SLOTS.filter((slot) => present.has(slot));
  const unknown = [...present].filter((slot) => !(MEAL_SLOTS as readonly string[]).includes(slot));
  return [...known, ...unknown];
}

/**
 * PRD_FOOD 13.1: `total d'un moment = somme stricte de ses lignes`, and a day is the same
 * addition over a wider set.
 *
 * `entries` are the lines of one day; the caller has already selected them on the stored
 * local date alone, so no time zone takes part (PRD_FOOD 10.1).
 */
export function dailyNutrition(
  date: string,
  entries: readonly FoodLogEntryPayloadV1[],
): DailyNutrition {
  const slots = slotsOf(entries).map((slot): MealSlotNutrition => {
    const lines = entries.filter((entry) => entry.slot === slot);
    return {
      slot,
      total: strictSum(lines.map(nutrientsOfLogEntry)),
      entryCount: lines.length,
      entryIds: lines.map((line) => line.id),
      unknownFrom: unknownFrom(lines),
    };
  });

  return {
    date,
    slots,
    total: strictSum(entries.map(nutrientsOfLogEntry)),
    entryCount: entries.length,
    isRecorded: entries.length > 0,
    unknownFrom: unknownFrom(entries),
  };
}
