import {
  ACTIVITY_ENVIRONMENTS,
  ACTIVITY_MOVEMENTS,
  MAX_CUSTOM_MOVEMENT_NAME_LENGTH,
  MAX_NOTES_LENGTH,
  PERCEIVED_EFFORT_MAX,
  PERCEIVED_EFFORT_MIN,
  SESSION_MAX_SECONDS,
  SESSION_MIN_SECONDS,
  activityEnvironmentSchema,
  activityMovementSchema,
  activitySessionPayloadV1Schema,
  instantSchema,
  localTimeSchema,
  originTypeSchema,
} from "@mue/contracts";
import { z } from "zod";

/**
 * The `ActivitySession` shapes the MCP tools read, re-exported from `@mue/contracts`.
 *
 * This file used to *transcribe* Android's domain model, with a `TODO(sync-agent)` asking for it
 * to move into `@mue/contracts` as `activitySessionPayloadV1` once that package carried more than
 * one aggregate. It does now, so the transcription is gone and what remains is a set of
 * re-exports: PLATFORM-CONTRACT section 3 makes the contracts package the single source of
 * truth, and two copies of a bound are two bounds that will one day differ.
 *
 * Two things changed in the move, and both are widenings the MCP slice could not see:
 *
 * - `source` is an enum rather than `z.string().min(1).max(40)`, and `agent` is one of its
 *   members. The eight sessions this database already holds were written with it.
 * - the payload's three child collections are described rather than left as `z.array(z.unknown())`
 *   — the V1 tools still create none of them, but a *phone* now pushes sessions that carry all
 *   three, and a read tool has to be able to describe what it returns.
 */

export {
  ACTIVITY_ENVIRONMENTS,
  ACTIVITY_MOVEMENTS,
  MAX_CUSTOM_MOVEMENT_NAME_LENGTH,
  MAX_NOTES_LENGTH,
  PERCEIVED_EFFORT_MAX,
  PERCEIVED_EFFORT_MIN,
  SESSION_MAX_SECONDS,
  SESSION_MIN_SECONDS,
  activityEnvironmentSchema,
  activityMovementSchema,
  localTimeSchema,
};

/** The stored session, as the contract states it. */
export const activitySessionPayloadSchema = activitySessionPayloadV1Schema;

export type ActivitySessionPayload = z.infer<typeof activitySessionPayloadSchema>;

/**
 * How the session entered Mue, for a session an agent created.
 *
 * `agent` is not one of Android's three `ActivitySource` ids, and that is deliberate rather than
 * an oversight: `ActivitySource.fromId` is documented as total and non-throwing precisely so a
 * newer build can add an id, and an unknown one degrades to `MANUAL`. Recording `manual` instead
 * would be a small lie about a session nobody typed, and the sync metadata cannot carry the truth
 * on its own — `originType` says the change arrived from an agent, not that the *session* was
 * created by one, and the two diverge the moment the phone edits an agent-created session.
 */
export const AGENT_ACTIVITY_SOURCE = "agent";

/** What a read tool returns: the payload plus the section 12.1 metadata. */
export const activitySessionViewSchema = activitySessionPayloadV1Schema.safeExtend({
  revision: z.string(),
  createdAt: instantSchema,
  updatedAt: instantSchema,
  deletedAt: instantSchema.nullable(),
  originType: originTypeSchema,
  originId: z.string().nullable(),
  lastMutationId: z.string(),
});

export type ActivitySessionView = z.infer<typeof activitySessionViewSchema>;
