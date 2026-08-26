// Reading a Ciqual English name as "the same food, in a state".
//
// `alim_nom_eng` is a comma-separated descriptor list whose trailing segments describe
// the preparation and whose leading segments describe the food:
//
//   Rice, white, raw                                  -> Rice, white              | raw
//   Rice, white, cooked, no added salt                -> Rice, white              | cooked
//   Chicken, breast, without skin, grilled/pan-fried  -> Chicken, breast, ...     | cooked
//   Lentil, pink or red, dried                        -> Lentil, pink or red      | reference
//
// Stripping trailing segments that belong to a closed preparation vocabulary — and only
// those — is what makes the two states of one food meet on the same stem. `parboiled`
// is deliberately absent from that vocabulary: it names a product, not a preparation,
// and admitting it would collapse `Rice, white, parboiled, raw` onto plain white rice.

export type PreparationState =
  /** The state a cooked entry is measured against: raw, dried, dry. */
  | "reference"
  /** Heated in a way that moves water and nothing else — PRD_FOOD 8.6's case. */
  | "cooked"
  /**
   * Heated or preserved in a way that adds or removes matter. PRD_FOOD 8.6 is explicit
   * that a ratio cannot model these, so they are never paired: they become their own
   * entry or none.
   */
  | "transformed"
  /** No preparation segment at all. */
  | "none";

const REFERENCE = new Set(["raw", "dried", "dry", "fresh", "uncooked"]);

/** Ordered: the earlier a preparation appears, the better a reference partner it makes. */
const COOKED = [
  "cooked, no added salt",
  "boiled/cooked in water",
  "cooked in water",
  "boiled",
  "steamed",
  "cooked",
  "poached",
  "grilled/pan-fried",
  "roasted/baked",
  "baked",
  "grilled",
  "braised",
  "stewed",
  "microwaved",
];

const TRANSFORMED = [
  // Salting is matter added, which PRD_FOOD 8.6 says a ratio cannot model. It is listed
  // here rather than among the modifiers because Ciqual pairs `Cod, raw` with `Cod,
  // salted, boiled/cooked in water`, and treating "salted" as a qualification derives a
  // 0.62 ratio between a fish and a preserved fish.
  "salted",
  "half-salted",
  "with added salt",
  "brined",
  "in brine",
  "fried",
  "pre-fried",
  "deep-fried",
  "pan-fried with fat",
  "sautéed/pan-fried with fat",
  "breaded",
  "canned",
  "canned in syrup",
  "canned in light syrup",
  "canned in brine",
  "in syrup",
  "in light syrup",
  "in oil",
  "in olive oil",
  "in sunflower oil",
  "in oil and spices",
  "candied",
  "confit",
  "smoked",
  "au gratin",
  "dehydrated",
  "dried or dehydrated",
  "dehydrated and reconstituted",
  "freeze-dried",
  "reconstituted",
];

/** Segments that qualify a preparation without being one. */
const MODIFIERS = new Set([
  "no added salt",
  "unsalted",
  "drained",
  "undrained",
  "without added fat",
  "with added fat",
  "average",
]);

const COOKED_SET = new Set(COOKED);
const TRANSFORMED_SET = new Set(TRANSFORMED);

function isPreparationSegment(segment: string): boolean {
  return (
    REFERENCE.has(segment) ||
    COOKED_SET.has(segment) ||
    TRANSFORMED_SET.has(segment) ||
    MODIFIERS.has(segment)
  );
}

export interface ParsedName {
  /** The food, with its preparation removed: the key two states are paired on. */
  readonly stem: string;
  readonly state: PreparationState;
  /** The preparation segment that decided the state, for the report. */
  readonly preparation: string | null;
  /** Lower is a better reference partner; `Infinity` when not cooked. */
  readonly cookedRank: number;
}

export function parseName(nameEng: string): ParsedName {
  const segments = nameEng.split(",").map((segment) => segment.trim());
  const lower = segments.map((segment) => segment.toLowerCase());

  let cut = segments.length;
  while (cut > 1 && isPreparationSegment(lower[cut - 1] as string)) cut -= 1;

  const stem = segments.slice(0, cut).join(", ");
  const tail = lower.slice(cut);

  const transformed = tail.find((segment) => TRANSFORMED_SET.has(segment));
  if (transformed !== undefined) {
    return { stem, state: "transformed", preparation: transformed, cookedRank: Infinity };
  }

  const cooked = tail.find((segment) => COOKED_SET.has(segment));
  if (cooked !== undefined) {
    return { stem, state: "cooked", preparation: cooked, cookedRank: COOKED.indexOf(cooked) };
  }

  const reference = tail.find((segment) => REFERENCE.has(segment));
  if (reference !== undefined) {
    return { stem, state: "reference", preparation: reference, cookedRank: Infinity };
  }

  return { stem, state: "none", preparation: null, cookedRank: Infinity };
}
