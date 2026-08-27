import {
  COOKED_RATIO_MAX_THOUSANDTHS,
  COOKED_RATIO_MIN_THOUSANDTHS,
  ENERGY_PER_100_MAX_MILLI_KCAL,
  FOOD_BRAND_MAX_LENGTH,
  FOOD_NAME_MAX_LENGTH,
  FOOD_NAME_MIN_LENGTH,
  FOOD_PAYLOAD_VERSION_1,
  type FoodPayloadV1,
  MACRO_PER_100_MAX_MILLIGRAMS,
  referenceUnitSchema,
  UNCONSTRAINED_TEXT_MAX_LENGTH,
  USUAL_SERVING_MAX_THOUSANDTHS,
  USUAL_SERVING_MIN_THOUSANDTHS,
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
 * A custom food: PRD_FOOD 21.5's `create_food`, `update_food` and `delete_food`.
 *
 * ## Everything a person says is a decimal; everything Mue stores is an integer
 *
 * PRD_FOOD 8.6 and 15 fix the units: thousandths of a kilocalorie, milligrams, thousandths of
 * a gram. Nothing on this wire is a float, for the same reason `weightCg` is not: a float
 * entering the database is a value that can be rounded a second time, differently, on a
 * different client. So the tool takes the decimals a person actually says -- *"52 kcal, 0.3 g
 * of protein"* -- and does the one conversion, here, once.
 *
 * ## An unknown nutrient is absent, and stays absent
 *
 * PRD_FOOD 13.1 forbids inventing a value, and a zero is an invention: *"0 g of fat"* is a
 * claim about the food, while a missing key is a statement about what is known. Every nutrient
 * is therefore optional, absence is preserved on the way in and on the way back out, and an
 * update that does not mention a nutrient leaves the stored one exactly as it is -- including
 * leaving it absent.
 *
 * ## Why `source` is not an input
 *
 * PRD_FOOD 21.1 marks the Ciqual catalogue *"Synchronisé: Non"* and the contract's
 * `foodSourceSchema` has no member for it, so no tool can push one -- PRD_FOOD 21.4's one
 * reserved limit needs no check of its own. Of the two remaining sources, `open_food_facts`
 * describes a *copy of a scanned product*, which is something the phone makes from a barcode
 * scan and a network fetch this server does not perform. So a food an agent creates is
 * `custom`, which is what it is; an update keeps whatever the stored food already was, so
 * correcting a scanned product does not quietly re-label its provenance.
 */

const CREATE_TOOL_NAME = "mue.create_food";
const UPDATE_TOOL_NAME = "mue.update_food";
const DELETE_TOOL_NAME = "mue.delete_food";

const MAX_ENERGY_KCAL_PER_100 = ENERGY_PER_100_MAX_MILLI_KCAL / 1000;
const MAX_MACRO_GRAMS_PER_100 = MACRO_PER_100_MAX_MILLIGRAMS / 1000;

const foodViewShape = {
  id: z.uuid().describe("The food's stable identifier."),
  name: z.string(),
  source: z
    .string()
    .describe("`custom` for a food someone described, `open_food_facts` for a scanned product."),
  referenceUnit: z.string().describe("`gram` or `millilitre`: what the per-100 values are per."),
  rawLabel: z.string().describe("What the reference state is called, such as `Raw`."),
  cookedLabel: z.string().describe("What the cooked state is called, such as `Cooked`."),
  energyKcalPer100: z.number().nullable().describe("Kilocalories per 100. Null when unknown."),
  proteinGramsPer100: z
    .number()
    .nullable()
    .describe("Grams of protein per 100. Null when unknown."),
  carbsGramsPer100: z
    .number()
    .nullable()
    .describe("Grams of carbohydrate per 100. Null when unknown."),
  fatGramsPer100: z.number().nullable().describe("Grams of fat per 100. Null when unknown."),
  fibreGramsPer100: z.number().nullable().describe("Grams of fibre per 100. Null when unknown."),
  brand: z.string().nullable(),
  barcode: z.string().nullable(),
  servingLabel: z.string().nullable().describe("What one usual serving is called."),
  servingGrams: z
    .number()
    .nullable()
    .describe("How much one usual serving weighs, in the food's reference unit."),
  cookedRatio: z
    .number()
    .nullable()
    .describe("Cooked weight divided by raw weight, when the person recorded one."),
  ...metadataShape,
};

const foodViewSchema = z.object(foodViewShape);

const nullableThousandths = (value: number | undefined): number | null =>
  value === undefined ? null : value / 1000;

function foodView(stored: StoredAggregate<FoodPayloadV1>): Record<string, unknown> {
  const { payload, meta } = stored;
  return {
    id: payload.id,
    name: payload.name,
    source: payload.source,
    referenceUnit: payload.referenceUnit,
    rawLabel: payload.rawLabel,
    cookedLabel: payload.cookedLabel,
    energyKcalPer100: nullableThousandths(payload.energyMilliKcal),
    proteinGramsPer100: nullableThousandths(payload.proteinMilligrams),
    carbsGramsPer100: nullableThousandths(payload.carbsMilligrams),
    fatGramsPer100: nullableThousandths(payload.fatMilligrams),
    fibreGramsPer100: nullableThousandths(payload.fibreMilligrams),
    brand: payload.brand ?? null,
    barcode: payload.barcode ?? null,
    servingLabel: payload.servingLabel ?? null,
    servingGrams: nullableThousandths(payload.servingThousandths),
    cookedRatio: nullableThousandths(payload.cookedRatioThousandths),
    ...meta,
  };
}

const nutrientInputs = {
  energyKcalPer100: z
    .number()
    .min(0)
    .max(MAX_ENERGY_KCAL_PER_100)
    .optional()
    .describe(
      `Kilocalories per 100 ${"g or ml"} of the reference state, at most ${MAX_ENERGY_KCAL_PER_100}. Leave it out when it is unknown: an absent value is honest, a zero is a claim.`,
    ),
  proteinGramsPer100: z
    .number()
    .min(0)
    .max(MAX_MACRO_GRAMS_PER_100)
    .optional()
    .describe("Grams of protein per 100. Leave it out when unknown; never send 0 for unknown."),
  carbsGramsPer100: z
    .number()
    .min(0)
    .max(MAX_MACRO_GRAMS_PER_100)
    .optional()
    .describe("Grams of carbohydrate per 100. Leave it out when unknown."),
  fatGramsPer100: z
    .number()
    .min(0)
    .max(MAX_MACRO_GRAMS_PER_100)
    .optional()
    .describe("Grams of fat per 100. Leave it out when unknown."),
  fibreGramsPer100: z
    .number()
    .min(0)
    .max(MAX_MACRO_GRAMS_PER_100)
    .optional()
    .describe("Grams of fibre per 100. Leave it out when unknown."),
};

const descriptorInputs = {
  brand: z
    .string()
    .min(1)
    .max(FOOD_BRAND_MAX_LENGTH)
    .optional()
    .describe("The brand, for a packaged product. Leave it out for a generic food."),
  barcode: z
    .string()
    .min(8)
    .max(14)
    .regex(/^\d+$/)
    .optional()
    .describe("The product barcode, digits only, when the person read one out."),
  servingLabel: z
    .string()
    .min(1)
    .max(UNCONSTRAINED_TEXT_MAX_LENGTH)
    .optional()
    .describe("What one usual serving is called, such as `1 slice`. Needs `servingGrams` with it."),
  servingGrams: z
    .number()
    .min(USUAL_SERVING_MIN_THOUSANDTHS / 1000)
    .max(USUAL_SERVING_MAX_THOUSANDTHS / 1000)
    .optional()
    .describe("How much one usual serving weighs, in the reference unit. Needs `servingLabel`."),
  cookedRatio: z
    .number()
    .min(COOKED_RATIO_MIN_THOUSANDTHS / 1000)
    .max(COOKED_RATIO_MAX_THOUSANDTHS / 1000)
    .optional()
    .describe(
      "Cooked weight divided by raw weight, when the person measured both. Leave it out otherwise; the server does not estimate it.",
    ),
};

const CLEARABLE = [
  "energyKcalPer100",
  "proteinGramsPer100",
  "carbsGramsPer100",
  "fatGramsPer100",
  "fibreGramsPer100",
  "brand",
  "barcode",
  "servingLabel",
  "servingGrams",
  "cookedRatio",
] as const;

const clearInput = z
  .array(z.enum(CLEARABLE))
  .max(CLEARABLE.length)
  .optional()
  .describe(
    "Fields the person asked to remove, named one by one. Leaving a field out of the call keeps it; this is the only way to make it unknown again.",
  );

const createInputSchema = {
  name: z
    .string()
    .min(FOOD_NAME_MIN_LENGTH)
    .max(FOOD_NAME_MAX_LENGTH)
    .optional()
    .describe("Required. What the person calls this food."),
  referenceUnit: referenceUnitSchema
    .optional()
    .describe(
      "`gram` for a solid, `millilitre` for a liquid. Defaults to `gram`, which is what the app defaults to.",
    ),
  rawLabel: z
    .string()
    .min(1)
    .max(UNCONSTRAINED_TEXT_MAX_LENGTH)
    .optional()
    .describe("What the reference state is called. Defaults to `Raw`, the app's own default."),
  cookedLabel: z
    .string()
    .min(1)
    .max(UNCONSTRAINED_TEXT_MAX_LENGTH)
    .optional()
    .describe("What the cooked state is called. Defaults to `Cooked`, the app's own default."),
  ...nutrientInputs,
  ...descriptorInputs,
  idempotencyKey: idempotencyKeyInput,
};

const writeDataSchema = z.object({
  food: foodViewSchema.describe("The food as it was stored."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface FoodArgs {
  id?: string | undefined;
  name?: string | undefined;
  referenceUnit?: string | undefined;
  rawLabel?: string | undefined;
  cookedLabel?: string | undefined;
  energyKcalPer100?: number | undefined;
  proteinGramsPer100?: number | undefined;
  carbsGramsPer100?: number | undefined;
  fatGramsPer100?: number | undefined;
  fibreGramsPer100?: number | undefined;
  brand?: string | undefined;
  barcode?: string | undefined;
  servingLabel?: string | undefined;
  servingGrams?: number | undefined;
  cookedRatio?: number | undefined;
  clear?: readonly (typeof CLEARABLE)[number][] | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

/** `RawLabel` and `CookedLabel` defaults, from Android's `Food` companion. */
const DEFAULT_RAW_LABEL = "Raw";
const DEFAULT_COOKED_LABEL = "Cooked";

/**
 * A usual serving is a label *and* a weight, or neither.
 *
 * `FoodValidation.USUAL_SERVING_PAIR_ERROR` states it, and it is checked here rather than on
 * the wire for the reason `food.ts` gives in the contract: the payload's bounds answer "can
 * this have been stored?", and an Open Food Facts copy already in an outbox may carry half a
 * pair. A tool writing a *new* food is not in that position and has no reason to write half.
 */
function servingPairError(label: string | null, grams: number | null) {
  if ((label === null) === (grams === null)) return null;
  return invalidPayload(
    "A usual serving needs both `servingLabel` and `servingGrams`, or neither.",
    label === null ? "servingLabel" : "servingGrams",
  );
}

function optionalThousandths(
  value: number | undefined,
  stored: number | undefined,
  cleared: boolean,
): number | undefined {
  if (value !== undefined) return toThousandths(value);
  if (cleared) return undefined;
  return stored;
}

function optionalText(
  value: string | undefined,
  stored: string | undefined,
  cleared: boolean,
): string | undefined {
  if (value !== undefined) return value;
  if (cleared) return undefined;
  return stored;
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

function buildFoodPayload(
  id: string,
  args: FoodArgs,
  stored: FoodPayloadV1 | null,
): FoodPayloadV1 | ReturnType<typeof invalidPayload> {
  const cleared = new Set(args.clear ?? []);

  const servingLabel = optionalText(
    args.servingLabel,
    stored?.servingLabel,
    cleared.has("servingLabel"),
  );
  const servingThousandths = optionalThousandths(
    args.servingGrams,
    stored?.servingThousandths,
    cleared.has("servingGrams"),
  );
  const pairError = servingPairError(servingLabel ?? null, servingThousandths ?? null);
  if (pairError !== null) return pairError;

  const base = {
    id,
    name: args.name ?? stored?.name ?? "",
    // An update never re-labels provenance: a scanned product stays a scanned product.
    source: stored?.source ?? ("custom" as const),
    referenceUnit: (args.referenceUnit ??
      stored?.referenceUnit ??
      "gram") as FoodPayloadV1["referenceUnit"],
    rawLabel: args.rawLabel ?? stored?.rawLabel ?? DEFAULT_RAW_LABEL,
    cookedLabel: args.cookedLabel ?? stored?.cookedLabel ?? DEFAULT_COOKED_LABEL,
  };

  return withOptional(base, {
    energyMilliKcal: optionalThousandths(
      args.energyKcalPer100,
      stored?.energyMilliKcal,
      cleared.has("energyKcalPer100"),
    ),
    proteinMilligrams: optionalThousandths(
      args.proteinGramsPer100,
      stored?.proteinMilligrams,
      cleared.has("proteinGramsPer100"),
    ),
    carbsMilligrams: optionalThousandths(
      args.carbsGramsPer100,
      stored?.carbsMilligrams,
      cleared.has("carbsGramsPer100"),
    ),
    fatMilligrams: optionalThousandths(
      args.fatGramsPer100,
      stored?.fatMilligrams,
      cleared.has("fatGramsPer100"),
    ),
    fibreMilligrams: optionalThousandths(
      args.fibreGramsPer100,
      stored?.fibreMilligrams,
      cleared.has("fibreGramsPer100"),
    ),
    brand: optionalText(args.brand, stored?.brand, cleared.has("brand")),
    barcode: optionalText(args.barcode, stored?.barcode, cleared.has("barcode")),
    ...(servingLabel === undefined ? {} : { servingLabel }),
    ...(servingThousandths === undefined ? {} : { servingThousandths }),
    cookedRatioThousandths: optionalThousandths(
      args.cookedRatio,
      stored?.cookedRatioThousandths,
      cleared.has("cookedRatio"),
    ),
    // The stored values that have no input of their own are carried through untouched: a
    // product copied from Open Food Facts keeps the identifiers that say where it came from.
    ...(stored?.sourceId === undefined ? {} : { sourceId: stored.sourceId }),
    ...(stored?.sourceVersion === undefined ? {} : { sourceVersion: stored.sourceVersion }),
    ...(stored?.imageRef === undefined ? {} : { imageRef: stored.imageRef }),
  }) as FoodPayloadV1;
}

async function createHandler(context: ToolContext, args: FoodArgs) {
  if (args.name === undefined) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      missingRequiredField(
        "name",
        "Give the name the person uses for this food. Ask them; the server will not name it for them.",
      ),
    );
  }

  const id = crypto.randomUUID();
  const payload = buildFoodPayload(id, args, null);
  if (!("id" in payload)) return refuse(context, CREATE_TOOL_NAME, payload);

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: CREATE_TOOL_NAME,
    aggregateType: "food",
    aggregateId: id,
    op: "upsert",
    payloadSchemaVersion: FOOD_PAYLOAD_VERSION_1,
    payload,
    baseRevision: null,
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;
  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId);
}

export const createFoodTool: MueTool = {
  name: CREATE_TOOL_NAME,
  title: "Create a custom food",
  description: [
    "Describe a food Mue does not already know, so it can be logged and used in recipes.",
    "",
    "Every nutrient is optional and every one you leave out stays unknown. Do not send 0 for a",
    "value you were not told: 0 g of fat is a claim about the food, and it will be shown to the",
    "person as one. Give only what they read off the packet or told you.",
    "",
    "Values are per 100 g, or per 100 ml when `referenceUnit` is `millilitre`.",
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
  handler: (context, args) => createHandler(context, args as FoodArgs),
};

const updateInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the food to change, as a read tool returned it."),
  name: z
    .string()
    .min(FOOD_NAME_MIN_LENGTH)
    .max(FOOD_NAME_MAX_LENGTH)
    .optional()
    .describe("The corrected name. Omit to leave it as it is."),
  referenceUnit: referenceUnitSchema
    .optional()
    .describe(
      "The corrected reference unit. Changing it does not convert the stored per-100 values, so only change it when the food was set up wrongly.",
    ),
  rawLabel: z
    .string()
    .min(1)
    .max(UNCONSTRAINED_TEXT_MAX_LENGTH)
    .optional()
    .describe(
      "The corrected name of the reference state, such as `Raw`. Omit to leave it as it is.",
    ),
  cookedLabel: z
    .string()
    .min(1)
    .max(UNCONSTRAINED_TEXT_MAX_LENGTH)
    .optional()
    .describe(
      "The corrected name of the cooked state, such as `Cooked`. Omit to leave it as it is.",
    ),
  ...nutrientInputs,
  ...descriptorInputs,
  clear: clearInput,
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

async function updateHandler(context: ToolContext, args: FoodArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the food to change. The server will not guess which food was meant.",
      ),
    );
  }

  const stored = await context.services.getFood(context.identity.userId, args.id);
  if (stored === null || stored.meta.deletedAt !== null) {
    return refuse(context, UPDATE_TOOL_NAME, notFound("food", args.id, "food"));
  }

  const payload = buildFoodPayload(args.id, args, stored.payload);
  if (!("id" in payload)) return refuse(context, UPDATE_TOOL_NAME, payload);

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPDATE_TOOL_NAME,
    aggregateType: "food",
    aggregateId: args.id,
    op: "upsert",
    payloadSchemaVersion: FOOD_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;
  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId);
}

export const updateFoodTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Update a custom food",
  description: [
    "Correct a food the person already has. Send only the fields that changed.",
    "",
    "Anything you leave out keeps its stored value, including a nutrient that is currently",
    "unknown -- omitting it does not set it to zero. To make a value unknown again, name it in",
    "`clear`; that is the only way, and it should reflect something the person actually said.",
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
  handler: (context, args) => updateHandler(context, args as FoodArgs),
};

const deleteInputSchema = {
  id: z.uuid().optional().describe("Required. The identifier of the food to delete."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const deleteDataSchema = z.object({
  id: z.uuid().describe("The food that was deleted."),
  deleted: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  revision: z.string().describe("The revision the tombstone was written at."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

async function deleteHandler(context: ToolContext, args: FoodArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the food to delete. The server will not choose one.",
      ),
    );
  }

  const stored = await context.services.getFood(context.identity.userId, args.id);
  if (stored === null) {
    return refuse(context, DELETE_TOOL_NAME, notFound("food", args.id, "food"));
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: DELETE_TOOL_NAME,
    aggregateType: "food",
    aggregateId: args.id,
    op: "delete",
    payloadSchemaVersion: FOOD_PAYLOAD_VERSION_1,
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

export const deleteFoodTool: MueTool = {
  name: DELETE_TOOL_NAME,
  title: "Delete a custom food",
  description: [
    "Delete a food from the person's own catalogue.",
    "",
    "Meals already logged from it are not touched: each logged line carries its own copy of what",
    "was eaten, so the history stays correct and stays readable. A recipe that used this food",
    "keeps the ingredient and shows it by the name it had.",
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
  handler: (context, args) => deleteHandler(context, args as FoodArgs),
};

async function readBack(
  context: ToolContext,
  id: string,
  status: "applied" | "duplicate",
  mutationId: string,
) {
  const current = await context.services.getFood(context.identity.userId, id);
  if (current === null) throw new Error("foods lost a row between apply and read");
  return toolSuccess({
    food: foodView(current),
    created: status === "applied",
    mutationId,
    serverTime: new Date().toISOString(),
  });
}
