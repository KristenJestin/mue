import {
  BARCODE_MAX_LENGTH,
  BARCODE_MIN_LENGTH,
  FOOD_NAME_MAX_LENGTH,
  foodSourceSchema,
} from "@mue/contracts";
import { z } from "zod";
import { decodeListCursor, encodeListCursor, encodePairKey, InvalidCursorError } from "../cursor";
import { envelopeSchema, invalidPayload, toolFailure, toolSuccess } from "../errors";
import { foodView, foodViewSchema } from "./food";
import { cursorInput, freshnessShape, includeDeletedInput, limitInput } from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * PRD_FOOD 21.5's `search_foods`: *"recherche dans les aliments accessibles à
 * l'utilisateur"*.
 *
 * ## Which foods are reachable from here, and which are not
 *
 * PRD_FOOD 21.1 lists three kinds and marks all three MCP-accessible: custom foods, copied
 * Open Food Facts products, and the embedded Ciqual catalogue. The first two are
 * synchronised aggregates and this tool searches them.
 *
 * **The third is not on this server.** The same table marks Ciqual *"Synchronisé: Non"* — it
 * is a versioned asset built by `@mue/ciqual` and shipped inside the Android application, so
 * there is no row here to match against. Rather than answer as though the catalogue were
 * empty, the tool says so in `catalogue.ciqualSearchable` and in its own description: an
 * agent that finds nothing learns that a whole source was not consulted, instead of
 * concluding the person has no such food and offering to create a duplicate of one they
 * already have on the phone.
 *
 * ## What "the same name" means here
 *
 * Case, and case alone. PRD_FOOD 9.4 also asks for accent-insensitivity, and that sentence
 * describes the phone's offline search; the server holds no folded column for a food, and
 * the one fold this codebase has — `foldExerciseName` — is `trim().toLowerCase()`. Inventing
 * a second, richer definition of sameness here would make two searches over the same words
 * disagree depending on which one ran. Recorded rather than half-done.
 */

export const SEARCH_FOODS_DEFAULT_LIMIT = 25;
export const SEARCH_FOODS_MAX_LIMIT = 100;

const TOOL_NAME = "mue.search_foods";

const inputSchema = {
  search: z
    .string()
    .min(1)
    .max(FOOD_NAME_MAX_LENGTH)
    .optional()
    .describe(
      "What to look for, matched anywhere in a food's name or brand, ignoring case. Omit it to list the whole catalogue in name order.",
    ),
  barcode: z
    .string()
    .min(BARCODE_MIN_LENGTH)
    .max(BARCODE_MAX_LENGTH)
    .regex(/^\d+$/, "expected a barcode of digits only")
    .optional()
    .describe("An exact barcode, when the person read one out. Combines with `search`."),
  source: foodSourceSchema
    .optional()
    .describe(
      "Restrict to one source: `custom` for foods someone described, `open_food_facts` for scanned products. Omit for both.",
    ),
  cursor: cursorInput,
  limit: limitInput(SEARCH_FOODS_DEFAULT_LIMIT, SEARCH_FOODS_MAX_LIMIT),
  includeDeleted: includeDeletedInput,
};

const dataSchema = z.object({
  foods: z.array(foodViewSchema).describe("The matching foods, in name order."),
  nextCursor: z
    .string()
    .nullable()
    .describe("Pass to `cursor` for the next page. Null when this page is the last."),
  hasMore: z.boolean(),
  catalogue: z
    .object({
      searched: z
        .array(z.string())
        .describe("The sources this search actually consulted (PRD_FOOD 21.1)."),
      ciqualSearchable: z
        .literal(false)
        .describe(
          "The generic Ciqual catalogue is shipped inside the phone application and is not held on this server, so it was not searched. Finding nothing here does not mean the person has no such food: it may be a Ciqual entry only the phone can see. Say so rather than offering to create a duplicate.",
        ),
      matchedOn: z
        .string()
        .describe("How the text was matched, so an empty result can be understood."),
    })
    .describe("What was searched, and what was not."),
  ...freshnessShape,
});

interface SearchArgs {
  search?: string | undefined;
  barcode?: string | undefined;
  source?: string | undefined;
  cursor?: string | undefined;
  limit?: number | undefined;
  includeDeleted?: boolean | undefined;
}

async function handler(context: ToolContext, args: SearchArgs) {
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

  const text = args.search?.trim() ?? "";
  if (args.search !== undefined && text === "") {
    // A search of nothing but spaces would match every row, which is not what was asked for
    // and is not what an empty `search` means either. Naming the field is what makes the
    // difference fixable in one turn.
    return toolFailure(
      invalidPayload(
        "`search` is blank. Omit it to list everything, or give something to match.",
        "search",
      ),
    );
  }

  const limit = args.limit ?? SEARCH_FOODS_DEFAULT_LIMIT;
  const page = await context.services.searchFoods({
    userId: context.identity.userId,
    text: text === "" ? null : text,
    barcode: args.barcode ?? null,
    source: args.source ?? null,
    afterKey,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  const last = page.foods.at(-1);
  return toolSuccess({
    foods: page.foods.map(foodView),
    nextCursor:
      page.hasMore && last !== undefined
        ? encodeListCursor(encodePairKey(last.payload.name, last.payload.id))
        : null,
    hasMore: page.hasMore,
    catalogue: {
      searched: args.source === undefined ? ["custom", "open_food_facts"] : [args.source],
      ciqualSearchable: false,
      matchedOn:
        "A fragment of the name or the brand, ignoring case. Accents are not folded on the server, so `creme` does not match `crème`; try the accented spelling as well.",
    },
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const searchFoodsTool: MueTool = {
  name: TOOL_NAME,
  title: "Search foods",
  description: [
    "Search the foods this account holds: the ones the person described themselves and the",
    "packaged products they scanned. Match on part of a name or a brand, on an exact barcode,",
    "or list everything in name order by leaving `search` out.",
    "",
    "This is how you find the `id` that `mue.create_food_log` and `mue.create_recipe` need.",
    "",
    "Two things to carry into what you say next. Mue's generic food catalogue lives on the",
    "phone and is not searchable here, so finding nothing does not prove the food is missing --",
    "check with the person before creating a duplicate. And an unknown nutrient comes back as",
    "null: a food with no fibre figure has an unknown fibre content, not none.",
  ].join("\n"),
  inputSchema,
  outputSchema: envelopeSchema(dataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:read"],
  handler: (context, args) => handler(context, args as SearchArgs),
};
