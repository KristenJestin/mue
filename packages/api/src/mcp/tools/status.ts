import { CURRENT_PAYLOAD_SCHEMA_VERSIONS, instantSchema, sequenceSchema } from "@mue/contracts";
import { z } from "zod";
import { envelopeSchema, toolSuccess } from "../errors";
import { serverTimeShape } from "./shared";
import type { MueTool, ToolContext } from "./types";

/**
 * Section 14.2's `get_sync_status`, and FR-SYNC-008's whole point.
 *
 * The section that matters most here is FR-SYNC-008: *"Un agent n'obtient aucune fausse
 * garantie de fraîcheur."* Every list tool already carries `lastAndroidSyncAt` for that
 * reason; this tool exists so an agent can ask the question on its own, before it decides
 * whether to answer from what it can read or to say that the phone has not called in.
 *
 * ## What it deliberately does not report
 *
 * No counts per domain, no dates, no weights, no titles. Section 15.2 splits the scopes by
 * domain, and a status tool that said "eleven weight measurements" would be a weight read
 * reachable without `weight:read`. What is left is the journal's own shape -- its head
 * sequence, how many changes it holds, and three instants -- which is the account's
 * operational state and not its health record.
 *
 * ## Why `profile:read`
 *
 * Section 15.2's scopes are per domain and this tool belongs to none of them. `profile:read`
 * is the narrowest thing in the vocabulary that means "may read something about this
 * account at all", so it is what is asked for. Inventing a `sync:read` outside section
 * 15.2's list would have been a scope no consent page describes and no agent knows to ask
 * for.
 */

const inputSchema = {};

const dataSchema = z.object({
  journalSequence: sequenceSchema.describe(
    "The newest position in this account's change journal. It is the only ordering the system has; `0` means nothing has ever been recorded.",
  ),
  changeCount: z
    .int()
    .describe("How many changes the journal holds for this account, all aggregates together."),
  lastChangeAt: instantSchema
    .nullable()
    .describe("When the server last accepted any change, whoever authored it."),
  lastAgentChangeAt: instantSchema
    .nullable()
    .describe("When the server last accepted a change authored by an agent."),
  supportedPayloadVersions: z
    .record(z.string(), z.array(z.int()))
    .describe(
      "The payload schema versions this server can apply, per aggregate kind. Reference information about the contract, not about the account.",
    ),
  ...serverTimeShape,
  lastAndroidSyncAt: instantSchema
    .nullable()
    .describe(
      "The last moment the server saw the Android phone synchronise, or null if it never has. Anything the person recorded on the phone after this instant has not reached the server yet.",
    ),
});

async function handler(context: ToolContext) {
  const status = await context.services.syncStatus(context.identity.userId);
  return toolSuccess({
    journalSequence: status.journalSequence,
    changeCount: status.changeCount,
    lastChangeAt: status.lastChangeAt,
    lastAgentChangeAt: status.lastAgentChangeAt,
    supportedPayloadVersions: Object.fromEntries(
      Object.entries(CURRENT_PAYLOAD_SCHEMA_VERSIONS).map(([type, versions]) => [
        type,
        [...versions],
      ]),
    ),
    serverTime: new Date().toISOString(),
    lastAndroidSyncAt: status.lastAndroidSyncAt,
  });
}

export const getSyncStatusTool: MueTool = {
  name: "mue.get_sync_status",
  title: "Get synchronisation status",
  description: [
    "Ask how fresh this server's copy of the person's data is, before relying on it.",
    "",
    "`lastAndroidSyncAt` is the last moment the phone synchronised. Anything recorded on the",
    "phone after that instant is not here yet, so a reading you cannot see may still exist. When",
    "it is null the phone has never synchronised and this server holds only what agents wrote.",
    "",
    "Nothing here is a health value: no weights, no sessions, no meals, no counts of them.",
  ].join("\n"),
  inputSchema,
  outputSchema: envelopeSchema(dataSchema).shape,
  annotations: {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
  scopes: ["profile:read"],
  handler: (context) => handler(context),
};
