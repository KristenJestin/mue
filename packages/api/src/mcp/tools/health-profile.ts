import {
  BIRTH_DATE_YEAR_PATTERN,
  birthDay,
  LIFETIME_MAX_YEARS,
  HEALTH_PROFILE_AGGREGATE_ID,
  HEALTH_PROFILE_PAYLOAD_VERSION_1,
  HEIGHT_MAX_CM,
  HEIGHT_MIN_CM,
  type HealthProfilePayloadV1,
} from "@mue/contracts";
import { z } from "zod";
import { envelopeSchema, invalidPayload, toolSuccess } from "../errors";
import {
  applyWrite,
  baseRevisionOf,
  expectedRevisionInput,
  freshnessShape,
  idempotencyKeyInput,
  metadataShape,
  mutationIdFor,
  refuse,
  serverTimeShape,
} from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * The health profile of section 13.4: a height and a birth date, one aggregate per account.
 *
 * ## Why an update states what changed and not the whole profile
 *
 * The wire requires an upsert to carry the complete aggregate (section 12.2), and both fields
 * are required-and-nullable so the server can tell a stated emptiness from a silence. An agent
 * has neither piece of that context: told *"I'm 1m78"*, it knows a height and nothing about a
 * birth date, and a tool that made it send `birthDate: null` would have it assert something it
 * was never told.
 *
 * So the tool takes only what the person said, reads the stored profile, and builds the complete
 * payload from the two. A field nobody mentioned is resubmitted as it stands, with the stored
 * revision as `baseRevision` — which is exactly the position section 13.4's three-way merge
 * expects an author to be in, so a birth date another device set concurrently survives.
 *
 * Clearing is a separate, explicit act: `clearHeightCm`. That keeps *"I emptied this"* sayable
 * without making *"I did not mention this"* mean the same thing, which is the whole distinction
 * section 13.4 is built on.
 */

const GET_TOOL_NAME = "mue.get_health_profile";
const UPDATE_TOOL_NAME = "mue.update_health_profile";

const profileShape = {
  heightCm: z
    .int()
    .nullable()
    .describe("Height in whole centimetres, or null when the person has not given one."),
  birthDate: z
    .string()
    .nullable()
    .describe("Date of birth, YYYY-MM-DD, or null when the person has not given one."),
};

const getDataSchema = z.object({
  profile: z
    .object({ ...profileShape, ...metadataShape })
    .nullable()
    .describe("The stored profile, or null when the account has never had one."),
  ...freshnessShape,
});

async function getHandler(context: ToolContext) {
  const stored = await context.services.getHealthProfile(context.identity.userId);
  return toolSuccess({
    profile: stored === null ? null : { ...stored.payload, ...stored.meta },
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getHealthProfileTool: MueTool = {
  name: GET_TOOL_NAME,
  title: "Get the health profile",
  description: [
    "Read the person's height and date of birth, the two values Mue keeps about their body.",
    "",
    "Either may be null, and null means the person has never given it -- not that it is zero and",
    "not that it is unknown to them. `profile` itself is null when the account has no profile at",
    "all. Do not fill either in from something you inferred elsewhere.",
  ].join("\n"),
  inputSchema: {},
  outputSchema: envelopeSchema(getDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["profile:read"],
  handler: (context) => getHandler(context),
};

const updateInputSchema = {
  heightCm: z
    .int()
    .min(HEIGHT_MIN_CM)
    .max(HEIGHT_MAX_CM)
    .optional()
    .describe(
      `Height in whole centimetres, ${HEIGHT_MIN_CM} to ${HEIGHT_MAX_CM}. Omit it entirely when the person did not give a height: omitting leaves the stored one untouched.`,
    ),
  birthDate: z.iso
    .date()
    .regex(BIRTH_DATE_YEAR_PATTERN)
    .optional()
    .describe(
      `Date of birth, YYYY-MM-DD, year 1900 to 2099, not in the future and not more than ${LIFETIME_MAX_YEARS} years ago. Omit it when the person did not give one; omitting leaves the stored one untouched.`,
    ),
  clearHeightCm: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove their height. Leaving `heightCm` out is not the same thing and does not clear it.",
    ),
  clearBirthDate: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove their date of birth. Leaving `birthDate` out is not the same thing and does not clear it.",
    ),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const updateDataSchema = z.object({
  profile: z.object({ ...profileShape, ...metadataShape }).describe("The profile as it now is."),
  changed: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface UpdateArgs {
  heightCm?: number | undefined;
  birthDate?: string | undefined;
  clearHeightCm?: boolean | undefined;
  clearBirthDate?: boolean | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

/** A field the caller stated, cleared, or did not mention. The three are distinct. */
function fieldValue<T>(
  given: T | undefined,
  clear: boolean | undefined,
  stored: T | null,
): T | null {
  if (given !== undefined) return given;
  if (clear === true) return null;
  return stored;
}

async function updateHandler(context: ToolContext, args: UpdateArgs) {
  if (
    args.heightCm === undefined &&
    args.birthDate === undefined &&
    args.clearHeightCm !== true &&
    args.clearBirthDate !== true
  ) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        "An update states at least one of `heightCm`, `birthDate`, `clearHeightCm` or `clearBirthDate`. Ask the person what to change.",
        "heightCm",
      ),
    );
  }
  if (args.heightCm !== undefined && args.clearHeightCm === true) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload("Give a `heightCm` or clear it, not both.", "clearHeightCm"),
    );
  }
  if (args.birthDate !== undefined && args.clearBirthDate === true) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload("Give a `birthDate` or clear it, not both.", "clearBirthDate"),
    );
  }
  // Rule `birthDay` -- `pastEventDay` and `lifetimeFloor` together, which is PRD section 11.2's
  // "Pas dans le futur, pas antérieure de plus de 120 ans" in the order it states them.
  //
  // Only an argument is judged, never the merged value: `lifetimeFloor` moves with the calendar,
  // so re-judging a stored date would one day make an unrelated height edit impossible for
  // someone who had simply got older than the bound. `birthDateSchema` keeps the absolute
  // 1900-2099 pattern that lets a journalled profile stay parseable for ever.
  if (args.birthDate !== undefined) {
    const born = birthDay("birthDate", args.birthDate, {
      hint: "Ask the person for their date of birth; the server will not derive one from an age.",
    });
    if (born !== undefined) {
      return refuse(context, UPDATE_TOOL_NAME, invalidPayload(born.message, born.field));
    }
  }

  const stored = await context.services.getHealthProfile(context.identity.userId);
  const payload: HealthProfilePayloadV1 = {
    heightCm: fieldValue(args.heightCm, args.clearHeightCm, stored?.payload.heightCm ?? null),
    birthDate: fieldValue(args.birthDate, args.clearBirthDate, stored?.payload.birthDate ?? null),
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPDATE_TOOL_NAME,
    aggregateType: "healthProfile",
    aggregateId: HEALTH_PROFILE_AGGREGATE_ID,
    op: "upsert",
    payloadSchemaVersion: HEALTH_PROFILE_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored?.meta.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  const current = await context.services.getHealthProfile(context.identity.userId);
  if (current === null) throw new Error("health_profile lost its row between apply and read");

  return toolSuccess({
    profile: { ...current.payload, ...current.meta },
    changed: outcome.result.status === "applied",
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const updateHealthProfileTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Update the health profile",
  description: [
    "Set the person's height, their date of birth, or both. What you leave out is left alone.",
    "",
    "Send only what the person actually told you. Omitting a field keeps the stored value, so",
    "there is never a reason to repeat a value you did not hear -- and never a reason to guess",
    "one. Removing a value is a separate, explicit request: `clearHeightCm` or `clearBirthDate`.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: updateInputSchema,
  outputSchema: envelopeSchema(updateDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // It replaces two fields of one record and removes nothing that is not named.
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["profile:write"],
  handler: (context, args) => updateHandler(context, args as UpdateArgs),
};
