import {
  defaultLocalTimeForMealSlot,
  estimationSchema,
  FOOD_LOG_ENTRY_PAYLOAD_VERSION_1,
  FOOD_NAME_MAX_LENGTH,
  FOOD_NAME_MIN_LENGTH,
  type FoodLogEntryPayloadV1,
  INGREDIENT_QUANTITY_MAX_THOUSANDTHS,
  INGREDIENT_QUANTITY_MIN_THOUSANDTHS,
  LINE_MAX_MILLI_KCAL,
  localDateSchema,
  localTimeSchema,
  MACRO_PER_100_MAX_MILLIGRAMS,
  mealSlotForLocalTime,
  mealSlotSchema,
  SERVINGS_MAX_THOUSANDTHS,
  SERVINGS_MIN_THOUSANDTHS,
  SERVINGS_STEP_THOUSANDTHS,
  UNCONSTRAINED_TEXT_MAX_LENGTH,
  USUAL_PORTIONS_MAX_THOUSANDTHS,
  USUAL_PORTIONS_MIN_THOUSANDTHS,
  USUAL_PORTIONS_STEP_THOUSANDTHS,
} from "@mue/contracts";
import { z } from "zod";
import { envelopeSchema, invalidPayload, missingRequiredField, toolSuccess } from "../errors";
import type { StoredAggregate } from "../services";
import {
  applyWrite,
  baseRevisionOf,
  expectedRevisionInput,
  idempotencyKeyInput,
  metadataShape,
  mutationIdFor,
  notFound,
  offStep,
  refuse,
  serverTimeShape,
  toThousandths,
} from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * One line of the food journal: PRD_FOOD 21.5's `create_food_log`, `update_food_log` and
 * `delete_food_log`.
 *
 * ## The moment is deduced from the time
 *
 * PRD_FOOD 8.4 makes `slot` a required field of a stored line, and PRD_FOOD 10.3 says where it
 * comes from: the clock. So `slot` is *optional here* and the server derives it -- through
 * `mealSlotForLocalTime` in `@mue/contracts`, which is the same rule the phone applies, in one
 * place rather than two.
 *
 * That is not a convenience. An agent handed a required enum it was told nothing about fills it
 * in, plausibly and wrongly, and a yoghurt at ten in the morning becomes a breakfast that
 * silently changes what the person's day looks like. Making the field optional, saying in its
 * description that it is deduced, and saying when to override it, is what stops that -- and it
 * costs nothing, because the time is a thing people actually say.
 *
 * The inverse is allowed and is the same rule read backwards: told *"at lunch"* and no clock,
 * the line is written at that moment's own default time, which PRD_FOOD 10.3 defines as the
 * default of a retroactive entry. When a moment has no default -- which can only happen for a
 * moment added to `MealSlot` after this build -- the tool asks for the time instead of
 * inventing one, which is section 14.4 applied to the server's own ignorance.
 *
 * ## `kind` is derived, not stated
 *
 * PRD_FOOD 10.2 gives a line three forms and each one *is* what it references: a line built
 * from a food is a `food` line, one from a recipe is a `recipe` line, and one from neither is a
 * `quick` add. So there is no `kind` input to get wrong -- it follows from whether `foodId` or
 * `recipeId` was given, and cannot disagree with `sourceRef`.
 *
 * ## Nothing here depends on the food arriving
 *
 * Section 10.2 and PRD_FOOD 21.2 make a line self-contained: it carries its own nutritional
 * snapshot, so `foodId` is provenance and not a reference that must resolve. A line whose food
 * this server has never seen is still a complete, applicable row.
 */

const CREATE_TOOL_NAME = "mue.create_food_log";
const UPDATE_TOOL_NAME = "mue.update_food_log";
const DELETE_TOOL_NAME = "mue.delete_food_log";

const MAX_LINE_KCAL = LINE_MAX_MILLI_KCAL / 1000;
const MAX_LINE_MACRO_GRAMS = (MACRO_PER_100_MAX_MILLIGRAMS * 100) / 1000;
const MAX_QUANTITY = INGREDIENT_QUANTITY_MAX_THOUSANDTHS / 1000;

// --- shapes ---------------------------------------------------------------------------

const lineViewSchema = z.object({
  id: z.uuid().describe("The line's stable identifier."),
  consumedOn: localDateSchema.describe("The day it was eaten."),
  consumedAt: z.string().describe("The local time it was eaten, HH:MM."),
  slot: z.string().describe("The moment of the day it belongs to."),
  kind: z.string().describe("`food`, `recipe` or `quick`."),
  title: z.string().describe("What the line says was eaten."),
  estimation: z.string().describe("`measured` when it was weighed, `approximate` when estimated."),
  weighedCooked: z.boolean().describe("Whether the weight was taken on the cooked food."),
  energyKcal: z.number().nullable().describe("Kilocalories for this line. Null when unknown."),
  proteinGrams: z.number().nullable(),
  carbsGrams: z.number().nullable(),
  fatGrams: z.number().nullable(),
  fibreGrams: z.number().nullable(),
  sourceRef: z
    .string()
    .nullable()
    .describe("The food or recipe this line came from, for provenance only."),
  amountLabel: z.string().nullable().describe("How the amount was described, such as `1 bowl`."),
  quantity: z.number().nullable().describe("How much, in `quantityUnit`."),
  quantityUnit: z.string().nullable().describe("`gram`, `millilitre` or `serving`."),
  portions: z.number().nullable().describe("How many of the food's usual servings this line is."),
  ...metadataShape,
});

const thousandthsOrNull = (value: number | undefined): number | null =>
  value === undefined ? null : value / 1000;

function lineView(stored: StoredAggregate<FoodLogEntryPayloadV1>): Record<string, unknown> {
  const { payload, meta } = stored;
  return {
    id: payload.id,
    consumedOn: payload.consumedOn,
    consumedAt: payload.consumedAt,
    slot: payload.slot,
    kind: payload.kind,
    title: payload.title,
    estimation: payload.estimation,
    weighedCooked: payload.weighedCooked,
    energyKcal: thousandthsOrNull(payload.energyMilliKcal),
    proteinGrams: thousandthsOrNull(payload.proteinMilligrams),
    carbsGrams: thousandthsOrNull(payload.carbsMilligrams),
    fatGrams: thousandthsOrNull(payload.fatMilligrams),
    fibreGrams: thousandthsOrNull(payload.fibreMilligrams),
    sourceRef: payload.sourceRef ?? null,
    amountLabel: payload.amountLabel ?? null,
    quantity: thousandthsOrNull(payload.quantityThousandths),
    quantityUnit: payload.quantityUnit ?? null,
    portions: thousandthsOrNull(payload.portionsThousandths),
    ...meta,
  };
}

/**
 * The `slot` input, and the sentence that keeps an agent from filling it in at random.
 *
 * The enum itself comes from `mealSlotSchema` rather than being written out here, so the day
 * `MealSlot` gains a moment this tool offers it without an edit. Spelling the ids out would
 * have been a second list to forget.
 */
const slotInput = mealSlotSchema
  .optional()
  .describe(
    "Optional. The moment of the day. Leave it out: Mue deduces the moment from `consumedAt`, which is how the app itself chooses it. Give it only when the person said what kind of meal it was -- 'for lunch', 'as a snack' -- and it disagrees with the clock.",
  );

const nutrientInputs = {
  energyKcal: z
    .number()
    .min(0)
    .max(MAX_LINE_KCAL)
    .optional()
    .describe(
      "Kilocalories for the amount actually eaten, not per 100 g. Leave it out when unknown; never send 0 for unknown.",
    ),
  proteinGrams: z
    .number()
    .min(0)
    .max(MAX_LINE_MACRO_GRAMS)
    .optional()
    .describe("Grams of protein for the amount eaten. Leave it out when unknown."),
  carbsGrams: z
    .number()
    .min(0)
    .max(MAX_LINE_MACRO_GRAMS)
    .optional()
    .describe("Grams of carbohydrate for the amount eaten. Leave it out when unknown."),
  fatGrams: z
    .number()
    .min(0)
    .max(MAX_LINE_MACRO_GRAMS)
    .optional()
    .describe("Grams of fat for the amount eaten. Leave it out when unknown."),
  fibreGrams: z
    .number()
    .min(0)
    .max(MAX_LINE_MACRO_GRAMS)
    .optional()
    .describe("Grams of fibre for the amount eaten. Leave it out when unknown."),
};

const amountInputs = {
  quantityGrams: z
    .number()
    .min(INGREDIENT_QUANTITY_MIN_THOUSANDTHS / 1000)
    .max(MAX_QUANTITY)
    .optional()
    .describe("How much was eaten, in grams. Use one of the three amount fields at most."),
  quantityMillilitres: z
    .number()
    .min(INGREDIENT_QUANTITY_MIN_THOUSANDTHS / 1000)
    .max(MAX_QUANTITY)
    .optional()
    .describe("How much was drunk, in millilitres. Use one of the three amount fields at most."),
  servings: z
    .number()
    .min(SERVINGS_MIN_THOUSANDTHS / 1000)
    .max(SERVINGS_MAX_THOUSANDTHS / 1000)
    .optional()
    .describe(
      "How many servings of a recipe were eaten: 0.25 to 10, in quarters. A count that is not a quarter is refused rather than rounded.",
    ),
  portions: z
    .number()
    .min(USUAL_PORTIONS_MIN_THOUSANDTHS / 1000)
    .max(USUAL_PORTIONS_MAX_THOUSANDTHS / 1000)
    .optional()
    .describe(
      "How many of the food's own usual servings this is: 0.5 to 20, in halves. Only when the food defines one.",
    ),
  amountLabel: z
    .string()
    .min(1)
    .max(UNCONSTRAINED_TEXT_MAX_LENGTH)
    .optional()
    .describe("How the person described the amount, such as `1 bowl`. Their words, not yours."),
};

const CLEARABLE = [
  "energyKcal",
  "proteinGrams",
  "carbsGrams",
  "fatGrams",
  "fibreGrams",
  "amountLabel",
  "quantity",
  "portions",
] as const;

interface LineArgs {
  id?: string | undefined;
  consumedOn?: string | undefined;
  consumedAt?: string | undefined;
  slot?: string | undefined;
  title?: string | undefined;
  foodId?: string | undefined;
  recipeId?: string | undefined;
  estimation?: string | undefined;
  weighedCooked?: boolean | undefined;
  energyKcal?: number | undefined;
  proteinGrams?: number | undefined;
  carbsGrams?: number | undefined;
  fatGrams?: number | undefined;
  fibreGrams?: number | undefined;
  quantityGrams?: number | undefined;
  quantityMillilitres?: number | undefined;
  servings?: number | undefined;
  portions?: number | undefined;
  amountLabel?: string | undefined;
  clear?: readonly (typeof CLEARABLE)[number][] | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

/** The server's own calendar day. PRD_FOOD 21.5: a line cannot be created in the future. */
function serverLocalDate(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/** Which of the three amount fields was given, converted onto the scale its unit selects. */
function resolveAmount(
  args: LineArgs,
):
  | { thousandths: number; unit: "gram" | "millilitre" | "serving" }
  | { none: true }
  | { error: ReturnType<typeof invalidPayload> } {
  const given = [
    args.quantityGrams === undefined ? null : ("gram" as const),
    args.quantityMillilitres === undefined ? null : ("millilitre" as const),
    args.servings === undefined ? null : ("serving" as const),
  ].filter((unit) => unit !== null);

  if (given.length === 0) return { none: true };
  if (given.length > 1) {
    return {
      error: invalidPayload(
        "Give at most one of `quantityGrams`, `quantityMillilitres` or `servings`: one line has one amount.",
        "quantityGrams",
      ),
    };
  }

  const unit = given[0] as "gram" | "millilitre" | "serving";
  if (unit === "serving") {
    const servings = args.servings as number;
    // The step is exactly the kind of rule that is invisible until a real value fails it:
    // 1.3 is inside the range, looks reasonable, and is not a quarter of anything.
    if (offStep(servings, SERVINGS_STEP_THOUSANDTHS)) {
      return {
        error: invalidPayload(
          "A serving count is in steps of 0.25. Ask the person for a quarter, a half or a whole rather than rounding for them.",
          "servings",
        ),
      };
    }
    return { thousandths: toThousandths(servings), unit };
  }

  const value = (unit === "gram" ? args.quantityGrams : args.quantityMillilitres) as number;
  const thousandths = toThousandths(value);
  if (
    thousandths < INGREDIENT_QUANTITY_MIN_THOUSANDTHS ||
    thousandths > INGREDIENT_QUANTITY_MAX_THOUSANDTHS
  ) {
    return {
      error: invalidPayload(
        `An amount is above 0 and at most ${MAX_QUANTITY} g or ml.`,
        unit === "gram" ? "quantityGrams" : "quantityMillilitres",
      ),
    };
  }
  return { thousandths, unit };
}

/** Only keys with a value are written, so an unknown nutrient stays an absent key. */
function withOptional(
  base: Record<string, unknown>,
  optionals: Record<string, number | string | undefined>,
): Record<string, unknown> {
  const result = { ...base };
  for (const [key, value] of Object.entries(optionals)) {
    if (value !== undefined) result[key] = value;
  }
  return result;
}

function nutrientThousandths(
  given: number | undefined,
  stored: number | undefined,
  cleared: boolean,
): number | undefined {
  if (given !== undefined) return toThousandths(given);
  if (cleared) return undefined;
  return stored;
}

// --- mue.create_food_log ----------------------------------------------------------------

const createInputSchema = {
  consumedOn: localDateSchema
    .optional()
    .describe(
      "Required. The day it was eaten, YYYY-MM-DD, in the person's local calendar. A past day is fine; a future day is refused, because a meal that has not happened is not a journal line.",
    ),
  consumedAt: localTimeSchema
    .optional()
    .describe(
      "Required unless you give `slot`. The local time it was eaten, 24-hour HH:MM. This is what the moment of the day is deduced from, so it is the field to ask for.",
    ),
  slot: slotInput,
  title: z
    .string()
    .min(FOOD_NAME_MIN_LENGTH)
    .max(FOOD_NAME_MAX_LENGTH)
    .optional()
    .describe("Required. What was eaten, in the person's own words."),
  foodId: z
    .uuid()
    .optional()
    .describe(
      "The food this line came from, when it came from one Mue holds. It is provenance: the line keeps its own copy of the values either way.",
    ),
  recipeId: z
    .uuid()
    .optional()
    .describe("The recipe this line came from, when it came from one. Use instead of `foodId`."),
  estimation: estimationSchema
    .optional()
    .describe(
      "`measured` only when the person weighed it. Defaults to `approximate`, which is what a described portion is, and Mue shows the difference.",
    ),
  weighedCooked: z
    .boolean()
    .optional()
    .describe(
      "True when the weight was taken on the cooked food rather than raw. Defaults to false. Only set it when the person said so.",
    ),
  ...nutrientInputs,
  ...amountInputs,
  idempotencyKey: idempotencyKeyInput,
};

const writeDataSchema = z.object({
  entry: lineViewSchema.describe("The journal line as it was stored."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  slotWasDeduced: z
    .boolean()
    .describe(
      "True when the moment was worked out from the time rather than given. Say which moment it landed in when it was, so the person can correct it.",
    ),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

/**
 * The time and the moment, each derivable from the other, neither invented.
 *
 * PRD_FOOD 10.3 supplies both directions and this is the whole of them. The one case with no
 * answer -- neither given -- is a question for the person, which is what section 14.4 asks for.
 */
function resolveWhen(
  args: LineArgs,
):
  | { consumedAt: string; slot: string; deduced: boolean }
  | { error: ReturnType<typeof invalidPayload> } {
  if (args.consumedAt === undefined && args.slot === undefined) {
    return {
      error: missingRequiredField(
        "consumedAt",
        "Give the time it was eaten as HH:MM, and the moment of the day follows from it. If the person only said which meal it was, give `slot` instead.",
      ),
    };
  }

  if (args.consumedAt === undefined) {
    const slot = args.slot as string;
    const fallback = defaultLocalTimeForMealSlot(slot);
    if (fallback === undefined) {
      return {
        error: missingRequiredField(
          "consumedAt",
          "This server has no usual time for that moment, so it needs the clock. Ask the person roughly when they ate and give `consumedAt`.",
        ),
      };
    }
    return { consumedAt: fallback, slot, deduced: false };
  }

  if (args.slot !== undefined) {
    return { consumedAt: args.consumedAt, slot: args.slot, deduced: false };
  }

  const derived = mealSlotForLocalTime(args.consumedAt);
  if (derived === undefined) {
    return { error: invalidPayload("`consumedAt` is a 24-hour local time, HH:MM.", "consumedAt") };
  }
  return { consumedAt: args.consumedAt, slot: derived, deduced: true };
}

async function createHandler(context: ToolContext, args: LineArgs) {
  if (args.consumedOn === undefined) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      missingRequiredField(
        "consumedOn",
        "Give the day it was eaten as YYYY-MM-DD. Resolve words like 'yesterday' yourself; the server will not assume today.",
      ),
    );
  }
  if (args.consumedOn > serverLocalDate()) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      invalidPayload(
        "A journal line records something that was eaten, so it cannot be dated in the future. To plan a meal ahead, that is a different thing from logging one.",
        "consumedOn",
      ),
    );
  }
  if (args.title === undefined) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      missingRequiredField(
        "title",
        "Say what was eaten, in the person's own words. The server will not name it from the food it points at.",
      ),
    );
  }
  if (args.foodId !== undefined && args.recipeId !== undefined) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      invalidPayload(
        "A line comes from a food or from a recipe, not both. Log them as two lines.",
        "recipeId",
      ),
    );
  }

  const when = resolveWhen(args);
  if ("error" in when) return refuse(context, CREATE_TOOL_NAME, when.error);

  const amount = resolveAmount(args);
  if ("error" in amount) return refuse(context, CREATE_TOOL_NAME, amount.error);

  if (args.portions !== undefined && offStep(args.portions, USUAL_PORTIONS_STEP_THOUSANDTHS)) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      invalidPayload("A count of usual servings is in steps of 0.5.", "portions"),
    );
  }

  const id = crypto.randomUUID();
  const payload = withOptional(
    {
      id,
      consumedOn: args.consumedOn,
      consumedAt: when.consumedAt,
      slot: when.slot,
      // Derived, never stated: the form a line takes *is* what it references (PRD_FOOD 10.2).
      kind: args.recipeId !== undefined ? "recipe" : args.foodId !== undefined ? "food" : "quick",
      title: args.title,
      // The weaker claim is the default. PRD_FOOD 13.2 makes an approximation visible rather
      // than hidden, and a portion someone described in words is an approximation.
      estimation: args.estimation ?? "approximate",
      // False is the reference state, which is what a weight is taken on unless someone says
      // otherwise. Not a guess about the meal: a statement that nobody said it was cooked.
      weighedCooked: args.weighedCooked ?? false,
    },
    {
      energyMilliKcal: nutrientThousandths(args.energyKcal, undefined, false),
      proteinMilligrams: nutrientThousandths(args.proteinGrams, undefined, false),
      carbsMilligrams: nutrientThousandths(args.carbsGrams, undefined, false),
      fatMilligrams: nutrientThousandths(args.fatGrams, undefined, false),
      fibreMilligrams: nutrientThousandths(args.fibreGrams, undefined, false),
      sourceRef: args.recipeId ?? args.foodId,
      amountLabel: args.amountLabel,
      ...("none" in amount
        ? {}
        : { quantityThousandths: amount.thousandths, quantityUnit: amount.unit }),
      portionsThousandths: args.portions === undefined ? undefined : toThousandths(args.portions),
    },
  ) as FoodLogEntryPayloadV1;

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: CREATE_TOOL_NAME,
    aggregateType: "foodLogEntry",
    aggregateId: id,
    op: "upsert",
    payloadSchemaVersion: FOOD_LOG_ENTRY_PAYLOAD_VERSION_1,
    payload,
    baseRevision: null,
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return readBack(
    context,
    outcome.result.aggregateId,
    outcome.result.status,
    mutationId,
    when.deduced,
  );
}

export const createFoodLogTool: MueTool = {
  name: CREATE_TOOL_NAME,
  title: "Log something eaten",
  description: [
    "Record one thing the person ate or drank. One line per item: a yoghurt and a banana at",
    "breakfast are two calls, not one -- Mue has no meal object, only lines grouped by moment.",
    "",
    "Give the time it was eaten and Mue works out the moment of the day itself, exactly as the",
    "app does. Only send `slot` when the person said what kind of meal it was and it disagrees",
    "with the clock. Do not pick a moment because the field exists.",
    "",
    "Values are for the amount actually eaten, not per 100 g, and every one you leave out stays",
    "unknown. Never send 0 for a value you were not told: Mue would show it as a fact.",
    "",
    "The day must not be in the future. Retrying after a lost response is safe as long as you",
    "send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: createInputSchema,
  outputSchema: envelopeSchema(writeDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:write"],
  handler: (context, args) => createHandler(context, args as LineArgs),
};

// --- mue.update_food_log ------------------------------------------------------------------

const updateInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the line to change, as a read tool returned it."),
  consumedOn: localDateSchema.optional().describe("The corrected day. Omit to leave it as it is."),
  consumedAt: localTimeSchema
    .optional()
    .describe(
      "The corrected time, HH:MM. Correcting the time alone does not move the line to another moment -- the moment already recorded stands. Send `slot` too when it should move.",
    ),
  slot: mealSlotSchema
    .optional()
    .describe(
      "The corrected moment of the day. Omit to leave the recorded one as it is; on an update the moment is not re-deduced from the time, because it may have been chosen deliberately.",
    ),
  title: z
    .string()
    .min(FOOD_NAME_MIN_LENGTH)
    .max(FOOD_NAME_MAX_LENGTH)
    .optional()
    .describe("The corrected description of what was eaten."),
  estimation: estimationSchema
    .optional()
    .describe("`measured` when the person says they actually weighed it."),
  weighedCooked: z
    .boolean()
    .optional()
    .describe("Whether the weight was taken on the cooked food."),
  ...nutrientInputs,
  ...amountInputs,
  clear: z
    .array(z.enum(CLEARABLE))
    .max(CLEARABLE.length)
    .optional()
    .describe(
      "Fields the person asked to remove. `quantity` removes the amount and its unit together, since neither means anything alone. Leaving a field out of the call keeps it.",
    ),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

async function updateHandler(context: ToolContext, args: LineArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the line to change. The server will not guess which line was meant.",
      ),
    );
  }

  const stored = await context.services.getFoodLogEntry(context.identity.userId, args.id);
  if (stored === null || stored.meta.deletedAt !== null) {
    return refuse(context, UPDATE_TOOL_NAME, notFound("foodLogEntry", args.id, "journal line"));
  }

  const consumedOn = args.consumedOn ?? stored.payload.consumedOn;
  if (consumedOn > serverLocalDate()) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload("A journal line cannot be dated in the future.", "consumedOn"),
    );
  }

  const slot = args.slot ?? stored.payload.slot;
  if (stored.payload.fromPlan !== undefined && slot !== stored.payload.slot) {
    // The contract refines that a line logged from a proposal carries that proposal's own
    // moment. Moving it would make the two disagree, so the caller is told which field is in
    // the way rather than having its provenance quietly dropped.
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        "This line was logged from a meal proposal for another moment, so it cannot be moved. Delete it and log a new line if the person ate it at a different time.",
        "slot",
      ),
    );
  }

  const amount = resolveAmount(args);
  if ("error" in amount) return refuse(context, UPDATE_TOOL_NAME, amount.error);

  if (args.portions !== undefined && offStep(args.portions, USUAL_PORTIONS_STEP_THOUSANDTHS)) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload("A count of usual servings is in steps of 0.5.", "portions"),
    );
  }

  const cleared = new Set(args.clear ?? []);
  const keepAmount = "none" in amount && !cleared.has("quantity");
  const payload = withOptional(
    {
      id: stored.payload.id,
      consumedOn,
      consumedAt: args.consumedAt ?? stored.payload.consumedAt,
      slot,
      kind: stored.payload.kind,
      title: args.title ?? stored.payload.title,
      estimation: args.estimation ?? stored.payload.estimation,
      weighedCooked: args.weighedCooked ?? stored.payload.weighedCooked,
    },
    {
      energyMilliKcal: nutrientThousandths(
        args.energyKcal,
        stored.payload.energyMilliKcal,
        cleared.has("energyKcal"),
      ),
      proteinMilligrams: nutrientThousandths(
        args.proteinGrams,
        stored.payload.proteinMilligrams,
        cleared.has("proteinGrams"),
      ),
      carbsMilligrams: nutrientThousandths(
        args.carbsGrams,
        stored.payload.carbsMilligrams,
        cleared.has("carbsGrams"),
      ),
      fatMilligrams: nutrientThousandths(
        args.fatGrams,
        stored.payload.fatMilligrams,
        cleared.has("fatGrams"),
      ),
      fibreMilligrams: nutrientThousandths(
        args.fibreGrams,
        stored.payload.fibreMilligrams,
        cleared.has("fibreGrams"),
      ),
      sourceRef: stored.payload.sourceRef,
      amountLabel:
        args.amountLabel ?? (cleared.has("amountLabel") ? undefined : stored.payload.amountLabel),
      ...(keepAmount
        ? {
            ...(stored.payload.quantityThousandths === undefined
              ? {}
              : { quantityThousandths: stored.payload.quantityThousandths }),
            ...(stored.payload.quantityUnit === undefined
              ? {}
              : { quantityUnit: stored.payload.quantityUnit }),
          }
        : "none" in amount
          ? {}
          : { quantityThousandths: amount.thousandths, quantityUnit: amount.unit }),
      portionsThousandths:
        args.portions !== undefined
          ? toThousandths(args.portions)
          : cleared.has("portions")
            ? undefined
            : stored.payload.portionsThousandths,
      fromPlan: stored.payload.fromPlan,
    },
  ) as FoodLogEntryPayloadV1;

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPDATE_TOOL_NAME,
    aggregateType: "foodLogEntry",
    aggregateId: args.id,
    op: "upsert",
    payloadSchemaVersion: FOOD_LOG_ENTRY_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId, false);
}

export const updateFoodLogTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Correct a logged item",
  description: [
    "Correct a line already in the food journal, including one recorded days ago.",
    "",
    "Send only what changed; anything you leave out keeps its stored value, including a value",
    "that is currently unknown. To make one unknown again, name it in `clear`.",
    "",
    "Correcting the time does not move the line to another moment of the day: the moment was",
    "recorded once and may have been chosen deliberately. Send `slot` as well when the person",
    "says it belongs elsewhere.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: updateInputSchema,
  outputSchema: envelopeSchema(writeDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:write"],
  handler: (context, args) => updateHandler(context, args as LineArgs),
};

// --- mue.delete_food_log ------------------------------------------------------------------

const deleteInputSchema = {
  id: z.uuid().optional().describe("Required. The identifier of the journal line to delete."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const deleteDataSchema = z.object({
  id: z.uuid().describe("The line that was deleted."),
  deleted: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  revision: z.string().describe("The revision the tombstone was written at."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

async function deleteHandler(context: ToolContext, args: LineArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the line to delete. The server will not choose one.",
      ),
    );
  }

  const stored = await context.services.getFoodLogEntry(context.identity.userId, args.id);
  if (stored === null) {
    return refuse(context, DELETE_TOOL_NAME, notFound("foodLogEntry", args.id, "journal line"));
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: DELETE_TOOL_NAME,
    aggregateType: "foodLogEntry",
    aggregateId: args.id,
    op: "delete",
    payloadSchemaVersion: FOOD_LOG_ENTRY_PAYLOAD_VERSION_1,
    payload: null,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return toolSuccess({
    id: args.id,
    deleted: outcome.result.status === "applied",
    revision: outcome.result.revision ?? stored.meta.revision,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const deleteFoodLogTool: MueTool = {
  name: DELETE_TOOL_NAME,
  title: "Remove a logged item",
  description: [
    "Delete one line from the food journal. The day's totals recompute without it.",
    "",
    "This removes what the person recorded eating. Ask before you call it unless they asked for",
    "it in as many words -- to fix a wrong amount or a wrong name, use `mue.update_food_log`",
    "instead.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: deleteInputSchema,
  outputSchema: envelopeSchema(deleteDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // Section 14.6: "Les suppressions sont explicitement annotées comme destructives."
    destructiveHint: true,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:write", "data:delete"],
  handler: (context, args) => deleteHandler(context, args as LineArgs),
};

async function readBack(
  context: ToolContext,
  id: string,
  status: "applied" | "duplicate",
  mutationId: string,
  slotWasDeduced: boolean,
) {
  const current = await context.services.getFoodLogEntry(context.identity.userId, id);
  if (current === null) throw new Error("food_log_entries lost a row between apply and read");
  return toolSuccess({
    entry: lineView(current),
    created: status === "applied",
    slotWasDeduced,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}
