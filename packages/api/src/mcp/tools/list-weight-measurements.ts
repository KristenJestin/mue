import { instantSchema, localDateSchema } from "@mue/contracts";
import { z } from "zod";
import {
  decodeListCursor,
  encodeListCursor,
  InvalidCursorError,
  listCursorSchema,
} from "../cursor";
import { envelopeSchema, invalidPayload, toolFailure, toolSuccess } from "../errors";
import type { MueTool, ToolContext } from "./types";

export const LIST_WEIGHT_MEASUREMENTS_DEFAULT_LIMIT = 50;
export const LIST_WEIGHT_MEASUREMENTS_MAX_LIMIT = 200;

/**
 * Section 14.2's read tool for weights.
 *
 * The two date filters are optional and there is no default window: an agent that
 * passes neither gets the whole history, one page at a time, which is exactly what
 * section 14.2 and section 21 require ("parcourir toutes les pesees ... sans fenetre
 * temporelle imposee"). The only bound is the page size, and it is the caller's.
 */
const inputSchema = {
  from: localDateSchema
    .optional()
    .describe("Inclusive earliest date, YYYY-MM-DD. Omit for no lower bound."),
  to: localDateSchema
    .optional()
    .describe("Inclusive latest date, YYYY-MM-DD. Omit for no upper bound."),
  cursor: listCursorSchema
    .optional()
    .describe(
      "`nextCursor` from the previous page. Pass it back unchanged; never build or parse one.",
    ),
  limit: z
    .int()
    .min(1)
    .max(LIST_WEIGHT_MEASUREMENTS_MAX_LIMIT)
    .optional()
    .describe(`Measurements per page. Defaults to ${LIST_WEIGHT_MEASUREMENTS_DEFAULT_LIMIT}.`),
  includeDeleted: z
    .boolean()
    .optional()
    .describe(
      "Include deleted measurements, which carry a non-null `deletedAt`. Defaults to false.",
    ),
};

const measurementSchema = z.object({
  date: localDateSchema.describe("The day the weight belongs to. One measurement per day."),
  weightCg: z
    .int()
    .describe("Weight in hundredths of a kilogram, the exact integer the phone stores."),
  weightKg: z.number().describe("`weightCg` divided by 100, for display only."),
  revision: z
    .string()
    .describe("Server revision of this measurement, as a decimal string. Rises on every change."),
  createdAt: instantSchema,
  updatedAt: instantSchema,
  deletedAt: instantSchema.nullable().describe("Set when the measurement was deleted."),
  originType: z
    .enum(["android", "agent", "server"])
    .describe("Provenance: what kind of author last changed this measurement."),
  originId: z.string().nullable().describe("Provenance: which device, agent or process."),
  lastMutationId: z.string().describe("The operation that produced the current revision."),
});

const dataSchema = z.object({
  measurements: z.array(measurementSchema),
  nextCursor: z
    .string()
    .nullable()
    .describe("Pass to `cursor` for the next page. Null when this page is the last."),
  hasMore: z.boolean(),
  serverTime: instantSchema.describe("The server clock when this page was read."),
  lastAndroidSyncAt: instantSchema
    .nullable()
    .describe(
      "The last moment the server saw the Android phone synchronise, or null if it never has. Anything recorded on the phone after this instant has not reached the server, so do not present these measurements as certainly current.",
    ),
});

interface ListWeightMeasurementsArgs {
  from?: string | undefined;
  to?: string | undefined;
  cursor?: string | undefined;
  limit?: number | undefined;
  includeDeleted?: boolean | undefined;
}

async function handler(context: ToolContext, args: ListWeightMeasurementsArgs) {
  let afterDate: string | null = null;
  if (args.cursor !== undefined) {
    try {
      afterDate = decodeListCursor(args.cursor);
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

  const limit = args.limit ?? LIST_WEIGHT_MEASUREMENTS_DEFAULT_LIMIT;
  const page = await context.services.listWeightMeasurements({
    userId: context.identity.userId,
    from: args.from ?? null,
    to: args.to ?? null,
    afterDate,
    limit,
    includeDeleted: args.includeDeleted ?? false,
  });

  const last = page.measurements.at(-1);
  return toolSuccess({
    measurements: page.measurements.map((measurement) => ({
      ...measurement,
      weightKg: measurement.weightCg / 100,
    })),
    nextCursor: page.hasMore && last !== undefined ? encodeListCursor(last.date) : null,
    hasMore: page.hasMore,
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: await context.services.lastAndroidSyncAt(context.identity.userId),
  });
}

export const listWeightMeasurementsTool: MueTool = {
  name: "mue.list_weight_measurements",
  title: "List weight measurements",
  description: [
    "Read weight measurements, newest first, one page at a time.",
    "",
    "With no `from` and no `to` this walks the entire history: keep calling it with the",
    "`nextCursor` you were given until `hasMore` is false. No time window is imposed.",
    "",
    "Every measurement carries its provenance, its server revision and its dates. The page",
    "also carries `lastAndroidSyncAt`, the last time the phone synchronised: readings the",
    "person took after that instant are not here yet.",
  ].join("\n"),
  inputSchema,
  outputSchema: envelopeSchema(dataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["weight:read"],
  handler: (context, args) => handler(context, args as ListWeightMeasurementsArgs),
};
