import {
  RECIPE_NAME_MAX_LENGTH,
  recipeTypeSchema,
  type FoodPayloadV1,
  type RecipePayloadV1,
} from "@mue/contracts";
import { perServing, recipeTotal, unresolvedIngredientIds, type Nutrients } from "@mue/domain";
import { z } from "zod";
import { decodeListCursor, encodeListCursor, encodePairKey, InvalidCursorError } from "../cursor";
import { envelopeSchema, missingRequiredField, toolFailure, toolSuccess } from "../errors";
import type { StoredAggregate } from "../services";
import {
  computedNutrientsSchema,
  computedNutrientsView,
  NO_UNKNOWN_CONTRIBUTIONS,
  provenanceShape,
  sameUnknownContributions,
} from "./nutrition-view";
import { recipeView, recipeViewSchema } from "./recipe";
import { cursorInput, freshnessShape, includeDeletedInput, limitInput, notFound } from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * Reading recipes: PRD_FOOD 21.5's `get_recipe` and `list_recipes`.
 *
 * ## The nutrition here is computed, and it is the only place the server computes a recipe's
 *
 * `recipePayloadV1Schema` has no nutrition fields, and `recipe.ts` records why: PRD_FOOD 13.1
 * derives a recipe's values from its ingredients, every time, and storing them would be
 * storing a value that goes stale the moment a food is corrected. So the per-serving block
 * is assembled at read time from the ingredient quantities and the foods this account holds,
 * through `@mue/domain` — the same functions `NutritionMath.kt` mirrors, and no arithmetic in
 * this file.
 *
 * ## An ingredient whose food is missing makes the recipe unknown, not lighter
 *
 * PRD_FOOD 21.2 lets a recipe name a food the client has not received; the ingredient still
 * renders from its `foodName` snapshot. On the server the same case is a food this account
 * does not hold — deleted, or never synchronised — and the honest answer is that the
 * recipe's values cannot be worked out, not that they are smaller. Dropping the term would
 * report a lighter recipe with no sign that anything was missing, which is the single worst
 * thing this module can do. `unknownFrom` names the ingredients responsible.
 */

export const LIST_RECIPES_DEFAULT_LIMIT = 25;
export const LIST_RECIPES_MAX_LIMIT = 100;

const GET_TOOL_NAME = "mue.get_recipe";
const LIST_TOOL_NAME = "mue.list_recipes";

// --- the computed block both tools carry ---------------------------------------------------

const nutritionSchema = z.object({
  perServing: computedNutrientsSchema.describe(
    "PRD_FOOD 13.1: the whole recipe divided by `baseServings`. This is what one serving is worth.",
  ),
  wholeRecipe: computedNutrientsSchema.describe(
    "The strict sum of every ingredient's contribution, for the quantities as written.",
  ),
  unresolvedIngredientIds: z
    .array(z.string())
    .describe(
      "Ingredients whose food this server does not hold, so their contribution could not be worked out. Non-empty means every nutrient above is unknown -- the recipe is not lighter, it is unmeasured. Empty is the ordinary case.",
    ),
  provenance: z
    .object(provenanceShape)
    .describe("Where these values came from and how, as PRD_FOOD 21.5 requires."),
});

interface RecipeNutrition {
  readonly perServing: Nutrients;
  readonly wholeRecipe: Nutrients;
  readonly unresolved: readonly string[];
}

function computeNutrition(
  recipe: RecipePayloadV1,
  foods: ReadonlyMap<string, FoodPayloadV1>,
): RecipeNutrition {
  const whole = recipeTotal(recipe, foods);
  return {
    wholeRecipe: whole,
    perServing: perServing(whole, recipe.baseServings),
    unresolved: unresolvedIngredientIds(recipe, foods),
  };
}

function nutritionView(recipe: RecipePayloadV1, nutrition: RecipeNutrition) {
  // Every metric is unknown for the same reason -- an ingredient that could not be resolved
  // contributes nothing to any of the five. When nothing is unresolved but a *food* is simply
  // missing a nutrient, the arithmetic has already made that metric null and there is no
  // ingredient to blame, so the list is empty and the `known: false` stands on its own.
  const unknownFrom =
    nutrition.unresolved.length === 0
      ? NO_UNKNOWN_CONTRIBUTIONS
      : sameUnknownContributions(nutrition.unresolved);
  return {
    perServing: computedNutrientsView(nutrition.perServing, unknownFrom),
    wholeRecipe: computedNutrientsView(nutrition.wholeRecipe, unknownFrom),
    unresolvedIngredientIds: [...nutrition.unresolved],
    provenance: {
      computedBy: "server",
      method: "strictSum",
      rule: "PRD_FOOD 13.1",
      source: `The ${recipe.ingredients.length} ingredient quantities of this recipe, each scaled against the per-100 values of the food it names, then divided by ${recipe.baseServings} serving(s). Nothing is stored: a corrected food changes this answer and changes no journal line.`,
      approximate: true,
      contributionCount: recipe.ingredients.length,
    },
  };
}

/** Every food a page of recipes names, read once. */
async function foodsFor(
  context: ToolContext,
  recipes: readonly StoredAggregate<RecipePayloadV1>[],
) {
  const ids = recipes.flatMap((recipe) =>
    recipe.payload.ingredients.map((ingredient) => ingredient.foodId),
  );
  return context.services.foodsByIds(context.identity.userId, ids);
}

// --- mue.get_recipe ------------------------------------------------------------------------

const getInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The recipe's identifier, as `mue.list_recipes` returned it."),
  includeDeleted: includeDeletedInput,
};

const getDataSchema = z.object({
  recipe: recipeViewSchema.describe("The recipe with its ingredients, in order."),
  nutrition: nutritionSchema,
  ...freshnessShape,
});

interface GetArgs {
  id?: string | undefined;
  includeDeleted?: boolean | undefined;
}

async function getHandler(context: ToolContext, args: GetArgs) {
  if (args.id === undefined) {
    return toolFailure(
      missingRequiredField(
        "id",
        "Give the identifier of the recipe to read. Find it with `mue.list_recipes`; the server will not match a name.",
      ),
    );
  }

  const stored = await context.services.getRecipe(context.identity.userId, args.id);
  if (stored === null || (stored.meta.deletedAt !== null && args.includeDeleted !== true)) {
    return toolFailure(notFound("recipe", args.id, "recipe"));
  }

  const foods = await foodsFor(context, [stored]);
  return toolSuccess({
    recipe: recipeView(stored),
    nutrition: nutritionView(stored.payload, computeNutrition(stored.payload, foods)),
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getRecipeTool: MueTool = {
  name: GET_TOOL_NAME,
  title: "Read one recipe",
  description: [
    "Read a whole recipe: its ingredients with their quantities, its steps, and what one",
    "serving of it is worth.",
    "",
    "The nutritional values are computed here and now from the ingredients -- Mue stores no",
    "total for a recipe, so correcting a food changes this answer and changes no journal line",
    "that was ever written from it.",
    "",
    "If `unresolvedIngredientIds` is not empty, this server does not hold the food behind one",
    "of the ingredients, and every value is therefore unknown. Say the recipe cannot be worked",
    "out; do not report the smaller figure that ignoring those ingredients would give.",
  ].join("\n"),
  inputSchema: getInputSchema,
  outputSchema: envelopeSchema(getDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:read"],
  handler: (context, args) => getHandler(context, args as GetArgs),
};

// --- mue.list_recipes ----------------------------------------------------------------------

const listInputSchema = {
  type: recipeTypeSchema
    .optional()
    .describe("Only recipes of this type: `breakfast`, `main` or `snack`. Omit for all three."),
  favouritesOnly: z
    .boolean()
    .optional()
    .describe("True for the recipes the person marked as favourites. Defaults to false."),
  search: z
    .string()
    .min(1)
    .max(RECIPE_NAME_MAX_LENGTH)
    .optional()
    .describe("Match part of the recipe's name, ignoring case. Omit for every recipe."),
  cursor: cursorInput,
  limit: limitInput(LIST_RECIPES_DEFAULT_LIMIT, LIST_RECIPES_MAX_LIMIT),
  includeDeleted: includeDeletedInput,
};

const listDataSchema = z.object({
  recipes: z
    .array(z.object({ recipe: recipeViewSchema, nutrition: nutritionSchema }))
    .describe("The matching recipes, in name order, each with its computed per-serving values."),
  nextCursor: z
    .string()
    .nullable()
    .describe("Pass to `cursor` for the next page. Null when this page is the last."),
  hasMore: z.boolean(),
  ...freshnessShape,
});

interface ListArgs {
  type?: string | undefined;
  favouritesOnly?: boolean | undefined;
  search?: string | undefined;
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

  const limit = args.limit ?? LIST_RECIPES_DEFAULT_LIMIT;
  const page = await context.services.listRecipes({
    userId: context.identity.userId,
    type: args.type ?? null,
    favouritesOnly: args.favouritesOnly ?? false,
    text: args.search ?? null,
    afterKey,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  // One query for every food the page names, rather than one per ingredient: a page of
  // twenty-five recipes is otherwise a hundred round trips to say the same thing.
  const foods = await foodsFor(context, page.recipes);
  const last = page.recipes.at(-1);
  return toolSuccess({
    recipes: page.recipes.map((stored) => ({
      recipe: recipeView(stored),
      nutrition: nutritionView(stored.payload, computeNutrition(stored.payload, foods)),
    })),
    nextCursor:
      page.hasMore && last !== undefined
        ? encodeListCursor(encodePairKey(last.payload.name, last.payload.id))
        : null,
    hasMore: page.hasMore,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const listRecipesTool: MueTool = {
  name: LIST_TOOL_NAME,
  title: "List saved recipes",
  description: [
    "Read the recipes this account holds, in name order, filterable by type and by favourite.",
    "Each one comes back complete, with its ingredients and what one serving is worth.",
    "",
    "This is how you find the `id` that `mue.plan_meal` and `mue.create_food_log` need.",
    "",
    "A recipe carries no badge for who wrote it, and none is available: PRD_FOOD 11 says a",
    "recipe is neither badged nor filtered by the origin of its writing, so do not describe one",
    "as yours.",
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
