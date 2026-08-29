import {
  type AggregateType,
  instantSchema,
  localDateSchema,
  measurementSourceTypeSchema,
  type MueError,
  type MutationOp,
  originTypeSchema,
  revisionSchema,
  sexSchema,
  WEIGHT_MAX_CENTIGRAMS,
  WEIGHT_MIN_CENTIGRAMS,
  WEIGHT_STEP_CENTIGRAMS,
} from "@mue/contracts";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { invalidPayload, toolFailure } from "../errors";
import { mutationIdFromIdempotencyKey } from "../idempotency";
import type { ToolContext } from "./types";

/**
 * The pieces every Mue tool repeats, in one place.
 *
 * Twenty-eight tools share an idempotency key, an expected revision, a block of section
 * 12.1 metadata and one write path. Written out twenty-eight times those would be
 * twenty-eight chances to word a rule differently -- and, worse, twenty-eight chances to
 * *forget* one: a write tool that omitted its audit call would break section 14.7 silently,
 * because nothing about the write would look wrong. So the audit is not something a tool
 * remembers to do, it is something [applyWrite] does on the tool's behalf.
 */

// --- inputs every write tool shares --------------------------------------------------

/**
 * Section 14.6: *"Un outil additif fournit une clé d'idempotence ou utilise l'identifiant
 * de mutation MCP fourni par le client."*
 *
 * `z.uuid()` and not `z.uuidv7()`, deliberately. See `../idempotency.ts`: an agent has
 * `crypto.randomUUID()` and nothing else, which is a v4, and requiring the version the
 * sync contract stores would make the field impossible to fill and every retry a second
 * row. The key is *derived* into a `mutationId` instead.
 */
export const idempotencyKeyInput = z
  .uuid()
  .optional()
  .describe(
    "Optional UUID identifying this call. Send the same one when retrying after a lost or unclear response: the change is applied once and the retry returns the first result instead of repeating it.",
  );

/** Section 14.6: *"Les outils de mise à jour acceptent la révision attendue lorsqu'elle est connue."* */
export const expectedRevisionInput = revisionSchema
  .optional()
  .describe(
    "The `revision` you last read for this record, if you have one. It is recorded with the change so a concurrent edit is visible in the audit. Omit it rather than guessing.",
  );

export const fromDateInput = localDateSchema
  .optional()
  .describe("Inclusive earliest date, YYYY-MM-DD. Omit for no lower bound.");

export const toDateInput = localDateSchema
  .optional()
  .describe("Inclusive latest date, YYYY-MM-DD. Omit for no upper bound.");

export const cursorInput = z
  .string()
  .min(1)
  .max(512)
  .regex(/^[A-Za-z0-9_-]+$/)
  .optional()
  .describe(
    "`nextCursor` from the previous page. Pass it back unchanged; never build or parse one.",
  );

export function limitInput(defaultLimit: number, maxLimit: number) {
  return z
    .int()
    .min(1)
    .max(maxLimit)
    .optional()
    .describe(`Records per page, at most ${maxLimit}. Defaults to ${defaultLimit}.`);
}

export const includeDeletedInput = z
  .boolean()
  .optional()
  .describe(
    "Include deleted records, which carry a non-null `deletedAt`. Defaults to false. A deletion leaves a tombstone rather than erasing the record.",
  );

// --- outputs every read tool shares ---------------------------------------------------

/** PRD section 12.1's metadata, which section 14.2 requires every read to carry. */
export const metadataShape = {
  revision: revisionSchema.describe(
    "Server revision of this record. Rises on every accepted change. Quote it back as `expectedRevision` when you edit.",
  ),
  createdAt: instantSchema.describe("When the record was first accepted by the server."),
  updatedAt: instantSchema.describe("When the record last changed."),
  deletedAt: instantSchema
    .nullable()
    .describe("Set when the record was deleted. Null for a live record."),
  originType: originTypeSchema.describe(
    "Provenance: what kind of author last changed this record.",
  ),
  originId: z.string().nullable().describe("Provenance: which device, agent or process."),
  lastMutationId: z.string().describe("The operation that produced the current revision."),
};

export const serverTimeShape = {
  serverTime: instantSchema.describe("The server clock when this call was answered."),
};

export const freshnessShape = {
  ...serverTimeShape,
  lastAndroidSyncAt: instantSchema
    .nullable()
    .describe(
      "The last moment the server saw the Android phone synchronise, or null if it never has. Anything recorded on the phone after this instant has not reached the server, so do not present this data as certainly current.",
    ),
};

/**
 * The body composition of PRD_SCALE 12.3, as a tool reports it.
 *
 * `bodyCompositionV1Schema` is not reused verbatim, and the difference is the audience. The
 * contract's version is a *wire* schema whose bounds refuse a malformed payload; this one is a
 * catalogue entry an agent reads to understand what the four numbers are, so every field
 * carries the sentence FR-BODY-003 and PRD_SCALE 13.3 require -- these are estimates, in whole
 * storage units, from equations validated on a population that is not everyone.
 *
 * There is deliberately no tool that takes this object on its own. BR-SCALE-006 makes a
 * composition an optional child of a weighing that cannot exist alone, and PRD_SCALE 22 says it
 * in as many words: *"il n'existe pas d'outil indépendant capable de créer une composition
 * orpheline."* Here that is structural rather than a rule someone enforces -- it appears only
 * inside a measurement, on the way in and on the way out.
 */
export const bodyCompositionViewSchema = z.object({
  formulaId: z
    .string()
    .describe("The published formula set these estimates come from, e.g. `mue-foot-to-foot-v1`."),
  formulaVersion: z.int().describe("Which version of that formula set produced them."),
  inputWeightCg: z
    .int()
    .describe("The weight the equations were fed. Always equal to the measurement's `weightCg`."),
  inputHeightCm: z.int().describe("The height the equations were fed, in whole centimetres."),
  inputAgeYears: z
    .int()
    .describe(
      "The whole age on the day of the weighing, not today's age. Editing the profile later never rewrites this.",
    ),
  inputSex: sexSchema.describe(
    "The sex term the equations were fed. Recorded because the estimate depends on it.",
  ),
  bodyFatDeciPercent: z
    .int()
    .describe("Estimated body fat in tenths of a percent: 231 is 23.1%. An estimate, not a scan."),
  fatFreeMassCg: z
    .int()
    .describe("Estimated fat-free mass in hundredths of a kilogram: 5567 is 55.67 kg."),
  bodyWaterDeciPercent: z
    .int()
    .describe("Estimated body water in tenths of a percent, from an average hydration factor."),
  restingEnergyKcal: z
    .int()
    .describe(
      "Resting energy expenditure in whole kilocalories (Mifflin-St Jeor). Computed from weight, height, age and sex -- the scale does not measure it.",
    ),
});

export const weightMeasurementViewSchema = z.object({
  date: localDateSchema.describe("The day the weight belongs to. One measurement per day."),
  weightCg: z
    .int()
    .describe("Weight in hundredths of a kilogram, the exact integer the phone stores."),
  weightKg: z.number().describe("`weightCg` divided by 100, for display only."),
  /*
   * PRD_SCALE 21.1 and 22's three additions, reported by every weight read and by the upsert.
   *
   * They are on this shared shape rather than on one tool, because the three tools describe the
   * same record and a field visible through one of them and not another is how a caller comes to
   * believe a composition was not stored. It is also what makes `mue.upsert_weight_measurement`
   * able to restate what it did not change: see its own note on BR-SCALE-007.
   */
  sourceType: measurementSourceTypeSchema.describe(
    "How the weight was obtained: typed by hand, read from a scale, written by an agent, or by the server. Which scale is never reported: its identifier, address and name stay on the phone.",
  ),
  impedanceOhm: z
    .int()
    .nullable()
    .describe(
      "Raw bioimpedance in ohms, measured by the scale at the same time as the weight. Null when none was usable -- a scale that could not measure one reports an absence, never a zero. It is a field of the measurement, so it is present even when no composition could be estimated.",
    ),
  bodyComposition: bodyCompositionViewSchema
    .nullable()
    .describe(
      "Estimated composition for this weighing, or null when there is none. Absence is ordinary: it needs an impedance, a height, a birth date and a sex, and an age and BMI inside the range the equation was developed for.",
    ),
  ...metadataShape,
});

// --- errors ---------------------------------------------------------------------------

/**
 * A record the caller named and the account does not hold.
 *
 * The identifier is echoed back in `aggregateId` because the caller supplied it and it is
 * what lets an agent tell "I used a stale id" from "the account is empty". The *message*
 * names the field and never a value, per section 16.
 */
export function notFound(
  aggregateType: AggregateType,
  aggregateId: string,
  what: string,
): MueError {
  return {
    code: "http.not_found",
    message: `No ${what} with that identifier. Check the id you were given, or list them again.`,
    retryable: false,
    aggregateType,
    aggregateId,
  };
}

// --- units ----------------------------------------------------------------------------

/**
 * Kilograms as a person says them, turned into the integer the domain stores.
 *
 * Android's `Weight.ofKilogramsOrNull` *"rounds to the nearest 0.05 kg, then range-checks"*,
 * so this does the same arithmetic rather than a different one -- a scale reading of 70.13
 * is stored as 70.15 by the phone and must be stored as 70.15 here, or the same weight typed
 * two ways would produce two different rows.
 *
 * `rounded` is returned rather than hidden, and the tool reports it, so an agent can tell the
 * person what was actually written down. Rounding a *stated* value onto the domain's own
 * resolution is not the same as inventing one section 14.4 forbids: nothing is filled in that
 * the person did not say.
 */
export function kilogramsToCentigrams(kilograms: number): {
  centigrams: number;
  rounded: boolean;
} {
  const exact = kilograms * 100;
  const centigrams = Math.round(exact / WEIGHT_STEP_CENTIGRAMS) * WEIGHT_STEP_CENTIGRAMS;
  return { centigrams, rounded: Math.abs(exact - centigrams) > 1e-9 };
}

export function weightRangeError(field: string): MueError {
  return invalidPayload(
    `A weight is between ${WEIGHT_MIN_CENTIGRAMS / 100} and ${WEIGHT_MAX_CENTIGRAMS / 100} kg.`,
    field,
  );
}

/** A decimal amount, in the thousandths the food domain stores everything in. */
export function toThousandths(value: number): number {
  return Math.round(value * 1000);
}

/**
 * A quantised value, refused rather than silently moved onto its step.
 *
 * The opposite decision from [kilogramsToCentigrams], and the difference is what the caller
 * is claiming. `70.13 kg` is a reading off a scale whose resolution the domain then applies;
 * `1.3 servings` is a count, and a count that is not a quarter is a count the person did not
 * give. Rounding the second would change what was said, so it is reported with the step named
 * -- which is the whole value of an error that names the field.
 */
export function offStep(value: number, stepThousandths: number): boolean {
  return toThousandths(value) % stepThousandths !== 0;
}

// --- the write path -------------------------------------------------------------------

/** The `mutationId` a write uses: derived from the caller's key, or minted. */
export function mutationIdFor(idempotencyKey: string | undefined): string {
  return idempotencyKey === undefined
    ? Bun.randomUUIDv7()
    : mutationIdFromIdempotencyKey(idempotencyKey);
}

export interface WriteAttempt {
  readonly toolName: string;
  readonly aggregateType: AggregateType;
  readonly aggregateId: string;
  readonly op: MutationOp;
  readonly payloadSchemaVersion: number;
  /** The complete aggregate for an upsert, null for a delete (section 12.2). */
  readonly payload: unknown;
  readonly baseRevision: string | null;
  readonly mutationId: string;
}

/**
 * A write that reached the journal. `rejected` is not one of these: it is already a
 * `CallToolResult`, because there is nothing a tool can usefully do with it but return it.
 */
export interface AppliedWrite {
  readonly status: "applied" | "duplicate";
  readonly aggregateId: string;
  readonly revision: string | null;
  readonly sequence: string | null;
}

export type WriteOutcome =
  | { readonly ok: true; readonly result: AppliedWrite }
  | { readonly ok: false; readonly failure: CallToolResult };

/**
 * One write, journalled and audited, for every tool that makes one.
 *
 * Three things happen here and none of them is a tool's business to remember:
 *
 *  - the mutation goes to `@mue/domain` through `../domain-bridge.ts`, so an agent's write
 *    takes a revision from the same counter as a phone's, is appended to the same journal
 *    at the same sequence, and reaches every device by the same pull (FR-SYNC-004);
 *  - the outcome is written to `agent_audit` -- section 14.7's eight fields --
 *    whether it succeeded or was refused, because a write that was asked for and refused
 *    is exactly the event an audit exists to hold;
 *  - a business rejection from the domain becomes the tool's own structured error, with the
 *    field and the aggregate the domain named still on it, so the agent reads one error
 *    vocabulary whether the refusal came from the tool or from the rule underneath it.
 */
export async function applyWrite(
  context: ToolContext,
  attempt: WriteAttempt,
): Promise<WriteOutcome> {
  const result = await context.services.applyAgentMutation({
    userId: context.identity.userId,
    mutationId: attempt.mutationId,
    originId: context.identity.clientId,
    aggregateType: attempt.aggregateType,
    aggregateId: attempt.aggregateId,
    op: attempt.op,
    payloadSchemaVersion: attempt.payloadSchemaVersion,
    payload: attempt.payload,
    baseRevision: attempt.baseRevision,
    clientOccurredAt: new Date().toISOString(),
  });

  if (result.status === "rejected") {
    const error = result.error ?? invalidPayload("The change was refused by a business rule.");
    await context.services.recordAudit({
      agentId: context.identity.clientId,
      toolName: attempt.toolName,
      mutationId: attempt.mutationId,
      aggregates: [],
      result: "error",
      revision: null,
      error,
    });
    return { ok: false, failure: toolFailure(error) };
  }

  await context.services.recordAudit({
    agentId: context.identity.clientId,
    toolName: attempt.toolName,
    mutationId: attempt.mutationId,
    aggregates: [{ type: attempt.aggregateType, id: result.aggregateId }],
    result: "ok",
    revision: result.revision,
    error: null,
  });

  return {
    ok: true,
    result: {
      status: result.status,
      aggregateId: result.aggregateId,
      revision: result.revision,
      sequence: result.sequence,
    },
  };
}

/**
 * A refusal the tool decided on its own, before anything was submitted.
 *
 * Audited for the same reason a domain rejection is (section 14.7 lists "l'erreur
 * éventuelle" among its fields), and with no mutation id, no aggregate and no revision --
 * because nothing was written.
 */
export async function refuse(
  context: ToolContext,
  toolName: string,
  error: MueError,
): Promise<CallToolResult> {
  await context.services.recordAudit({
    agentId: context.identity.clientId,
    toolName,
    mutationId: null,
    aggregates: [],
    result: "error",
    revision: null,
    error,
  });
  return toolFailure(error);
}

/**
 * The revision a write quotes as its base.
 *
 * The caller's `expectedRevision` when it gave one; otherwise the revision the server
 * currently holds, which is what "l'auteur éditait" honestly means for a tool that has just
 * read the record in order to edit it. Null when there is nothing to be based on.
 */
export function baseRevisionOf(
  expected: string | undefined,
  current: string | undefined,
): string | null {
  return expected ?? current ?? null;
}
