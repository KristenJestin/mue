import type { MueScope } from "@mue/auth";
import { createActivityTool } from "./create-activity";
import { listWeightMeasurementsTool } from "./list-weight-measurements";
import type { MueTool } from "./types";

export type { MueTool, ToolContext } from "./types";
export { createActivityTool } from "./create-activity";
export {
  listWeightMeasurementsTool,
  LIST_WEIGHT_MEASUREMENTS_DEFAULT_LIMIT,
  LIST_WEIGHT_MEASUREMENTS_MAX_LIMIT,
} from "./list-weight-measurements";

/**
 * Every tool this build exposes.
 *
 * Section 14.2 and 14.3 list ten read tools and nine write ones; the vertical slice of
 * section 24 needs two of them, and the rest are additive -- a new entry in this array
 * and a file next to it, with no change to the transport, the authorization or the
 * audit.
 *
 * Nothing in this list takes a query, a table name, a path or a command. That is what
 * makes sections 14.1 and 16 -- never expose the tables, raw SQL, the file system or
 * the process -- a property of the catalogue rather than a rule someone has to
 * remember.
 */
export const MUE_TOOLS: readonly MueTool[] = [listWeightMeasurementsTool, createActivityTool];

/** True when the caller holds every scope the tool declares. */
export function isToolPermitted(tool: MueTool, granted: ReadonlySet<MueScope>): boolean {
  return tool.scopes.every((scope) => granted.has(scope));
}

/**
 * The tools an agent may see.
 *
 * Section 22.5 requires that a read scope reach no write tool. Filtering the catalogue
 * is the first half of that: an agent granted `weight:read` alone never learns that
 * `mue.create_activity` exists, so it does not spend a turn trying. The second half is
 * the check `buildMcpServer` makes when a tool is called, which does not trust this
 * list -- a client may hold a catalogue from a previous, wider authorization.
 */
export function toolsForScopes(granted: ReadonlySet<MueScope>): readonly MueTool[] {
  return MUE_TOOLS.filter((tool) => isToolPermitted(tool, granted));
}
