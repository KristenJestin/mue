import { z } from "zod";
import { localTimeSchema } from "./activity";
import {
  FOOD_NAME_MAX_LENGTH,
  FOOD_NAME_MIN_LENGTH,
  MACRO_PER_100_MAX_MILLIGRAMS,
  UNCONSTRAINED_TEXT_MAX_LENGTH,
} from "./food";
import { INGREDIENT_QUANTITY_MAX_THOUSANDTHS, INGREDIENT_QUANTITY_MIN_THOUSANDTHS } from "./recipe";
import {
  SERVINGS_MAX_THOUSANDTHS,
  SERVINGS_MIN_THOUSANDTHS,
  SERVINGS_STEP_THOUSANDTHS,
  mealPlanAggregateIdSchema,
  mealSlotSchema,
} from "./meal-plan";
import { localDateSchema } from "./primitives";

/**
 * The `FoodLogEntry` aggregate of PRD_FOOD 21.2: *"la ligne seule, autoportante puisqu'elle
 * contient son instantané"*.
 *
 * Section 10.2 says the same thing from the protocol's side: *"Une ligne de journal est
 * autoportante : elle contient l'instantané nutritionnel de ce qui a été mangé et ne dépend donc
 * pas de la réception préalable de son aliment ou de sa recette."* So the nutrients here are the
 * line's own numbers, computed once at the moment it was written, and neither the food nor the
 * recipe named in `sourceRef` has to exist for the line to be applied. `sourceRef` is a plain
 * string and not a `z.uuid()` for exactly that reason — it is a trace of provenance, not a
 * reference the client resolves before it can render the row.
 *
 * PRD_FOOD 21.3: *"les lignes sont indépendantes. Deux lignes créées séparément coexistent, elles
 * ne fusionnent jamais."* Which is section 13.3's first rule, and it is why the identifier is a
 * minted UUID and not a business key: two lines really can describe the same food at the same
 * minute, and the protocol must not confuse them for one.
 */

export const FOOD_LOG_ENTRY_PAYLOAD_VERSION_1 = 1;

/** `FoodLogKind`. */
export const FOOD_LOG_KINDS = ["food", "recipe", "quick"] as const;

/** `QuantityUnit`. */
export const QUANTITY_UNITS = ["gram", "millilitre", "serving"] as const;

/** `Estimation`. */
export const ESTIMATIONS = ["measured", "approximate"] as const;

/** `Energy.QUICK_ADD_MAX_MILLI_KCAL`: a whole line may hold up to 5000 kcal. */
export const LINE_MAX_MILLI_KCAL = 5_000_000;

/**
 * `Servings.USUAL_RANGE` and `USUAL_STEP_THOUSANDTHS`: 0.5 to 20, in halves.
 *
 * This is a *different* scale from [servingsThousandthsSchema], and the two are not
 * interchangeable. `FoodLogEntry.portions` is the number of the food's own usual servings the
 * line represents — `FoodValidation.USUAL_PORTIONS_ERROR` states 0.5 to 20 in steps of 0.5 —
 * while a consumed serving count is 0.25 to 10 in quarters. Reading one as the other would accept
 * `750` where the domain refuses it.
 */
export const USUAL_PORTIONS_MIN_THOUSANDTHS = 500;
export const USUAL_PORTIONS_MAX_THOUSANDTHS = 20_000;
export const USUAL_PORTIONS_STEP_THOUSANDTHS = 500;

export const foodLogKindSchema = z.enum(FOOD_LOG_KINDS).meta({
  id: "FoodLogKind",
  description: "Whether the line came from a food, a recipe or a quick add (PRD_FOOD 10.2).",
});

export const quantityUnitSchema = z.enum(QUANTITY_UNITS).meta({
  id: "QuantityUnit",
  description:
    "The unit `quantityThousandths` is stated in. `serving` selects the serving scale; a weight or a volume selects the ingredient scale.",
});

export const estimationSchema = z.enum(ESTIMATIONS).meta({
  id: "Estimation",
  description:
    "Whether the line's numbers were weighed or estimated. PRD_FOOD 13.2 makes an approximation visible rather than hidden.",
});

export const usualPortionsThousandthsSchema = z
  .int()
  .min(USUAL_PORTIONS_MIN_THOUSANDTHS)
  .max(USUAL_PORTIONS_MAX_THOUSANDTHS)
  .multipleOf(USUAL_PORTIONS_STEP_THOUSANDTHS)
  .meta({
    id: "UsualPortionsThousandths",
    description:
      "A count of a food's usual servings, in thousandths: 0.5 to 20 in steps of 0.5 (PRD_FOOD 15).",
    examples: [1_500],
  });

const lineEnergySchema = z.int().min(0).max(LINE_MAX_MILLI_KCAL);
const lineMacroSchema = z
  .int()
  .min(0)
  .max(MACRO_PER_100_MAX_MILLIGRAMS * 100);

/**
 * One consumption, with its own nutritional snapshot.
 *
 * `quantityThousandths` carries two different quantities under one name, and `quantityUnit` is
 * what says which — that is the shape `FoodPayloads.kt` already journals, taking
 * `measuredQuantity?.thousandths ?: consumedServings?.thousandths`. The refinements below are
 * what make that pair honest rather than merely permissive: a value read on the wrong scale is
 * the kind of wrong that no shape check can see, because both scales are integers in overlapping
 * ranges.
 */
export const foodLogEntryPayloadV1Schema = z
  .object({
    id: z.uuid(),
    consumedOn: localDateSchema,
    /** `LocalTime.toString()`, which is whole minutes: every writer validates hours and minutes. */
    consumedAt: localTimeSchema,
    slot: mealSlotSchema,
    kind: foodLogKindSchema,
    title: z.string().min(FOOD_NAME_MIN_LENGTH).max(FOOD_NAME_MAX_LENGTH),
    estimation: estimationSchema,
    /** Whether the weight was taken on the cooked food rather than the reference state. */
    weighedCooked: z.boolean(),
    energyMilliKcal: lineEnergySchema.optional(),
    proteinMilligrams: lineMacroSchema.optional(),
    carbsMilligrams: lineMacroSchema.optional(),
    fatMilligrams: lineMacroSchema.optional(),
    fibreMilligrams: lineMacroSchema.optional(),
    /**
     * The food or recipe this line was built from, for provenance only.
     *
     * Not a `z.uuid()`, and not resolved before the line is applied: PRD_FOOD 21.2 makes the line
     * self-contained, so a client that has never received the food still renders it in full.
     */
    sourceRef: z.string().min(1).max(UNCONSTRAINED_TEXT_MAX_LENGTH).optional(),
    amountLabel: z.string().min(1).max(UNCONSTRAINED_TEXT_MAX_LENGTH).optional(),
    quantityThousandths: z.int().min(1).max(INGREDIENT_QUANTITY_MAX_THOUSANDTHS).optional(),
    quantityUnit: quantityUnitSchema.optional(),
    portionsThousandths: usualPortionsThousandthsSchema.optional(),
    /**
     * The proposal this line was logged from, if any.
     *
     * It is the meal plan's own aggregate identifier, so it is validated by the meal plan's own
     * schema rather than by a second copy of the pattern — which is what makes the separator
     * change a single edit instead of two that could disagree.
     */
    fromPlan: mealPlanAggregateIdSchema.optional(),
  })
  // A quantity and its unit are one fact in two fields. Half of it says nothing: a bare number
  // has no scale to be read on, and a bare unit measures nothing.
  .refine(
    (entry) => (entry.quantityThousandths === undefined) === (entry.quantityUnit === undefined),
    {
      error: "a quantity and its unit are stated together, or neither is",
      path: ["quantityUnit"],
    },
  )
  // The scale the unit selects. `LoggedAmount.Portioned` carries a `Servings`, whose range and
  // quarter step are `Servings.CONSUMED_RANGE`; `LoggedAmount.Measured` carries a `Quantity`,
  // whose range is `Quantity.INGREDIENT_*`. One field, two domains, and the unit is the
  // discriminator — so this is where a value on the wrong scale is caught.
  .refine(
    (entry) =>
      entry.quantityUnit !== "serving" ||
      (entry.quantityThousandths !== undefined &&
        entry.quantityThousandths >= SERVINGS_MIN_THOUSANDTHS &&
        entry.quantityThousandths <= SERVINGS_MAX_THOUSANDTHS &&
        entry.quantityThousandths % SERVINGS_STEP_THOUSANDTHS === 0),
    {
      error: "a serving count is 0.25 to 10 in steps of 0.25, stated in thousandths (PRD_FOOD 15)",
      path: ["quantityThousandths"],
    },
  )
  .refine(
    (entry) =>
      entry.quantityUnit === "serving" ||
      entry.quantityUnit === undefined ||
      (entry.quantityThousandths !== undefined &&
        entry.quantityThousandths >= INGREDIENT_QUANTITY_MIN_THOUSANDTHS &&
        entry.quantityThousandths <= INGREDIENT_QUANTITY_MAX_THOUSANDTHS),
    {
      error: "a weight or a volume is above 0 and at most 5000 g or ml, stated in thousandths",
      path: ["quantityThousandths"],
    },
  )
  // A line logged from a proposal was logged at that proposal's moment: `FoodDayViewModel` builds
  // it from the plan itself. A `fromPlan` naming another slot would be a trace of provenance that
  // contradicts the line it is attached to.
  .refine((entry) => entry.fromPlan === undefined || entry.fromPlan.endsWith(`:${entry.slot}`), {
    error: "a line logged from a proposal carries that proposal's own slot",
    path: ["fromPlan"],
  })
  .meta({
    id: "FoodLogEntryPayloadV1",
    description:
      "One consumption with its own nutritional snapshot, payload schema version 1. Self-contained: it does not depend on its food or its recipe having arrived (PRD section 10.2).",
  });

export type FoodLogEntryPayloadV1 = z.infer<typeof foodLogEntryPayloadV1Schema>;
