import {
  BIRTH_DATE_YEAR_PATTERN,
  birthDay,
  LIFETIME_MAX_YEARS,
  HEALTH_PROFILE_AGGREGATE_ID,
  HEALTH_PROFILE_PAYLOAD_VERSION_1,
  HEIGHT_MAX_CM,
  HEIGHT_MIN_CM,
  type HealthProfilePayloadV1,
  sexSchema,
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
 * The health profile of section 13.4: a height, a birth date and a sex, one aggregate per
 * account.
 *
 * ## Why an update states what changed and not the whole profile
 *
 * The wire requires an upsert to carry the complete aggregate (section 12.2), and both of the
 * original fields are required-and-nullable so the server can tell a stated emptiness from a
 * silence. An agent has neither piece of that context: told *"I'm 1m78"*, it knows a height and
 * nothing about a birth date, and a tool that made it send `birthDate: null` would have it
 * assert something it was never told.
 *
 * So the tool takes only what the person said, reads the stored profile, and builds the complete
 * payload from the two. A field nobody mentioned is resubmitted as it stands, with the stored
 * revision as `baseRevision` — which is exactly the position section 13.4's three-way merge
 * expects an author to be in, so a birth date another device set concurrently survives.
 *
 * Clearing is a separate, explicit act: `clearHeightCm`. That keeps *"I emptied this"* sayable
 * without making *"I did not mention this"* mean the same thing, which is the whole distinction
 * section 13.4 is built on.
 *
 * ## The third field, and the bug that resubmission had while it was invisible
 *
 * PRD_SCALE 22 puts the sex in this aggregate. The paragraph above says the tool resubmits what
 * it did not hear — and it could not, because `getHealthProfile` returned `{heightCm,
 * birthDate}` and nothing else. So the moment a phone recorded a sex, every update built a
 * payload without one *and quoted a `baseRevision` whose snapshot had one*, which is precisely
 * the evidence section 13.4's merge reads as **the author removed this field**. It did what it
 * was told: an agent correcting a height erased the person's sex, and with it every future body
 * composition, because FR-BODY-001 cannot compute one without it.
 *
 * Nothing about the merge was wrong. The reads and the writes of a field-merged aggregate are
 * one loop, and a field missing from either half of it is deleted by an edit to any other. That
 * is why `sex` appears in four places below — the read shape, the input, the resubmission and
 * the output shape — and why none of them is optional to get right.
 */

const GET_TOOL_NAME = "mue.get_health_profile";
const UPDATE_TOOL_NAME = "mue.update_health_profile";

/**
 * The profile as a tool reports it: three fields, always present, null when unstated.
 *
 * ## A deliberate divergence from the wire, for `sex`
 *
 * `HealthProfilePayloadV1.sex` is `.optional()` — an unstated sex is an *absent key*, and
 * `sexSchema` refuses `null`. That shape exists for a reason that belongs to the wire and only
 * to the wire: absence is what a client written before the field existed sends, and section
 * 13.4's merge compares it against a base that is absent too, so `incoming === base` and a sex
 * another device set survives. Nullability would have bought the same outcome at the cost of
 * making every existing client's payload incomplete.
 *
 * A tool result has none of that context. There is one snapshot, no merge and no author, so the
 * absent/null distinction carries no information here — and it costs something real: a key that
 * is sometimes missing invites `profile.sex === undefined`, which reads identically for *"the
 * person has not said"* and *"this server did not tell me"*. Its two neighbours are already
 * always-present-and-nullable, so reporting the third the same way gives an agent one rule for
 * reading all three: **null means the person has not given it**.
 *
 * This is a change to the shape of `mue.get_health_profile`'s answer, and it is recorded as a
 * decision rather than absorbed: `profile` gains a `sex` key, always present, `"female"`,
 * `"male"` or `null`. The same shape is what `mue.update_health_profile` returns.
 */
const profileShape = {
  heightCm: z
    .int()
    .nullable()
    .describe("Height in whole centimetres, or null when the person has not given one."),
  birthDate: z
    .string()
    .nullable()
    .describe("Date of birth, YYYY-MM-DD, or null when the person has not given one."),
  sex: sexSchema
    .nullable()
    .describe(
      "Sex, or null when the person has not given one. Mue keeps it for one purpose: the body-composition estimates need it as an input. It changes nothing else -- the BMI does not read it and its categories are the same for everyone.",
    ),
};

/** The stored payload, in the always-present shape [profileShape] describes. */
function profileView(payload: HealthProfilePayloadV1) {
  return { ...payload, sex: payload.sex ?? null };
}

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
    profile: stored === null ? null : { ...profileView(stored.payload), ...stored.meta },
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getHealthProfileTool: MueTool = {
  name: GET_TOOL_NAME,
  title: "Get the health profile",
  description: [
    "Read the person's height, date of birth and sex: the three values Mue keeps about their body.",
    "",
    "Any of them may be null, and null means the person has never given it -- not that it is zero",
    "and not that it is unknown to them. `profile` itself is null when the account has no profile",
    "at all. Do not fill any of them in from something you inferred elsewhere.",
    "",
    "The sex is kept for one purpose and is used for nothing else: the body-composition estimates",
    "take it as an input. It has no bearing on the BMI, whose categories are the same for everyone.",
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
  sex: sexSchema
    .optional()
    .describe(
      "The person's sex, `female` or `male`, and only when they stated it themselves. Never infer one from a name, a pronoun or anything else: Mue uses it solely as an input to the body-composition estimates, and a guess there is a wrong estimate presented as a measurement. Omit it when they did not say; omitting leaves the stored one untouched.",
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
  clearSex: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove their sex. Body-composition estimates stop being possible for future weighings; the ones already recorded keep the value they were computed with and are not touched.",
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
  sex?: HealthProfilePayloadV1["sex"];
  clearHeightCm?: boolean | undefined;
  clearBirthDate?: boolean | undefined;
  clearSex?: boolean | undefined;
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
    args.sex === undefined &&
    args.clearHeightCm !== true &&
    args.clearBirthDate !== true &&
    args.clearSex !== true
  ) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload(
        "An update states at least one of `heightCm`, `birthDate`, `sex`, `clearHeightCm`, `clearBirthDate` or `clearSex`. Ask the person what to change.",
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
  if (args.sex !== undefined && args.clearSex === true) {
    return refuse(
      context,
      UPDATE_TOOL_NAME,
      invalidPayload("Give a `sex` or clear it, not both.", "clearSex"),
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
  // The stored sex is resubmitted with the other two, and this is the line the regression was
  // the absence of. Its shape differs from theirs -- `sexSchema.optional()` refuses `null`, so
  // an unstated sex is an *omitted key* and never a nulled one, and a payload carrying
  // `sex: null` would be journalled and then stop every pull that re-parses it.
  const sex = fieldValue(args.sex, args.clearSex, stored?.payload.sex ?? null);
  const payload: HealthProfilePayloadV1 = {
    heightCm: fieldValue(args.heightCm, args.clearHeightCm, stored?.payload.heightCm ?? null),
    birthDate: fieldValue(args.birthDate, args.clearBirthDate, stored?.payload.birthDate ?? null),
    ...(sex === null ? {} : { sex }),
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
    profile: { ...profileView(current.payload), ...current.meta },
    changed: outcome.result.status === "applied",
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const updateHealthProfileTool: MueTool = {
  name: UPDATE_TOOL_NAME,
  title: "Update the health profile",
  description: [
    "Set the person's height, their date of birth, their sex, or any combination. What you leave",
    "out is left alone.",
    "",
    "Send only what the person actually told you. Omitting a field keeps the stored value, so",
    "there is never a reason to repeat a value you did not hear -- and never a reason to guess",
    "one. Removing a value is a separate, explicit request: `clearHeightCm`, `clearBirthDate` or",
    "`clearSex`.",
    "",
    "The sex is used for one thing, the body-composition estimates, and it must come from the",
    "person saying it. Do not infer it from a name or a pronoun: a guess there produces estimates",
    "that look measured and are not.",
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
