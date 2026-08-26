import { instantSchema, localDateSchema } from "@mue/contracts";
import { z } from "zod";

/**
 * The `ActivitySession` payload, transcribed from Android's own domain model.
 *
 * TODO(sync-agent): move this to `@mue/contracts` as `activitySessionPayloadV1` and
 * add `"activitySession"` to `AGGREGATE_TYPES`. `@mue/contracts` ships one aggregate
 * today (`measurement`), so there is nothing here to reuse yet, and PLATFORM-CONTRACT
 * section 3 makes the contracts package the single source of truth. This file exists
 * so the MCP slice can be built and proven before that lands, and it is deliberately a
 * transcription with no rule of its own: the bounds below are Android's constants, not
 * new policy.
 *
 * Sources, all under `apps/android/.../domain/model/`:
 *   `Movement.kt`            - the movement ids
 *   `ActivityEnvironment.kt` - the environment and source ids
 *   `ActivitySession.kt`     - the custom-name and notes lengths
 *   `ActivityUnits.kt`       - the session duration range and the 1-10 effort scale
 *
 * The whole session is one opaque aggregate (PLATFORM-CONTRACT decision 1), so the
 * three child collections travel inside it and never merge on their own ids.
 */

export const ACTIVITY_MOVEMENTS = [
  "walking",
  "running",
  "cycling",
  "swimming",
  "strength_training",
  "rowing",
  "elliptical",
  "hiking",
  "yoga",
  "climbing",
  "dancing",
  "pilates",
  "mobility",
  "team_sport",
  "other",
] as const;

export const ACTIVITY_ENVIRONMENTS = ["indoor", "outdoor", "unknown"] as const;

/** `ActivitySession.MAX_CUSTOM_MOVEMENT_NAME_LENGTH`. */
export const MAX_CUSTOM_MOVEMENT_NAME_LENGTH = 60;
/** `ActivitySession.MAX_NOTES_LENGTH`. */
export const MAX_NOTES_LENGTH = 500;
/** `ActivityDuration.SESSION_MIN_SECONDS` and `SESSION_MAX_SECONDS`, 1 min to 99 h 59. */
export const SESSION_MIN_SECONDS = 60;
export const SESSION_MAX_SECONDS = 359_940;
/** `PerceivedEffort.MIN` and `MAX`, the 1-to-10 scale of the Activity PRD 8.2. */
export const PERCEIVED_EFFORT_MIN = 1;
export const PERCEIVED_EFFORT_MAX = 10;

/**
 * How the session entered Mue, as Android's `ActivitySource` spells it.
 *
 * `agent` is not one of Android's three values, and that is deliberate rather than an
 * oversight: `ActivitySource.fromId` is documented as total and non-throwing precisely
 * so a newer build can add an id, and an unknown one degrades to `MANUAL`. Recording
 * `manual` instead would be a small lie about a session nobody typed, and the sync
 * metadata cannot carry the truth on its own -- `originType` says the change arrived
 * from an agent, not that the *session* was created by one, and the two diverge the
 * moment the phone edits an agent-created session.
 *
 * TODO(sync-agent): confirm this id when `activitySession` joins `@mue/contracts`.
 */
export const AGENT_ACTIVITY_SOURCE = "agent";

/** Local wall time, minute precision, exactly as Room stores `started_at_time`. */
export const localTimeSchema = z
  .string()
  .regex(/^([01]\d|2[0-3]):[0-5]\d$/, "expected a 24-hour local time such as 18:00");

export const activityMovementSchema = z.enum(ACTIVITY_MOVEMENTS);
export const activityEnvironmentSchema = z.enum(ACTIVITY_ENVIRONMENTS);

/**
 * One stored session. `metrics`, `equipment` and `exercises` are the `jsonb` columns
 * of `mue_app.activity_sessions`; V1's agent tools create none of them, and they are
 * described here so a reader of this file is not left thinking a session has no
 * children.
 */
export const activitySessionPayloadSchema = z.object({
  id: z.uuid(),
  movement: activityMovementSchema,
  customMovementName: z.string().min(1).max(MAX_CUSTOM_MOVEMENT_NAME_LENGTH).nullable(),
  environment: activityEnvironmentSchema,
  startedOn: localDateSchema,
  startedAtTime: localTimeSchema.nullable(),
  durationSeconds: z.int().min(SESSION_MIN_SECONDS).max(SESSION_MAX_SECONDS),
  perceivedEffort: z.int().min(PERCEIVED_EFFORT_MIN).max(PERCEIVED_EFFORT_MAX).nullable(),
  notes: z.string().min(1).max(MAX_NOTES_LENGTH).nullable(),
  source: z.string().min(1).max(40),
  metrics: z.array(z.unknown()),
  equipment: z.array(z.unknown()),
  exercises: z.array(z.unknown()),
});

export type ActivitySessionPayload = z.infer<typeof activitySessionPayloadSchema>;

/** What a read tool returns: the payload plus the section 12.1 metadata. */
export const activitySessionViewSchema = activitySessionPayloadSchema.extend({
  revision: z.string(),
  createdAt: instantSchema,
  updatedAt: instantSchema,
  deletedAt: instantSchema.nullable(),
  originType: z.enum(["android", "agent", "server"]),
  originId: z.string().nullable(),
  lastMutationId: z.string(),
});

export type ActivitySessionView = z.infer<typeof activitySessionViewSchema>;
