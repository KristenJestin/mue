import { z } from "zod";
import { UNCONSTRAINED_TEXT_MAX_LENGTH, referenceUnitSchema } from "./food";

/**
 * The `Recipe` aggregate of PRD_FOOD 21.2: *"la recette **avec** ses ingrédients, synchronisée
 * atomiquement. Une recette n'apparaît jamais sans ses ingrédients."*
 *
 * The ingredients are fields of the recipe and not an aggregate of their own, which is the same
 * decision `activity.ts` makes about a session's exercises and for the same reason: PRD_FOOD 21.3
 * says *"les ingrédients ne sont pas fusionnés ligne à ligne"* — the whole recipe is replaced by
 * the last accepted mutation. An ingredient id is therefore a position marker inside a snapshot,
 * never a merge key, and `RecipeDao.saveDetailWithMutation` deletes and reinserts the whole list
 * on every save, so it is not even stable across two writes.
 *
 * ## `foodName` is a snapshot, and it is load-bearing
 *
 * PRD_FOOD 21.2: *"Une recette peut référencer un aliment que le client n'a pas encore reçu. Le
 * client applique la recette et affiche l'ingrédient par son instantané de nom et de quantité
 * jusqu'à réception de l'aliment ; il ne rejette pas l'agrégat."* `recipe_ingredient` carries no
 * foreign key onto `food` precisely so that this is possible, so an ingredient whose food has not
 * arrived is a row that renders rather than a transaction that aborts.
 */

export const RECIPE_PAYLOAD_VERSION_1 = 1;

/** `RecipeType`. */
export const RECIPE_TYPES = ["breakfast", "main", "snack"] as const;

/** `Recipe.MIN_NAME_LENGTH` and `MAX_NAME_LENGTH`, which are the food's. */
export const RECIPE_NAME_MIN_LENGTH = 1;
export const RECIPE_NAME_MAX_LENGTH = 80;

/** `Recipe.BASE_SERVINGS_RANGE`. */
export const BASE_SERVINGS_MIN = 1;
export const BASE_SERVINGS_MAX = 12;

/** `Recipe.MIN_INGREDIENTS` and `MAX_INGREDIENTS`. */
export const INGREDIENTS_MIN = 1;
export const INGREDIENTS_MAX = 40;

/** `Recipe.MAX_STEPS` and `MAX_STEP_LENGTH`. */
export const STEPS_MAX = 30;
export const STEP_MAX_LENGTH = 500;

/** `Recipe.MAX_DESCRIPTION_LENGTH`. */
export const RECIPE_DESCRIPTION_MAX_LENGTH = 500;

/** `Recipe.PREP_TIME_MINUTES_RANGE`. */
export const PREP_TIME_MINUTES_MIN = 1;
export const PREP_TIME_MINUTES_MAX = 1_440;

/** `Quantity.INGREDIENT_MIN_THOUSANDTHS` and `INGREDIENT_MAX_THOUSANDTHS`: above 0, at most 5000. */
export const INGREDIENT_QUANTITY_MIN_THOUSANDTHS = 1;
export const INGREDIENT_QUANTITY_MAX_THOUSANDTHS = 5_000_000;

export const recipeTypeSchema = z.enum(RECIPE_TYPES).meta({
  id: "RecipeType",
  description: "Which meal a recipe belongs to (PRD_FOOD 8.3).",
});

export const recipeIngredientSchema = z
  .object({
    id: z.uuid(),
    foodId: z.uuid(),
    /** Thousandths of a gram or a millilitre, whichever `unit` names. */
    quantityThousandths: z
      .int()
      .min(INGREDIENT_QUANTITY_MIN_THOUSANDTHS)
      .max(INGREDIENT_QUANTITY_MAX_THOUSANDTHS),
    unit: referenceUnitSchema,
    position: z.int().min(0).max(INGREDIENTS_MAX),
    /**
     * The food's name as it was when the ingredient was written.
     *
     * Optional because `FoodPayloads.kt` omits it when the domain has none, and required by
     * nothing: a client that already holds the food reads the live name instead. It is what makes
     * an ingredient renderable before its food arrives (PRD_FOOD 21.2).
     */
    foodName: z.string().min(1).max(UNCONSTRAINED_TEXT_MAX_LENGTH).optional(),
  })
  .meta({
    id: "RecipeIngredient",
    description:
      "One ingredient, with a snapshot of its food's name so the recipe renders before the food arrives.",
  });

export type RecipeIngredient = z.infer<typeof recipeIngredientSchema>;

/**
 * A recipe and its ingredients.
 *
 * `steps` is `.optional()` and not a required empty array, because that is the shape already in
 * the outboxes: `RecipePayload.steps` defaults to the empty list and `SyncJson` does not encode
 * defaults, so a recipe with no steps has journalled no `steps` key at all. `ingredients` needs
 * no such treatment — `INGREDIENTS_MIN` is one, so the list is never equal to its default and is
 * always written.
 */
export const recipePayloadV1Schema = z
  .object({
    id: z.uuid(),
    name: z.string().min(RECIPE_NAME_MIN_LENGTH).max(RECIPE_NAME_MAX_LENGTH),
    type: recipeTypeSchema,
    baseServings: z.int().min(BASE_SERVINGS_MIN).max(BASE_SERVINGS_MAX),
    isFavourite: z.boolean(),
    ingredients: z.array(recipeIngredientSchema).min(INGREDIENTS_MIN).max(INGREDIENTS_MAX),
    description: z.string().min(1).max(RECIPE_DESCRIPTION_MAX_LENGTH).optional(),
    prepTimeMinutes: z.int().min(PREP_TIME_MINUTES_MIN).max(PREP_TIME_MINUTES_MAX).optional(),
    steps: z.array(z.string().min(1).max(STEP_MAX_LENGTH)).max(STEPS_MAX).optional(),
    imageRef: z.string().min(1).max(UNCONSTRAINED_TEXT_MAX_LENGTH).optional(),
  })
  .meta({
    id: "RecipePayloadV1",
    description:
      "A recipe with its ingredients, payload schema version 1. The list is never empty: PRD_FOOD 21.2 forbids a recipe appearing without it.",
  });

export type RecipePayloadV1 = z.infer<typeof recipePayloadV1Schema>;
