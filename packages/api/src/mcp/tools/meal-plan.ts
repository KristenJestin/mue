import {
  localDateSchema,
  mealPlanAggregateId,
  mealSlotSchema,
  MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1,
  MEAL_PLAN_MAX_DAYS_AHEAD,
  SERVINGS_MAX_THOUSANDTHS,
  SERVINGS_MIN_THOUSANDTHS,
  SERVINGS_STEP_THOUSANDTHS,
  type MealPlanEntryPayloadV1,
} from "@mue/contracts";
import { z } from "zod";
import { decodeListCursor, encodeListCursor, encodePairKey, InvalidCursorError } from "../cursor";
import {
  envelopeSchema,
  invalidPayload,
  missingRequiredField,
  toolFailure,
  toolSuccess,
} from "../errors";
import type { StoredAggregate } from "../services";
import {
  applyWrite,
  baseRevisionOf,
  cursorInput,
  expectedRevisionInput,
  freshnessShape,
  fromDateInput,
  idempotencyKeyInput,
  includeDeletedInput,
  limitInput,
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
 * Meal proposals: PRD_FOOD 21.5's `list_meal_plan`, `plan_meal` and `unplan_meal`.
 *
 * ## A proposal is the mirror image of a journal line
 *
 * PRD_FOOD 21.5's common rules say *"une ligne de journal ne peut pas être créée dans le
 * futur"*, and PRD_FOOD 15 states the other half in the same table: *"Date proposée :
 * aujourd'hui ou dans le futur, dans les 60 jours"*. `MealPlanEntry.isPlannableOn` is the
 * Kotlin of it — *"the mirror image of `FoodLogEntry.isLoggableOn`: never behind, never past
 * 60 days"* — and `plan_meal` enforces exactly that, from
 * [MEAL_PLAN_MAX_DAYS_AHEAD] in `@mue/contracts` rather than from a 60 written here.
 *
 * PRD_FOOD 12 is the reason both halves matter: planning is *"l'amorce qui rend le journal
 * possible"*. A proposal for yesterday is not a plan, it is a journal line someone forgot to
 * write — and `mue.create_food_log` is the tool for that.
 *
 * ## Its identity is `(date, moment)`, and there is no `id` to hold
 *
 * PRD_FOOD 21.3 makes the pair the business key, so at most one proposal exists per date and
 * moment and `plan_meal` is an upsert by nature: planning a second recipe for Friday dinner
 * *replaces* the first, which is PRD_FOOD 12's `Swap` and not a duplicate. The tool says so,
 * because an agent that expected an error and got a silent replacement would tell the person
 * they now have two.
 *
 * The wire identifier is `<date>:<moment>`, built by `mealPlanAggregateId` and by nothing
 * else here. The separator is a colon, and `meal-plan.ts` in `@mue/contracts` records what it
 * cost to learn that.
 *
 * ## `unplan_meal` is a deletion, and is annotated as one
 *
 * PRD_FOOD 21.5: *"les suppressions sont annotées comme destructives"*. So it declares
 * `destructiveHint` and, like every other delete in this catalogue, it asks for `data:delete`
 * on top of `nutrition:write` — an agent trusted to write every domain cannot remove a
 * proposal, and does not even see the tool.
 *
 * PRD_FOOD 12 makes the consequence small and worth stating: `Dismiss` *"retire la
 * proposition, laisse le moment libre, et ne touche ni la recette ni le journal"*. Nothing
 * eaten is lost by this call.
 */

export const LIST_MEAL_PLAN_DEFAULT_LIMIT = 50;
export const LIST_MEAL_PLAN_MAX_LIMIT = 200;

const LIST_TOOL_NAME = "mue.list_meal_plan";
const PLAN_TOOL_NAME = "mue.plan_meal";
const UNPLAN_TOOL_NAME = "mue.unplan_meal";

const MIN_SERVINGS = SERVINGS_MIN_THOUSANDTHS / 1000;
const MAX_SERVINGS = SERVINGS_MAX_THOUSANDTHS / 1000;

// --- the shape a proposal comes back in ----------------------------------------------------

const planViewSchema = z.object({
  aggregateId: z
    .string()
    .describe(
      "The proposal's identifier, `<date>:<moment>`. It is the date and the moment, not a separate key: there is one proposal per pair.",
    ),
  plannedOn: localDateSchema.describe("The day the meal is proposed for."),
  slot: z.string().describe("The moment of the day it is proposed for."),
  recipeId: z.uuid().describe("The recipe proposed. Read it with `mue.get_recipe`."),
  recipeName: z
    .string()
    .nullable()
    .describe(
      "The recipe's current name, or null when this server no longer holds that recipe -- deleting a recipe frees the proposals that named it (PRD_FOOD 11) without deleting them.",
    ),
  plannedServings: z.number().describe("How many servings are proposed."),
  plannedServingsThousandths: z
    .int()
    .describe("The same count as the integer Mue stores: thousandths of a serving."),
  consumedLogEntryId: z
    .uuid()
    .nullable()
    .describe(
      "The journal line this proposal became, once the person confirmed it. Null while it is still waiting.",
    ),
  isConsumed: z
    .boolean()
    .describe(
      "True once it has been eaten and logged. A proposal enters no total until then (PRD_FOOD 12).",
    ),
  ...metadataShape,
});

function planView(
  stored: StoredAggregate<MealPlanEntryPayloadV1>,
  recipeName: string | null,
): Record<string, unknown> {
  const { payload, meta } = stored;
  return {
    aggregateId: mealPlanAggregateId(payload.plannedOn, payload.slot),
    plannedOn: payload.plannedOn,
    slot: payload.slot,
    recipeId: payload.recipeId,
    recipeName,
    plannedServings: payload.plannedServingsThousandths / 1000,
    plannedServingsThousandths: payload.plannedServingsThousandths,
    consumedLogEntryId: payload.consumedLogEntryId ?? null,
    isConsumed: payload.consumedLogEntryId !== undefined,
    ...meta,
  };
}

/** The server's own calendar day, as `food-log.ts` computes it for the opposite bound. */
function serverLocalDate(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/** The furthest day a proposal may be made for: today plus [MEAL_PLAN_MAX_DAYS_AHEAD]. */
function furthestPlannableDate(): string {
  const now = new Date();
  const furthest = new Date(
    Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + MEAL_PLAN_MAX_DAYS_AHEAD),
  );
  return furthest.toISOString().slice(0, 10);
}

// --- mue.list_meal_plan ---------------------------------------------------------------------

const listInputSchema = {
  from: fromDateInput,
  to: localDateSchema
    .optional()
    .describe("Inclusive latest date, YYYY-MM-DD. Omit for no upper bound."),
  cursor: cursorInput,
  limit: limitInput(LIST_MEAL_PLAN_DEFAULT_LIMIT, LIST_MEAL_PLAN_MAX_LIMIT),
  includeDeleted: includeDeletedInput,
};

const listDataSchema = z.object({
  entries: z
    .array(planViewSchema)
    .describe("The proposals, earliest first, and within a day in the order the moments occur."),
  nextCursor: z
    .string()
    .nullable()
    .describe("Pass to `cursor` for the next page. Null when this page is the last."),
  hasMore: z.boolean(),
  ...freshnessShape,
});

interface ListArgs {
  from?: string | undefined;
  to?: string | undefined;
  cursor?: string | undefined;
  limit?: number | undefined;
  includeDeleted?: boolean | undefined;
}

async function listHandler(context: ToolContext, args: ListArgs) {
  let afterKey: string | null = null;
  if (args.cursor !== undefined) {
    try {
      afterKey = decodeListCursor(args.cursor);
    } catch (error) {
      if (!(error instanceof InvalidCursorError)) throw error;
      return toolFailure({
        code: "sync.invalid_cursor",
        message: "The cursor is not one this server issued. Start again without a cursor.",
        retryable: false,
        field: "cursor",
      });
    }
  }

  if (args.from !== undefined && args.to !== undefined && args.from > args.to) {
    return toolFailure(invalidPayload("`from` is later than `to`, so no day can match.", "from"));
  }

  const limit = args.limit ?? LIST_MEAL_PLAN_DEFAULT_LIMIT;
  const page = await context.services.listMealPlan({
    userId: context.identity.userId,
    from: args.from ?? null,
    to: args.to ?? null,
    afterKey,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  const names = await recipeNamesFor(context, page.entries);
  const last = page.entries.at(-1);
  return toolSuccess({
    entries: page.entries.map((entry) =>
      planView(entry, names.get(entry.payload.recipeId) ?? null),
    ),
    nextCursor:
      page.hasMore && last !== undefined
        ? encodeListCursor(encodePairKey(last.payload.plannedOn, last.payload.slot))
        : null,
    hasMore: page.hasMore,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

/**
 * The names of the recipes a page proposes, so an agent can say *"lasagne on Friday"* rather
 * than read a UUID out loud.
 *
 * A recipe that is gone leaves no name, and that is reported rather than hidden: PRD_FOOD 11
 * says deleting a recipe *frees* the proposals that referenced it, so a nameless proposal is
 * a real state and not a lookup that failed.
 */
async function recipeNamesFor(
  context: ToolContext,
  entries: readonly StoredAggregate<MealPlanEntryPayloadV1>[],
): Promise<ReadonlyMap<string, string>> {
  const names = new Map<string, string>();
  for (const id of new Set(entries.map((entry) => entry.payload.recipeId))) {
    const recipe = await context.services.getRecipe(context.identity.userId, id);
    if (recipe !== null && recipe.meta.deletedAt === null) names.set(id, recipe.payload.name);
  }
  return names;
}

export const listMealPlanTool: MueTool = {
  name: LIST_TOOL_NAME,
  title: "List meal proposals",
  description: [
    "Read the meal proposals over a period: which recipe is planned for which day and moment,",
    "and whether it has been eaten yet.",
    "",
    "A proposal is an intention, not a record. It enters no daily total until the person",
    "confirms it, so never add one to what they have eaten. `isConsumed` says which ones",
    "already became journal lines.",
    "",
    "There is at most one proposal per day and moment.",
  ].join("\n"),
  inputSchema: listInputSchema,
  outputSchema: envelopeSchema(listDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:read"],
  handler: (context, args) => listHandler(context, args as ListArgs),
};

// --- mue.plan_meal ---------------------------------------------------------------------------

const planInputSchema = {
  plannedOn: localDateSchema
    .optional()
    .describe(
      `Required. The day to plan for, YYYY-MM-DD. Today or later, and at most ${MEAL_PLAN_MAX_DAYS_AHEAD} days ahead. A past day is refused: that is a meal that has happened, and it belongs in the journal.`,
    ),
  slot: mealSlotSchema
    .optional()
    .describe(
      "Required. The moment of the day to plan for. Unlike a journal line there is no clock to deduce it from, so it has to be said. Ask rather than choose.",
    ),
  recipeId: z
    .uuid()
    .optional()
    .describe("Required. The recipe to propose, as `mue.list_recipes` returned it."),
  servings: z
    .number()
    .min(MIN_SERVINGS)
    .max(MAX_SERVINGS)
    .optional()
    .describe(
      `Required. How many servings to plan: ${MIN_SERVINGS} to ${MAX_SERVINGS}, in quarters. Ask the person; the app asks too, and a count nobody stated would be invented.`,
    ),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const planDataSchema = z.object({
  entry: planViewSchema.describe("The proposal as it now stands."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  replaced: z
    .boolean()
    .describe(
      "True when a different proposal already stood for that day and moment and has been replaced. There is one proposal per pair, so tell the person what was swapped out.",
    ),
  replacedRecipeId: z
    .uuid()
    .nullable()
    .describe("The recipe that was proposed before this call, when one was."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface PlanArgs {
  plannedOn?: string | undefined;
  slot?: string | undefined;
  recipeId?: string | undefined;
  servings?: number | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

async function planHandler(context: ToolContext, args: PlanArgs) {
  if (args.plannedOn === undefined) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      missingRequiredField(
        "plannedOn",
        "Give the day to plan for as YYYY-MM-DD. Resolve words like 'Friday' yourself; the server will not assume a day.",
      ),
    );
  }
  if (args.slot === undefined) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      missingRequiredField(
        "slot",
        "Say which moment of the day this is for. A proposal has no time to deduce it from, so the server will not pick one.",
      ),
    );
  }
  if (args.recipeId === undefined) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      missingRequiredField(
        "recipeId",
        "Give the identifier of the recipe to propose. Find it with `mue.list_recipes`.",
      ),
    );
  }
  if (args.servings === undefined) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      missingRequiredField(
        "servings",
        "Say how many servings to plan. The app asks for it before it will save a proposal, and the server will not choose a number nobody stated.",
      ),
    );
  }

  const today = serverLocalDate();
  if (args.plannedOn < today) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      invalidPayload(
        "A proposal is for a meal that has not happened yet, so it cannot be dated in the past. To record a meal that was eaten, use `mue.create_food_log`.",
        "plannedOn",
      ),
    );
  }
  if (args.plannedOn > furthestPlannableDate()) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      invalidPayload(
        `A meal can be planned at most ${MEAL_PLAN_MAX_DAYS_AHEAD} days ahead.`,
        "plannedOn",
      ),
    );
  }

  if (offStep(args.servings, SERVINGS_STEP_THOUSANDTHS)) {
    return refuse(
      context,
      PLAN_TOOL_NAME,
      invalidPayload(
        "A serving count is in steps of 0.25. Ask the person for a quarter, a half or a whole rather than rounding for them.",
        "servings",
      ),
    );
  }

  const recipe = await context.services.getRecipe(context.identity.userId, args.recipeId);
  if (recipe === null || recipe.meta.deletedAt !== null) {
    // Not a rule the contract states -- `recipeId` is a bare uuid on the wire, and the phone
    // can legitimately hold a plan whose recipe has not arrived. It is checked *here* because
    // an agent that mistypes an identifier would otherwise get a silent, un-openable card on
    // the person's day screen, and the failure would surface on the phone rather than in the
    // turn that caused it.
    return refuse(context, PLAN_TOOL_NAME, notFound("recipe", args.recipeId, "recipe"));
  }

  const existing = await context.services.getMealPlanEntry(
    context.identity.userId,
    args.plannedOn,
    args.slot,
  );
  const live = existing !== null && existing.meta.deletedAt === null ? existing : null;

  const payload: MealPlanEntryPayloadV1 = {
    plannedOn: args.plannedOn,
    slot: args.slot as MealPlanEntryPayloadV1["slot"],
    recipeId: args.recipeId,
    plannedServingsThousandths: toThousandths(args.servings),
    // A proposal that had already been eaten keeps the line it became: PRD_FOOD 12 links the
    // two, and swapping the recipe of a confirmed proposal must not orphan the journal line.
    ...(live?.payload.consumedLogEntryId === undefined
      ? {}
      : { consumedLogEntryId: live.payload.consumedLogEntryId }),
  };

  const aggregateId = mealPlanAggregateId(args.plannedOn, args.slot);
  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: PLAN_TOOL_NAME,
    aggregateType: "mealPlanEntry",
    aggregateId,
    op: "upsert",
    payloadSchemaVersion: MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, live?.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  const current = await context.services.getMealPlanEntry(
    context.identity.userId,
    args.plannedOn,
    args.slot,
  );
  if (current === null) throw new Error("meal_plan_entries lost a row between apply and read");

  const replacedRecipeId =
    live !== null && live.payload.recipeId !== args.recipeId ? live.payload.recipeId : null;
  return toolSuccess({
    entry: planView(current, recipe.payload.name),
    created: outcome.result.status === "applied",
    replaced: replacedRecipeId !== null,
    replacedRecipeId,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const planMealTool: MueTool = {
  name: PLAN_TOOL_NAME,
  title: "Plan a meal",
  description: [
    "Propose a recipe for a day and a moment. The person sees it on that day as a card they",
    "can accept, swap or dismiss; it is an intention, not a record.",
    "",
    "The day must be today or later, and at most sixty days ahead. A meal that has already",
    "been eaten is not a proposal -- use `mue.create_food_log`.",
    "",
    "There is one proposal per day and moment, so planning a second recipe for the same",
    "dinner replaces the first rather than adding to it. The reply says what was replaced;",
    "tell the person.",
    "",
    "A proposal enters no daily total until the person confirms they ate it.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: planInputSchema,
  outputSchema: envelopeSchema(planDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:write"],
  handler: (context, args) => planHandler(context, args as PlanArgs),
};

// --- mue.unplan_meal -------------------------------------------------------------------------

const unplanInputSchema = {
  plannedOn: localDateSchema
    .optional()
    .describe("Required. The day the proposal is on, YYYY-MM-DD."),
  slot: mealSlotSchema.optional().describe("Required. The moment the proposal is at."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const unplanDataSchema = z.object({
  aggregateId: z.string().describe("The proposal that was removed, `<date>:<moment>`."),
  plannedOn: localDateSchema,
  slot: z.string(),
  deleted: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  revision: z.string().describe("The revision the tombstone was written at."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface UnplanArgs {
  plannedOn?: string | undefined;
  slot?: string | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

async function unplanHandler(context: ToolContext, args: UnplanArgs) {
  if (args.plannedOn === undefined) {
    return refuse(
      context,
      UNPLAN_TOOL_NAME,
      missingRequiredField(
        "plannedOn",
        "Give the day of the proposal to remove as YYYY-MM-DD. The server will not choose one.",
      ),
    );
  }
  if (args.slot === undefined) {
    return refuse(
      context,
      UNPLAN_TOOL_NAME,
      missingRequiredField(
        "slot",
        "Say which moment of the day the proposal is at. The server will not remove a whole day's plans from an unstated moment.",
      ),
    );
  }

  const aggregateId = mealPlanAggregateId(args.plannedOn, args.slot);
  const stored = await context.services.getMealPlanEntry(
    context.identity.userId,
    args.plannedOn,
    args.slot,
  );
  if (stored === null) {
    return refuse(
      context,
      UNPLAN_TOOL_NAME,
      notFound("mealPlanEntry", aggregateId, "meal proposal"),
    );
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UNPLAN_TOOL_NAME,
    aggregateType: "mealPlanEntry",
    aggregateId,
    op: "delete",
    payloadSchemaVersion: MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1,
    payload: null,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return toolSuccess({
    aggregateId,
    plannedOn: args.plannedOn,
    slot: args.slot,
    deleted: outcome.result.status === "applied",
    revision: outcome.result.revision ?? stored.meta.revision,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const unplanMealTool: MueTool = {
  name: UNPLAN_TOOL_NAME,
  title: "Remove a meal proposal",
  description: [
    "Remove the proposal on one day and moment, leaving that moment free.",
    "",
    "This touches neither the recipe nor the food journal: nothing the person ate is lost. If",
    "they ate the meal and want it recorded, log it first with `mue.create_food_log`.",
    "",
    "To propose a different recipe instead, call `mue.plan_meal` again -- it replaces what is",
    "there, and does not need the old one removed first.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: unplanInputSchema,
  outputSchema: envelopeSchema(unplanDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // PRD_FOOD 21.5: "les suppressions sont annotées comme destructives".
    destructiveHint: true,
    idempotentHint: true,
    openWorldHint: false,
  },
  // The deletion permission of section 15.2, on top of the domain's write scope. An agent
  // granted every write but not `data:delete` does not see this tool at all.
  scopes: ["nutrition:write", "data:delete"],
  handler: (context, args) => unplanHandler(context, args as UnplanArgs),
};
