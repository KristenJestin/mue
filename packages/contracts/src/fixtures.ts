import { join } from "node:path";
import type { ZodType } from "zod";
import {
  type ActivitySessionPayloadV1,
  type CustomExerciseDefinitionPayloadV1,
  activitySessionPayloadV1Schema,
  customExerciseDefinitionPayloadV1Schema,
} from "./activity";
import { cursorSchema } from "./cursor";
import { type MueError, mueErrorSchema } from "./errors";
import { type FoodPayloadV1, foodPayloadV1Schema } from "./food";
import { type FoodLogEntryPayloadV1, foodLogEntryPayloadV1Schema } from "./food-log";
import { type HealthProfilePayloadV1, healthProfilePayloadV1Schema } from "./health-profile";
import { type MealPlanEntryPayloadV1, mealPlanEntryPayloadV1Schema } from "./meal-plan";
import { type MeasurementPayloadV1, measurementPayloadV1Schema } from "./measurement";
import { type AggregateMeta, aggregateMetaSchema } from "./meta";
import { type MutationEnvelope, mutationEnvelopeSchema } from "./mutation";
import { canonicalJson } from "./openapi";
import { type RecipePayloadV1, recipePayloadV1Schema } from "./recipe";
import {
  type PullRequest,
  type PullResponse,
  type PushRequest,
  type PushResponse,
  pullRequestSchema,
  pullResponseSchema,
  pushRequestSchema,
  pushResponseSchema,
} from "./sync";

/**
 * Where the JVM contract test reads them from. That test is offline: it parses each
 * fixture into its hand-written DTO, re-serialises and compares as a JSON tree, so a
 * field the server added and Kotlin ignores shows up as a diff, and a field Kotlin
 * requires and the server dropped fails to parse. No server, no network, no emulator.
 */
export const FIXTURE_RESOURCE_PATH = [
  "apps",
  "android",
  "app",
  "src",
  "test",
  "resources",
  "contract",
] as const;

export interface ContractFixture {
  /** File name under the fixture directory. */
  readonly file: string;
  /** The openapi.json component this instance is a member of. */
  readonly schema: string;
  readonly kind: "valid" | "edge" | "error";
  readonly description: string;
  readonly value: unknown;
  /** Checked at emit time, so no instance ships that its own schema rejects. */
  readonly validator: ZodType;
}

const DEVICE_ORIGIN = { type: "android", id: "device-7f3c1a04" } as const;

const MUTATION_ID = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6";
const TOMBSTONE_MUTATION_ID = "0198f0a1-9e8d-7c6b-b5a4-938271605f4e";

/**
 * Past 2^53, so a client that read the sequence as a JSON number instead of a decimal
 * string fails this fixture rather than a user's data three months later.
 */
const LARGE_SEQUENCE = "9007199254740993";
const NEXT_SEQUENCE = "9007199254740994";
const THIRD_SEQUENCE = "9007199254740995";
const FOURTH_SEQUENCE = "9007199254740996";
const FIFTH_SEQUENCE = "9007199254740997";

const CURSOR = toBase64Url(JSON.stringify({ v: 1, seq: LARGE_SEQUENCE }));

const validMeasurement = {
  date: "2026-08-25",
  weightCg: 7_845,
} satisfies MeasurementPayloadV1;

/** Two boundaries at once: the minimum legal weight, recorded on a leap day. */
const edgeMeasurement = {
  date: "2028-02-29",
  weightCg: 3_000,
} satisfies MeasurementPayloadV1;

const PROFILE_MUTATION_ID = "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071";

/**
 * The owner's own profile, as his phone journalled it and could not send: 171 cm, born on the
 * 18th of November 1998. It is here rather than a rounder invention because the bug this
 * aggregate exists to close was a *value* bug — a UUIDv4 where the schema said v7 — and a
 * fixture built from made-up numbers would have looked exactly as green.
 */
const validHealthProfile = {
  heightCm: 171,
  birthDate: "1998-11-18",
} satisfies HealthProfilePayloadV1;

/**
 * The cleared profile: both fields stated as null rather than omitted. It is the instance that
 * proves "the user emptied this" is expressible, which is what section 13.4's field-by-field
 * merge needs to tell apart from "this client did not mention it".
 */
const clearedHealthProfile = {
  heightCm: null,
  birthDate: null,
} satisfies HealthProfilePayloadV1;

const SESSION_ID = "3a0f7b26-9c41-4a5e-8d13-6f2b8e04c751";
const TIMER_SESSION_ID = "5c81d0a4-2e77-4b39-9a06-1d4f7c3b8e92";
const BENCH_PRESS_ID = "8b2b1c9a-3a4f-4b1c-9d5e-7f8a0b1c2d3e";
const SPLIT_SQUAT_ID = "d41f6c58-7b90-4e2a-8c31-5a6b7c8d9e0f";

/**
 * A real strength session: two exercises, four sets, an estimated-energy metric and the gear.
 *
 * It is deliberately the *complicated* case rather than a skeleton, because the whole argument
 * for making the session one aggregate is that its children travel inside it — a fixture with
 * three empty arrays would round-trip green through a Kotlin DTO that could not hold a set.
 */
const validActivitySession = {
  id: SESSION_ID,
  movement: "strength_training",
  customMovementName: null,
  environment: "indoor",
  startedOn: "2026-08-25",
  startedAtTime: "18:30",
  durationSeconds: 3_600,
  perceivedEffort: 7,
  notes: "Felt strong on the second set.",
  source: "manual",
  metrics: [{ kind: "estimated_energy", value: 380, source: "manual" }],
  equipment: [
    { equipmentType: "barbell", customName: null, position: 0 },
    { equipmentType: "other", customName: "Home rack", position: 1 },
  ],
  exercises: [
    {
      id: "1f6a2d70-4c8b-4e15-9f27-3b6d0a4e8c19",
      position: 0,
      notes: null,
      definition: {
        id: BENCH_PRESS_ID,
        name: "Bench press",
        trackingMode: "weight_and_reps",
        equipment: "barbell",
        isCustom: false,
      },
      sets: [
        {
          id: "2c7b3e81-5d9c-4f26-8a38-4c7e1b5f9d20",
          position: 0,
          setType: "warmup",
          repetitions: 10,
          loadGrams: 40_000,
          durationSeconds: null,
          perceivedEffort: null,
        },
        {
          id: "3d8c4f92-6e0d-4a37-9b49-5d8f2c6a0e31",
          position: 1,
          setType: "working",
          repetitions: 5,
          loadGrams: 82_500,
          durationSeconds: null,
          perceivedEffort: null,
        },
      ],
    },
    {
      id: "4e9d5a03-7f1e-4b48-8c50-6e9a3d7b1f42",
      position: 1,
      notes: "Left leg lagging.",
      definition: {
        id: SPLIT_SQUAT_ID,
        name: "Bulgarian split squat",
        trackingMode: "weight_and_reps",
        equipment: "dumbbells",
        isCustom: true,
      },
      sets: [
        {
          id: "5f0e6b14-8a2f-4c59-9d61-7f0b4e8c2a53",
          position: 0,
          setType: "working",
          repetitions: 8,
          loadGrams: 20_000,
          durationSeconds: null,
          perceivedEffort: null,
        },
        {
          id: "6a1f7c25-9b30-4d6a-8e72-8a1c5f9d3b64",
          position: 1,
          setType: "drop",
          repetitions: 12,
          loadGrams: null,
          durationSeconds: null,
          perceivedEffort: 9,
        },
      ],
    },
  ],
} satisfies ActivitySessionPayloadV1;

/**
 * The forty-second session the timer wrote, on the movement that requires a free name.
 *
 * Its duration is the boundary this contract deliberately *widens*. `SESSION_MIN_SECONDS` is 60
 * and belongs to the manual form alone, while `ActivityDuration.TIMED_MIN_SECONDS` is 1 and is
 * what a stored session can actually be. A contract that had copied the form's floor would have
 * refused this row for ever, and no screen could have edited it back into range — the manual form
 * has no seconds field. That is why this instance is here rather than a rounder one.
 */
const edgeActivitySession = {
  id: TIMER_SESSION_ID,
  movement: "other",
  customMovementName: "Bouldering warm-up",
  environment: "unknown",
  startedOn: "2028-02-29",
  startedAtTime: null,
  durationSeconds: 40,
  perceivedEffort: null,
  notes: null,
  source: "timer",
  metrics: [],
  equipment: [],
  exercises: [],
} satisfies ActivitySessionPayloadV1;

const validCustomExercise = {
  id: SPLIT_SQUAT_ID,
  name: "Bulgarian split squat",
  trackingMode: "weight_and_reps",
  equipment: "dumbbells",
} satisfies CustomExerciseDefinitionPayloadV1;

/** The longest name `ExerciseDefinition.MAX_NAME_LENGTH` allows, and no equipment at all. */
const edgeCustomExercise = {
  id: "9e0a1b2c-3d4e-4f50-8a61-7b8c9d0e1f23",
  name: "Weighted single-leg calf raise on a deficit step, slow ecc",
  trackingMode: "weight_and_duration",
  equipment: null,
} satisfies CustomExerciseDefinitionPayloadV1;

const FOOD_ID = "7c3d9e15-6a2b-4f80-9c47-1e5d8a2b3c40";
const RECIPE_ID = "b5e8f271-0c3a-4d69-8e12-9f4a7b0c5d38";

/** A product copied from Open Food Facts, with its brand, its barcode and its usual serving. */
const validFood = {
  id: FOOD_ID,
  name: "Skyr nature",
  source: "open_food_facts",
  referenceUnit: "gram",
  rawLabel: "Raw",
  cookedLabel: "Cooked",
  energyMilliKcal: 63_000,
  proteinMilligrams: 10_400,
  carbsMilligrams: 4_000,
  fatMilligrams: 200,
  brand: "Isey",
  barcode: "5701092103246",
  sourceId: "5701092103246",
  sourceVersion: "2026-07-14",
  servingLabel: "pot",
  servingThousandths: 150_000,
} satisfies FoodPayloadV1;

/**
 * A custom food whose macros are genuinely unknown and whose energy is genuinely zero.
 *
 * The two are different facts and this instance states both at once. `energyMilliKcal: 0` is a
 * measured zero — black coffee — while the absent protein, carbs, fat and fibre keys are "nobody
 * knows". PRD_FOOD 13.1 forbids turning the second into the first, and a payload that wrote `0`
 * for an unknown macro would have the server hand that invention back as fact on the next pull.
 * An absent key is how the distinction survives the wire, and this fixture is what stops a
 * Kotlin DTO quietly defaulting it to zero.
 */
const edgeFood = {
  id: "0d1e2f30-4a5b-4c60-9d71-8e9f0a1b2c34",
  name: "Cafe noir sans sucre",
  source: "custom",
  referenceUnit: "millilitre",
  rawLabel: "Raw",
  cookedLabel: "Cooked",
  energyMilliKcal: 0,
} satisfies FoodPayloadV1;

const validRecipe = {
  id: RECIPE_ID,
  name: "Skyr bowl",
  type: "breakfast",
  baseServings: 2,
  isFavourite: true,
  ingredients: [
    {
      id: "c6f9a382-1d4b-4e7a-9f23-0a5b8c1d6e49",
      foodId: FOOD_ID,
      quantityThousandths: 300_000,
      unit: "gram",
      position: 0,
      foodName: "Skyr nature",
    },
    {
      id: "d70ab493-2e5c-4f8b-8034-1b6c9d2e7f50",
      foodId: "e81bc504-3f6d-4a9c-9145-2c7d0e3f8a61",
      quantityThousandths: 80_000,
      unit: "gram",
      position: 1,
      foodName: "Myrtilles",
    },
  ],
  description: "The one that survives a 6 a.m. start.",
  prepTimeMinutes: 5,
  steps: ["Spoon the skyr into two bowls.", "Top with the blueberries."],
} satisfies RecipePayloadV1;

/**
 * The smallest recipe PRD_FOOD 21.2 allows: one ingredient, and no `steps` key at all.
 *
 * `steps` is absent rather than `[]` because absent is what is already in the outboxes —
 * `RecipePayload.steps` defaults to the empty list and `SyncJson` does not encode defaults. A
 * contract that required the key would refuse every stepless recipe a phone has journalled.
 */
const edgeRecipe = {
  id: "f92cd615-4a7e-4b0d-8256-3d8e1f4a9b72",
  name: "Oeufs durs",
  type: "snack",
  baseServings: 12,
  isFavourite: false,
  ingredients: [
    {
      id: "0a3de726-5b8f-4c1e-9367-4e9f2a5b0c83",
      foodId: "1b4ef837-6c90-4d2f-8478-5f0a3b6c1d94",
      quantityThousandths: 60_000,
      unit: "gram",
      position: 0,
    },
  ],
} satisfies RecipePayloadV1;

const MEAL_PLAN_ID = "2026-09-01:dinner";
const PLANNED_LOG_ENTRY_ID = "2c5fa948-7d01-4e30-9589-6a1b4c7d2e05";

/**
 * A line logged from the proposal below, measured in servings.
 *
 * Two constraints meet in it and neither is visible in the shape. `quantityUnit: "serving"`
 * selects the *consumed* scale — 0.25 to 10 in quarters — so `1500` is legal here where `1333`
 * would not be, though both are integers well inside the other scale's range. And `fromPlan`
 * carries the meal plan's own identifier, spelled with a colon: the `/` Android wrote until this
 * change is not in `aggregateIdSchema`'s alphabet and never was.
 */
const validFoodLogEntry = {
  id: PLANNED_LOG_ENTRY_ID,
  consumedOn: "2026-09-01",
  consumedAt: "20:15",
  slot: "dinner",
  kind: "recipe",
  title: "Skyr bowl",
  estimation: "measured",
  weighedCooked: false,
  energyMilliKcal: 284_000,
  proteinMilligrams: 24_600,
  carbsMilligrams: 18_200,
  fatMilligrams: 3_100,
  sourceRef: RECIPE_ID,
  amountLabel: "1.5 servings",
  quantityThousandths: 1_500,
  quantityUnit: "serving",
  fromPlan: MEAL_PLAN_ID,
} satisfies FoodLogEntryPayloadV1;

/**
 * A quick add: a title, an approximate energy, and no amount of any kind.
 *
 * `LoggedAmount.Unmeasured` has no unit, so `quantityThousandths` and `quantityUnit` are both
 * absent — together, which is the pair rule the payload refines. It is the instance that proves a
 * line needs neither a food, nor a recipe, nor a quantity to be a complete aggregate that a
 * client can render on its own (PRD section 10.2).
 */
const edgeFoodLogEntry = {
  id: "3d60ba59-8e12-4f41-8690-7b2c5d8e3f16",
  consumedOn: "2026-09-01",
  consumedAt: "00:00",
  slot: "snack",
  kind: "quick",
  title: "Restaurant, no idea",
  estimation: "approximate",
  weighedCooked: false,
  energyMilliKcal: 750_000,
} satisfies FoodLogEntryPayloadV1;

const validMealPlanEntry = {
  plannedOn: "2026-09-01",
  slot: "dinner",
  recipeId: RECIPE_ID,
  plannedServingsThousandths: 1_500,
} satisfies MealPlanEntryPayloadV1;

/** The quarter serving at the floor of the scale, on a proposal that has already been eaten. */
const edgeMealPlanEntry = {
  plannedOn: "2028-02-29",
  slot: "breakfast",
  recipeId: "f92cd615-4a7e-4b0d-8256-3d8e1f4a9b72",
  plannedServingsThousandths: 250,
  consumedLogEntryId: PLANNED_LOG_ENTRY_ID,
} satisfies MealPlanEntryPayloadV1;

const ACTIVITY_MUTATION_ID = "0198f0a3-5e6f-7081-8b2c-3d4e5f607182";
const MEAL_PLAN_MUTATION_ID = "0198f0a4-6f70-7192-9c3d-4e5f60718293";

/** The envelope of the richest aggregate there is, so the Kotlin union is exercised on it. */
const activitySessionMutation = {
  mutationId: ACTIVITY_MUTATION_ID,
  aggregateType: "activitySession",
  aggregateId: SESSION_ID,
  op: "upsert",
  baseRevision: null,
  payloadSchemaVersion: 1,
  payload: validActivitySession,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T19:31:12.480Z",
} satisfies MutationEnvelope;

/**
 * The envelope whose identifier is the whole point: `2026-09-01:dinner`.
 *
 * `aggregateIdSchema` accepts `[A-Za-z0-9._:-]`, so this parses and `2026-09-01/dinner` does not.
 * Every meal-plan row a phone journalled before this change carries the second spelling, which is
 * why `MealPlanIdRepair` exists on the Android side rather than the contract simply changing.
 */
const mealPlanEntryMutation = {
  mutationId: MEAL_PLAN_MUTATION_ID,
  aggregateType: "mealPlanEntry",
  aggregateId: MEAL_PLAN_ID,
  op: "upsert",
  baseRevision: "2",
  payloadSchemaVersion: 1,
  payload: validMealPlanEntry,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-31T21:04:58.220Z",
} satisfies MutationEnvelope;

const upsertMutation = {
  mutationId: MUTATION_ID,
  aggregateType: "measurement",
  aggregateId: validMeasurement.date,
  op: "upsert",
  baseRevision: "3",
  payloadSchemaVersion: 1,
  payload: validMeasurement,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T06:12:04.117Z",
} satisfies MutationEnvelope;

const deleteMutation = {
  mutationId: TOMBSTONE_MUTATION_ID,
  aggregateType: "measurement",
  aggregateId: "2026-08-24",
  op: "delete",
  baseRevision: "9",
  payloadSchemaVersion: 1,
  payload: null,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T06:12:05.004Z",
} satisfies MutationEnvelope;

/** A creation: `baseRevision` null is section 12.2's "si elle existe", and it is not zero. */
const healthProfileMutation = {
  mutationId: PROFILE_MUTATION_ID,
  aggregateType: "healthProfile",
  aggregateId: "me",
  op: "upsert",
  baseRevision: null,
  payloadSchemaVersion: 1,
  payload: validHealthProfile,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T06:12:04.902Z",
} satisfies MutationEnvelope;

const liveMeta = {
  id: validMeasurement.date,
  revision: "4",
  createdAt: "2026-08-25T06:12:04.500Z",
  updatedAt: "2026-08-25T06:12:04.500Z",
  deletedAt: null,
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: MUTATION_ID,
} satisfies AggregateMeta;

const profileMeta = {
  id: "me",
  revision: "2",
  createdAt: "2026-08-25T06:12:04.900Z",
  updatedAt: "2026-08-25T06:12:04.950Z",
  deletedAt: null,
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: PROFILE_MUTATION_ID,
} satisfies AggregateMeta;

const activityMeta = {
  id: SESSION_ID,
  revision: "1",
  createdAt: "2026-08-25T19:31:12.900Z",
  updatedAt: "2026-08-25T19:31:12.900Z",
  deletedAt: null,
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: ACTIVITY_MUTATION_ID,
} satisfies AggregateMeta;

/** An agent's write, so `originType: "agent"` appears in a fixture rather than only in prose. */
const mealPlanMeta = {
  id: MEAL_PLAN_ID,
  revision: "3",
  createdAt: "2026-08-31T21:04:59.010Z",
  updatedAt: "2026-08-31T21:04:59.010Z",
  deletedAt: null,
  originType: "agent",
  originId: "agent-kitchen-01",
  lastMutationId: MEAL_PLAN_MUTATION_ID,
} satisfies AggregateMeta;

const tombstoneMeta = {
  id: "2026-08-24",
  revision: "10",
  createdAt: "2026-08-24T06:03:11.000Z",
  updatedAt: "2026-08-25T06:12:05.310Z",
  deletedAt: "2026-08-25T06:12:05.310Z",
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: TOMBSTONE_MUTATION_ID,
} satisfies AggregateMeta;

/** Every optional field absent: the shape a client must still parse. */
const minimalError = {
  code: "server.unavailable",
  message: "The server is restarting. Retry after a backoff.",
  retryable: true,
} satisfies MueError;

/** Carries currentRevision, so the client rebases instead of guessing. */
const conflictError = {
  code: "sync.revision_conflict",
  message: "The measurement for 2026-08-25 has moved on since baseRevision 3.",
  retryable: false,
  aggregateType: "measurement",
  aggregateId: "2026-08-25",
  currentRevision: "7",
} satisfies MueError;

/** PRD section 14.4: name the missing value, never invent one. */
const missingFieldError = {
  code: "sync.missing_required_field",
  message: "payload.weightCg is required for a measurement upsert.",
  retryable: false,
  aggregateType: "measurement",
  aggregateId: "2026-08-25",
  field: "payload.weightCg",
} satisfies MueError;

const upgradeRequiredError = {
  code: "sync.upgrade_required",
  message:
    "The server holds measurement payloads at schema version 2, which this client did not declare.",
  retryable: false,
  aggregateType: "measurement",
} satisfies MueError;

const pushRequest = {
  mutations: [
    upsertMutation,
    healthProfileMutation,
    activitySessionMutation,
    mealPlanEntryMutation,
    deleteMutation,
  ],
} satisfies PushRequest;

/** All three outcomes in one body: a rejection never blocks the rest (FR-SYNC-007). */
const pushResponse = {
  results: [
    { mutationId: MUTATION_ID, status: "applied", revision: "4", sequence: LARGE_SEQUENCE },
    {
      mutationId: TOMBSTONE_MUTATION_ID,
      status: "duplicate",
      revision: "10",
      sequence: NEXT_SEQUENCE,
    },
    {
      mutationId: "0198f0a2-1111-7222-8333-444455556666",
      status: "rejected",
      error: conflictError,
    },
  ],
  serverTime: "2026-08-25T06:12:06.000Z",
} satisfies PushResponse;

/**
 * A client's own declaration of what it can apply (PRD section 12.4).
 *
 * It names all eight aggregates because Android now does: `SyncWire.SUPPORTED_SCHEMA_VERSIONS`
 * is derived from the same list, and a fixture that named two would let the Kotlin side ship a
 * map missing six without a test noticing — which is the shape of the defect that kept the four
 * food aggregates undeliverable while the matrix said otherwise.
 */
const pullRequest = {
  cursor: CURSOR,
  limit: 100,
  supportedSchemaVersions: {
    activitySession: [1],
    customExerciseDefinition: [1],
    food: [1],
    foodLogEntry: [1],
    healthProfile: [1],
    mealPlanEntry: [1],
    measurement: [1],
    recipe: [1],
  },
} satisfies PullRequest;

const pullPage = {
  status: "ok",
  changes: [
    {
      sequence: LARGE_SEQUENCE,
      aggregateType: "measurement",
      aggregateId: validMeasurement.date,
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: validMeasurement,
      meta: liveMeta,
    },
    {
      sequence: NEXT_SEQUENCE,
      aggregateType: "measurement",
      aggregateId: tombstoneMeta.id,
      op: "delete",
      payloadSchemaVersion: 1,
      payload: null,
      meta: tombstoneMeta,
    },
    {
      sequence: THIRD_SEQUENCE,
      aggregateType: "healthProfile",
      aggregateId: "me",
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: validHealthProfile,
      meta: profileMeta,
    },
    // A whole session and a proposal, on the read side. The change branches mirror the mutation
    // branches, so a Kotlin `SyncChangeSerializer` that learned an aggregate type on the write
    // side and forgot it on the read side fails here rather than on a phone at the next pull.
    {
      sequence: FOURTH_SEQUENCE,
      aggregateType: "activitySession",
      aggregateId: SESSION_ID,
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: validActivitySession,
      meta: activityMeta,
    },
    {
      sequence: FIFTH_SEQUENCE,
      aggregateType: "mealPlanEntry",
      aggregateId: MEAL_PLAN_ID,
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: validMealPlanEntry,
      meta: mealPlanMeta,
    },
  ],
  nextCursor: toBase64Url(JSON.stringify({ v: 1, seq: FIFTH_SEQUENCE })),
  hasMore: false,
  serverTime: "2026-08-25T06:12:07.000Z",
  lastAndroidSyncAt: "2026-08-25T06:12:06.900Z",
} satisfies PullResponse;

/** No nextCursor at all, so the cursor cannot advance past data the client cannot apply. */
const pullUpgradeRequired = {
  status: "upgrade_required",
  error: upgradeRequiredError,
  serverTime: "2026-08-25T06:12:07.000Z",
  lastAndroidSyncAt: null,
} satisfies PullResponse;

export const CONTRACT_FIXTURES: readonly ContractFixture[] = [
  {
    file: "measurement-v1-valid.json",
    schema: "MeasurementPayloadV1",
    kind: "valid",
    description: "A typical weight measurement payload.",
    value: validMeasurement,
    validator: measurementPayloadV1Schema,
  },
  {
    file: "measurement-v1-edge.json",
    schema: "MeasurementPayloadV1",
    kind: "edge",
    description: "The minimum legal weight, recorded on a leap day.",
    value: edgeMeasurement,
    validator: measurementPayloadV1Schema,
  },
  {
    file: "health-profile-v1-valid.json",
    schema: "HealthProfilePayloadV1",
    kind: "valid",
    description: "The owner's own profile: 171 cm, born 1998-11-18.",
    value: validHealthProfile,
    validator: healthProfilePayloadV1Schema,
  },
  {
    file: "health-profile-v1-edge.json",
    schema: "HealthProfilePayloadV1",
    kind: "edge",
    description: "A cleared profile: both fields null and present, never absent.",
    value: clearedHealthProfile,
    validator: healthProfilePayloadV1Schema,
  },
  {
    file: "activity-session-v1-valid.json",
    schema: "ActivitySessionPayloadV1",
    kind: "valid",
    description: "A strength session with two exercises, four sets, a metric and its gear.",
    value: validActivitySession,
    validator: activitySessionPayloadV1Schema,
  },
  {
    file: "activity-session-v1-edge.json",
    schema: "ActivitySessionPayloadV1",
    kind: "edge",
    description: "A forty-second timed session on `other`, below the manual form's own floor.",
    value: edgeActivitySession,
    validator: activitySessionPayloadV1Schema,
  },
  {
    file: "custom-exercise-definition-v1-valid.json",
    schema: "CustomExerciseDefinitionPayloadV1",
    kind: "valid",
    description: "A personal exercise definition with its tracking mode and equipment.",
    value: validCustomExercise,
    validator: customExerciseDefinitionPayloadV1Schema,
  },
  {
    file: "custom-exercise-definition-v1-edge.json",
    schema: "CustomExerciseDefinitionPayloadV1",
    kind: "edge",
    description: "The longest name allowed, and no equipment at all.",
    value: edgeCustomExercise,
    validator: customExerciseDefinitionPayloadV1Schema,
  },
  {
    file: "food-v1-valid.json",
    schema: "FoodPayloadV1",
    kind: "valid",
    description: "A copied Open Food Facts product with brand, barcode and usual serving.",
    value: validFood,
    validator: foodPayloadV1Schema,
  },
  {
    file: "food-v1-edge.json",
    schema: "FoodPayloadV1",
    kind: "edge",
    description: "A measured zero energy beside four macros that are absent, not zero.",
    value: edgeFood,
    validator: foodPayloadV1Schema,
  },
  {
    file: "recipe-v1-valid.json",
    schema: "RecipePayloadV1",
    kind: "valid",
    description: "A recipe with two ingredients, their food-name snapshots, and its steps.",
    value: validRecipe,
    validator: recipePayloadV1Schema,
  },
  {
    file: "recipe-v1-edge.json",
    schema: "RecipePayloadV1",
    kind: "edge",
    description: "One ingredient, twelve base servings, and no `steps` key at all.",
    value: edgeRecipe,
    validator: recipePayloadV1Schema,
  },
  {
    file: "food-log-entry-v1-valid.json",
    schema: "FoodLogEntryPayloadV1",
    kind: "valid",
    description: "A line logged from a proposal, on the serving scale, naming a colon-joined id.",
    value: validFoodLogEntry,
    validator: foodLogEntryPayloadV1Schema,
  },
  {
    file: "food-log-entry-v1-edge.json",
    schema: "FoodLogEntryPayloadV1",
    kind: "edge",
    description: "A quick add: no food, no recipe, no quantity and no unit.",
    value: edgeFoodLogEntry,
    validator: foodLogEntryPayloadV1Schema,
  },
  {
    file: "meal-plan-entry-v1-valid.json",
    schema: "MealPlanEntryPayloadV1",
    kind: "valid",
    description: "A dinner planned at one and a half servings.",
    value: validMealPlanEntry,
    validator: mealPlanEntryPayloadV1Schema,
  },
  {
    file: "meal-plan-entry-v1-edge.json",
    schema: "MealPlanEntryPayloadV1",
    kind: "edge",
    description: "The quarter serving at the floor of the scale, already consumed.",
    value: edgeMealPlanEntry,
    validator: mealPlanEntryPayloadV1Schema,
  },
  {
    file: "mutation-upsert-activity-session-v1.json",
    schema: "MutationEnvelope",
    kind: "valid",
    description: "The whole session in one envelope, children and all.",
    value: activitySessionMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "mutation-upsert-meal-plan-entry-v1.json",
    schema: "MutationEnvelope",
    kind: "edge",
    description: "The identifier that used to carry a `/`: `2026-09-01:dinner`.",
    value: mealPlanEntryMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "mutation-upsert-health-profile-v1.json",
    schema: "MutationEnvelope",
    kind: "valid",
    description: "The upsert the phone could not send, with its constant aggregate id.",
    value: healthProfileMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "mutation-upsert-measurement-v1.json",
    schema: "MutationEnvelope",
    kind: "valid",
    description: "An upsert, carrying the full aggregate.",
    value: upsertMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "mutation-delete-measurement.json",
    schema: "MutationEnvelope",
    kind: "edge",
    description: "A delete: null payload, and a baseRevision that must still be honoured.",
    value: deleteMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "aggregate-meta-live.json",
    schema: "AggregateMeta",
    kind: "valid",
    description: "Metadata for a live aggregate, deletedAt null.",
    value: liveMeta,
    validator: aggregateMetaSchema,
  },
  {
    file: "aggregate-meta-tombstone.json",
    schema: "AggregateMeta",
    kind: "edge",
    description: "Metadata for a tombstone, which the client keeps to block resurrection.",
    value: tombstoneMeta,
    validator: aggregateMetaSchema,
  },
  {
    file: "error-minimal.json",
    schema: "MueError",
    kind: "error",
    description: "Every optional field absent, and retryable.",
    value: minimalError,
    validator: mueErrorSchema,
  },
  {
    file: "error-revision-conflict.json",
    schema: "MueError",
    kind: "error",
    description: "Carries currentRevision so the client can rebase.",
    value: conflictError,
    validator: mueErrorSchema,
  },
  {
    file: "error-missing-required-field.json",
    schema: "MueError",
    kind: "error",
    description: "Names the missing field (PRD section 14.4).",
    value: missingFieldError,
    validator: mueErrorSchema,
  },
  {
    file: "error-upgrade-required.json",
    schema: "MueError",
    kind: "error",
    description: "An aggregate type but no id: the whole type is unreadable.",
    value: upgradeRequiredError,
    validator: mueErrorSchema,
  },
  {
    file: "push-request.json",
    schema: "PushRequest",
    kind: "valid",
    description: "An outbox batch of one upsert and one delete.",
    value: pushRequest,
    validator: pushRequestSchema,
  },
  {
    file: "push-response.json",
    schema: "PushResponse",
    kind: "valid",
    description: "applied, duplicate and rejected in one body.",
    value: pushResponse,
    validator: pushResponseSchema,
  },
  {
    file: "pull-request.json",
    schema: "PullRequest",
    kind: "valid",
    description: "A resumed pull, declaring the payload versions it can apply.",
    value: pullRequest,
    validator: pullRequestSchema,
  },
  {
    file: "pull-response-ok.json",
    schema: "PullResponse",
    kind: "valid",
    description: "A page of changes, with a sequence past 2^53.",
    value: pullPage,
    validator: pullResponseSchema,
  },
  {
    file: "pull-response-upgrade-required.json",
    schema: "PullResponse",
    kind: "edge",
    description: "The upgrade demand, which structurally carries no nextCursor.",
    value: pullUpgradeRequired,
    validator: pullResponseSchema,
  },
];

/** A manifest, so the JVM test enumerates the fixtures instead of listing them twice. */
export function buildFixtureManifest(): unknown {
  return {
    version: 1,
    fixtures: CONTRACT_FIXTURES.map((fixture) => ({
      file: fixture.file,
      schema: fixture.schema,
      kind: fixture.kind,
      description: fixture.description,
    })),
  };
}

/** The exact bytes each fixture file holds, keyed by file name. */
export function buildFixtureFiles(): Map<string, string> {
  const files = new Map<string, string>();
  for (const fixture of CONTRACT_FIXTURES) {
    fixture.validator.parse(fixture.value);
    files.set(fixture.file, canonicalJson(fixture.value));
  }
  files.set("index.json", canonicalJson(buildFixtureManifest()));
  return files;
}

export async function writeContractFixtures(directory: string): Promise<number> {
  const files = buildFixtureFiles();
  for (const [file, contents] of files) {
    await Bun.write(join(directory, file), contents);
  }
  return files.size;
}

export function fixtureDirectory(repoRoot: string): string {
  return join(repoRoot, ...FIXTURE_RESOURCE_PATH);
}

/** The repository root, from this file's location inside packages/contracts/src. */
export const REPO_ROOT = join(import.meta.dir, "..", "..", "..");

function toBase64Url(value: string): string {
  return btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

// Parsed rather than asserted, so the literal cursors above stay bound to the one
// definition of the cursor's wire form.
export const FIXTURE_CURSOR = cursorSchema.parse(CURSOR);

if (import.meta.main) {
  const directory = fixtureDirectory(REPO_ROOT);
  const written = await writeContractFixtures(directory);
  console.log(`wrote ${written} contract fixtures to ${directory}`);
}
