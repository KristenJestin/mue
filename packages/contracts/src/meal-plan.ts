import { z } from "zod";
import { localDateSchema } from "./primitives";

/**
 * The `MealPlanEntry` aggregate of PRD_FOOD 21.2: *"la proposition seule"*, at most one per date
 * and moment (PRD section 10.1).
 *
 * ## Its identifier, and the character that is not in it
 *
 * PRD_FOOD 21.3 makes `(date, moment)` the business key, so the aggregate identifier is that
 * pair — the same argument that makes a measurement's date its own identity, and the same
 * consequence: two devices planning the same dinner address one row and cannot open a rival to
 * it. That leaves only the question of how a pair is written down as one string, and the answer
 * is fixed here rather than on the phone, because `aggregateIdSchema` is what a stored row is
 * ultimately judged against.
 *
 * Android wrote `"$plannedOn/${slot.id}"`. `aggregateIdSchema` is `[A-Za-z0-9._:-]+`, which has
 * no `/`, so **every meal-plan row already journalled would have been refused the day this
 * aggregate joined `AGGREGATE_TYPES`** — parsed out at the envelope, before any handler saw it,
 * and marked `failed` with `sync.invalid_payload`. The rows were being written all week.
 *
 * The separator is therefore `:`, which is in the identifier alphabet, and
 * `MealPlanKey.SEPARATOR` on Android now emits it. A contract change does nothing for rows
 * already written, so `MealPlanIdRepair` rewrites the ones that exist — see its file for why
 * that repair is provably safe, and note that it is safe here for a reason peculiar to this
 * aggregate: `mealPlanEntry` has never been in `SENDABLE_LOCAL_AGGREGATE_TYPES`, so no such row
 * has ever left a phone and no server has ever recorded one under its old spelling.
 *
 * The alternative — widening `aggregateIdSchema` to admit `/` — was rejected. The identifier
 * appears in log lines, in error contexts and in MCP tool arguments; an alphabet without a path
 * separator is a property worth keeping for the seven aggregates that do not need it, and one
 * aggregate's spelling is not a reason to spend it.
 */

export const MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1 = 1;

/** `MealSlot`, sorted so a reader can find one; the enum's order carries no meaning. */
export const MEAL_SLOTS = ["breakfast", "lunch", "snack", "dinner"] as const;

/** `MealPlanKey.SEPARATOR`. In `aggregateIdSchema`'s alphabet, which `/` is not. */
export const MEAL_PLAN_ID_SEPARATOR = ":";

/** `Servings.CONSUMED_RANGE` and `CONSUMED_STEP_THOUSANDTHS`: 0.25 to 10, in quarters. */
export const SERVINGS_MIN_THOUSANDTHS = 250;
export const SERVINGS_MAX_THOUSANDTHS = 10_000;
export const SERVINGS_STEP_THOUSANDTHS = 250;

export const mealSlotSchema = z.enum(MEAL_SLOTS).meta({
  id: "MealSlot",
  description: "The moment of the day a line or a proposal belongs to (PRD_FOOD 8.4).",
});

/**
 * A number of servings, in thousandths.
 *
 * The step is stated as well as the range because `Servings.isConsumedCount` states both, and
 * because a step is exactly the kind of constraint that is invisible until a real value fails it:
 * `1333` is inside the range, is a plausible-looking integer, and is not a quarter of anything.
 * PRD_FOOD 8.5 plans a meal with the counter it consumes one with, so a planned count and a
 * consumed count share this schema.
 */
export const servingsThousandthsSchema = z
  .int()
  .min(SERVINGS_MIN_THOUSANDTHS)
  .max(SERVINGS_MAX_THOUSANDTHS)
  .multipleOf(SERVINGS_STEP_THOUSANDTHS)
  .meta({
    id: "ServingsThousandths",
    description:
      "A serving count in thousandths, 0.25 to 10 in steps of 0.25 (PRD_FOOD 15). The step is part of the type: a value inside the range but off the quarter is refused.",
    examples: [1_500],
  });

/**
 * The identifier of one proposal: its date and its moment, joined by [MEAL_PLAN_ID_SEPARATOR].
 *
 * It is a pattern of its own rather than a plain `aggregateIdSchema`, so that the generated
 * specification says what the identifier of *this* aggregate looks like instead of leaving an
 * author to infer it — and so that a client cannot invent a third spelling of the same pair.
 */
export const mealPlanAggregateIdSchema = z
  .string()
  .regex(
    /^\d{4}-\d{2}-\d{2}:(?:breakfast|lunch|snack|dinner)$/,
    "expected a meal plan identifier such as 2026-09-01:dinner",
  )
  .meta({
    id: "MealPlanAggregateId",
    description:
      "The business key of a proposal, `<date>:<slot>`. The separator is a colon: `aggregateIdSchema`'s alphabet has no `/`.",
    examples: ["2026-09-01:dinner"],
  });

/** The one place the pair is written down as a string, so no caller spells it a second way. */
export function mealPlanAggregateId(plannedOn: string, slot: string): string {
  return `${plannedOn}${MEAL_PLAN_ID_SEPARATOR}${slot}`;
}

/**
 * One proposal.
 *
 * `plannedOn` and `slot` repeat the aggregate identifier, in the two pieces it is made of, for
 * the reason `MeasurementPayloadV1.date` repeats its own: an upsert states the complete aggregate
 * (section 12.2), and a payload replayed from the journal alone has to say which day and which
 * moment it plans. The identifier is then derivable from the payload, and the envelope refines
 * that the two agree — so an author cannot address one dinner and describe another.
 */
export const mealPlanEntryPayloadV1Schema = z
  .object({
    plannedOn: localDateSchema,
    slot: mealSlotSchema,
    recipeId: z.uuid(),
    plannedServingsThousandths: servingsThousandthsSchema,
    /**
     * The journal line this proposal became, once it was eaten.
     *
     * Absent rather than null, because that is the shape already journalled:
     * `MealPlanEntryPayload.consumedLogEntryId` defaults to null and `SyncJson` does not encode
     * defaults, so an unconsumed proposal has written no key at all.
     */
    consumedLogEntryId: z.uuid().optional(),
  })
  .meta({
    id: "MealPlanEntryPayloadV1",
    description:
      "One meal proposal, payload schema version 1. At most one per date and moment; the identifier is `<plannedOn>:<slot>` (PRD_FOOD 21.3).",
  });

export type MealPlanEntryPayloadV1 = z.infer<typeof mealPlanEntryPayloadV1Schema>;
