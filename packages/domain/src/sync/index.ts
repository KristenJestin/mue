// The sync engine. Every rule PRD sections 11 to 13 states about a synchronised
// aggregate is implemented here once, and Hono routes, TanStack Start server
// functions and MCP tools all call these functions (section 20.2).

export { activitySessionHandler } from "./activity-session";
export {
  buildActivitySessionUpsert,
  buildMeasurementDelete,
  buildMeasurementUpsert,
  createActivitySession,
  deleteMeasurement,
  listMeasurements,
  readMeasurementRevision,
  upsertMeasurement,
  type ActivitySessionView,
  type AuthoredMeasurement,
  type CreateActivitySessionCommand,
  type CreateActivitySessionResult,
  type MeasurementRevision,
  type MeasurementView,
} from "./authoring";
export { decodeCursor, encodeCursor } from "./cursor";
export { customExerciseDefinitionHandler, foldExerciseName } from "./custom-exercise";
export {
  invalidCursor,
  invalidRequest,
  mueError,
  SyncRequestError,
  unauthenticated,
  type ErrorContext,
} from "./errors";
export {
  foodHandler,
  foodLogEntryHandler,
  mealPlanEntryHandler,
  recipeHandler,
} from "./food-aggregates";
export { healthProfileHandler, mergeHealthProfile } from "./health-profile";
export { measurementHandler } from "./measurement";
export { refusesResurrection, type OpaqueState } from "./opaque";
export { readChanges, readLastAndroidSyncAt } from "./pull";
export { submitMutation, submitMutations } from "./push";
export { AGGREGATE_HANDLERS, handlerFor } from "./registry";
export type {
  AggregateHandler,
  AppliedOutcome,
  ApplyOutcome,
  MutationOrigin,
  RejectedOutcome,
  SyncContext,
} from "./types";
export { readMutationId, validateMutation, type MutationValidation } from "./validate";
