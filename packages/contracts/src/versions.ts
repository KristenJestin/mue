import {
  ACTIVITY_SESSION_PAYLOAD_VERSION_1,
  CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1,
} from "./activity";
import { FOOD_PAYLOAD_VERSION_1 } from "./food";
import { FOOD_LOG_ENTRY_PAYLOAD_VERSION_1 } from "./food-log";
import { HEALTH_PROFILE_PAYLOAD_VERSION_1 } from "./health-profile";
import { MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1 } from "./meal-plan";
import { MEASUREMENT_PAYLOAD_VERSION_1 } from "./measurement";
import type { AggregateType } from "./primitives";
import { RECIPE_PAYLOAD_VERSION_1 } from "./recipe";

/**
 * The payload versions this build can produce and apply, per aggregate type (PRD section 12.4).
 * A client declares its own set on every pull; the server compares against what it holds.
 *
 * It lives in its own module rather than beside one payload, because it now describes eight and
 * would otherwise make whichever file held it import the other seven for no reason of its own.
 *
 * `satisfies Record<AggregateType, …>` is the load-bearing part: an aggregate type added to
 * `AGGREGATE_TYPES` without a version listed here stops the build, instead of reaching
 * `validateMutation` as an `undefined` lookup that would read as "this server supports no
 * version of it" and reject every mutation with an upgrade demand no upgrade could satisfy.
 */
export const CURRENT_PAYLOAD_SCHEMA_VERSIONS = {
  activitySession: [ACTIVITY_SESSION_PAYLOAD_VERSION_1],
  customExerciseDefinition: [CUSTOM_EXERCISE_DEFINITION_PAYLOAD_VERSION_1],
  food: [FOOD_PAYLOAD_VERSION_1],
  foodLogEntry: [FOOD_LOG_ENTRY_PAYLOAD_VERSION_1],
  healthProfile: [HEALTH_PROFILE_PAYLOAD_VERSION_1],
  mealPlanEntry: [MEAL_PLAN_ENTRY_PAYLOAD_VERSION_1],
  measurement: [MEASUREMENT_PAYLOAD_VERSION_1],
  recipe: [RECIPE_PAYLOAD_VERSION_1],
} as const satisfies Record<AggregateType, readonly number[]>;
