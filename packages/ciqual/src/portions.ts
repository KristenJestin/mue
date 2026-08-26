// PRD_FOOD 8.2's usual portions, which are a product decision and not data.
//
// Ciqual publishes none, and nothing in a composition table could: "how much of this
// does a person eat at once" is not a property of the food, it is a property of how the
// food is sold and held. So this is a hand-maintained file, and the reason it stays
// short is an asymmetry worth stating.
//
// A wrong calorie figure is visible. Four hundred kilocalories for a tomato looks wrong
// to anybody who reads it, and it gets reported. A wrong portion weight is invisible:
// "1 bowl = 250 g" against a bowl that actually holds 400 g produces a total that looks
// perfectly reasonable and is sixty per cent out, every single time, and nobody ever
// finds out. PRD_FOOD 13.3 already accepts that a real portion is never exactly a
// theoretical one; that is an argument for offering the portion where it genuinely
// helps, not for inventing one everywhere.
//
// So a portion exists only where the food comes in a unit a person handles - an egg, a
// pot, a slice, a fruit - and not for rice, pasta, oil or minced beef, which are either
// weighed or estimated and are helped by neither.

import portionsFile from "../portions.json" with { type: "json" };

import { NAME_LENGTH_RANGE, THOUSANDTHS_PER_UNIT, USUAL_SERVING_RANGE, isInRange } from "./units";

export interface PortionEntry {
  readonly code: string;
  readonly label: string;
  readonly grams: number;
  readonly note?: string;
}

export interface Portion {
  readonly code: string;
  readonly label: string;
  readonly thousandths: number;
}

export const portionEntries = (portionsFile as unknown as { portions: PortionEntry[] }).portions;

export class PortionValidationError extends Error {
  constructor(readonly problems: readonly string[]) {
    super(`portions.json is invalid:\n  ${problems.join("\n  ")}`);
    this.name = "PortionValidationError";
  }
}

/**
 * Reads the file and refuses anything PRD_FOOD 15 refuses.
 *
 * Throwing rather than skipping is the point. A portion silently dropped for being
 * 4 000 g is a portion the author believes shipped, and the food quietly loses the
 * affordance they wrote it for; a build that stops is a line to fix.
 */
export function loadPortions(
  entries: readonly PortionEntry[] = portionEntries,
): Map<string, Portion> {
  const problems: string[] = [];
  const byCode = new Map<string, Portion>();

  for (const entry of entries) {
    const label = entry.label.trim();
    const thousandths = Math.round(entry.grams * THOUSANDTHS_PER_UNIT);

    if (entry.code.trim() === "") problems.push(`empty code on "${label}"`);
    if (label.length < NAME_LENGTH_RANGE.min || label.length > NAME_LENGTH_RANGE.max) {
      problems.push(`${entry.code}: label must be 1..80 characters, got ${label.length}`);
    }
    if (!Number.isFinite(entry.grams)) {
      problems.push(`${entry.code}: grams is not a number`);
    } else if (!isInRange(thousandths, USUAL_SERVING_RANGE)) {
      // PRD_FOOD 15: a usual serving weighs 1 to 2 000 g or ml.
      problems.push(
        `${entry.code} "${label}": ${entry.grams} g is outside PRD_FOOD 15's 1..2000 g`,
      );
    }
    if (byCode.has(entry.code)) {
      problems.push(`${entry.code}: listed twice; a food has at most one usual portion`);
    }
    byCode.set(entry.code, { code: entry.code, label, thousandths });
  }

  if (problems.length > 0) throw new PortionValidationError(problems);
  return byCode;
}
