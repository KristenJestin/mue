import { z } from "zod";

/**
 * The `Food` aggregate of PRD_FOOD 21.2: *"l'aliment seul"*.
 *
 * ## Absent, not null
 *
 * Every nutrient and every optional descriptor is `.optional()` here rather than `.nullable()`,
 * which is the opposite of what `HealthProfilePayloadV1` does — and the difference is the merge
 * rule, not taste. Section 13.4 merges the profile *field by field*, so it has to tell "the user
 * cleared this" from "this client said nothing", and only a required-and-nullable field can. A
 * custom food is replaced whole by the last accepted mutation (PRD_FOOD 21.3), so there is no
 * field-level merge and therefore no second meaning for an absent key to carry.
 *
 * There is also a stronger reason, and it is the one that decides it: the phone has **already
 * journalled these payloads**. `FoodPayloads.kt` declares every unknown value as an absent field
 * — deliberately, so a missing protein is never stored as a `0` the server would hand back as
 * fact (PRD_FOOD 13.1) — and `SyncJson` does not encode defaults. Rows written in that shape are
 * sitting in outboxes right now. A contract that required a `null` where those rows have nothing
 * would refuse every one of them, which is the exact failure this work exists to end.
 *
 * ## Why `ciqual` is not a source
 *
 * PRD_FOOD 21.1 marks the embedded Ciqual catalogue `Synchronisé: Non` — it is a versioned
 * reference every phone already holds, not personal data. `RoomFoodCatalogueRepository.save`
 * refuses a read-only source and `FoodDao.replaceCiqual` journals nothing, so no such row exists
 * to send. Leaving the id out of the enum makes that a property of the wire rather than of two
 * methods remembering: there is no payload here that describes a Ciqual entry, so no client and
 * no agent can push one, and PRD_FOOD 21.4's one reserved limit — *"aucun client MCP ne peut
 * modifier ni supprimer une entrée du catalogue Ciqual"* — needs no check of its own.
 */

export const FOOD_PAYLOAD_VERSION_1 = 1;

/** `FoodSource`, minus the one id PRD_FOOD 21.1 does not synchronise. */
export const SYNCHRONISED_FOOD_SOURCES = ["custom", "open_food_facts"] as const;

/** `ReferenceUnit`. */
export const REFERENCE_UNITS = ["gram", "millilitre"] as const;

/** `Food.MIN_NAME_LENGTH` and `MAX_NAME_LENGTH`. */
export const FOOD_NAME_MIN_LENGTH = 1;
export const FOOD_NAME_MAX_LENGTH = 80;

/** `Food.MAX_BRAND_LENGTH`. */
export const FOOD_BRAND_MAX_LENGTH = 80;

/** `Food.BARCODE_LENGTH_RANGE`, digits only, as `FoodValidation.validateBarcode` checks. */
export const BARCODE_MIN_LENGTH = 8;
export const BARCODE_MAX_LENGTH = 14;

/** `Energy.PER_100_MAX_MILLI_KCAL`: 0 to 900 kcal per 100 g or ml. */
export const ENERGY_PER_100_MAX_MILLI_KCAL = 900_000;

/** `Macro.PER_100_MAX_MILLIGRAMS`: 0 to 100 g per 100 g or ml. */
export const MACRO_PER_100_MAX_MILLIGRAMS = 100_000;

/** `Quantity.USUAL_SERVING_MIN_THOUSANDTHS` and `USUAL_SERVING_MAX_THOUSANDTHS`: 1 to 2000. */
export const USUAL_SERVING_MIN_THOUSANDTHS = 1_000;
export const USUAL_SERVING_MAX_THOUSANDTHS = 2_000_000;

/** `CookedRatio.MIN_THOUSANDTHS` and `MAX_THOUSANDTHS`: a ratio of 0.3 to 5. */
export const COOKED_RATIO_MIN_THOUSANDTHS = 300;
export const COOKED_RATIO_MAX_THOUSANDTHS = 5_000;

/**
 * The bound for a free-form string the domain gives no constant of its own.
 *
 * `rawLabel`, `cookedLabel`, `sourceId`, `sourceVersion`, `servingLabel` and `imageRef` are all
 * stored without a length rule, so any bound here is invented — and an invented bound refuses a
 * stored row. It is set far above anything the forms can produce rather than at a guess of what
 * they *should*: the purpose is to stop an unbounded string reaching a `text` column and a JSON
 * document, not to re-adjudicate a value Android already accepted.
 */
export const UNCONSTRAINED_TEXT_MAX_LENGTH = 200;

export const foodSourceSchema = z.enum(SYNCHRONISED_FOOD_SOURCES).meta({
  id: "FoodSource",
  description:
    "Where a synchronised food came from. The embedded Ciqual catalogue is deliberately not a member: PRD_FOOD 21.1 marks it as reference data rather than personal data.",
});

export const referenceUnitSchema = z.enum(REFERENCE_UNITS).meta({
  id: "ReferenceUnit",
  description: "The unit the per-100 values are stated in (PRD_FOOD 8.6).",
});

/** A free-form label the domain bounds nowhere. See [UNCONSTRAINED_TEXT_MAX_LENGTH]. */
const unconstrainedText = z.string().min(1).max(UNCONSTRAINED_TEXT_MAX_LENGTH);

const energyPer100Schema = z.int().min(0).max(ENERGY_PER_100_MAX_MILLI_KCAL);
const macroPer100Schema = z.int().min(0).max(MACRO_PER_100_MAX_MILLIGRAMS);

/**
 * A food, per 100 g or 100 ml of its reference unit.
 *
 * Every number is a whole count of its canonical unit — thousandths of a kilocalorie, milligrams,
 * thousandths of a gram — exactly as Room stores them and for the same reason `weightCg` is an
 * integer: no float reaches the database, so nothing can be rounded twice.
 *
 * `id` repeats the aggregate identifier, as `MeasurementPayloadV1.date` does, because section
 * 12.2 makes an upsert state the complete aggregate and a payload replayed from the journal on
 * its own has to say which food it is.
 */
export const foodPayloadV1Schema = z
  .object({
    id: z.uuid(),
    name: z.string().min(FOOD_NAME_MIN_LENGTH).max(FOOD_NAME_MAX_LENGTH),
    source: foodSourceSchema,
    referenceUnit: referenceUnitSchema,
    /** The reference state's label. Defaulted to `Raw` by the domain, never empty. */
    rawLabel: unconstrainedText,
    cookedLabel: unconstrainedText,
    energyMilliKcal: energyPer100Schema.optional(),
    proteinMilligrams: macroPer100Schema.optional(),
    carbsMilligrams: macroPer100Schema.optional(),
    fatMilligrams: macroPer100Schema.optional(),
    fibreMilligrams: macroPer100Schema.optional(),
    brand: z.string().min(1).max(FOOD_BRAND_MAX_LENGTH).optional(),
    barcode: z
      .string()
      .min(BARCODE_MIN_LENGTH)
      .max(BARCODE_MAX_LENGTH)
      .regex(/^\d+$/, "expected a barcode of digits only")
      .optional(),
    sourceId: unconstrainedText.optional(),
    sourceVersion: unconstrainedText.optional(),
    servingLabel: unconstrainedText.optional(),
    servingThousandths: z
      .int()
      .min(USUAL_SERVING_MIN_THOUSANDTHS)
      .max(USUAL_SERVING_MAX_THOUSANDTHS)
      .optional(),
    cookedRatioThousandths: z
      .int()
      .min(COOKED_RATIO_MIN_THOUSANDTHS)
      .max(COOKED_RATIO_MAX_THOUSANDTHS)
      .optional(),
    imageRef: unconstrainedText.optional(),
  })
  /*
   * Two rules that are deliberately **not** here, and the test that decides which rules belong.
   *
   * `FoodValidation` states both: `MACRO_SUM_ERROR` — protein, carbs and fat cannot exceed 100 g
   * per 100 — and `USUAL_SERVING_PAIR_ERROR` — a usual serving needs both a label and a weight.
   * Both are rules of the *editor*, and the editor is not the only writer. `OpenFoodFactsMapper`
   * builds a `Nutrients` straight from the fetched product, field by field through
   * `ofPer100OrNull`, with no sum check anywhere on the path; PRD_FOOD 9.2 makes that copy a
   * synchronised aggregate. So a product whose own label does not add up is already stored on the
   * phone, and a wire that refused it would strand that row for ever with no screen able to
   * correct it.
   *
   * The bounds that *are* here all answer "can this have been stored?" — they are the ranges the
   * value classes themselves enforce, so nothing can reach Room outside them. That is the line
   * this contract draws, and it is the same one `activity.ts` draws for a session duration.
   */
  .meta({
    id: "FoodPayloadV1",
    description:
      "A custom food or a copied Open Food Facts product, payload schema version 1. Unknown nutrients are absent keys, never zero (PRD_FOOD 13.1).",
  });

export type FoodPayloadV1 = z.infer<typeof foodPayloadV1Schema>;
