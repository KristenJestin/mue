import type { DatabaseHandle } from "@mue/db";
import { authorMutation, createActivitySession } from "@mue/domain";
import type {
  AgentMutationCommand,
  AgentMutationResult,
  CreateActivityCommand,
  CreateActivityResult,
} from "./services";

/**
 * The one seam between the MCP tools and the business rules.
 *
 * PRD section 20.2 and PLATFORM-CONTRACT section 5 are explicit: the MCP tool for
 * `create_activity` and the sync handler for an `activitySession` upsert call the same function.
 * They now do. `@mue/domain` exports `createActivitySession`, `packages/domain/src/sync/
 * activity-session.ts` is the only implementation of section 13.3's rules for a session, and this
 * file is a direct call rather than the run-time lookup it used to be.
 *
 * What it replaced is worth recording, because the shape recurs. While the domain had no activity
 * write, this module resolved the export by name at call time and fell back to
 * `./provisional-activity-write.ts`, and `domain-bridge.test.ts` asserted *which of the two was
 * live* — a deliberate tripwire, so that "the scaffolding is still in use" was a visible test
 * result rather than a discovery made in production. The tripwire fired, the scaffolding is
 * deleted, and the cast it needed is gone with it.
 */

export type CreateActivitySessionService = (
  database: DatabaseHandle,
  command: CreateActivityCommand,
) => Promise<CreateActivityResult>;

/**
 * Kept, and now a constant.
 *
 * `mcp/index.ts` exports it and `domain-bridge.test.ts` asserts it, so removing it would be a
 * change to two files for no gain; keeping it means the question "is a rule still implemented
 * twice?" has an answer a test can read, for this rule and for the next one to be written this
 * way.
 */
export function isUsingProvisionalActivityWrite(): boolean {
  return false;
}

export function createActivitySessionService(): CreateActivitySessionService {
  return createActivitySession;
}

/**
 * The same seam, for the twenty-six tools that came after the vertical slice.
 *
 * `create_activity` got a function of its own because it was first and because the domain
 * export it waited for was written for it. Every write tool since -- a weight, a tombstone, a
 * profile, a recipe, a journal line -- differs from it only in the envelope it fills in, so they
 * share one: `@mue/domain`'s `authorMutation`, which is `createActivitySession` with the payload
 * left to the caller and the same `submitMutation` underneath.
 *
 * That underneath is the whole point, and it is what makes FR-SYNC-004 true for all of them at
 * once. An agent's write takes a revision from the same counter, is appended to `sync_journal`
 * with the same sequence, is recorded in `mutation_log` under the same idempotency rule, and is
 * handed to the phone by the same pull. There is no second write path for a tool to drift onto,
 * because there is no second function to drift into.
 */
export type AgentMutationService = (
  database: DatabaseHandle,
  command: AgentMutationCommand,
) => Promise<AgentMutationResult>;

export function createAgentMutationService(): AgentMutationService {
  return async (database, command) =>
    authorMutation(database, {
      userId: command.userId,
      mutationId: command.mutationId,
      origin: { type: "agent", id: command.originId },
      aggregateType: command.aggregateType,
      aggregateId: command.aggregateId,
      op: command.op,
      payloadSchemaVersion: command.payloadSchemaVersion,
      payload: command.payload,
      baseRevision: command.baseRevision,
      occurredAt: new Date(command.clientOccurredAt),
    });
}
