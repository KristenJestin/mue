import {
  type BodyCompositionV1,
  HEIGHT_MAX_CM,
  HEIGHT_MIN_CM,
  IMPEDANCE_MAX_OHM,
  localDateSchema,
  MEASUREMENT_PAYLOAD_VERSION_1,
  type MeasurementPayloadV1,
  pastEventDay,
  sexSchema,
  WEIGHT_MAX_CENTIGRAMS,
  WEIGHT_MIN_CENTIGRAMS,
  WEIGHT_STEP_CENTIGRAMS,
} from "@mue/contracts";
import { z } from "zod";
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
  expectedRevisionInput,
  freshnessShape,
  fromDateInput,
  idempotencyKeyInput,
  kilogramsToCentigrams,
  mutationIdFor,
  notFound,
  refuse,
  serverTimeShape,
  toDateInput,
  weightMeasurementViewSchema,
  weightRangeError,
} from "./shared";
import type { WeightMeasurementView } from "../services";
import type { MueTool, ToolContext } from "./types";

/**
 * The weight tools of sections 14.2 and 14.3, less the list one, which shipped first.
 *
 * ## The unit, and the one place a value is moved
 *
 * A weight is a whole number of hundredths of a kilogram, because that is what Room stores
 * and what `measurementPayloadV1Schema` requires — including its five-centigram step. Two
 * ways of saying the same weight therefore need two different treatments, and conflating
 * them is exactly the "right shape, wrong content" failure that is invisible until a real
 * value is pushed through:
 *
 *  - `weightKg` is a reading, and the domain's own resolution applies to it. Android's
 *    `Weight.ofKilogramsOrNull` rounds to the nearest 0.05 kg, so this does too, and the
 *    result says whether it rounded.
 *  - `weightCg` is an exact integer claim. A value off the step is refused, with the step
 *    named, rather than quietly moved — a caller that says `7013` believes it.
 */

const GET_TOOL_NAME = "mue.get_weight_measurement";
const STATISTICS_TOOL_NAME = "mue.get_weight_statistics";
const UPSERT_TOOL_NAME = "mue.upsert_weight_measurement";
const DELETE_TOOL_NAME = "mue.delete_weight_measurement";

// --- mue.get_weight_measurement ------------------------------------------------------

const getInputSchema = {
  date: localDateSchema
    .optional()
    .describe(
      "Required. The day to read, YYYY-MM-DD, in the person's local calendar. Resolve words like 'yesterday' yourself.",
    ),
  includeDeleted: z
    .boolean()
    .optional()
    .describe(
      "Return the measurement even if it was deleted, so its tombstone and revision are visible. Defaults to false, which reports a deleted day as absent.",
    ),
};

const getDataSchema = z.object({
  measurement: weightMeasurementViewSchema
    .nullable()
    .describe("The measurement for that day, or null when nothing was recorded."),
  ...freshnessShape,
});

interface GetArgs {
  date?: string | undefined;
  includeDeleted?: boolean | undefined;
}

async function getHandler(context: ToolContext, args: GetArgs) {
  if (args.date === undefined) {
    return toolFailure(
      missingRequiredField(
        "date",
        "Give the day to read as YYYY-MM-DD. Ask the person which day if it is unclear; the server will not assume today.",
      ),
    );
  }
  const measurement = await context.services.getWeightMeasurement(
    context.identity.userId,
    args.date,
    args.includeDeleted ?? false,
  );
  return toolSuccess({
    measurement:
      measurement === null ? null : { ...measurement, weightKg: measurement.weightCg / 100 },
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getWeightMeasurementTool: MueTool = {
  name: GET_TOOL_NAME,
  title: "Get one weight measurement",
  description: [
    "Read the weight recorded for one calendar day. Mue keeps at most one per day.",
    "",
    "`measurement` is null when nothing was recorded that day, which is a real answer: say so",
    "rather than reaching for the nearest day that does have one.",
    "",
    "A weighing taken on a scale also carries the impedance it measured and, when the profile",
    "allowed one to be estimated, a body composition. Both may be null on any given day, which is",
    "ordinary and not a failure. Present the four composition figures as the estimates they are.",
  ].join("\n"),
  inputSchema: getInputSchema,
  outputSchema: envelopeSchema(getDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  // Reading a body composition is reading a weighing: it is a field of this record, no more
  // and no less sensitive than the weight it belongs to, and BR-SCALE-006 gives it no
  // existence apart from it. So it takes the scope the record takes, and there is no second
  // scope guarding a subset of one row.
  scopes: ["weight:read"],
  handler: (context, args) => getHandler(context, args as GetArgs),
};

// --- mue.get_weight_statistics -------------------------------------------------------

const statisticsInputSchema = { from: fromDateInput, to: toDateInput };

const nullableWeight = (what: string) =>
  z.number().nullable().describe(`${what} Null when the range holds no measurement.`);

const statisticsDataSchema = z.object({
  count: z.int().describe("How many measurements the range holds."),
  firstDate: localDateSchema.nullable().describe("The earliest day in range that has a weight."),
  firstWeightKg: nullableWeight("The weight on `firstDate`, in kilograms."),
  lastDate: localDateSchema.nullable().describe("The latest day in range that has a weight."),
  lastWeightKg: nullableWeight("The weight on `lastDate`, in kilograms."),
  minWeightKg: nullableWeight("The lowest weight in range, in kilograms."),
  minDate: localDateSchema.nullable().describe("The day `minWeightKg` was recorded."),
  maxWeightKg: nullableWeight("The highest weight in range, in kilograms."),
  maxDate: localDateSchema.nullable().describe("The day `maxWeightKg` was recorded."),
  meanWeightKg: nullableWeight("The arithmetic mean of the measurements in range, in kilograms."),
  changeKg: z
    .number()
    .nullable()
    .describe(
      "`lastWeightKg` minus `firstWeightKg`. Null below two measurements: one weight is not a change.",
    ),
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

const asKilograms = (centigrams: number | null): number | null =>
  centigrams === null ? null : centigrams / 100;

async function statisticsHandler(context: ToolContext, args: StatisticsArgs) {
  if (args.from !== undefined && args.to !== undefined && args.from > args.to) {
    return toolFailure(invalidPayload("`from` is later than `to`, so no day can match.", "from"));
  }

  const statistics = await context.services.weightStatistics(
    context.identity.userId,
    args.from ?? null,
    args.to ?? null,
  );

  return toolSuccess({
    count: statistics.count,
    firstDate: statistics.firstDate,
    firstWeightKg: asKilograms(statistics.firstWeightCg),
    lastDate: statistics.lastDate,
    lastWeightKg: asKilograms(statistics.lastWeightCg),
    minWeightKg: asKilograms(statistics.minWeightCg),
    minDate: statistics.minDate,
    maxWeightKg: asKilograms(statistics.maxWeightCg),
    maxDate: statistics.maxDate,
    meanWeightKg: asKilograms(statistics.meanWeightCg),
    changeKg: statistics.changeCg === null ? null : Number((statistics.changeCg / 100).toFixed(2)),
    // Section 14.5: a computed value keeps its provenance and its method. These are
    // derived at read time from the measurements this account holds in the range, and
    // stored nowhere; nothing here is smoothed, extrapolated or predicted.
    method:
      "Computed at read time from the recorded measurements in range, tombstones excluded. Nothing is smoothed, interpolated or extrapolated.",
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const getWeightStatisticsTool: MueTool = {
  name: STATISTICS_TOOL_NAME,
  title: "Summarise weight measurements",
  description: [
    "Totals for the weight history: how many measurements, the first and last, the lowest and",
    "highest, the mean and the change between the ends of the range.",
    "",
    "With no `from` and no `to` this covers the whole history. Every figure is null when the range",
    "holds no measurement -- a mean of nothing is not zero, and `changeKg` is null when there is",
    "only one weight, because one weight is not a change.",
    "",
    "These are arithmetic over what was recorded. There is no trend line and no prediction.",
  ].join("\n"),
  inputSchema: statisticsInputSchema,
  outputSchema: envelopeSchema(statisticsDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["weight:read"],
  handler: (context, args) => statisticsHandler(context, args as StatisticsArgs),
};

// --- mue.upsert_weight_measurement ---------------------------------------------------

/**
 * The composition an agent may *relay*, minus the one field it must not state.
 *
 * ## Why `inputWeightCg` is not here
 *
 * BR-SCALE-015 makes it equal to the parent's weight, and `measurementPayloadV1Schema` refuses
 * a payload where it is not. A caller asked for it could only either repeat `weightCg` or
 * contradict it, so the tool fills it from the resolved weight and the contradiction is
 * unrepresentable rather than refused.
 *
 * ## Why the four estimates are required, and what that means for an agent
 *
 * They are required because the alternative is worse. If they were optional the server would
 * have to compute them from the impedance whenever they were missing -- and FR-BODY-006 is
 * explicit that creating body-composition estimates is *"proposé, jamais silencieux"*, a
 * proposal a person accepts on their phone, because it is health data being invented for them.
 * A tool that quietly produced four estimates because an agent mentioned an impedance would be
 * exactly the silent calculation that requirement forbids.
 *
 * So this object is for relaying a composition another client already computed, never for
 * asking the server to compute one. An agent that has an impedance and no estimates sends
 * `impedanceOhm` alone: FR-BODY-004 and BR-SCALE-008 have the reading stored on the
 * measurement regardless, and PRD_SCALE 22 has it synchronise to every client, which is
 * precisely the material FR-BODY-006's proposal is made from. Nothing is lost by waiting.
 *
 * PRD_SCALE 22 then settles whose arithmetic stands: *"le serveur recalcule les résultats avec
 * la version demandée et rejette toute version inconnue. Les valeurs dérivées fournies par le
 * client ne font pas autorité."* The four values below are checked against the server's own and
 * replaced where they differ, and a formula set this build does not implement is refused
 * outright -- both in `packages/domain/src/sync/measurement.ts`, on the one write path a phone
 * and an agent share.
 */
const bodyCompositionInput = z
  .object({
    formulaId: z
      .string()
      .describe(
        "The published formula set the estimates came from, e.g. `mue-foot-to-foot-v1`. The server refuses a set it does not implement rather than answering with another one's numbers.",
      ),
    formulaVersion: z
      .int()
      .describe("Which version of that formula set. An unknown version is refused, not adapted."),
    inputHeightCm: z
      .int()
      .min(HEIGHT_MIN_CM)
      .max(HEIGHT_MAX_CM)
      .describe(
        "The height the equations were fed, in whole centimetres. The one used on the day, not the one in the profile today.",
      ),
    inputAgeYears: z
      .int()
      .min(0)
      .max(150)
      .describe(
        "The whole age on the day of the weighing. Never today's age: editing a profile must not rewrite a weighing taken years ago.",
      ),
    inputSex: sexSchema.describe(
      "The sex term the equations were fed. The estimate depends on it, so it is recorded with the estimate.",
    ),
    bodyFatDeciPercent: z
      .int()
      .describe("Body fat in tenths of a percent: 231 is 23.1%. Recalculated by the server."),
    fatFreeMassCg: z
      .int()
      .describe(
        "Fat-free mass in hundredths of a kilogram: 5567 is 55.67 kg. Recalculated by the server.",
      ),
    bodyWaterDeciPercent: z
      .int()
      .describe("Body water in tenths of a percent. Recalculated by the server."),
    restingEnergyKcal: z
      .int()
      .describe("Resting energy in whole kilocalories. Recalculated by the server."),
  })
  .optional()
  .describe(
    "A body composition another client already estimated for this weighing. Only relay one you were given: never make these numbers up, and never compute them yourself. With an impedance alone the phone can offer the person the estimate instead, which is how Mue creates one. `inputWeightCg` is not yours to state -- it is always this measurement's weight.",
  );

const upsertInputSchema = {
  date: localDateSchema
    .optional()
    .describe(
      "Required. The day the weight belongs to, YYYY-MM-DD, in the person's local calendar. A weighing is something that happened, so this day cannot be in the future. Recording a second weight for a day replaces the first, without warning.",
    ),
  weightKg: z
    .number()
    .optional()
    .describe(
      `Required, unless \`weightCg\` is given. The weight in kilograms, as the person or their scale said it. Stored to the nearest ${WEIGHT_STEP_CENTIGRAMS / 100} kg, which is Mue's resolution; the result says whether it was rounded.`,
    ),
  weightCg: z
    .int()
    .optional()
    .describe(
      `Use instead of \`weightKg\` only when you have the exact integer Mue stores: hundredths of a kilogram, a multiple of ${WEIGHT_STEP_CENTIGRAMS}. It is not rounded, it is refused if it is off the step.`,
    ),
  impedanceOhm: z
    .int()
    .positive()
    .max(IMPEDANCE_MAX_OHM)
    .optional()
    .describe(
      "Bioimpedance in ohms, as a scale measured it alongside this weight. Only pass one you were actually given; a scale that could not take a reading reports nothing, and a zero is not a reading. Omitting it keeps whatever is already recorded for this day when the weight is unchanged.",
    ),
  bodyComposition: bodyCompositionInput,
  clearImpedanceOhm: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove the impedance recorded for this day. It removes the body composition with it, which has nothing left to be an estimate of.",
    ),
  clearBodyComposition: z
    .boolean()
    .optional()
    .describe(
      "Set true only when the person asked to remove the body composition estimated for this day. The weight and the impedance stay.",
    ),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const upsertDataSchema = z.object({
  measurement: weightMeasurementViewSchema.describe("The measurement as it was stored."),
  created: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  rounded: z
    .boolean()
    .describe(
      "True when `weightKg` was moved onto Mue's 0.05 kg resolution. Tell the person what was actually stored when it is.",
    ),
  scaleReadingsRemoved: z
    .boolean()
    .describe(
      "True when this call changed the weight of a day that had an impedance or a body composition recorded with it, so those were removed: they were measured with the old weight and would be false attached to the new one. Say so rather than letting the person discover it.",
    ),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface CompositionArgs {
  formulaId: string;
  formulaVersion: number;
  inputHeightCm: number;
  inputAgeYears: number;
  inputSex: BodyCompositionV1["inputSex"];
  bodyFatDeciPercent: number;
  fatFreeMassCg: number;
  bodyWaterDeciPercent: number;
  restingEnergyKcal: number;
}

interface UpsertArgs {
  date?: string | undefined;
  weightKg?: number | undefined;
  weightCg?: number | undefined;
  impedanceOhm?: number | undefined;
  bodyComposition?: CompositionArgs | undefined;
  clearImpedanceOhm?: boolean | undefined;
  clearBodyComposition?: boolean | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

/** Section 14.4 for this tool: what the domain cannot do without, and nothing invented. */
function resolveWeight(
  args: UpsertArgs,
): { centigrams: number; rounded: boolean } | { error: ReturnType<typeof invalidPayload> } {
  if (args.weightKg !== undefined && args.weightCg !== undefined) {
    return {
      error: invalidPayload("Give either `weightKg` or `weightCg`, not both.", "weightCg"),
    };
  }
  if (args.weightCg !== undefined) {
    if (args.weightCg % WEIGHT_STEP_CENTIGRAMS !== 0) {
      return {
        error: invalidPayload(
          `\`weightCg\` is a multiple of ${WEIGHT_STEP_CENTIGRAMS}, Mue's resolution. Send kilograms in \`weightKg\` and the server will apply the step itself.`,
          "weightCg",
        ),
      };
    }
    if (args.weightCg < WEIGHT_MIN_CENTIGRAMS || args.weightCg > WEIGHT_MAX_CENTIGRAMS) {
      return { error: weightRangeError("weightCg") };
    }
    return { centigrams: args.weightCg, rounded: false };
  }
  const kilograms = args.weightKg as number;
  if (!Number.isFinite(kilograms)) {
    return { error: invalidPayload("`weightKg` is a number of kilograms.", "weightKg") };
  }
  const converted = kilogramsToCentigrams(kilograms);
  if (
    converted.centigrams < WEIGHT_MIN_CENTIGRAMS ||
    converted.centigrams > WEIGHT_MAX_CENTIGRAMS
  ) {
    return { error: weightRangeError("weightKg") };
  }
  return converted;
}

/**
 * What this call means to store beside the weight, from what the caller said and what the day
 * already held.
 *
 * ## Why anything is carried forward at all
 *
 * Section 12.2 makes an upsert state the *complete* aggregate, and BR-SCALE-007 turns that into
 * an instruction: *"un payload complet sans composition retire l'ancienne composition"*. That is
 * right on the wire -- a manual correction really does invalidate a composition -- and it means
 * a tool that submits `{date, weightCg}` deletes the impedance and the composition of that day
 * every single time. An agent asked to fix a typo in a weight would silently destroy the only
 * quantity the scale actually measured, and FR-BODY-004 is unambiguous about the asymmetry:
 * *"les formules sont discutables et remplaçables, la mesure ne l'est pas"*.
 *
 * So the tool does what `mue.update_health_profile` does for the profile: it reads the stored
 * record, and builds the complete payload from what the person said plus what stands. Omitting
 * a field means "I was not told about this", not "remove it"; removing is `clearImpedanceOhm`
 * or `clearBodyComposition`, said out loud.
 *
 * ## Except when the weight itself changed, and then both go
 *
 * PRD_SCALE 21.1 settles that case and gives the reason: an impedance was measured at the same
 * instant as the weight it came with, so *"la rattacher à une valeur saisie à la main en ferait
 * une donnée fausse"*. BR-SCALE-013 says the same for a weight corrected by hand, and
 * BR-SCALE-015 makes it structural -- `inputWeightCg` must equal the parent's weight, so a
 * carried-forward composition on a changed weight is a payload the contract refuses outright.
 * Both are therefore dropped, and `scaleReadingsRemoved` tells the agent so it can tell the
 * person, rather than leaving them to notice.
 *
 * A caller that really does have the new day's readings states them, and stated values always
 * win: this rule is about what silence means, never about overriding what was said.
 *
 * ## A tombstone carries nothing forward
 *
 * Re-recording a day the person deleted is *"a separate, explicit act"*, as the delete tool
 * says. The composition went with the tombstone (BR-SCALE-007); the impedance is still on the
 * row because `applyDelete` deliberately leaves it there to say what was deleted, and quietly
 * reattaching it to a new statement would resurrect half of something the person removed.
 */
interface ScaleFields {
  readonly sourceType: MeasurementPayloadV1["sourceType"];
  readonly impedanceOhm: number | undefined;
  readonly bodyComposition: BodyCompositionV1 | undefined;
  /** The stored readings this call is about to drop because the weight moved. */
  readonly scaleReadingsRemoved: boolean;
}

function resolveScaleFields(
  args: UpsertArgs,
  weightCg: number,
  stored: WeightMeasurementView | null,
): ScaleFields {
  const statedComposition: BodyCompositionV1 | undefined =
    args.bodyComposition === undefined
      ? undefined
      : // BR-SCALE-015, filled in rather than asked for: it is the parent's weight by
        // definition, so the caller has no way to state a value that disagrees.
        { ...args.bodyComposition, inputWeightCg: weightCg };

  // A tombstone is not a measurement, so `held` is the live record and nothing else.
  const held = stored !== null && stored.deletedAt === null ? stored : null;
  // ...and only one for the *same* weight is something there is anything to keep from.
  const keepable = held !== null && held.weightCg === weightCg ? held : null;

  const impedanceOhm =
    args.impedanceOhm ??
    (args.clearImpedanceOhm === true ? undefined : (keepable?.impedanceOhm ?? undefined));

  const carried =
    args.clearBodyComposition === true ? undefined : (keepable?.bodyComposition ?? undefined);
  const bodyComposition =
    statedComposition ??
    // A composition whose parent has no impedance has no input to be an estimate of
    // (BR-SCALE-008), and the handler would drop it anyway. Not carrying it here means the
    // payload the server journals is the one it was asked for, rather than one it corrected.
    (impedanceOhm === undefined ? undefined : carried);

  // Only what the weight change took, and only what nothing replaced. Not fired for a
  // `clear*` the caller asked for: that is not news to whoever asked.
  const weightChanged = held !== null && held.weightCg !== weightCg;
  const scaleReadingsRemoved =
    weightChanged &&
    ((held.impedanceOhm !== null && impedanceOhm === undefined) ||
      (held.bodyComposition !== null && bodyComposition === undefined));

  return {
    // PRD_SCALE 21.1's business provenance. A restatement that changes nothing keeps what the
    // day already said it was -- rewriting a scale weighing's provenance because an agent
    // touched its revision would lose a fact nothing can recover. Anything else is this
    // agent's own value, and `agent` is the enum member that exists to say so; the field was
    // omitted before, which made every agent write claim to have been typed by hand.
    sourceType: keepable === null ? "agent" : (keepable.sourceType as ScaleFields["sourceType"]),
    impedanceOhm,
    bodyComposition,
    scaleReadingsRemoved,
  };
}

async function upsertHandler(context: ToolContext, args: UpsertArgs) {
  if (args.date === undefined) {
    return refuse(
      context,
      UPSERT_TOOL_NAME,
      missingRequiredField(
        "date",
        "Give the day the weight belongs to, as YYYY-MM-DD. Ask the person if the day is unclear; the server will not assume today.",
      ),
    );
  }
  if (args.weightKg === undefined && args.weightCg === undefined) {
    return refuse(
      context,
      UPSERT_TOOL_NAME,
      missingRequiredField(
        "weightKg",
        "Give the weight in `weightKg`. Ask the person; the server will not estimate one from anything else it holds.",
      ),
    );
  }
  // Rule `pastEventDay`. PRD section 11.1 and BR-009: "Aucune mesure ne peut porter une date
  // postérieure à aujourd'hui". The phone's form has always refused one; this tool did not.
  const day = pastEventDay("date", args.date, {
    hint: "Ask the person which day they weighed themselves; the server will not assume today.",
  });
  if (day !== undefined) {
    return refuse(context, UPSERT_TOOL_NAME, invalidPayload(day.message, day.field));
  }

  if (args.impedanceOhm !== undefined && args.clearImpedanceOhm === true) {
    return refuse(
      context,
      UPSERT_TOOL_NAME,
      invalidPayload("Give an `impedanceOhm` or clear it, not both.", "clearImpedanceOhm"),
    );
  }
  if (args.bodyComposition !== undefined && args.clearBodyComposition === true) {
    return refuse(
      context,
      UPSERT_TOOL_NAME,
      invalidPayload("Give a `bodyComposition` or clear it, not both.", "clearBodyComposition"),
    );
  }

  const weight = resolveWeight(args);
  if ("error" in weight) return refuse(context, UPSERT_TOOL_NAME, weight.error);

  // Read with tombstones included: section 13.3 lets a deleted day be recorded again only
  // by a mutation based on the *current* tombstone, and this is where that revision comes
  // from. Without it, re-recording a day the person deleted would be refused for ever.
  //
  // Since PRD_SCALE 22 it is also the merge base: the view carries the provenance, the
  // impedance and the composition, and [resolveScaleFields] restates what this call did not
  // change instead of deleting it.
  const stored = await context.services.getWeightMeasurement(
    context.identity.userId,
    args.date,
    true,
  );

  const scale = resolveScaleFields(args, weight.centigrams, stored);
  if (args.bodyComposition !== undefined && scale.impedanceOhm === undefined) {
    // BR-SCALE-008 and FR-BODY-001: a composition is estimated *from* an impedance, so one
    // whose weighing carries none has no input to be an estimate of. The handler would drop it
    // and keep the weight, which is right for a payload arriving off the wire and wrong here:
    // this caller stated a composition in the same breath as removing its input, and telling
    // it so is what lets it fix the call. Naming the field it is missing rather than the one
    // it sent, because that is the one to add.
    return refuse(
      context,
      UPSERT_TOOL_NAME,
      invalidPayload(
        "A `bodyComposition` is estimated from an impedance, so give the `impedanceOhm` it was estimated from. Send the impedance on its own if you do not have the estimates.",
        "impedanceOhm",
      ),
    );
  }

  const payload: MeasurementPayloadV1 = {
    date: args.date,
    weightCg: weight.centigrams,
    sourceType: scale.sourceType,
    ...(scale.impedanceOhm === undefined ? {} : { impedanceOhm: scale.impedanceOhm }),
    ...(scale.bodyComposition === undefined ? {} : { bodyComposition: scale.bodyComposition }),
  };

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPSERT_TOOL_NAME,
    aggregateType: "measurement",
    aggregateId: args.date,
    op: "upsert",
    payloadSchemaVersion: MEASUREMENT_PAYLOAD_VERSION_1,
    payload,
    baseRevision: baseRevisionOf(args.expectedRevision, stored?.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  const current = await context.services.getWeightMeasurement(
    context.identity.userId,
    outcome.result.aggregateId,
    true,
  );
  if (current === null) throw new Error("measurements lost a row between apply and read");

  return toolSuccess({
    // Read back rather than echoed, and that is what makes PRD_SCALE 22's *"les valeurs
    // dérivées fournies par le client ne font pas autorité"* visible to the agent: where the
    // server recalculated an estimate or dropped a composition its equations refuse, this is
    // the composition that was actually stored and not the one that was submitted.
    measurement: { ...current, weightKg: current.weightCg / 100 },
    created: outcome.result.status === "applied",
    rounded: weight.rounded,
    scaleReadingsRemoved: scale.scaleReadingsRemoved,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const upsertWeightMeasurementTool: MueTool = {
  name: UPSERT_TOOL_NAME,
  title: "Record a weight measurement",
  description: [
    "Record the weight for one calendar day. Mue keeps one measurement per day, so recording a",
    "second one for the same day replaces the first -- there is no warning and no second copy.",
    "",
    "What this writes is final: it appears on the phone at its next synchronisation with no",
    "confirmation step. Never invent the day or the weight. If either is missing the tool returns",
    "an error naming the field: ask the person and call again.",
    "",
    "A day may also carry an impedance the scale measured and a body composition estimated from",
    "it. What you leave out is left alone: restating the same weight keeps them, and removing one",
    "is an explicit `clearImpedanceOhm` or `clearBodyComposition`. Changing the weight does remove",
    "both, because they were measured with the old weight -- the result says so in",
    "`scaleReadingsRemoved`, and it is worth telling the person.",
    "",
    "Only pass a `bodyComposition` you were handed by another client. The server recalculates the",
    "four estimates and refuses a formula set it does not implement, so yours do not stand; and",
    "estimating one from an impedance is something the person is asked about on their phone, never",
    "something done for them here. An impedance on its own is always worth recording.",
    "",
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: upsertInputSchema,
  outputSchema: envelopeSchema(upsertDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // It replaces the weight of one day, which section 13.2 makes the defined behaviour
    // rather than a loss: the replaced version stays in the journal for audit. That still
    // holds now that a day carries an impedance and a composition. They are removed only when
    // the weight they were measured with is replaced -- PRD_SCALE 21.1's own rule, not a side
    // effect -- the result says so in `scaleReadingsRemoved`, and the version that held them
    // stays in `sync_journal`, which retention never sweeps.
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  /*
   * `weight:write`, and nothing added for the composition.
   *
   * PRD_SCALE 22 asks that writing a composition be a health-data write, and it is: it is a
   * field of this payload, gated by this tool's scope, on the same tool that writes the weight.
   * A `composition:write` beside it would be worse than redundant -- it would advertise a
   * permission over an object that BR-SCALE-006 says does not exist on its own, and section
   * 15.2's list is the set of scopes a person can actually be asked to grant. A composition is
   * as sensitive as the weighing it belongs to and is inseparable from it, so it takes the same
   * permission, exactly as `create-activity.ts` takes `activity:write` for a whole session
   * rather than one per child collection.
   *
   * No `data:delete` either, for the same reason `destructiveHint` is false: nothing here is a
   * deletion. `mue.delete_weight_measurement` below declares it.
   */
  scopes: ["weight:write"],
  handler: (context, args) => upsertHandler(context, args as UpsertArgs),
};

// --- mue.delete_weight_measurement ---------------------------------------------------

const deleteInputSchema = {
  date: localDateSchema
    .optional()
    .describe("Required. The day whose measurement to delete, YYYY-MM-DD."),
  expectedRevision: expectedRevisionInput,
  idempotencyKey: idempotencyKeyInput,
};

const deleteDataSchema = z.object({
  date: localDateSchema.describe("The day that was deleted."),
  deleted: z
    .boolean()
    .describe("False when this call replayed an earlier one with the same `idempotencyKey`."),
  revision: z.string().describe("The revision the tombstone was written at."),
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface DeleteArgs {
  date?: string | undefined;
  expectedRevision?: string | undefined;
  idempotencyKey?: string | undefined;
}

async function deleteHandler(context: ToolContext, args: DeleteArgs) {
  if (args.date === undefined) {
    return refuse(
      context,
      DELETE_TOOL_NAME,
      missingRequiredField(
        "date",
        "Give the day to delete, as YYYY-MM-DD. Ask the person which day; the server will not choose one.",
      ),
    );
  }

  // Tombstones included, and that is what keeps a retry idempotent rather than turning
  // into a "not found" the second time: after the first call the row exists and is
  // deleted, so the replay reaches `applyWrite` and gets the stored result back.
  const stored = await context.services.getWeightMeasurement(
    context.identity.userId,
    args.date,
    true,
  );
  if (stored === null) {
    // Nothing to delete is not a deletion turned into a draft (section 14.6): there is no
    // record here at all, and writing a tombstone for a day nobody ever weighed would tell
    // the phone to remember an absence it already has.
    return refuse(
      context,
      DELETE_TOOL_NAME,
      notFound("measurement", args.date, "weight measurement on that day"),
    );
  }

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: DELETE_TOOL_NAME,
    aggregateType: "measurement",
    aggregateId: args.date,
    op: "delete",
    payloadSchemaVersion: MEASUREMENT_PAYLOAD_VERSION_1,
    payload: null,
    baseRevision: baseRevisionOf(args.expectedRevision, stored.revision),
    mutationId,
  });
  if (!outcome.ok) return outcome.failure;

  return toolSuccess({
    date: args.date,
    deleted: outcome.result.status === "applied",
    revision: outcome.result.revision ?? stored.revision,
    mutationId,
    serverTime: new Date().toISOString(),
  });
}

export const deleteWeightMeasurementTool: MueTool = {
  name: DELETE_TOOL_NAME,
  title: "Delete a weight measurement",
  description: [
    "Delete the weight recorded for one day. The deletion reaches the phone at its next",
    "synchronisation and is not reversed by an older copy still sitting on it.",
    "",
    "This removes the person's record of that day. Ask before you call it unless they asked for",
    "it in as many words. Recording a weight for the same day afterwards is possible and is a",
    "separate, explicit act.",
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
  // Section 15.2's explicit deletion permission, *in addition to* the domain's write
  // scope. Every scope a tool declares must be held, so an agent trusted to record
  // weights still cannot remove one until the owner grants `data:delete` as well.
  scopes: ["weight:write", "data:delete"],
  handler: (context, args) => deleteHandler(context, args as DeleteArgs),
};
