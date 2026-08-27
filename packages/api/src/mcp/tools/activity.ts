import {
  ACTIVITY_SESSION_PAYLOAD_VERSION_1,
  type ActivitySessionPayloadV1,
  localDateSchema,
} from "@mue/contracts";
import { z } from "zod";
import {
  activityEnvironmentSchema,
  activityMovementSchema,
  activitySessionViewSchema,
  localTimeSchema,
  MAX_CUSTOM_MOVEMENT_NAME_LENGTH,
  MAX_NOTES_LENGTH,
  PERCEIVED_EFFORT_MAX,
  PERCEIVED_EFFORT_MIN,
  SESSION_MAX_SECONDS,
  SESSION_MIN_SECONDS,
} from "../activity";
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
  fromDateInput,
  idempotencyKeyInput,
  includeDeletedInput,
  limitInput,
  mutationIdFor,
  notFound,
  refuse,
  serverTimeShape,
  toDateInput,
} from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * The activity tools of sections 14.2 and 14.3, less `create_activity`, which shipped first.
 *
 * ## Why an update reads before it writes
 *
 * Section 10.2 makes a session an atomic aggregate and `opaque.ts` makes it *opaque*: the
 * payload replaces the row whole, because a session's children carry ids Room re-mints on
 * every save and so cannot be merge keys. An update tool therefore cannot send a patch --
 * there is nothing on the server that would know how to apply one.
 *
 * What it can do is read the stored session, put the caller's fields on top of it, and submit
 * the result. That is the same three-way position `update_health_profile` is in, and it has the
 * same consequence for section 14.4: a field the person did not mention is carried through
 * unchanged rather than nulled, and removing one is an explicit `clear…` of its own.
 */

const LIST_TOOL_NAME = "mue.list_activities";
const GET_TOOL_NAME = "mue.get_activity";
const STATISTICS_TOOL_NAME = "mue.get_activity_statistics";
const UPDATE_TOOL_NAME = "mue.update_activity";
const DELETE_TOOL_NAME = "mue.delete_activity";

const LIST_DEFAULT_LIMIT = 25;
const LIST_MAX_LIMIT = 100;

// --- mue.list_activities --------------------------------------------------------------

const listInputSchema = {
  from: fromDateInput,
  to: toDateInput,
  movement: activityMovementSchema
    .optional()
    .describe("Keep only sessions of this movement. Omit for every movement."),
  cursor: cursorInput,
  limit: limitInput(LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT),
  includeDeleted: includeDeletedInput,
};

const listDataSchema = z.object({
  activities: z.array(activitySessionViewSchema),
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
  movement?: string | undefined;
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

  const limit = args.limit ?? LIST_DEFAULT_LIMIT;
  const page = await context.services.listActivities({
    userId: context.identity.userId,
    from: args.from ?? null,
    to: args.to ?? null,
    movement: args.movement ?? null,
    afterKey,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  const last = page.activities.at(-1);
  return toolSuccess({
    activities: page.activities,
    // The keyset is the pair the ordering is on. A day holds more than one session, so a
    // cursor on the date alone would silently drop the rest of the day a page ended inside.
    nextCursor:
      page.hasMore && last !== undefined
        ? encodeListCursor(encodePairKey(last.startedOn, last.id))
        : null,
    hasMore: page.hasMore,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const listActivitiesTool: MueTool = {
  name: LIST_TOOL_NAME,
  title: "List activity sessions",
  description: [
    "Read finished activity sessions, most recent day first, one page at a time.",
    "",
    "With no `from` and no `to` this walks the entire history: keep calling it with the",
    "`nextCursor` you were given until `hasMore` is false. No time window is imposed.",
    "",
    "Each session is complete: its movement, day, start time, duration, effort and notes, plus",
    "any metrics, equipment and strength exercises it carries. `lastAndroidSyncAt` says how",
    "recent this copy is -- a session recorded on the phone after that instant is not here yet.",
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

// --- mue.get_activity -------------------------------------------------------------------

const getInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe(
      "Required. The session's identifier, as `list_activities` or a creation returned it. Never invent one.",
    ),
  includeDeleted: includeDeletedInput,
};

const getDataSchema = z.object({
  activity: activitySessionViewSchema
    .nullable()
    .describe("The session, or null when this account holds none with that identifier."),
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
        "Give the identifier of the session to read. Find it with `mue.list_activities` rather than guessing one.",
      ),
    );
  }
  const activity = await context.services.getActivity(
    context.identity.userId,
    args.id,
    args.includeDeleted ?? false,
  );
  return toolSuccess({
    activity,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getActivityTool: MueTool = {
  name: GET_TOOL_NAME,
  title: "Get one activity session",
  description: [
    "Read one finished activity session in full, by its identifier.",
    "",
    "`activity` is null when this account holds no session with that identifier -- say so rather",
    "than offering a different session. Use `mue.list_activities` to find an identifier.",
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

// --- mue.get_activity_statistics --------------------------------------------------------

const statisticsInputSchema = { from: fromDateInput, to: toDateInput };

const statisticsDataSchema = z.object({
  sessionCount: z.int().describe("How many sessions the range holds."),
  totalDurationSeconds: z
    .int()
    .describe("The sum of the sessions' durations, in seconds. Zero when there are none."),
  firstDate: localDateSchema.nullable().describe("The earliest day in range that has a session."),
  lastDate: localDateSchema.nullable().describe("The latest day in range that has a session."),
  byMovement: z
    .array(
      z.object({
        movement: z.string(),
        sessionCount: z.int(),
        totalDurationSeconds: z.int(),
      }),
    )
    .describe("The same totals per movement, longest first. Movements with no session are absent."),
  method: z
    .string()
    .describe(
      "How these numbers were obtained, so a derived figure is never mistaken for a recorded one.",
    ),
  ...freshnessShape,
});

interface StatisticsArgs {
  from?: string | undefined;
  to?: string | undefined;
}

async function statisticsHandler(context: ToolContext, args: StatisticsArgs) {
  if (args.from !== undefined && args.to !== undefined && args.from > args.to) {
    return toolFailure(invalidPayload("`from` is later than `to`, so no day can match.", "from"));
  }
  const statistics = await context.services.activityStatistics(
    context.identity.userId,
    args.from ?? null,
    args.to ?? null,
  );
  return toolSuccess({
    ...statistics,
    // Section 14.5: a computed value keeps its method. Note what is *not* here: there is no
    // energy total. An estimated energy is a metric an author recorded on a session
    // (PRD_ACTIVITIES 8.3); a figure derived from a duration would be a value nobody stated.
    method:
      "Counted at read time from the sessions in range, tombstones excluded. Durations are the ones recorded; no energy is derived from them.",
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getActivityStatisticsTool: MueTool = {
  name: STATISTICS_TOOL_NAME,
  title: "Summarise activity sessions",
  description: [
    "Totals for the activity history: how many sessions, how long they lasted altogether, the",
    "span they cover, and the same broken down by movement.",
    "",
    "With no `from` and no `to` this covers the whole history. There is no energy figure: Mue",
    "records an energy only when someone entered one, and a number derived from a duration would",
    "be an estimate nobody made.",
  ].join("\n"),
  inputSchema: statisticsInputSchema,
  outputSchema: envelopeSchema(statisticsDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["activity:read"],
  handler: (context, args) => statisticsHandler(context, args as StatisticsArgs),
};

// --- mue.update_activity ----------------------------------------------------------------

const updateInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the session to change, as a read tool returned it."),
  movement: activityMovementSchema
    .optional()
    .describe("The corrected movement. Omit to leave it as it is."),
  startedOn: localDateSchema
    .optional()
    .describe("The corrected day, YYYY-MM-DD. Omit to leave it as it is."),
  durationMinutes: z
    .int()
    .min(1)
    .max(Math.floor(SESSION_MAX_SECONDS / 60))
    .optional()
    .describe("The corrected duration in minutes. Omit to leave it as it is."),
  durationSeconds: z
    .int()
    .min(1)
    .max(SESSION_MAX_SECONDS)
    .optional()
    .describe("Use instead of `durationMinutes` when the person gave seconds."),
  startedAtTime: localTimeSchema
    .optional()
    .describe("The corrected local start time, 24-hour HH:MM. Omit to leave it as it is."),
  customMovementName: z
    .string()
    .min(1)
    .max(MAX_CUSTOM_MOVEMENT_NAME_LENGTH)
    .optional()
    .describe("Required when the session's movement is or becomes `other`, and refused otherwise."),
  environment: activityEnvironmentSchema
    .optional()
    .describe("The corrected environment. `unknown` is a real answer, not an absence."),
  perceivedEffort: z
    .int()
    .min(PERCEIVED_EFFORT_MIN)
    .max(PERCEIVED_EFFORT_MAX)
    .optional()
    .describe("The corrected effort on a 1-to-10 scale. Omit to leave it as it is."),
  notes: z
    .string()
    .min(1)
    .max(MAX_NOTES_LENGTH)
    .optional()
    .describe("The corrected note, in the words of the person. Omit to leave it as it is."),
  clearStartedAtTime: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove the start time. Omitting `startedAtTime` keeps it; it does not remove it.",
    ),
  clearPerceivedEffort: z
    .boolean()
    .optional()
    .describe("Set true only when the person asked to remove the recorded effort."),
  clearNotes: z
    .boolean()
    .optional()
    .describe("Set true only when the person asked to remove the note."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const updateDataSchema = z.object({
  activity: activitySessionViewSchema.describe("The session as it now stands."),
  changed: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface UpdateArgs {
  id?: string | undefined;
  movement?: string | undefined;
  startedOn?: string | undefined;
  durationMinutes?: number | undefined;
  durationSeconds?: number | undefined;
  startedAtTime?: string | undefined;
  customMovementName?: string | undefined;
  environment?: string | undefined;
  perceivedEffort?: number | undefined;
  notes?: string | undefined;
  clearStartedAtTime?: boolean | undefined;
  clearPerceivedEffort?: boolean | undefined;
  clearNotes?: boolean | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

/** Stated, cleared, or not mentioned. The three are distinct, and only the first two act. */
function edited<T>(given: T | undefined, clear: boolean | undefined, stored: T | null): T | null {
  if (given !== undefined) return given;
  if (clear === true) return null;
  return stored;
}

async function updateHandler(context: ToolContext, args: UpdateArgs) {
  if (args.id === undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      missingRequiredField(
        "id",
        "Give the identifier of the session to change. Find it with `mue.list_activities`; the server will not guess which session was meant.",
      ),
    );
  }
  if (args.durationMinutes !== undefined && args.durationSeconds !== undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        "Give either `durationMinutes` or `durationSeconds`, not both.",
        "durationSeconds",
      ),
    );
  }

  const stored = await context.services.getActivityPayload(context.identity.userId, args.id);
  if (stored === null || stored.meta.deletedAt !== null) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      notFound("activitySession", args.id, "activity session"),
    );
  }

  const movement = (args.movement ??
    stored.payload.movement) as ActivitySessionPayloadV1["movement"];
  const customMovementName =
    args.customMovementName ??
    // A movement changed *away* from `other` cannot keep the name the old movement needed:
    // the contract refuses a custom name on any other movement, so carrying it forward would
    // make an edit the person asked for fail on a field they never mentioned.
    (movement === "other" ? stored.payload.customMovementName : null);

  if (movement === "other" && customMovementName === null) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      missingRequiredField(
        "customMovementName",
        "`movement` is `other`, so the session needs a name. Ask the person what to call it.",
      ),
    );
  }
  if (movement !== "other" && args.customMovementName !== undefined) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        "`customMovementName` belongs to `movement: other` only.",
        "customMovementName",
      ),
    );
  }

  const durationSeconds =
    args.durationSeconds ??
    (args.durationMinutes === undefined
      ? stored.payload.durationSeconds
      : args.durationMinutes * 60);
  if (
    (args.durationMinutes !== undefined || args.durationSeconds !== undefined) &&
    (durationSeconds < SESSION_MIN_SECONDS || durationSeconds > SESSION_MAX_SECONDS)
  ) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        `A session lasts between ${SESSION_MIN_SECONDS} and ${SESSION_MAX_SECONDS} seconds.`,
        args.durationSeconds === undefined ? "durationMinutes" : "durationSeconds",
      ),
    );
  }

  const payload: ActivitySessionPayloadV1 = {
    ...stored.payload,
    movement,
    customMovementName,
    environment: (args.environment ??
      stored.payload.environment) as ActivitySessionPayloadV1["environment"],
    startedOn: args.startedOn ?? stored.payload.startedOn,
    startedAtTime: edited(
      args.startedAtTime,
      args.clearStartedAtTime,
      stored.payload.startedAtTime,
    ),
    durationSeconds,
    perceivedEffort: edited(
      args.perceivedEffort,
      args.clearPerceivedEffort,
      stored.payload.perceivedEffort,
    ),
    notes: edited(args.notes, args.clearNotes, stored.payload.notes),
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPDATE_TOOL_NAME,
    aggregateType: "activitySession",
    aggregateId: args.id,
    op: "upsert",
    payloadSchemaVersion: ACTIVITY_SESSION_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  const current = await context.services.getActivity(
    context.identity.userId,
    outcome.result.aggregateId,
    true,
  );
  if (current === null) throw new Error("activity_sessions lost a row between apply and read");

  return toolSuccess({
    activity: current,
    changed: outcome.result.status === "applied",
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const updateActivityTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Update an activity session",
  description: [
    "Correct a session that is already recorded. Send only the fields the person changed.",
    "",
    "Anything you leave out keeps its stored value, so there is no need to repeat what you did",
    "not hear and no reason to guess it. Removing a value the session has -- its start time, its",
    "effort, its note -- is a separate, explicit request: `clearStartedAtTime` and its siblings.",
    "",
    "The change is final and reaches the phone at its next synchronisation. Retrying after a lost",
    "response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: updateInputSchema,
  outputSchema: envelopeSchema(updateDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // It replaces fields of one session; the version it replaced stays in the journal.
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["activity:write"],
  handler: (context, args) => updateHandler(context, args as UpdateArgs),
};

// --- mue.delete_activity ----------------------------------------------------------------

const deleteInputSchema = {
  id: z
    .uuid()
    .optional()
    .describe("Required. The identifier of the session to delete, as a read tool returned it."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const deleteDataSchema = z.object({
  id: z.uuid().describe("The session that was deleted."),
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
        "Give the identifier of the session to delete. Find it with `mue.list_activities`; the server will not choose one.",
      ),
    );
  }

  // Tombstones included, which is what keeps a retry idempotent: after the first call the
  // row exists and is deleted, so the replay reaches the journal and gets the stored result
  // rather than a "not found" for a session it just removed.
  const stored = await context.services.getActivityPayload(context.identity.userId, args.id);
  if (stored === null) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      notFound("activitySession", args.id, "activity session"),
    );
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: DELETE_TOOL_NAME,
    aggregateType: "activitySession",
    aggregateId: args.id,
    op: "delete",
    payloadSchemaVersion: ACTIVITY_SESSION_PAYLOAD_VERSION_1,
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

export const deleteActivityTool: MueTool = {
  name: DELETE_TOOL_NAME,
  title: "Delete an activity session",
  description: [
    "Delete a recorded activity session. The deletion reaches the phone at its next",
    "synchronisation and is not reversed by an older copy still sitting on it.",
    "",
    "This removes the person's record of that session, with its metrics, equipment and exercises.",
    "Ask before you call it unless they asked for it in as many words. To fix a mistake in a",
    "session, use `mue.update_activity` instead: deleting and recreating loses its history.",
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
  scopes: ["activity:write", "data:delete"],
  handler: (context, args) => deleteHandler(context, args as DeleteArgs),
};
