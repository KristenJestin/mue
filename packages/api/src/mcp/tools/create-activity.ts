import { instantSchema, localDateSchema, pastEventDay } from "@mue/contracts";
import { z } from "zod";
import {
  activityEnvironmentSchema,
  activityMovementSchema,
  activitySessionViewSchema,
  AGENT_ACTIVITY_SOURCE,
  localTimeSchema,
  MAX_CUSTOM_MOVEMENT_NAME_LENGTH,
  MAX_NOTES_LENGTH,
  PERCEIVED_EFFORT_MAX,
  PERCEIVED_EFFORT_MIN,
  SESSION_MAX_SECONDS,
  SESSION_MIN_SECONDS,
} from "../activity";
import {
  envelopeSchema,
  invalidPayload,
  missingRequiredField,
  toolFailure,
  toolSuccess,
} from "../errors";
import { mutationIdFromIdempotencyKey } from "../idempotency";
import type { MueTool, ToolContext } from "./types";

/**
 * Section 14.4, whose own worked example is the acceptance test:
 *
 *   "Hier, j'ai couru pendant 35 minutes a partir de 18 h."
 *
 * That sentence carries a movement, a date, a start time and a duration, which is
 * exactly the minimum this tool needs, and what it produces is a *final*
 * `ActivitySession` -- no draft, no second confirmation on the phone.
 *
 * The fields the domain cannot do without are optional in the JSON Schema and required
 * in the handler. That is deliberate. The SDK rejects a schema-invalid call before the
 * handler runs and reports it as a validation string; section 14.4 asks instead for a
 * structured business error the agent can act on -- one that names the field so the
 * agent can ask the person for it and call again. Their descriptions still open with
 * "Required", so the catalogue is no less precise for it.
 */
const inputSchema = {
  movement: activityMovementSchema
    .optional()
    .describe(
      "Required. What the person did. Use `other` only when nothing else fits, and then give `customMovementName`.",
    ),
  startedOn: localDateSchema
    .optional()
    .describe(
      "Required. The calendar day the session belongs to, YYYY-MM-DD, in the local time of the person. A session is something that happened, so this day cannot be in the future. Resolve words like 'yesterday' yourself; the server will not guess a date.",
    ),
  durationMinutes: z
    .int()
    .min(1)
    .max(Math.floor(SESSION_MAX_SECONDS / 60))
    .optional()
    .describe("Required, unless `durationSeconds` is given. How long the session lasted."),
  durationSeconds: z
    .int()
    .min(SESSION_MIN_SECONDS)
    .max(SESSION_MAX_SECONDS)
    .optional()
    .describe("Use instead of `durationMinutes` when the person gave seconds."),
  startedAtTime: localTimeSchema
    .optional()
    .describe(
      "Optional local start time, 24-hour HH:MM. Omit it when the person did not say; never assume midnight.",
    ),
  customMovementName: z
    .string()
    .min(1)
    .max(MAX_CUSTOM_MOVEMENT_NAME_LENGTH)
    .optional()
    .describe("Required when `movement` is `other`, and refused otherwise."),
  environment: activityEnvironmentSchema
    .optional()
    .describe(
      "Optional. `unknown` is a real answer and the default: it records that the person did not say where.",
    ),
  perceivedEffort: z
    .int()
    .min(PERCEIVED_EFFORT_MIN)
    .max(PERCEIVED_EFFORT_MAX)
    .optional()
    .describe("Optional effort on a 1-to-10 scale, only if the person gave one."),
  notes: z
    .string()
    .min(1)
    .max(MAX_NOTES_LENGTH)
    .optional()
    .describe("Optional free note in the words of the person."),
  idempotencyKey: z
    .uuid()
    .optional()
    .describe(
      "Optional UUID identifying this creation. Send the same one when retrying: the session is created once and the retry returns the first result.",
    ),
};

const dataSchema = z.object({
  activity: activitySessionViewSchema.describe("The session as it was stored. Final, not a draft."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  serverTime: instantSchema,
});

interface CreateActivityArgs {
  movement?: string | undefined;
  startedOn?: string | undefined;
  durationMinutes?: number | undefined;
  durationSeconds?: number | undefined;
  startedAtTime?: string | undefined;
  customMovementName?: string | undefined;
  environment?: string | undefined;
  perceivedEffort?: number | undefined;
  notes?: string | undefined;
  idempotencyKey?: string | undefined;
}

/**
 * Section 14.4 in one function, so neither half can be lost in a later edit: the server
 * never fabricates a mandatory value, and an optional one stays absent rather than
 * being invented.
 */
function validate(args: CreateActivityArgs) {
  if (args.movement === undefined) {
    return missingRequiredField(
      "movement",
      "Say what the person did. Ask them if you do not know; do not choose one for them.",
    );
  }
  if (args.startedOn === undefined) {
    return missingRequiredField(
      "startedOn",
      "Give the calendar day of the session as YYYY-MM-DD. Ask the person if the day is unclear; the server will not assume today.",
    );
  }
  if (args.durationMinutes === undefined && args.durationSeconds === undefined) {
    return missingRequiredField(
      "durationMinutes",
      "Give how long the session lasted, in `durationMinutes` or `durationSeconds`. Ask the person; the server will not estimate it.",
    );
  }
  if (args.durationMinutes !== undefined && args.durationSeconds !== undefined) {
    return invalidPayload(
      "Give either `durationMinutes` or `durationSeconds`, not both.",
      "durationSeconds",
    );
  }
  if (args.movement === "other" && args.customMovementName === undefined) {
    return missingRequiredField(
      "customMovementName",
      "`movement` is `other`, so the activity needs a name. Ask the person what to call it.",
    );
  }
  if (args.movement !== "other" && args.customMovementName !== undefined) {
    return invalidPayload(
      "`customMovementName` belongs to `movement: other` only.",
      "customMovementName",
    );
  }
  const seconds = args.durationSeconds ?? (args.durationMinutes ?? 0) * 60;
  if (seconds < SESSION_MIN_SECONDS || seconds > SESSION_MAX_SECONDS) {
    return invalidPayload(
      `A session lasts between ${SESSION_MIN_SECONDS} and ${SESSION_MAX_SECONDS} seconds.`,
      args.durationSeconds === undefined ? "durationMinutes" : "durationSeconds",
    );
  }
  // Rule `pastEventDay`. This tool creates a *finished* session -- FR-ACTIVITY-005 says
  // "Interdire les dates futures" and the form on the phone has always refused one -- and until
  // F-02 it was the one write path that did not. The rule is shared rather than restated here,
  // so a session, a weighing and a journal line answer the same question the same way.
  const day = pastEventDay("startedOn", args.startedOn, {
    hint: "Resolve a relative day against the person's own calendar before you send it.",
  });
  if (day !== undefined) return invalidPayload(day.message, day.field);
  return null;
}

async function handler(context: ToolContext, args: CreateActivityArgs) {
  const problem = validate(args);
  if (problem !== null) {
    // The refusal is audited too. Section 14.7 lists "the error, if any" among its
    // eight fields, and a write that was asked for and refused is exactly the event an
    // audit exists to hold. Nothing reaches `activity_sessions`.
    await context.services.recordAudit({
      agentId: context.identity.clientId,
      toolName: CREATE_ACTIVITY_TOOL_NAME,
      mutationId: null,
      aggregates: [],
      result: "error",
      revision: null,
      error: problem,
    });
    return toolFailure(problem);
  }

  const movement = args.movement as z.infer<typeof activityMovementSchema>;
  const durationSeconds = args.durationSeconds ?? (args.durationMinutes ?? 0) * 60;
  // `mutationIdSchema` is `z.uuidv7()` and `submitMutation` refuses anything else before it looks
  // at the payload. An agent supplies a `crypto.randomUUID()` — a v4 — so the key it can produce
  // is derived into an identifier the contract accepts, deterministically, so a retry carrying the
  // same key still deduplicates. See `../idempotency.ts`.
  const mutationId =
    args.idempotencyKey === undefined
      ? Bun.randomUUIDv7()
      : mutationIdFromIdempotencyKey(args.idempotencyKey);

  const outcome = await context.services.createActivitySession({
    userId: context.identity.userId,
    mutationId,
    originId: context.identity.clientId,
    clientOccurredAt: new Date().toISOString(),
    payload: {
      id: crypto.randomUUID(),
      movement,
      // Absent stays absent: `null` is how the column holds "not given", not a value
      // the server picked.
      customMovementName: args.customMovementName ?? null,
      // The one default, and it is not an invention. Android's `ActivityEnvironment`
      // documents `unknown` as "a real answer, not a missing one", and the column is
      // not nullable.
      environment: (args.environment ?? "unknown") as z.infer<typeof activityEnvironmentSchema>,
      startedOn: args.startedOn as string,
      startedAtTime: args.startedAtTime ?? null,
      durationSeconds,
      perceivedEffort: args.perceivedEffort ?? null,
      notes: args.notes ?? null,
      source: AGENT_ACTIVITY_SOURCE,
      metrics: [],
      equipment: [],
      exercises: [],
    },
  });

  await context.services.recordAudit({
    agentId: context.identity.clientId,
    toolName: CREATE_ACTIVITY_TOOL_NAME,
    mutationId,
    aggregates: [{ type: "activitySession", id: outcome.activity.id }],
    result: "ok",
    revision: outcome.activity.revision,
    error: null,
  });

  return toolSuccess({
    activity: outcome.activity,
    created: outcome.created,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

const CREATE_ACTIVITY_TOOL_NAME = "mue.create_activity";

export const createActivityTool: MueTool = {
  name: CREATE_ACTIVITY_TOOL_NAME,
  title: "Create an activity session",
  description: [
    "Record a finished activity session: a movement, the day it happened, and how long it lasted.",
    "",
    "What this creates is final. There is no draft and no confirmation step on the phone, so only",
    "call it once the person has actually described a session they completed.",
    "",
    "Never invent a required value. If the movement, the day or the duration is missing, the tool",
    "returns an error naming the field: ask the person for it and call again. Leave an optional",
    "value out rather than guessing it -- an absent start time is better than a wrong one.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema,
  outputSchema: envelopeSchema(dataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // It only ever adds a session; nothing existing is replaced or removed.
    destructiveHint: false,
    // True because of `idempotencyKey`, which section 14.6 requires of an additive
    // tool. Without one the caller gets a fresh mutation id and a second session.
    idempotentHint: true,
    // The server reaches no third party: section 8.1 keeps it private and section 16
    // forbids sending anything to an AI provider.
    openWorldHint: false,
  },
  scopes: ["activity:write"],
  handler: (context, args) => handler(context, args as CreateActivityArgs),
};
