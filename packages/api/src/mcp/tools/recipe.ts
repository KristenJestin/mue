import {
  BASE_SERVINGS_MAX,
  BASE_SERVINGS_MIN,
  INGREDIENT_QUANTITY_MAX_THOUSANDTHS,
  INGREDIENT_QUANTITY_MIN_THOUSANDTHS,
  INGREDIENTS_MAX,
  INGREDIENTS_MIN,
  PREP_TIME_MINUTES_MAX,
  PREP_TIME_MINUTES_MIN,
  RECIPE_DESCRIPTION_MAX_LENGTH,
  RECIPE_NAME_MAX_LENGTH,
  RECIPE_NAME_MIN_LENGTH,
  RECIPE_PAYLOAD_VERSION_1,
  type RecipePayloadV1,
  recipeTypeSchema,
  referenceUnitSchema,
  STEP_MAX_LENGTH,
  STEPS_MAX,
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
  refuse,
  serverTimeShape,
  toThousandths,
} from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * Recipes: PRD_FOOD 21.5's `create_recipe`, `update_recipe` and `delete_recipe`, and section
 * 14.5's *"le serveur doit permettre à un agent de créer une recette complète"*.
 *
 * ## The ingredient list is replaced whole, and that is stated rather than hidden
 *
 * PRD_FOOD 21.3: *"les ingrédients ne sont pas fusionnés ligne à ligne"*. An ingredient id is a
 * position marker inside a snapshot -- `RecipeDao.saveDetailWithMutation` deletes and reinserts
 * the whole list on every save, so it is not even stable across two writes of the same recipe.
 * So `update_recipe` takes the *complete* list when it takes one at all, and the tool says so:
 * an agent that sent one ingredient meaning "add this" would delete the other seven, and the
 * only defence against that is a description that does not let it think otherwise.
 *
 * ## `foodName` is a snapshot the server fills in
 *
 * PRD_FOOD 21.2 lets a recipe name a food the receiving client has not got yet, and the client
 * renders the ingredient from this snapshot rather than rejecting the recipe. It is not an
 * input: a caller could only supply a name that disagrees with the food it points at, and the
 * server already holds the true one. When the food is unknown to this account the snapshot is
 * left absent -- which is the honest answer, and is why `unit` becomes required in that case.
 *
 * ## What section 14.5 asks for that this cannot yet carry
 *
 * *"informations nutritionnelles calculées"* with their provenance. `recipePayloadV1Schema` has
 * no nutrition fields: PRD_FOOD 13.1 derives a recipe's values from its ingredients on the
 * client, every time. There is therefore nothing on the wire for the server to compute *into*,
 * and inventing a field here would be inventing an aggregate. Recorded, not worked around.
 */

const CREATE_TOOL_NAME = "mue.create_recipe";
const UPDATE_TOOL_NAME = "mue.update_recipe";
const DELETE_TOOL_NAME = "mue.delete_recipe";

const ingredientInputSchema = z.object({
  foodId: z
    .uuid()
    .describe("The food this ingredient is. Use an identifier a food tool returned, never a name."),
  quantity: z
    .number()
    .min(INGREDIENT_QUANTITY_MIN_THOUSANDTHS / 1000)
    .max(INGREDIENT_QUANTITY_MAX_THOUSANDTHS / 1000)
    .describe(
      "How much of it the recipe uses, in `unit`. Above 0 and at most 5000 g or ml, for the whole recipe rather than per serving.",
    ),
  unit: referenceUnitSchema
    .optional()
    .describe(
      "`gram` or `millilitre`. Omit it and the food's own reference unit is used; it is required only when this server does not hold the food.",
    ),
});

const ingredientViewSchema = z.object({
  id: z.uuid().describe("Position marker inside this snapshot of the recipe, not a stable key."),
  foodId: z.uuid(),
  quantityGrams: z
    .number()
    .describe("How much the recipe uses, in the unit `unit` names, for the whole recipe."),
  unit: z.string(),
  position: z.int(),
  foodName: z
    .string()
    .nullable()
    .describe(
      "The food's name when the ingredient was written, so it renders before the food does.",
    ),
});

const recipeViewSchema = z.object({
  id: z.uuid(),
  name: z.string(),
  type: z.string().describe("`breakfast`, `main` or `snack`."),
  baseServings: z.int().describe("How many servings the quantities below make."),
  isFavourite: z.boolean(),
  ingredients: z.array(ingredientViewSchema),
  description: z.string().nullable(),
  prepTimeMinutes: z.int().nullable(),
  steps: z.array(z.string()).describe("The instructions in order. Empty when there are none."),
  ...metadataShape,
});

function recipeView(stored: StoredAggregate<RecipePayloadV1>): Record<string, unknown> {
  const { payload, meta } = stored;
  return {
    id: payload.id,
    name: payload.name,
    type: payload.type,
    baseServings: payload.baseServings,
    isFavourite: payload.isFavourite,
    ingredients: payload.ingredients.map((ingredient) => ({
      id: ingredient.id,
      foodId: ingredient.foodId,
      quantityGrams: ingredient.quantityThousandths / 1000,
      unit: ingredient.unit,
      position: ingredient.position,
      foodName: ingredient.foodName ?? null,
    })),
    description: payload.description ?? null,
    prepTimeMinutes: payload.prepTimeMinutes ?? null,
    steps: payload.steps ?? [],
    ...meta,
  };
}

interface IngredientArg {
  foodId: string;
  quantity: number;
  unit?: string | undefined;
}

interface RecipeArgs {
  id?: string | undefined;
  name?: string | undefined;
  type?: string | undefined;
  baseServings?: number | undefined;
  ingredients?: readonly IngredientArg[] | undefined;
  isFavourite?: boolean | undefined;
  description?: string | undefined;
  prepTimeMinutes?: number | undefined;
  steps?: readonly string[] | undefined;
  clear?: readonly ("description" | "prepTimeMinutes" | "steps")[] | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

/**
 * The caller's ingredients, resolved against the foods this account holds.
 *
 * Each error names its own dotted path -- `ingredients.2.unit` -- because a recipe of eight
 * ingredients with one bad line is exactly the case where "something was wrong" costs an agent
 * a whole round of guessing.
 */
async function resolveIngredients(
  context: ToolContext,
  given: readonly IngredientArg[],
): Promise<RecipePayloadV1["ingredients"] | ReturnType<typeof invalidPayload>> {
  const resolved: RecipePayloadV1["ingredients"][number][] = [];
  for (const [index, ingredient] of given.entries()) {
    const food = await context.services.getFood(context.identity.userId, ingredient.foodId);
    const unit = ingredient.unit ?? food?.payload.referenceUnit;
    if (unit === undefined) {
      return invalidPayload(
        "This server does not hold that food, so the unit cannot be taken from it. Give `unit`, or create the food first with `mue.create_food`.",
        `ingredients.${index}.unit`,
      );
    }
    const quantityThousandths = toThousandths(ingredient.quantity);
    if (
      quantityThousandths < INGREDIENT_QUANTITY_MIN_THOUSANDTHS ||
      quantityThousandths > INGREDIENT_QUANTITY_MAX_THOUSANDTHS
    ) {
      return invalidPayload(
        "An ingredient quantity is above 0 and at most 5000 g or ml.",
        `ingredients.${index}.quantity`,
      );
    }
    resolved.push({
      // Minted per write, as Room does: PRD_FOOD 21.3 replaces the list whole, so this is a
      // marker inside the snapshot and never a merge key.
      id: crypto.randomUUID(),
      foodId: ingredient.foodId,
      quantityThousandths,
      unit: unit as RecipePayloadV1["ingredients"][number]["unit"],
      position: index,
      // Absent rather than guessed when the food is unknown here: the receiving client will
      // show it by name as soon as the food reaches it.
      ...(food === null ? {} : { foodName: food.payload.name }),
    });
  }
  return resolved;
}

const CLEARABLE = ["description", "prepTimeMinutes", "steps"] as const;

const commonInputs = {
  name: z
    .string()
    .min(RECIPE_NAME_MIN_LENGTH)
    .max(RECIPE_NAME_MAX_LENGTH)
    .optional()
    .describe("What the person calls this recipe."),
  type: recipeTypeSchema
    .optional()
    .describe("Which meal it belongs to: `breakfast`, `main` or `snack`."),
  baseServings: z
    .int()
    .min(BASE_SERVINGS_MIN)
    .max(BASE_SERVINGS_MAX)
    .optional()
    .describe(
      `How many servings the ingredient quantities make, ${BASE_SERVINGS_MIN} to ${BASE_SERVINGS_MAX}. Ask the person rather than assuming one.`,
    ),
  isFavourite: z
    .boolean()
    .optional()
    .describe("Whether the person marked it a favourite. Defaults to false, which is neutral."),
  description: z
    .string()
    .min(1)
    .max(RECIPE_DESCRIPTION_MAX_LENGTH)
    .optional()
    .describe("A short description, in the person's own words. Leave it out when they gave none."),
  prepTimeMinutes: z
    .int()
    .min(PREP_TIME_MINUTES_MIN)
    .max(PREP_TIME_MINUTES_MAX)
    .optional()
    .describe("How long it takes to make, in minutes. Leave it out when they did not say."),
  steps: z
    .array(z.string().min(1).max(STEP_MAX_LENGTH))
    .max(STEPS_MAX)
    .optional()
    .describe(
      "The instructions, in order, one string per step. Leave it out when the person gave none; do not write instructions for them.",
    ),
  idempotencyKey: idempotencyKeyInput,
};

const createInputSchema = {
  ...commonInputs,
  ingredients: z
    .array(ingredientInputSchema)
    .min(INGREDIENTS_MIN)
    .max(INGREDIENTS_MAX)
    .optional()
    .describe(
      `Required. Every ingredient, with its quantity for the whole recipe. Between ${INGREDIENTS_MIN} and ${INGREDIENTS_MAX}: a recipe without ingredients is not something Mue can store.`,
    ),
};

const writeDataSchema = z.object({
  recipe: recipeViewSchema.describe("The recipe as it was stored, with its ingredients."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

async function createHandler(context: ToolContext, args: RecipeArgs) {
  for (const [field, message] of [
    [
      "name",
      "Give the name the person uses for this recipe. Ask them; the server will not name it.",
    ],
    [
      "type",
      "Say which meal the recipe is for: `breakfast`, `main` or `snack`. Ask the person rather than inferring it from the ingredients.",
    ],
    [
      "baseServings",
      "Say how many servings the quantities make. Ask the person; a serving count guessed from the ingredients would be wrong in every calculation that uses it.",
    ],
    [
      "ingredients",
      "Give the ingredients and their quantities. A recipe with none cannot be stored, and the server will not compose one.",
    ],
  ] as const) {
    if (args[field] === undefined) {
      return refuse(context, CREATE_TOOL_NAME, missingRequiredField(field, message));
    }
  }

  const ingredients = await resolveIngredients(context, args.ingredients as IngredientArg[]);
  if (!Array.isArray(ingredients)) return refuse(context, CREATE_TOOL_NAME, ingredients);

  const id = crypto.randomUUID();
  const payload: RecipePayloadV1 = {
    id,
    name: args.name as string,
    type: args.type as RecipePayloadV1["type"],
    baseServings: args.baseServings as number,
    // Not an invention: `false` is the state a recipe nobody starred is in, and the column
    // is not nullable. It is the neutral value, not a guess about what the person thinks.
    isFavourite: args.isFavourite ?? false,
    ingredients,
    ...(args.description === undefined ? {} : { description: args.description }),
    ...(args.prepTimeMinutes === undefined ? {} : { prepTimeMinutes: args.prepTimeMinutes }),
    ...(args.steps === undefined || args.steps.length === 0 ? {} : { steps: [...args.steps] }),
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: CREATE_TOOL_NAME,
    aggregateType: "recipe",
    aggregateId: id,
    op: "upsert",
    payloadSchemaVersion: RECIPE_PAYLOAD_VERSION_1,
    payload,
    baseRevision: null,
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;
  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId);
}

export const createRecipeTool: MueTool = {
  name: CREATE_TOOL_NAME,
  title: "Create a recipe",
  description: [
    "Save a complete recipe: its name, which meal it is for, how many servings it makes, its",
    "ingredients with their quantities, and its steps.",
    "",
    "Quantities are for the whole recipe, not per serving. Each ingredient names a food by its",
    "identifier -- create the food first with `mue.create_food` if Mue does not have it, so the",
    "recipe's nutrition adds up on the phone.",
    "",
    "Never invent a serving count or a step. If the person did not say how many servings it",
    "makes, ask: every per-serving figure Mue shows depends on it.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
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
  handler: (context, args) => createHandler(context, args as RecipeArgs),
};

const updateInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the recipe to change, as a read tool returned it."),
  ...commonInputs,
  ingredients: z
    .array(ingredientInputSchema)
    .min(INGREDIENTS_MIN)
    .max(INGREDIENTS_MAX)
    .optional()
    .describe(
      "The COMPLETE new ingredient list, replacing the stored one entirely. Mue does not merge ingredients line by line, so sending one ingredient removes all the others. Omit this to leave the ingredients untouched.",
    ),
  clear: z
    .array(z.enum(CLEARABLE))
    .max(CLEARABLE.length)
    .optional()
    .describe(
      "Fields the person asked to remove: `description`, `prepTimeMinutes` or `steps`. Leaving a field out of the call keeps it; this is the only way to remove one.",
    ),
  expectedRevision: expectedRevisionInput,
};

async function updateHandler(context: ToolContext, args: RecipeArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the recipe to change. The server will not guess which recipe was meant.",
      ),
    );
  }

  const stored = await context.services.getRecipe(context.identity.userId, args.id);
  if (stored === null || stored.meta.deletedAt !== null) {
    return refuse(context, UPDATE_TOOL_NAME, notFound("recipe", args.id, "recipe"));
  }

  let ingredients = stored.payload.ingredients;
  if (args.ingredients !== undefined) {
    const resolved = await resolveIngredients(context, args.ingredients);
    if (!Array.isArray(resolved)) return refuse(context, UPDATE_TOOL_NAME, resolved);
    ingredients = resolved;
  }

  const cleared = new Set(args.clear ?? []);
  const description = cleared.has("description")
    ? undefined
    : (args.description ?? stored.payload.description);
  const prepTimeMinutes = cleared.has("prepTimeMinutes")
    ? undefined
    : (args.prepTimeMinutes ?? stored.payload.prepTimeMinutes);
  const steps = cleared.has("steps")
    ? undefined
    : args.steps !== undefined
      ? [...args.steps]
      : stored.payload.steps;

  const payload: RecipePayloadV1 = {
    id: stored.payload.id,
    name: args.name ?? stored.payload.name,
    type: (args.type ?? stored.payload.type) as RecipePayloadV1["type"],
    baseServings: args.baseServings ?? stored.payload.baseServings,
    isFavourite: args.isFavourite ?? stored.payload.isFavourite,
    ingredients,
    ...(description === undefined ? {} : { description }),
    ...(prepTimeMinutes === undefined ? {} : { prepTimeMinutes }),
    ...(steps === undefined || steps.length === 0 ? {} : { steps }),
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPDATE_TOOL_NAME,
    aggregateType: "recipe",
    aggregateId: args.id,
    op: "upsert",
    payloadSchemaVersion: RECIPE_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;
  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId);
}

export const updateRecipeTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Update a recipe",
  description: [
    "Change a recipe the person already has, including one they wrote themselves.",
    "",
    "Send only what changed. `ingredients` is the exception and the trap: it replaces the whole",
    "list, because Mue does not merge ingredients one by one. To add a single ingredient, read",
    "the recipe, append to its list, and send all of them back. Omit `ingredients` entirely to",
    "leave them alone.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: updateInputSchema,
  outputSchema: envelopeSchema(writeDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // The ingredient list is replaced whole rather than merged, which can remove ingredients
    // the caller did not name. That is what `destructiveHint` is for.
    destructiveHint: true,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["nutrition:write"],
  handler: (context, args) => updateHandler(context, args as RecipeArgs),
};

const deleteInputSchema = {
  id: z.uuid().optional().describe("Required. The identifier of the recipe to delete."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const deleteDataSchema = z.object({
  id: z.uuid().describe("The recipe that was deleted."),
  deleted: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  revision: z.string().describe("The revision the tombstone was written at."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

async function deleteHandler(context: ToolContext, args: RecipeArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the recipe to delete. The server will not choose one.",
      ),
    );
  }

  const stored = await context.services.getRecipe(context.identity.userId, args.id);
  if (stored === null) {
    return refuse(context, DELETE_TOOL_NAME, notFound("recipe", args.id, "recipe"));
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: DELETE_TOOL_NAME,
    aggregateType: "recipe",
    aggregateId: args.id,
    op: "delete",
    payloadSchemaVersion: RECIPE_PAYLOAD_VERSION_1,
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

export const deleteRecipeTool: MueTool = {
  name: DELETE_TOOL_NAME,
  title: "Delete a recipe",
  description: [
    "Delete a recipe, with its ingredients and its steps.",
    "",
    "Meals already logged from it are not touched: each logged line carries its own copy of what",
    "was eaten. What is lost is the recipe itself, including any instructions the person wrote.",
    "",
    "Ask before you call it unless the person asked for it in as many words.",
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
  handler: (context, args) => deleteHandler(context, args as RecipeArgs),
};

async function readBack(
  context: ToolContext,
  id: string,
  status: "applied" | "duplicate",
  mutationId: string,
) {
  const current = await context.services.getRecipe(context.identity.userId, id);
  if (current === null) throw new Error("recipes lost a row between apply and read");
  return toolSuccess({
    recipe: recipeView(current),
    created: status === "applied",
    mutationId,
    serverTime: new Date().toISOString(),
  });
}
