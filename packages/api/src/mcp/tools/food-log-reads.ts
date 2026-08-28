import { localDateSchema, mealSlotSchema } from "@mue/contracts";
import { dailyNutrition, type DailyNutrition } from "@mue/domain";
import { z } from "zod";
import { decodeListCursor, encodeListCursor, encodePairKey, InvalidCursorError } from "../cursor";
import {
  envelopeSchema,
  invalidPayload,
  missingRequiredField,
  toolFailure,
  toolSuccess,
} from "../errors";
import { computedNutrientsSchema, computedNutrientsView, provenanceShape } from "./nutrition-view";
import { lineView, lineViewSchema } from "./food-log";
import {
  cursorInput,
  freshnessShape,
  fromDateInput,
  includeDeletedInput,
  limitInput,
} from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * Reading the food journal: PRD_FOOD 21.5's `list_food_logs` and `get_daily_nutrition`.
 *
 * They are one file because they read one table under one rule and differ in what they do
 * with it — `list_food_logs` walks a period a page at a time, `get_daily_nutrition` takes
 * one day whole and adds it up. The second cannot be a filtered call of the first, and the
 * reason is worth stating: **a total of a page is not a total of a day**. An agent that
 * stopped paging halfway would report a smaller day than the person ate, and nothing in the
 * answer would look wrong. So the day is unpaged, and the tool that totals it says how many
 * lines it read.
 *
 * ## What is deliberately not in a total
 *
 * A **proposal** never is. PRD_FOOD 12: *"Une proposition n'entre dans aucun total tant
 * qu'elle n'est pas confirmée."* That is structural here rather than remembered — these
 * tools read `food_log_entries` and `mue.list_meal_plan` reads `meal_plan_entries`, and
 * there is no query that mixes them.
 *
 * A **deleted line** never is either. A tombstone is not a consumption, so
 * `get_daily_nutrition` has no `includeDeleted`: the question it answers is what was eaten,
 * and the answer does not include what was taken back.
 */

export const LIST_FOOD_LOGS_DEFAULT_LIMIT = 50;
export const LIST_FOOD_LOGS_MAX_LIMIT = 200;

const LIST_TOOL_NAME = "mue.list_food_logs";
const DAILY_TOOL_NAME = "mue.get_daily_nutrition";

// --- mue.list_food_logs -------------------------------------------------------------------

const listInputSchema = {
  from: fromDateInput,
  to: toDateInputDescribed(),
  slot: mealSlotSchema
    .optional()
    .describe(
      "Only lines recorded at this moment of the day. Omit for every moment. The moments are the ones Mue itself uses; do not invent one.",
    ),
  cursor: cursorInput,
  limit: limitInput(LIST_FOOD_LOGS_DEFAULT_LIMIT, LIST_FOOD_LOGS_MAX_LIMIT),
  includeDeleted: includeDeletedInput,
};

/**
 * `toDateInput` with a sentence of its own, because a journal has a rule a weight history
 * does not: there is nothing after today, so an empty answer for a future range is the
 * correct answer and not a synchronisation problem.
 */
function toDateInputDescribed() {
  return localDateSchema
    .optional()
    .describe(
      "Inclusive latest date, YYYY-MM-DD. Omit for no upper bound. A range in the future holds nothing: a journal line cannot be dated ahead of today.",
    );
}

const listDataSchema = z.object({
  entries: z.array(lineViewSchema).describe("The journal lines, newest first."),
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
  slot?: string | undefined;
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

  const limit = args.limit ?? LIST_FOOD_LOGS_DEFAULT_LIMIT;
  const page = await context.services.listFoodLogEntries({
    userId: context.identity.userId,
    from: args.from ?? null,
    to: args.to ?? null,
    slot: args.slot ?? null,
    afterKey,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  const last = page.entries.at(-1);
  return toolSuccess({
    entries: page.entries.map(lineView),
    nextCursor:
      page.hasMore && last !== undefined
        ? encodeListCursor(
            // Day, clock and identifier, in one opaque string. All three, because a minute
            // really can hold two lines and a page really can end between them.
            encodePairKey(
              encodePairKey(last.payload.consumedOn, last.payload.consumedAt),
              last.payload.id,
            ),
          )
        : null,
    hasMore: page.hasMore,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const listFoodLogsTool: MueTool = {
  name: LIST_TOOL_NAME,
  title: "List food journal entries",
  description: [
    "Read the food journal over a period, newest first, one page at a time. Filter by moment",
    "of the day with `slot`.",
    "",
    "With no `from` and no `to` this walks the whole journal: keep calling it with the",
    "`nextCursor` you were given until `hasMore` is false.",
    "",
    "Every line carries its own nutritional snapshot, frozen when it was written -- correcting",
    "a food later does not change a line already recorded. A value that is unknown comes back",
    "as null, never as 0: report it as unknown rather than as none.",
    "",
    "For a day's totals use `mue.get_daily_nutrition` instead: adding up a page is not adding",
    "up a day.",
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

// --- mue.get_daily_nutrition --------------------------------------------------------------

const dailyInputSchema = {
  date: localDateSchema
    .optional()
    .describe(
      "Required. The day to total, YYYY-MM-DD, in the person's local calendar. Resolve words like 'yesterday' yourself; the server will not assume today.",
    ),
};

const slotTotalSchema = z.object({
  slot: z.string().describe("The moment of the day."),
  entryCount: z.int().describe("How many lines this moment holds."),
  totals: computedNutrientsSchema.describe("PRD_FOOD 10.1: this moment's own total."),
});

const dailyDataSchema = z.object({
  date: localDateSchema,
  isRecorded: z
    .boolean()
    .describe(
      "False when the day holds no line at all. The totals below are then a *known* zero, arithmetically -- but nothing was recorded, so do not say the person ate nothing. Say nothing was written down.",
    ),
  entryCount: z.int().describe("How many lines the day holds."),
  totals: computedNutrientsSchema.describe(
    "The day's totals. Each nutrient is known or unknown on its own: a known energy beside an unknown protein is the normal case.",
  ),
  slots: z
    .array(slotTotalSchema)
    .describe(
      "Only the moments that hold a line, in Mue's own order. An empty moment is absent rather than reported as zero (PRD_FOOD 10.1).",
    ),
  entries: z
    .array(lineViewSchema)
    .describe("Every line of the day, ordered by the clock, with the values each was saved with."),
  provenance: z
    .object(provenanceShape)
    .describe(
      "Where these totals came from and how they were obtained, which PRD_FOOD 21.5 requires of a value the server computed.",
    ),
  ...freshnessShape,
});

interface DailyArgs {
  date?: string | undefined;
}

function slotViews(day: DailyNutrition): readonly Record<string, unknown>[] {
  return day.slots.map((slot) => ({
    slot: slot.slot,
    entryCount: slot.entryCount,
    totals: computedNutrientsView(slot.total, slot.unknownFrom),
  }));
}

async function dailyHandler(context: ToolContext, args: DailyArgs) {
  if (args.date === undefined) {
    return toolFailure(
      missingRequiredField(
        "date",
        "Give the day to total as YYYY-MM-DD. The server will not assume today: a person asking about 'yesterday' at one in the morning does not mean the server's yesterday.",
      ),
    );
  }

  const stored = await context.services.foodLogEntriesOn(context.identity.userId, args.date);
  // The arithmetic is `@mue/domain`'s, which is PRD_FOOD 13.1 written once for the server and
  // mirrored from `NutritionMath.kt`. Nothing is added up in this file.
  const day = dailyNutrition(
    args.date,
    stored.map((entry) => entry.payload),
  );

  return toolSuccess({
    date: day.date,
    isRecorded: day.isRecorded,
    entryCount: day.entryCount,
    totals: computedNutrientsView(day.total, day.unknownFrom),
    slots: slotViews(day),
    entries: stored.map(lineView),
    provenance: {
      computedBy: "server",
      method: "strictSum",
      rule: "PRD_FOOD 13.1",
      source:
        "The live food journal lines stored on this date. Deleted lines are excluded, and meal proposals are not journal lines and never enter a total (PRD_FOOD 12).",
      approximate: true,
      contributionCount: day.entryCount,
    },
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getDailyNutritionTool: MueTool = {
  name: DAILY_TOOL_NAME,
  title: "Total one day of the food journal",
  description: [
    "Add up one day of the food journal and return the totals, each moment's own total, and",
    "every line they were computed from.",
    "",
    "Read the totals carefully, because two different things look similar:",
    "",
    "- `known: false` with `milliKcal: null` means **nobody measured it**. Say so. Never report",
    "  it as 0, and never leave it out of a summary as though it were nothing. `unknownFrom`",
    "  names the lines responsible, so you can tell the person which one to complete.",
    "- `known: true` with a value of 0 means a real, measured zero. Black coffee is that.",
    "",
    "Each nutrient is known or unknown on its own: a day can have a known energy and an unknown",
    "protein, and one unknown line makes only its own nutrients unknown for the whole day.",
    "",
    "`isRecorded: false` means the day holds no line at all. The totals are then arithmetically",
    "a known zero -- say that nothing was written down, not that nothing was eaten.",
    "",
    "The figures are approximate and the `display` strings already say so with `≈`; quote them",
    "rather than formatting the numbers yourself. Proposals are not totalled: a planned meal",
    "counts only once it has been logged.",
  ].join("\n"),
  inputSchema: dailyInputSchema,
  outputSchema: envelopeSchema(dailyDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:read"],
  handler: (context, args) => dailyHandler(context, args as DailyArgs),
};
