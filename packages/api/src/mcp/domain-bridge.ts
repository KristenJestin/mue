import type { DatabaseHandle } from "@mue/db";
import * as domain from "@mue/domain";
import { provisionalCreateActivitySession } from "./provisional-activity-write";
import type { CreateActivityCommand, CreateActivityResult } from "./services";

/**
 * The one seam between the MCP tools and the business rules.
 *
 * PRD section 20.2 and PLATFORM-CONTRACT section 5 are explicit: the MCP tool for
 * `create_activity` and the sync handler for an `activitySession` upsert call the same
 * function. That function is being written in `@mue/domain` in parallel with this
 * package, so the binding is made at run time rather than at import time: the moment
 * `@mue/domain` exports it, `/mcp` uses it and nothing here changes.
 *
 * The cast is the price of that. It is confined to this file, the expected signature is
 * written down as a type, and `domain-bridge.test.ts` asserts which of the two paths is
 * live, so "the fallback is still in use" is a visible test result rather than a
 * discovery made in production.
 *
 * TODO(sync-agent): once `@mue/domain` exports `createActivitySession`, delete
 * `./provisional-activity-write.ts` and reconcile the signature below with it.
 */

export type CreateActivitySessionService = (
  database: DatabaseHandle,
  command: CreateActivityCommand,
) => Promise<CreateActivityResult>;

const DOMAIN_EXPORT_NAME = "createActivitySession";

function resolveFromDomain(): CreateActivitySessionService | undefined {
  const candidate = (domain as Record<string, unknown>)[DOMAIN_EXPORT_NAME];
  return typeof candidate === "function" ? (candidate as CreateActivitySessionService) : undefined;
}

/** True while the business rule still lives in this package rather than in `@mue/domain`. */
export function isUsingProvisionalActivityWrite(): boolean {
  return resolveFromDomain() === undefined;
}

export function createActivitySessionService(): CreateActivitySessionService {
  return resolveFromDomain() ?? provisionalCreateActivitySession;
}
