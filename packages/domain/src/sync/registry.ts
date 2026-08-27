import { AGGREGATE_TYPES, type AggregateType } from "@mue/contracts";
import { activitySessionHandler } from "./activity-session";
import { customExerciseDefinitionHandler } from "./custom-exercise";
import {
  foodHandler,
  foodLogEntryHandler,
  mealPlanEntryHandler,
  recipeHandler,
} from "./food-aggregates";
import { healthProfileHandler } from "./health-profile";
import { measurementHandler } from "./measurement";
import type { AggregateHandler } from "./types";

/**
 * The aggregates this build synchronises, from PRD section 10.1's matrix — now all eight of them.
 *
 * Everything above this map is written against `AggregateHandler`, so an aggregate is a new
 * handler and a new entry rather than a new branch in the push or pull path. Adding six of them
 * at once was the proof of that: the batching, the idempotence, the cursor and the upgrade rules
 * learned nothing about any of them.
 *
 * `Record<AggregateType, …>` is exhaustive on purpose. A type added to `AGGREGATE_TYPES` with no
 * handler here stops the build, instead of reaching `applyOne`'s "unreachable" branch at runtime
 * as a mutation the contract accepts and nothing can apply.
 */
export const AGGREGATE_HANDLERS: Readonly<Record<AggregateType, AggregateHandler>> = {
  activitySession: activitySessionHandler,
  customExerciseDefinition: customExerciseDefinitionHandler,
  food: foodHandler,
  foodLogEntry: foodLogEntryHandler,
  healthProfile: healthProfileHandler,
  mealPlanEntry: mealPlanEntryHandler,
  measurement: measurementHandler,
  recipe: recipeHandler,
};

export function handlerFor(aggregateType: string): AggregateHandler | undefined {
  return (AGGREGATE_TYPES as readonly string[]).includes(aggregateType)
    ? AGGREGATE_HANDLERS[aggregateType as AggregateType]
    : undefined;
}
