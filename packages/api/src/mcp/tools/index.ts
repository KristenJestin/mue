import type { MueScope } from "@mue/auth";
import {
  deleteActivityTool,
  getActivityStatisticsTool,
  getActivityTool,
  listActivitiesTool,
  updateActivityTool,
} from "./activity";
import { createActivityTool } from "./create-activity";
import {
  createCustomExerciseTool,
  deleteCustomExerciseTool,
  getCustomExerciseTool,
  listCustomExercisesTool,
  updateCustomExerciseTool,
} from "./custom-exercise";
import { createFoodTool, deleteFoodTool, updateFoodTool } from "./food";
import { createFoodLogTool, deleteFoodLogTool, updateFoodLogTool } from "./food-log";
import { getHealthProfileTool, updateHealthProfileTool } from "./health-profile";
import { listWeightMeasurementsTool } from "./list-weight-measurements";
import { createRecipeTool, deleteRecipeTool, updateRecipeTool } from "./recipe";
import { getSyncStatusTool } from "./status";
import type { MueTool } from "./types";
import {
  deleteWeightMeasurementTool,
  getWeightMeasurementTool,
  getWeightStatisticsTool,
  upsertWeightMeasurementTool,
} from "./weight";

export type { MueTool, ToolContext } from "./types";
export { createActivityTool } from "./create-activity";
export {
  listWeightMeasurementsTool,
  LIST_WEIGHT_MEASUREMENTS_DEFAULT_LIMIT,
  LIST_WEIGHT_MEASUREMENTS_MAX_LIMIT,
} from "./list-weight-measurements";

/**
 * The catalogue, grouped the way PRD section 14 groups it.
 *
 * It used to be one flat array of two. Twenty-eight in one list reads as a wall, and the
 * question a reader actually has -- *"is every tool section 14.2 lists here?"* -- is answered
 * by comparing a short group against a short list, not by scanning. So the groups are the
 * document's own: the reads of 14.2, the writes of 14.3, and the food tools 14.3 defers to
 * PRD_FOOD 21.5.
 *
 * `MUE_TOOLS` is still one flat readonly array and `buildMcpServer` still walks it unchanged.
 * The grouping is how the list is *written*, not a second structure the registration loop has
 * to know about -- a registry that knew about groups would be a place for a group to be left
 * out.
 *
 * ## The names, where they differ from section 14.3
 *
 * Section 14.3 lists the food tools as `log_meal`, `update_meal_log` and `delete_meal_log`, and
 * then says the names may evolve with the food model. They did: PRD_FOOD 21.5 names them
 * `create_food_log`, `update_food_log` and `delete_food_log`, and PRD_FOOD 8.1 explains why it
 * matters rather than being a spelling preference -- **there is no meal object**. A moment of
 * the day is a label each line carries, not a thing that can be created, so a tool called
 * `log_meal` would tell an agent that one call records a meal. It does not: chicken, rice and
 * salad at dinner are three lines. The name that says so is the one that ships.
 *
 * `delete_food` is here and is in neither of section 14.3's two lists. Its own closing sentence
 * asks for it -- *"les capacités de création complète, modification et suppression sont
 * obligatoires"* -- and PRD_FOOD 21.5 names it outright.
 *
 * ## What is deliberately absent
 *
 * PRD_FOOD 21.5 also names six food *read* tools and a `plan_meal`/`unplan_meal` pair for meal
 * proposals. Section 14.2's read list predates the food model and carries none of them, and
 * section 14 itself names no meal-plan tool anywhere; the only route to them is section 17's
 * delegation to PRD_FOOD 21. They are a coherent increment of their own -- the reads are what
 * would let an agent find the identifier a food write needs -- and they are not this one.
 */

/** Section 14.2, in the order the section lists them. */
const READ_TOOLS: readonly MueTool[] = [
  getSyncStatusTool,
  getHealthProfileTool,
  listWeightMeasurementsTool,
  getWeightMeasurementTool,
  listActivitiesTool,
  getActivityTool,
  listCustomExercisesTool,
  getCustomExerciseTool,
  getWeightStatisticsTool,
  getActivityStatisticsTool,
];

/** Section 14.3's first list, in the order the section lists them. */
const WRITE_TOOLS: readonly MueTool[] = [
  upsertWeightMeasurementTool,
  deleteWeightMeasurementTool,
  updateHealthProfileTool,
  createActivityTool,
  updateActivityTool,
  deleteActivityTool,
  createCustomExerciseTool,
  updateCustomExerciseTool,
  deleteCustomExerciseTool,
];

/** Section 14.3's second list, under the names PRD_FOOD 21.5 settles. */
const FOOD_TOOLS: readonly MueTool[] = [
  createRecipeTool,
  updateRecipeTool,
  deleteRecipeTool,
  createFoodTool,
  updateFoodTool,
  deleteFoodTool,
  createFoodLogTool,
  updateFoodLogTool,
  deleteFoodLogTool,
];

/**
 * Every tool this build exposes.
 *
 * Nothing in this list takes a query, a table name, a path or a command. That is what makes
 * sections 14.1 and 16 -- never expose the tables, raw SQL, the file system or the process -- a
 * property of the catalogue rather than a rule someone has to remember.
 */
export const MUE_TOOLS: readonly MueTool[] = [...READ_TOOLS, ...WRITE_TOOLS, ...FOOD_TOOLS];

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
 *
 * Every scope a tool declares must be held, which is what makes section 15.2's separate
 * deletion permission bite: a delete tool declares its domain's write scope *and*
 * `data:delete`, so an agent trusted to record weights still cannot remove one.
 */
export function toolsForScopes(granted: ReadonlySet<MueScope>): readonly MueTool[] {
  return MUE_TOOLS.filter((tool) => isToolPermitted(tool, granted));
}
