// The asset itself: the exact JSON `CiqualCatalogue` and `CiqualEntry` declare.
//
// The Kotlin data classes in `apps/android/.../domain/model/CiqualEntry.kt` are the
// contract, and they are the contract on purpose - the shape is declared in the domain
// so the two sides of the file agree by construction. This module emits that and
// nothing else.
//
// Two properties of the emitted rows carry the module's whole null rule:
//
//   * every number is an **integer in the canonical unit** - thousandths of the display
//     unit - so the phone parses no float and the value shipped is bit-for-bit the value
//     stored;
//   * a constituent Ciqual did not determine is an **absent key**, and an explicit `0`
//     is a measured zero. PRD_FOOD 13.1 forbids reading one as the other, and
//     `JSON.stringify` dropping `undefined` is what makes the distinction survive the
//     file rather than being reconstructed by a convention on the far side.

import { ciqualEntryId } from "./uuid";
import type { Portion } from "./portions";
import type { SelectedFood } from "./subset";
import { thousandths } from "./table";
import {
  COOKED_RATIO_RANGE,
  ENERGY_PER_100_RANGE,
  MACRO_PER_100_RANGE,
  NAME_LENGTH_RANGE,
  USUAL_SERVING_RANGE,
  absentOrIn,
} from "./units";

export interface CiqualEntryJson {
  /**
   * A name-based UUID over `ciqual:<alim_code>`, emitted rather than derived on the
   * device. `ExerciseCatalogSeed.kt` writes its identifiers down for the same reason: an
   * id generated at seeding time differs on every install, and PRD_FOOD 21's synchronised
   * `Food` aggregate cannot reconcile rows whose primary key depends on which phone
   * created them.
   */
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly unit: string;
  readonly energyMilliKcal?: number;
  readonly proteinMilligrams?: number;
  readonly carbsMilligrams?: number;
  readonly fatMilligrams?: number;
  readonly fibreMilligrams?: number;
  readonly cookedRatioThousandths?: number;
  readonly servingLabel?: string;
  readonly servingThousandths?: number;
}

export interface CiqualCatalogueJson {
  readonly version: string;
  readonly entries: readonly CiqualEntryJson[];
}

export interface RejectedRow {
  readonly code: string;
  readonly name: string;
  readonly reason: string;
}

export interface ShortenedRow {
  readonly code: string;
  readonly from: string;
  readonly to: string;
}

export interface BuildResult {
  readonly catalogue: CiqualCatalogueJson;
  readonly rejected: readonly RejectedRow[];
  readonly shortened: readonly ShortenedRow[];
  readonly portionsAttached: number;
  readonly portionsUnmatched: readonly string[];
}

/**
 * Cutting inside a parenthetical leaves `Gazelle horn (oriental pastry with almonds`,
 * which reads as a truncation bug rather than as a name. Dropping from the unmatched
 * bracket keeps the food and loses only the gloss.
 */
function balanceParentheses(name: string): string {
  let depth = 0;
  let openedAt = -1;
  for (let index = 0; index < name.length; index += 1) {
    if (name[index] === "(") {
      if (depth === 0) openedAt = index;
      depth += 1;
    } else if (name[index] === ")") {
      depth = Math.max(0, depth - 1);
    }
  }
  if (depth === 0 || openedAt < 0) return name;
  return name.slice(0, openedAt).replace(/[\s,]+$/, "");
}

/**
 * PRD_FOOD 15 caps a name at 80 characters, and nineteen Ciqual rows are longer.
 *
 * Dropping them is the wrong answer: `Mashed potatoes, made from flakes, reconstituted
 * with semi-skimmed milk and water, no added salt` is a food someone eats, and a
 * catalogue that silently lacks mashed potato is worse than one that calls it `Mashed
 * potatoes, made from flakes`. Ciqual's names are ordered food-first, qualifications
 * after, so dropping trailing comma segments removes detail in the right order and never
 * changes what the row is about. Every shortening is printed in the report, because a
 * name the generator chose is a name nobody reviewed.
 */
export function shortenName(name: string, limit = NAME_LENGTH_RANGE.max): string {
  const cleaned = name.replace(/\s+/g, " ").trim();
  if (cleaned.length <= limit) return cleaned;

  const segments = cleaned.split(",").map((segment) => segment.trim());
  let kept = segments.length;
  while (kept > 1 && segments.slice(0, kept).join(", ").length > limit) kept -= 1;
  const shortened = balanceParentheses(segments.slice(0, kept).join(", "));
  if (shortened.length <= limit) return shortened;

  // A single segment longer than the limit: cut on a word boundary so the result still
  // reads as words rather than as a truncation artefact.
  const hard = shortened.slice(0, limit);
  const boundary = hard.lastIndexOf(" ");
  const cut = (boundary > limit / 2 ? hard.slice(0, boundary) : hard).trim();
  return balanceParentheses(cut).replace(/[\s,]+$/, "");
}

/** `undefined` is what `JSON.stringify` omits; `null` would emit `null` and mean zero-ish. */
function optional(value: number | null): number | undefined {
  return value === null ? undefined : value;
}

/**
 * Every rule `CiqualEntry.toFoodOrNull` applies, applied here first.
 *
 * Duplicating the check is the point rather than the cost: the Kotlin side rejects a bad
 * row by returning `null`, which on a phone means a food that silently does not exist.
 * Catching it at build time turns that into a line in a report, next to the code that
 * produced it. The one PRD_FOOD 15 rule deliberately not applied is the 100 g ceiling on
 * the sum of protein, carbohydrate and fat: that rule guards a form where a person can be
 * told which field to fix, and Ciqual's own rounding puts a handful of legitimate rows a
 * few hundredths over it.
 */
function rejectionReason(entry: CiqualEntryJson): string | null {
  if (entry.code.trim() === "") return "empty alim_code";
  const length = entry.name.trim().length;
  if (length < NAME_LENGTH_RANGE.min || length > NAME_LENGTH_RANGE.max) {
    return `name is ${length} characters, outside PRD_FOOD 15's 1..80`;
  }
  if (!absentOrIn(entry.energyMilliKcal, ENERGY_PER_100_RANGE)) {
    return `energy ${entry.energyMilliKcal} outside 0..900000 milli-kcal`;
  }
  for (const [name, value] of [
    ["protein", entry.proteinMilligrams],
    ["carbs", entry.carbsMilligrams],
    ["fat", entry.fatMilligrams],
    ["fibre", entry.fibreMilligrams],
  ] as const) {
    if (!absentOrIn(value, MACRO_PER_100_RANGE)) {
      return `${name} ${value} outside 0..100000 mg`;
    }
  }
  if (!absentOrIn(entry.cookedRatioThousandths, COOKED_RATIO_RANGE)) {
    return `cookedRatio ${entry.cookedRatioThousandths} outside 300..5000 thousandths`;
  }
  if (!absentOrIn(entry.servingThousandths, USUAL_SERVING_RANGE)) {
    return `serving ${entry.servingThousandths} outside 1000..2000000 thousandths`;
  }
  if ((entry.servingLabel === undefined) !== (entry.servingThousandths === undefined)) {
    return "PRD_FOOD 8.2: a usual serving is both halves or neither";
  }
  return null;
}

export function buildCatalogue(
  version: string,
  selected: readonly SelectedFood[],
  portions: ReadonlyMap<string, Portion>,
): BuildResult {
  const entries: CiqualEntryJson[] = [];
  const rejected: RejectedRow[] = [];
  const shortened: ShortenedRow[] = [];
  const matched = new Set<string>();

  for (const { food, cookedRatioThousandths, unit } of selected) {
    const portion = portions.get(food.code);
    if (portion !== undefined) matched.add(food.code);

    const entry: CiqualEntryJson = {
      id: ciqualEntryId(food.code),
      code: food.code,
      name: shortenName(food.nameEng),
      unit,
      ...(optional(thousandths(food, "energy")) === undefined
        ? {}
        : { energyMilliKcal: thousandths(food, "energy") as number }),
      ...(optional(thousandths(food, "protein")) === undefined
        ? {}
        : { proteinMilligrams: thousandths(food, "protein") as number }),
      ...(optional(thousandths(food, "carbs")) === undefined
        ? {}
        : { carbsMilligrams: thousandths(food, "carbs") as number }),
      ...(optional(thousandths(food, "fat")) === undefined
        ? {}
        : { fatMilligrams: thousandths(food, "fat") as number }),
      ...(optional(thousandths(food, "fibre")) === undefined
        ? {}
        : { fibreMilligrams: thousandths(food, "fibre") as number }),
      ...(cookedRatioThousandths === null ? {} : { cookedRatioThousandths }),
      ...(portion === undefined
        ? {}
        : { servingLabel: portion.label, servingThousandths: portion.thousandths }),
    };

    const cleaned = food.nameEng.replace(/\s+/g, " ").trim();
    if (entry.name !== cleaned) {
      shortened.push({ code: food.code, from: cleaned, to: entry.name });
    }

    const reason = rejectionReason(entry);
    if (reason !== null) {
      rejected.push({ code: entry.code, name: entry.name, reason });
      continue;
    }
    entries.push(entry);
  }

  return {
    catalogue: { version, entries },
    rejected,
    shortened,
    portionsAttached: matched.size,
    portionsUnmatched: [...portions.keys()].filter((code) => !matched.has(code)).sort(),
  };
}

/** Stable output: sorted by `alim_code`, two-space indent, trailing newline. */
export function serialiseCatalogue(catalogue: CiqualCatalogueJson): string {
  return `${JSON.stringify(catalogue, null, 2)}\n`;
}
