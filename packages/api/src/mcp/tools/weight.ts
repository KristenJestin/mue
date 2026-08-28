import {
  localDateSchema,
  MEASUREMENT_PAYLOAD_VERSION_1,
  pastEventDay,
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
  ].join("\n"),
  inputSchema: getInputSchema,
  outputSchema: envelopeSchema(getDataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
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
  mutationId: z.string().describe("The mutation this call produced, recorded in the agent audit."),
  ...serverTimeShape,
});

interface UpsertArgs {
  date?: string | undefined;
  weightKg?: number | undefined;
  weightCg?: number | undefined;
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

  const weight = resolveWeight(args);
  if ("error" in weight) return refuse(context, UPSERT_TOOL_NAME, weight.error);

  // Read with tombstones included: section 13.3 lets a deleted day be recorded again only
  // by a mutation based on the *current* tombstone, and this is where that revision comes
  // from. Without it, re-recording a day the person deleted would be refused for ever.
  const stored = await context.services.getWeightMeasurement(
    context.identity.userId,
    args.date,
    true,
  );

  const mutationId = mutationIdFor(args.idempotencyKey);
  const outcome = await applyWrite(context, {
    toolName: UPSERT_TOOL_NAME,
    aggregateType: "measurement",
    aggregateId: args.date,
    op: "upsert",
    payloadSchemaVersion: MEASUREMENT_PAYLOAD_VERSION_1,
    payload: { date: args.date, weightCg: weight.centigrams },
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
    measurement: { ...current, weightKg: current.weightCg / 100 },
    created: outcome.result.status === "applied",
    rounded: weight.rounded,
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
    "Retrying after a lost response is safe as long as you send the same `idempotencyKey`.",
  ].join("\n"),
  inputSchema: upsertInputSchema,
  outputSchema: envelopeSchema(upsertDataSchema).shape,
  annotations: {
    readOnlyHint: false,
    // It replaces the weight of one day, which section 13.2 makes the defined behaviour
    // rather than a loss: the replaced version stays in the journal for audit.
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
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
