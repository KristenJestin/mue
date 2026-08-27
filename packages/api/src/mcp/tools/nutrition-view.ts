import { energyLabel, macroLabel, type NutrientMetric, type Nutrients } from "@mue/domain";
import { z } from "zod";

/**
 * How a server-computed nutritional value is put on the wire, and why it cannot be read as
 * zero.
 *
 * ## The problem this shape exists for
 *
 * PRD_FOOD 13.1 makes `null` and `0` different facts: black coffee has a *known* zero
 * energy, an incomplete Open Food Facts card has none at all. Everything in the Food module
 * rests on the two never being confused, and the place they are most likely to be confused
 * is here — an agent reads JSON and then writes a sentence, and *"you had 0 g of protein"*
 * is a falsehood the owner has no way to catch.
 *
 * ## The encoding, and why it is an explicit null rather than an absent key
 *
 * The nutrients on the *wire between phone and server* are absent keys, because a payload's
 * job is to be replayed and `foodPayloadV1Schema` declares them `.optional()`. This is a
 * different audience with a different failure mode, and it is encoded differently on
 * purpose:
 *
 *  - **The key is always present.** An absent key is ambiguous to a reader that does not
 *    know the schema by heart: it reads the same as a field this build does not have, or a
 *    field the tool forgot. A present key with a `null` is a positive statement — *the
 *    server looked, and nobody measured it*.
 *  - **`null` is not `0` under any coercion an agent applies.** The dangerous idiom is
 *    `value ?? 0`, and it is dangerous against an absent key and a null alike; what
 *    distinguishes this shape is that the value does not sit alone. [known] is a boolean
 *    that has to be read as false, [unknownFrom] names the contributions responsible, and
 *    [display] is already the string PRD_FOOD 13.2 asks for. Four fields have to be ignored
 *    at once for a zero to be stated, where one absent key needs only to be defaulted.
 *  - **The MCP client validates it.** `outputSchema` declares the field `nullable()`, so a
 *    tool that ever returned an absent key would fail validation inside the SDK rather than
 *    reaching the agent as a plausible-looking object.
 *  - **It matches the twenty-eight tools already shipped**, which write `energyKcal:
 *    z.number().nullable()` and say *"Null when unknown"*. A second convention in the same
 *    catalogue is a convention nobody follows.
 *
 * ## Why the integer is the value and the decimal is a convenience
 *
 * PRD_FOOD 8.6 stores every figure as a whole count of its canonical unit, and a float that
 * enters a database is a value that can be rounded twice. So [ComputedEnergy.milliKcal] is
 * the number, and `kcal` is beside it for reading, exactly as `weightCg` and `weightKg` are
 * on a measurement. Nothing derived from these is stored.
 *
 * ## The `≈` of PRD_FOOD 13.2
 *
 * [display] carries it, always, for a value that came out of an addition or a division. The
 * section says *"Toute valeur issue d'un calcul ou d'une source externe est précédée de
 * `≈`"* — it is not conditional on how good the inputs were, and a day's total is an
 * addition every time. An agent that quotes `display` is correct by construction; one that
 * formats `kcal` itself at least had the correct rendering in front of it.
 */

const unknownFromSchema = z
  .array(z.string())
  .describe(
    "The contributions that carry no figure for this nutrient, which is *why* the total is unknown: the journal lines of the day, or the ingredients of the recipe. Empty when the total is known. Name them to the person rather than reporting a bare dash -- one of them can be corrected.",
  );

export const computedEnergySchema = z.object({
  known: z
    .boolean()
    .describe(
      "False when at least one contribution has no energy figure. PRD_FOOD 13.1: one unknown makes the total unknown, and unknown is never zero.",
    ),
  milliKcal: z
    .int()
    .nullable()
    .describe(
      "Thousandths of a kilocalorie, the exact integer Mue stores. Null when unknown -- which is not the same as 0, and must never be reported as 0.",
    ),
  kcal: z.number().nullable().describe("`milliKcal` divided by 1000, for reading only."),
  display: z
    .string()
    .describe(
      "The figure as PRD_FOOD 13.2 writes it: `≈ 1850 kcal` for a computed value, `—` when it is unknown. Quote this rather than formatting the number yourself.",
    ),
  unknownFrom: unknownFromSchema,
});

export const computedMacroSchema = z.object({
  known: z
    .boolean()
    .describe(
      "False when at least one contribution has no figure for this macronutrient. Unknown is never zero.",
    ),
  milligrams: z
    .int()
    .nullable()
    .describe(
      "Milligrams, the exact integer Mue stores. Null when unknown -- which is not the same as 0.",
    ),
  grams: z.number().nullable().describe("`milligrams` divided by 1000, for reading only."),
  display: z
    .string()
    .describe("The figure as PRD_FOOD 13.2 writes it: `≈ 10.5 g`, or `—` when unknown."),
  unknownFrom: unknownFromSchema,
});

/**
 * The five metrics of PRD_FOOD 8.2, each with its own knownness.
 *
 * Five separate objects rather than one `known` for the bundle, because PRD_FOOD 13.1's
 * propagation is *metric by metric*: a known energy beside an unknown protein is the normal
 * case, not an edge one, and a shape that could only say "this total is known" would have to
 * choose one of the two to lie about.
 */
export const computedNutrientsSchema = z.object({
  energy: computedEnergySchema,
  protein: computedMacroSchema,
  carbs: computedMacroSchema,
  fat: computedMacroSchema,
  fibre: computedMacroSchema,
});

export type UnknownContributions = Readonly<Record<NutrientMetric, readonly string[]>>;

/** No contribution is missing -- for a bundle whose provenance names none. */
export const NO_UNKNOWN_CONTRIBUTIONS: UnknownContributions = {
  energyMilliKcal: [],
  proteinMilligrams: [],
  carbsMilligrams: [],
  fatMilligrams: [],
  fibreMilligrams: [],
};

function computedEnergy(
  milliKcal: number | null,
  unknownFrom: readonly string[],
): Record<string, unknown> {
  return {
    known: milliKcal !== null,
    milliKcal,
    kcal: milliKcal === null ? null : milliKcal / 1000,
    display: energyLabel(milliKcal),
    unknownFrom: [...unknownFrom],
  };
}

function computedMacro(
  milligrams: number | null,
  unknownFrom: readonly string[],
): Record<string, unknown> {
  return {
    known: milligrams !== null,
    milligrams,
    grams: milligrams === null ? null : milligrams / 1000,
    display: macroLabel(milligrams),
    unknownFrom: [...unknownFrom],
  };
}

/**
 * A computed bundle, rendered.
 *
 * `unknownFrom` is threaded through rather than derived here, because only the caller knows
 * what a contribution *is*: a day's are its journal lines, a recipe's are its ingredients.
 * Passing [NO_UNKNOWN_CONTRIBUTIONS] is legitimate and says "this bundle cannot say which".
 */
export function computedNutrientsView(
  nutrients: Nutrients,
  unknownFrom: UnknownContributions,
): Record<string, unknown> {
  return {
    energy: computedEnergy(nutrients.energyMilliKcal, unknownFrom.energyMilliKcal),
    protein: computedMacro(nutrients.proteinMilligrams, unknownFrom.proteinMilligrams),
    carbs: computedMacro(nutrients.carbsMilligrams, unknownFrom.carbsMilligrams),
    fat: computedMacro(nutrients.fatMilligrams, unknownFrom.fatMilligrams),
    fibre: computedMacro(nutrients.fibreMilligrams, unknownFrom.fibreMilligrams),
  };
}

/** Every metric of a bundle is unknown for the same reason: one list, five times. */
export function sameUnknownContributions(ids: readonly string[]): UnknownContributions {
  return {
    energyMilliKcal: ids,
    proteinMilligrams: ids,
    carbsMilligrams: ids,
    fatMilligrams: ids,
    fibreMilligrams: ids,
  };
}

/**
 * PRD_FOOD 21.5: *"les valeurs calculées par le serveur conservent leur provenance et leur
 * méthode d'obtention"*.
 *
 * Every field here answers one of the two words. **Provenance**: which rows were read, how
 * many, and what was deliberately not read. **Method**: the named rule, where it is written
 * down, and that the result is approximate. Without them a total is a number an agent has to
 * take on trust; with them it is one it can explain, and one a person can check.
 */
export const provenanceShape = {
  computedBy: z
    .literal("server")
    .describe("Who did the arithmetic. Mue stores no total: it is recomputed on every read."),
  method: z
    .literal("strictSum")
    .describe(
      "PRD_FOOD 13.1's `somme stricte`: metric by metric, the sum of the contributions, and null for that metric as soon as one contribution is unknown. No unknown is treated as zero and no unknown term is dropped.",
    ),
  rule: z
    .string()
    .describe("The clause this value was computed under, so it can be looked up and checked."),
  source: z
    .string()
    .describe("Which stored rows were read. Nothing outside them contributed to the result."),
  approximate: z
    .literal(true)
    .describe(
      "PRD_FOOD 13.2: a value that came out of a calculation is approximate and is written with `≈`. Present the figures as approximate; the `display` strings already are.",
    ),
  contributionCount: z
    .int()
    .describe("How many contributions were summed. Zero means nothing was read, not zero eaten."),
};
