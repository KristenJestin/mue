// PRD_FOOD 9.5, applied: "le catalogue se decoupe la ou les chiffres changent, pas la
// ou le vocabulaire change".
//
// Ciqual 2025 has 3 484 foods. Shipping them all would break the three things 9.5
// names: it would claim a precision the module contradicts with a leading `~`, it
// would make search unusable, and - the reason that actually bites - it would make an
// MCP client's choice arbitrary, because eight yoghurts that differ by "fortified with
// vitamin D" are eight equally good answers to "yoghurt" and two runs would pick two.
//
// The reduction is therefore not a hand-picked list. It is three mechanical passes
// over the whole table, plus a small reviewable file of decisions a rule cannot make:
//
//   1. an allowlist of sub-groups, because "baby milk" and "cooking aids" are not
//      foods this module logs;
//   2. exclusion patterns for the three separations 9.5 calls unjustified - brands,
//      varieties, labels and provenance;
//   3. clustering inside each sub-sub-group at 9.5's own 15 %: an entry whose five
//      kept constituents all land within 15 % of one already kept adds a row to the
//      search results and nothing to the numbers.
//
// Every pass is counted in the build report, so a size change is a diff to read rather
// than a surprise to discover.

import rulesFile from "../subset.rules.json" with { type: "json" };
import overridesFile from "../subset.overrides.json" with { type: "json" };

import { asNumber, SHIPPED_CONSTITUENTS, type CiqualFood, type ShippedConstituent } from "./table";
import type { Pair } from "./pairing";
import { parseName } from "./preparation";

export interface SubsetRules {
  readonly targetSize: { readonly min: number; readonly max: number };
  readonly subGroupAllowlist: readonly string[];
  /** Sub-sub-groups removed inside an otherwise allowed sub-group. */
  readonly subSubGroupDenylist: readonly string[];
  /**
   * The unit PRD_FOOD 9.5's 15 % is applied inside, per sub-group. Absent means
   * `headNoun`, which is the right answer for most of the table.
   */
  readonly clusterBy: Readonly<Record<string, ClusterUnit>>;
  readonly millilitreSubGroups: readonly string[];
  readonly excludePatterns: readonly {
    readonly pattern: string;
    readonly reason: string;
    /**
     * Patterns are case-insensitive by default, which is what a word list wants.
     * A pattern that reads capitalisation as meaning - `\([A-Z]` for a brand in
     * parentheses - must say so, or it also matches Ciqual's own `(average)` rows and
     * deletes exactly the generic entries this subset is trying to keep.
     */
    readonly caseSensitive?: boolean;
  }[];
  readonly requireEnergy: boolean;
  readonly clustering: {
    readonly relativeTolerance: number;
    readonly floor: Readonly<Record<ShippedConstituent, number>>;
    /**
     * The most rows one cluster may keep, however far apart they are.
     *
     * PRD_FOOD 9.5 says two rows need to differ by more than 15 %; it does not say that
     * nine rows are fine because each differs from the last by 16 %. Ciqual publishes
     * eight rows of garden peas - raw, cooked, canned, frozen raw, frozen cooked,
     * boiled, puree, pre-cooked - and a greedy 15 % walk keeps most of them, which is
     * precisely the arbitrary choice 9.5's third reason wants gone: asked for "peas",
     * an MCP client picks one of eight and two runs disagree.
     */
    readonly maxPerCluster: number;
  };
}

export interface SubsetOverrides {
  readonly keep: readonly { readonly code: string; readonly note: string }[];
  readonly drop: readonly { readonly code: string; readonly note: string }[];
}

export const subsetRules = rulesFile as unknown as SubsetRules;
export const subsetOverrides = overridesFile as unknown as SubsetOverrides;

export type DropReason =
  | "subGroupNotAllowed"
  | "subSubGroupDenied"
  | "excludedPattern"
  | "noEnergy"
  | "mergedIntoReference"
  | "clustered"
  | "clusterFull"
  | "override";

export interface DroppedFood {
  readonly code: string;
  readonly nameEng: string;
  readonly reason: DropReason;
  /** The pattern, the representative or the note that decided it. */
  readonly detail: string;
}

export interface SelectedFood {
  readonly food: CiqualFood;
  /** Present when a raw/cooked pair collapsed into this row (PRD_FOOD 8.6). */
  readonly cookedRatioThousandths: number | null;
  readonly cookedFrom: string | null;
  readonly unit: "gram" | "millilitre";
}

export interface SubsetResult {
  readonly selected: readonly SelectedFood[];
  readonly dropped: readonly DroppedFood[];
  readonly droppedByReason: Readonly<Record<DropReason, number>>;
}

/**
 * The one numeric comparison the module makes, shared by clustering and by PRD_FOOD
 * 8.6's one-entry-or-two test so that "the same" means the same thing in both.
 *
 * An unknown on either side agrees: it is not evidence of a difference, and a row that
 * documents nothing is exactly the row that should not earn its own place in a search
 * result. The floor is what stops a relative test from firing on `0,79` against `0,90`,
 * where the gap is under what the source resolves.
 */
export function withinTolerance(
  a: number | null,
  b: number | null,
  relative: number,
  floor: number,
): boolean {
  if (a === null || b === null) return true;
  const allowed = Math.max(relative * Math.max(Math.abs(a), Math.abs(b)), floor);
  return Math.abs(a - b) <= allowed;
}

function isNutritionallyEqual(a: CiqualFood, b: CiqualFood, rules: SubsetRules): boolean {
  return SHIPPED_CONSTITUENTS.every((constituent) =>
    withinTolerance(
      asNumber(a, constituent),
      asNumber(b, constituent),
      rules.clustering.relativeTolerance,
      rules.clustering.floor[constituent],
    ),
  );
}

/**
 * How generic an entry is, lower being more generic and therefore the better
 * representative of its cluster.
 *
 * Ciqual publishes explicit `(average)` rows precisely as generic stand-ins, so they
 * win outright. After that, fewer comma-separated descriptors means fewer
 * qualifications, which is the same statement PRD_FOOD 9.5 makes about vocabulary.
 * The `alim_code` tie-break is what makes the choice reproducible rather than
 * dependent on iteration order.
 */
export function genericRank(food: CiqualFood): [number, number, number, string] {
  const lower = food.nameEng.toLowerCase();
  return [
    lower.includes("(average)") ? 0 : 1,
    food.nameEng.split(",").length,
    food.nameEng.length,
    food.code,
  ];
}

/**
 * The set inside which PRD_FOOD 9.5's 15 % is allowed to merge two rows: one sub-group,
 * one head noun.
 *
 * The sub-group alone is the wrong unit, and Ciqual shows why. Every vegetable oil in
 * sub-group 0902 is 900 kcal and 100 g of fat per 100 g, so a sub-group-wide 15 %
 * collapses thirty-three oils into one and a search for "olive oil" returns nothing.
 * That is not what 9.5 asks for: the two separations it calls unjustified are *the same
 * food* qualified by a brand and *the same food* qualified by a variety. Olive oil and
 * sunflower oil are two foods that happen to share their numbers.
 *
 * `alim_nom_eng` puts the food first and its qualifications after the first comma, so
 * the head noun is a usable stand-in for "the same food": it keeps `Apple, raw` with
 * `Apple, Golden variety, raw`, and it keeps `Chicken, breast` with `Chicken, thigh` -
 * where 9.5's own example then keeps both rows, because their fat differs by far more
 * than 15 %.
 */
export type ClusterUnit = "headNoun" | "subSubGroup" | "subGroup";

/**
 * The key the per-food cap counts against: the food with its preparation and its
 * preservation removed.
 *
 * The head noun is the wrong key here even though it is the right one for clustering.
 * `Rice` covers white, wholegrain, basmati, red and wild, and PRD_FOOD 9.5 names
 * exactly that split as justified - "riz blanc et riz complet n'ont pas les memes
 * fibres". A cap counting head nouns keeps two of them and throws white rice out of the
 * catalogue. The preparation stem keeps `Rice, white` apart from `Rice, wholegrain`
 * while still gathering the raw, cooked, canned and frozen rows of each.
 */
export function capKey(food: CiqualFood): string {
  const stem = parseName(food.nameEng)
    .stem.toLowerCase()
    .split(",")
    .map((segment) => segment.trim())
    // Preservation is not a different food; PRD_FOOD 9.5 splits on figures, and where
    // freezing or canning does move them the clustering pass has already kept both.
    .filter((segment) => !PRESERVATION.has(segment))
    .join(", ")
    .replace(/\byogh?[ou]rt\b/g, "yogurt");
  return `${food.groupCode}/${food.subGroupCode}/${stem}`;
}

const PRESERVATION = new Set([
  "frozen",
  "canned",
  "in brine",
  "pre-cooked",
  "vacuum-packed",
  "sterilised",
  "pasteurised",
  "puree",
  "pureed",
]);

/** `Apple, Golden, raw` and `Apple, cooked` share a head noun; `Olive oil` does not. */
export function headNounKey(food: CiqualFood): string {
  const head = (food.nameEng.split(",")[0] ?? food.nameEng)
    .toLowerCase()
    .trim()
    // Ciqual spells the same product both ways, sometimes in adjacent rows.
    .replace(/\byogh?[ou]rt\b/g, "yogurt")
    .replace(/[^a-z0-9 ]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return `${food.groupCode}/${food.subGroupCode}/${head}`;
}

export function clusterKey(
  food: CiqualFood,
  clusterBy: Readonly<Record<string, ClusterUnit>> = {},
): string {
  const unit = clusterBy[food.subGroupCode] ?? "headNoun";

  // Some sub-groups are species lists, not qualifications of one food: `fish, raw`
  // holds a hundred and eight species whose head nouns all differ and whose numbers do
  // not. Bogue, megrim and pond smelt are three ways of writing "white fish, 80 kcal",
  // and 9.5 splits the catalogue where the figures change.
  if (unit === "subGroup") return `${food.groupCode}/${food.subGroupCode}`;

  // And some sit in between: Ciqual's own sub-sub-groups already say "beef and veal",
  // "hard or pressed cheeses". Inside one of those, 9.5's own worked example applies -
  // a chicken breast and a thigh with skin stay apart because their fat differs by far
  // more than 15 %, while two cuts that do not differ stop being two rows.
  if (unit === "subSubGroup") {
    return `${food.groupCode}/${food.subGroupCode}/${food.subSubGroupCode}`;
  }

  return headNounKey(food);
}

function compareRank(a: CiqualFood, b: CiqualFood): number {
  const [a0, a1, a2, a3] = genericRank(a);
  const [b0, b1, b2, b3] = genericRank(b);
  if (a0 !== b0) return a0 - b0;
  if (a1 !== b1) return a1 - b1;
  if (a2 !== b2) return a2 - b2;
  return a3 < b3 ? -1 : a3 > b3 ? 1 : 0;
}

export function selectSubset(
  foods: Iterable<CiqualFood>,
  pairs: readonly Pair[],
  rules: SubsetRules = subsetRules,
  overrides: SubsetOverrides = subsetOverrides,
): SubsetResult {
  const dropped: DroppedFood[] = [];
  const forcedKeep = new Map(overrides.keep.map((entry) => [entry.code, entry.note]));
  const forcedDrop = new Map(overrides.drop.map((entry) => [entry.code, entry.note]));
  const allowed = new Set(rules.subGroupAllowlist);
  const denied = new Set(rules.subSubGroupDenylist);
  const millilitre = new Set(rules.millilitreSubGroups);
  const patterns = rules.excludePatterns.map((entry) => ({
    regex: new RegExp(entry.pattern, entry.caseSensitive === true ? "" : "i"),
    source: entry.pattern,
    reason: entry.reason,
  }));

  // PRD_FOOD 8.6: a pair whose composition did not move is one row, and the cooked
  // half of it must not also appear on its own.
  const ratioByReference = new Map<string, Pair>();
  const mergedCooked = new Map<string, Pair>();
  for (const pair of pairs) {
    if (pair.verdict !== "oneEntry") continue;
    ratioByReference.set(pair.reference.code, pair);
    mergedCooked.set(pair.cooked.code, pair);
  }

  const candidates: CiqualFood[] = [];
  for (const food of foods) {
    const forced = forcedKeep.has(food.code);

    const dropNote = forcedDrop.get(food.code);
    if (dropNote !== undefined) {
      dropped.push({
        code: food.code,
        nameEng: food.nameEng,
        reason: "override",
        detail: dropNote,
      });
      continue;
    }

    const merged = mergedCooked.get(food.code);
    if (merged !== undefined) {
      dropped.push({
        code: food.code,
        nameEng: food.nameEng,
        reason: "mergedIntoReference",
        detail: `${merged.reference.code} ${merged.reference.nameEng} (ratio ${merged.ratioThousandths})`,
      });
      continue;
    }

    if (!forced && denied.has(food.subSubGroupCode)) {
      dropped.push({
        code: food.code,
        nameEng: food.nameEng,
        reason: "subSubGroupDenied",
        detail: food.subSubGroupCode,
      });
      continue;
    }

    if (!forced && !allowed.has(food.subGroupCode)) {
      dropped.push({
        code: food.code,
        nameEng: food.nameEng,
        reason: "subGroupNotAllowed",
        detail: food.subGroupCode,
      });
      continue;
    }

    const matched = forced ? undefined : patterns.find((entry) => entry.regex.test(food.nameEng));
    if (matched !== undefined) {
      dropped.push({
        code: food.code,
        nameEng: food.nameEng,
        reason: "excludedPattern",
        detail: `${matched.source} (${matched.reason})`,
      });
      continue;
    }

    if (!forced && rules.requireEnergy && asNumber(food, "energy") === null) {
      // PRD_FOOD 9.3 accepts a food with no values because an Open Food Facts record is
      // often incomplete. A *catalogue* row with no energy is a different thing: it can
      // never contribute to a total, so it is search noise with no upside.
      dropped.push({ code: food.code, nameEng: food.nameEng, reason: "noEnergy", detail: "-" });
      continue;
    }

    candidates.push(food);
  }

  const buckets = new Map<string, CiqualFood[]>();
  for (const food of candidates) {
    const key = clusterKey(food, rules.clusterBy);
    buckets.set(key, [...(buckets.get(key) ?? []), food]);
  }

  const kept: CiqualFood[] = [];
  for (const key of [...buckets.keys()].sort()) {
    const bucket = [...(buckets.get(key) as CiqualFood[])].sort(compareRank);
    const representatives: CiqualFood[] = [];
    for (const food of bucket) {
      if (forcedKeep.has(food.code)) {
        representatives.push(food);
        continue;
      }
      const twin = representatives.find((representative) =>
        isNutritionallyEqual(representative, food, rules),
      );
      if (twin === undefined) {
        representatives.push(food);
        continue;
      }
      dropped.push({
        code: food.code,
        nameEng: food.nameEng,
        reason: "clustered",
        detail: `${twin.code} ${twin.nameEng}`,
      });
    }
    kept.push(...representatives);
  }

  // The second constraint, and a different one from clustering: however far apart their
  // numbers are, one food does not get nine rows. Ciqual publishes garden peas raw,
  // cooked, boiled, canned, frozen-raw, frozen-cooked, pureed, mixed with carrots and
  // pre-cooked-to-be-recooked, and each step of that ladder clears 15 % from the last,
  // so clustering alone keeps most of it. PRD_FOOD 9.5's third reason is exactly this
  // case: asked for "peas", an MCP client picks one of nine arbitrarily and two
  // equivalent recipes stop producing the same numbers.
  //
  // Applied at head-noun granularity even where clustering used a coarser unit, because
  // the cap is about one *food* spreading out, not about how wide the comparison was.
  const capped: CiqualFood[] = [];
  const perHeadNoun = new Map<string, number>();
  for (const food of kept.sort(compareRank)) {
    const key = capKey(food);
    const used = perHeadNoun.get(key) ?? 0;
    if (forcedKeep.has(food.code) || used < rules.clustering.maxPerCluster) {
      perHeadNoun.set(key, used + 1);
      capped.push(food);
      continue;
    }
    dropped.push({
      code: food.code,
      nameEng: food.nameEng,
      reason: "clusterFull",
      detail: `${rules.clustering.maxPerCluster} rows already kept for "${key}"`,
    });
  }

  const selected = capped
    .sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0))
    .map((food) => {
      const pair = ratioByReference.get(food.code);
      return {
        food,
        cookedRatioThousandths: pair?.ratioThousandths ?? null,
        cookedFrom: pair?.cooked.code ?? null,
        unit: millilitre.has(food.subGroupCode) ? ("millilitre" as const) : ("gram" as const),
      };
    });

  const droppedByReason = {
    subGroupNotAllowed: 0,
    subSubGroupDenied: 0,
    excludedPattern: 0,
    noEnergy: 0,
    mergedIntoReference: 0,
    clustered: 0,
    clusterFull: 0,
    override: 0,
  } as Record<DropReason, number>;
  for (const entry of dropped) droppedByReason[entry.reason] += 1;

  return { selected, dropped, droppedByReason };
}
