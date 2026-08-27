import {
  CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1,
  type CustomExerciseDefinitionPayloadV1,
  equipmentTypeSchema,
  MAX_EXERCISE_NAME_LENGTH,
  trackingModeSchema,
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
import {
  applyWrite,
  baseRevisionOf,
  cursorInput,
  expectedRevisionInput,
  freshnessShape,
  idempotencyKeyInput,
  includeDeletedInput,
  limitInput,
  metadataShape,
  mutationIdFor,
  notFound,
  refuse,
  serverTimeShape,
} from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * Personal exercise definitions: section 14.2's two read tools and section 14.3's three
 * write ones.
 *
 * ## `isCustom` is not an input, and cannot be
 *
 * PRD section 10.1 marks the seventeen definitions Mue ships *"Synchronisé: Non"* — they are
 * versioned reference data every phone already holds, not personal data. So
 * `customExerciseDefinitionPayloadV1Schema` carries no `isCustom` field at all, and there is
 * no value of it that describes a provided definition. These tools inherit that: an agent
 * cannot rename or replace one of Mue's own exercises, because the shape it would have to
 * send does not exist.
 *
 * ## The delete tool, and why it will refuse
 *
 * Section 14.3 lists `delete_custom_exercise` and PRD_ACTIVITIES 9.2 says *"une définition
 * personnalisée est conservée définitivement"*. Both cannot be satisfied, and the domain rule
 * wins: `customExerciseDefinitionHandler` refuses a tombstone, because `strength_exercises`
 * holds a `RESTRICT` foreign key onto the definition and a phone applying such a change would
 * abort the transaction carrying its own cursor — it would stop synchronising for good, on a
 * page it can never get past.
 *
 * The tool is shipped anyway, and it goes down the same path as every other write so the
 * refusal is the domain's own structured error rather than a second opinion held here. That is
 * the difference between an agent that can tell the person *why* Mue keeps a definition and an
 * agent that gets `unknown tool` and guesses.
 */

const LIST_TOOL_NAME = "mue.list_custom_exercises";
const GET_TOOL_NAME = "mue.get_custom_exercise";
const CREATE_TOOL_NAME = "mue.create_custom_exercise";
const UPDATE_TOOL_NAME = "mue.update_custom_exercise";
const DELETE_TOOL_NAME = "mue.delete_custom_exercise";

const LIST_DEFAULT_LIMIT = 50;
const LIST_MAX_LIMIT = 200;

const exerciseShape = {
  id: z.uuid().describe("The definition's stable identifier."),
  name: z.string().describe("The name the person gave it."),
  trackingMode: trackingModeSchema.describe("Which measures a set of this exercise carries."),
  equipment: equipmentTypeSchema
    .nullable()
    .describe("The gear it uses, or null when the person did not say."),
};

const exerciseViewSchema = z.object({ ...exerciseShape, ...metadataShape });

// --- mue.list_custom_exercises ---------------------------------------------------------

const listInputSchema = {
  cursor: cursorInput,
  limit: limitInput(LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT),
  includeDeleted: includeDeletedInput,
};

const listDataSchema = z.object({
  exercises: z.array(exerciseViewSchema),
  nextCursor: z
    .string()
    .nullable()
    .describe("Pass to `cursor` for the next page. Null when this page is the last."),
  hasMore: z.boolean(),
  ...freshnessShape,
});

interface ListArgs {
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

  const limit = args.limit ?? LIST_DEFAULT_LIMIT;
  const page = await context.services.listCustomExercises({
    userId: context.identity.userId,
    afterKey,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  const last = page.exercises.at(-1);
  return toolSuccess({
    exercises: page.exercises.map((entry) => ({ ...entry.payload, ...entry.meta })),
    nextCursor:
      page.hasMore && last !== undefined
        ? // Ordered by the folded name, which is what the uniqueness index is on, with the
          // identifier breaking a tie between two rows that fold to the same name.
          encodeListCursor(encodePairKey(last.payload.name.trim().toLowerCase(), last.payload.id))
        : null,
    hasMore: page.hasMore,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const listCustomExercisesTool: MueTool = {
  name: LIST_TOOL_NAME,
  title: "List personal exercises",
  description: [
    "Read the exercises the person defined themselves, in name order, one page at a time.",
    "",
    "These are their own definitions only. The exercises Mue ships with are reference data and",
    "are not listed here; they are on the phone already and cannot be changed from outside it.",
  ].join("\n"),
  inputSchema: listInputSchema,
  outputSchema: envelopeSchema(listDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["activity:read"],
  handler: (context, args) => listHandler(context, args as ListArgs),
};

// --- mue.get_custom_exercise ------------------------------------------------------------

const getInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The definition's identifier, as `list_custom_exercises` returned it."),
  includeDeleted: includeDeletedInput,
};

const getDataSchema = z.object({
  exercise: exerciseViewSchema
    .nullable()
    .describe("The definition, or null when this account holds none with that identifier."),
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
        "Give the identifier of the definition to read. Find it with `mue.list_custom_exercises`.",
      ),
    );
  }
  const stored = await context.services.getCustomExercise(
    context.identity.userId,
    args.id,
    args.includeDeleted ?? false,
  );
  return toolSuccess({
    exercise: stored === null ? null : { ...stored.payload, ...stored.meta },
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getCustomExerciseTool: MueTool = {
  name: GET_TOOL_NAME,
  title: "Get one personal exercise",
  description: [
    "Read one exercise the person defined themselves, by its identifier.",
    "",
    "`exercise` is null when this account holds no definition with that identifier. Use",
    "`mue.list_custom_exercises` to find one rather than constructing an identifier.",
  ].join("\n"),
  inputSchema: getInputSchema,
  outputSchema: envelopeSchema(getDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["activity:read"],
  handler: (context, args) => getHandler(context, args as GetArgs),
};

// --- mue.create_custom_exercise -----------------------------------------------------------

const createInputSchema = {
  name: z
    .string()
    .min(1)
    .max(MAX_EXERCISE_NAME_LENGTH)
    .optional()
    .describe(
      "Required. What the person calls this exercise. A name already in use, ignoring case and surrounding spaces, means they meant the existing exercise: read it instead of creating a second one.",
    ),
  trackingMode: trackingModeSchema
    .optional()
    .describe(
      "Required. Which measures a set of this exercise records: `weight_and_reps`, `reps_only`, `duration` or `weight_and_duration`. Ask the person rather than assuming.",
    ),
  equipment: equipmentTypeSchema
    .optional()
    .describe("Optional. The gear it uses. Leave it out when the person did not say."),
  idempotencyKey: idempotencyKeyInput,
};

const writeDataSchema = z.object({
  exercise: exerciseViewSchema.describe("The definition as it was stored."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface CreateArgs {
  name?: string | undefined;
  trackingMode?: string | undefined;
  equipment?: string | undefined;
  idempotencyKey?: string | undefined;
}

async function createHandler(context: ToolContext, args: CreateArgs) {
  if (args.name === undefined) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      missingRequiredField(
        "name",
        "Give the name the person uses for this exercise. Ask them; the server will not name it for them.",
      ),
    );
  }
  if (args.trackingMode === undefined) {
    return refuse(
      context,
      CREATE_TOOL_NAME,
      missingRequiredField(
        "trackingMode",
        "Say what a set of this exercise records: weight and repetitions, repetitions alone, a duration, or weight and duration. Ask the person; guessing it makes every set of theirs the wrong shape.",
      ),
    );
  }

  const id = crypto.randomUUID();
  const payload: CustomExerciseDefinitionPayloadV1 = {
    id,
    name: args.name,
    trackingMode: args.trackingMode as CustomExerciseDefinitionPayloadV1["trackingMode"],
    // Absent stays absent: null is how the column holds "the person did not say", not a
    // piece of gear the server picked.
    equipment: (args.equipment ?? null) as CustomExerciseDefinitionPayloadV1["equipment"],
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: CREATE_TOOL_NAME,
    aggregateType: "customExerciseDefinition",
    aggregateId: id,
    op: "upsert",
    payloadSchemaVersion: CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1,
    payload,
    baseRevision: null,
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId);
}

export const createCustomExerciseTool: MueTool = {
  name: CREATE_TOOL_NAME,
  title: "Create a personal exercise",
  description: [
    "Define an exercise the person invented or that Mue does not ship, so their strength sessions",
    "can record sets of it.",
    "",
    "A definition is kept for good once it exists, so create one only when the person actually",
    "named an exercise they train. Check `mue.list_custom_exercises` first: a name already there,",
    "ignoring case and spaces, is the same exercise and a second one cannot take the name.",
    "",
    "Never guess `trackingMode`. If you do not know what a set of it records, ask.",
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
  scopes: ["activity:write"],
  handler: (context, args) => createHandler(context, args as CreateArgs),
};

// --- mue.update_custom_exercise ------------------------------------------------------------

const updateInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the definition to change, as a read tool returned it."),
  name: z
    .string()
    .min(1)
    .max(MAX_EXERCISE_NAME_LENGTH)
    .optional()
    .describe("The corrected name. Omit to leave it as it is."),
  trackingMode: trackingModeSchema
    .optional()
    .describe(
      "The corrected tracking mode. Changing it does not rewrite the sets already recorded, so only change it when the person says the exercise itself was set up wrongly.",
    ),
  equipment: equipmentTypeSchema
    .optional()
    .describe("The corrected gear. Omit to leave it as it is."),
  clearEquipment: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove the gear. Omitting `equipment` keeps it.",
    ),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

interface UpdateArgs {
  id?: string | undefined;
  name?: string | undefined;
  trackingMode?: string | undefined;
  equipment?: string | undefined;
  clearEquipment?: boolean | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

async function updateHandler(context: ToolContext, args: UpdateArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the definition to change. Find it with `mue.list_custom_exercises`.",
      ),
    );
  }
  if (
    args.name === undefined &&
    args.trackingMode === undefined &&
    args.equipment === undefined &&
    args.clearEquipment !== true
  ) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        "An update states at least one of `name`, `trackingMode`, `equipment` or `clearEquipment`.",
        "name",
      ),
    );
  }

  const stored = await context.services.getCustomExercise(context.identity.userId, args.id, false);
  if (stored === null) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      notFound("customExerciseDefinition", args.id, "personal exercise definition"),
    );
  }

  const payload: CustomExerciseDefinitionPayloadV1 = {
    id: stored.payload.id,
    name: args.name ?? stored.payload.name,
    trackingMode: (args.trackingMode ??
      stored.payload.trackingMode) as CustomExerciseDefinitionPayloadV1["trackingMode"],
    equipment: (args.equipment !== undefined
      ? args.equipment
      : args.clearEquipment === true
        ? null
        : stored.payload.equipment) as CustomExerciseDefinitionPayloadV1["equipment"],
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPDATE_TOOL_NAME,
    aggregateType: "customExerciseDefinition",
    aggregateId: args.id,
    op: "upsert",
    payloadSchemaVersion: CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return readBack(context, outcome.result.aggregateId, outcome.result.status, mutationId);
}

export const updateCustomExerciseTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Update a personal exercise",
  description: [
    "Rename an exercise the person defined, or correct what a set of it records.",
    "",
    "Send only what changed; anything you leave out keeps its stored value. A name already used",
    "by another definition, ignoring case and spaces, is refused rather than merged: the two are",
    "the same exercise to Mue and only one may hold the name.",
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
  scopes: ["activity:write"],
  handler: (context, args) => updateHandler(context, args as UpdateArgs),
};

// --- mue.delete_custom_exercise --------------------------------------------------------------

const deleteInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the definition the person asked to remove."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const deleteDataSchema = z.object({
  id: z.uuid().describe("The definition that was deleted."),
  deleted: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  revision: z.string().describe("The revision the tombstone was written at."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface DeleteArgs {
  id?: string | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

async function deleteHandler(context: ToolContext, args: DeleteArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the definition the person asked to remove. Find it with `mue.list_custom_exercises`.",
      ),
    );
  }

  const stored = await context.services.getCustomExercise(context.identity.userId, args.id, true);
  if (stored === null) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      notFound("customExerciseDefinition", args.id, "personal exercise definition"),
    );
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  // Submitted rather than short-circuited here. The rule that refuses it lives in
  // `customExerciseDefinitionHandler` and is the same rule a phone would meet; holding a
  // second copy of it in this file is how the two would one day disagree.
  const outcome = await applyWrite(context, {
    toolName: DELETE_TOOL_NAME,
    aggregateType: "customExerciseDefinition",
    aggregateId: args.id,
    op: "delete",
    payloadSchemaVersion: CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1,
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

export const deleteCustomExerciseTool: MueTool = {
  name: DELETE_TOOL_NAME,
  title: "Delete a personal exercise",
  description: [
    "Ask Mue to remove an exercise the person defined.",
    "",
    "Mue keeps a personal exercise definition permanently, including when no session uses it any",
    "more, because sessions already recorded point at it. So this call is refused, with an error",
    "that says why: tell the person their exercise stays, and offer to rename it with",
    "`mue.update_custom_exercise` if what they wanted was for it to stop appearing under that",
    "name.",
    "",
    "The tool exists so that request has an answer. It is not a way to remove one.",
  ].join("\n"),
  inputSchema: deleteInputSchema,
  outputSchema: envelopeSchema(deleteDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // Section 14.6: a deletion is annotated destructive. It is annotated on what the tool
    // asks for, not on what the domain currently allows -- a client's confirmation policy
    // is decided from the annotation before the call, and the refusal comes after it.
    destructiveHint: true,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["activity:write", "data:delete"],
  handler: (context, args) => deleteHandler(context, args as DeleteArgs),
};

async function readBack(
  context: ToolContext,
  id: string,
  status: "applied" | "duplicate",
  mutationId: string,
) {
  const current = await context.services.getCustomExercise(context.identity.userId, id, true);
  if (current === null) throw new Error("custom_exercises lost a row between apply and read");
  return toolSuccess({
    exercise: { ...current.payload, ...current.meta },
    created: status === "applied",
    mutationId,
    serverTime: new Date().toISOString(),
  });
}
