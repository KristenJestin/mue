import type {
  ActivitySessionPayloadV1,
  AggregateType,
  BodyCompositionV1,
  CustomExerciseDefinitionPayloadV1,
  FoodLogEntryPayloadV1,
  FoodPayloadV1,
  HealthProfilePayloadV1,
  MealPlanEntryPayloadV1,
  MueError,
  MutationOp,
  OriginType,
  RecipePayloadV1,
} from "@mue/contracts";
import type { ActivitySessionPayload, ActivitySessionView } from "./activity";

/**
 * Everything the MCP tools are allowed to reach.
 *
 * PRD section 20.2 requires the Hono routes, the TanStack Start server functions and
 * the MCP tools to call the *same* services, so a tool never talks to Drizzle and never
 * holds a rule of its own. This interface is that seam: the tools depend on it, and
 * `./store.ts` is the one implementation, which in turn delegates every write to
 * `@mue/domain` through `./domain-bridge.ts`.
 *
 * It is also what makes the tools testable without a database, and what makes the
 * "never expose tables, raw SQL, the filesystem or the process" rule of sections 14.1
 * and 16 structural: there is no method here through which a query could be passed.
 *
 * ## One write method, twenty-six write tools
 *
 * The reads are per-aggregate because their filters, their keysets and their statistics
 * genuinely differ. The writes are not: every one of them mints an envelope and hands it
 * to `submitMutation`, and the only things that vary are the aggregate type, its
 * identifier and its payload -- all three of which `mutationEnvelopeSchema` already
 * describes. So there is a single [MueMcpServices.applyAgentMutation], and a tool's job
 * is to turn what a person said into a payload the contract accepts. A method per tool
 * would have been twenty-six chances for one of them to skip the journal.
 */

/** The section 12.1 metadata every synchronised aggregate carries. */
export interface AggregateMetadata {
  readonly revision: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly deletedAt: string | null;
  readonly originType: OriginType;
  readonly originId: string | null;
  readonly lastMutationId: string;
}

/**
 * One stored aggregate, kept as the payload the contract describes plus its metadata.
 *
 * They are separate fields rather than one flat object because the two are used
 * differently: a read tool spreads both into its result, while an update tool takes the
 * *payload* alone as the base it edits and resubmits. Flattening them would make an
 * update path that has to remember to strip seven metadata keys before it can build a
 * legal payload, and forgetting one is a validation failure the agent would see.
 */
export interface StoredAggregate<Payload> {
  readonly payload: Payload;
  readonly meta: AggregateMetadata;
}

/**
 * One weighing, with everything PRD_SCALE 22 lets leave the phone and nothing else.
 *
 * ## Why the whole aggregate and not the weight alone
 *
 * `mue.upsert_weight_measurement` states a *complete* payload (section 12.2), and BR-SCALE-007
 * makes a complete payload with no composition the order to delete the stored one. A view that
 * stopped at `weightCg` therefore left the tool nothing to restate, and correcting a weight by
 * a tenth of a kilogram destroyed the impedance and the composition of that day. The read shape
 * has to be at least as wide as the write shape or the write is a deletion in disguise.
 *
 * ## What is deliberately absent
 *
 * There is no field for *which* scale. PRD_SCALE 16.2 and 22 are explicit that the local
 * identifier, the Bluetooth address and the advertised name never leave the phone -- and
 * `measurements` has no column for any of them, so this is not a field withheld here
 * but a fact the server was never told. `sourceType` is the business provenance and is
 * synchronised; it says a weighing came from a scale and says nothing about the device.
 */
export interface WeightMeasurementView {
  readonly date: string;
  /** Hundredths of a kilogram, the integer unit Android stores. */
  readonly weightCg: number;
  /** `manual`, `scale`, `agent` or `server` (PRD_SCALE 21.1). Never null: the column defaults. */
  readonly sourceType: string;
  /**
   * Raw impedance in ohms, on the measurement and not on the composition (FR-BODY-004,
   * BR-SCALE-008). Null when no usable reading was taken -- BR-SCALE-005 makes a scale's
   * refusal an absence and never a value. Present even where no composition could be
   * computed, which is what FR-BODY-006's retroactive calculation needs.
   */
  readonly impedanceOhm: number | null;
  /** The optional child of BR-SCALE-006, or null when this date carries none. */
  readonly bodyComposition: BodyCompositionV1 | null;
  readonly revision: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly deletedAt: string | null;
  readonly originType: OriginType;
  readonly originId: string | null;
  readonly lastMutationId: string;
}

export interface ListWeightMeasurementsQuery {
  readonly userId: string;
  /** Inclusive lower bound, or null. Null on both ends means the whole history. */
  readonly from: string | null;
  /** Inclusive upper bound, or null. */
  readonly to: string | null;
  /** Keyset: the date the previous page ended on. Null starts at the beginning. */
  readonly afterDate: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListWeightMeasurementsResult {
  readonly measurements: readonly WeightMeasurementView[];
  readonly hasMore: boolean;
}

/**
 * What a weight history adds up to, computed at read time and stored nowhere.
 *
 * Every field is null when the range holds no measurement. Section 14.4's rule about
 * mandatory values has a reading here too: a mean of nothing is not zero, and a server
 * that answered `0` would be stating a weight nobody recorded.
 */
export interface WeightStatistics {
  readonly count: number;
  readonly firstDate: string | null;
  readonly firstWeightCg: number | null;
  readonly lastDate: string | null;
  readonly lastWeightCg: number | null;
  readonly minWeightCg: number | null;
  readonly minDate: string | null;
  readonly maxWeightCg: number | null;
  readonly maxDate: string | null;
  /** The arithmetic mean of the measurements in range, in centigrams. Not rounded. */
  readonly meanWeightCg: number | null;
  /** `lastWeightCg - firstWeightCg`, in centigrams. Null below two measurements. */
  readonly changeCg: number | null;
}

export interface ListActivitiesQuery {
  readonly userId: string;
  readonly from: string | null;
  readonly to: string | null;
  readonly movement: string | null;
  /** Keyset: the `startedOn` and `id` of the last row of the previous page. */
  readonly afterKey: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListActivitiesResult {
  readonly activities: readonly ActivitySessionView[];
  readonly hasMore: boolean;
}

export interface MovementTotal {
  readonly movement: string;
  readonly sessionCount: number;
  readonly totalDurationSeconds: number;
}

/**
 * What an activity history adds up to.
 *
 * There is no energy here and there never will be from this method: PRD_ACTIVITIES makes
 * an estimated energy a *metric an author recorded*, and a total the server derived from
 * a duration would be exactly the invented value section 14.4 forbids.
 */
export interface ActivityStatistics {
  readonly sessionCount: number;
  readonly totalDurationSeconds: number;
  readonly firstDate: string | null;
  readonly lastDate: string | null;
  readonly byMovement: readonly MovementTotal[];
}

export interface ListCustomExercisesQuery {
  readonly userId: string;
  /** Keyset: the folded name and `id` of the last row of the previous page. */
  readonly afterKey: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListCustomExercisesResult {
  readonly exercises: readonly StoredAggregate<CustomExerciseDefinitionPayloadV1>[];
  readonly hasMore: boolean;
}

// --- PRD_FOOD 21.5, the food reads ---------------------------------------------------

/**
 * The journal over a period, filterable by moment (PRD_FOOD 21.5's `list_food_logs`).
 *
 * `slot` is a plain string and not a union, for the reason `meal-slot-clock.ts` gives:
 * `MealSlot` is expected to grow, and a query type that enumerated the moments would be a
 * second copy of the enum. The tool validates it against `mealSlotSchema`; by the time it
 * reaches here it is a moment the contract accepted.
 */
export interface ListFoodLogEntriesQuery {
  readonly userId: string;
  readonly from: string | null;
  readonly to: string | null;
  readonly slot: string | null;
  /** Keyset: the `consumedOn`, `consumedAt` and `id` of the last row of the previous page. */
  readonly afterKey: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListFoodLogEntriesResult {
  readonly entries: readonly StoredAggregate<FoodLogEntryPayloadV1>[];
  readonly hasMore: boolean;
}

/**
 * Foods this account can reach, matched on what a person would type.
 *
 * `text` folds the way the rest of this server folds a name — case, and case alone, as
 * `foldExerciseName` does. PRD_FOOD 9.4 also asks for accent-insensitivity, and that is a
 * capability of the *phone's* offline search: the server holds no folded column for a food
 * and inventing one fold here would be a second, drifting definition of what two names
 * being "the same" means. Recorded rather than silently half-done.
 */
export interface SearchFoodsQuery {
  readonly userId: string;
  /** A fragment of a name or a brand. Null lists the catalogue in name order. */
  readonly text: string | null;
  readonly barcode: string | null;
  /** PRD_FOOD 9.4: *"Un filtre restreint à une source."* Null means both. */
  readonly source: string | null;
  /** Keyset: the `name` and `id` of the last row of the previous page. */
  readonly afterKey: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface SearchFoodsResult {
  readonly foods: readonly StoredAggregate<FoodPayloadV1>[];
  readonly hasMore: boolean;
}

/** Saved recipes, filterable by type and by favourite (PRD_FOOD 21.5's `list_recipes`). */
export interface ListRecipesQuery {
  readonly userId: string;
  readonly type: string | null;
  readonly favouritesOnly: boolean;
  /** A fragment of the name. PRD_FOOD 11: a recipe is searchable by name. */
  readonly text: string | null;
  /** Keyset: the `name` and `id` of the last row of the previous page. */
  readonly afterKey: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListRecipesResult {
  readonly recipes: readonly StoredAggregate<RecipePayloadV1>[];
  readonly hasMore: boolean;
}

/** Proposals over a period (PRD_FOOD 21.5's `list_meal_plan`). */
export interface ListMealPlanQuery {
  readonly userId: string;
  readonly from: string | null;
  readonly to: string | null;
  /** Keyset: the `plannedOn` and `slot` of the last row of the previous page. */
  readonly afterKey: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListMealPlanResult {
  readonly entries: readonly StoredAggregate<MealPlanEntryPayloadV1>[];
  readonly hasMore: boolean;
}

/**
 * FR-SYNC-008 and section 12.3, as one answer.
 *
 * `journalSequence` is the head of the per-user journal, which is the only ordering this
 * system has; the three instants are civil clocks and are for display and audit alone.
 */
export interface SyncStatus {
  readonly journalSequence: string;
  readonly changeCount: number;
  readonly lastChangeAt: string | null;
  readonly lastAndroidSyncAt: string | null;
  readonly lastAgentChangeAt: string | null;
}

/**
 * One write, whatever the aggregate.
 *
 * `baseRevision` is section 14.6's "révision attendue lorsqu'elle est connue": null when
 * the caller has none, and never invented by a tool.
 */
export interface AgentMutationCommand {
  readonly userId: string;
  /** FR-SYNC-006 and section 14.6: replaying this id repeats no effect. */
  readonly mutationId: string;
  readonly originId: string;
  readonly aggregateType: AggregateType;
  readonly aggregateId: string;
  readonly op: MutationOp;
  readonly payloadSchemaVersion: number;
  /** The complete aggregate for an upsert, null for a delete (section 12.2). */
  readonly payload: unknown;
  readonly baseRevision: string | null;
  /** The agent's own clock, for display and audit only (section 12.3). */
  readonly clientOccurredAt: string;
}

export interface AgentMutationResult {
  /** `duplicate` is a replay: the effect happened once and this is the stored result. */
  readonly status: "applied" | "duplicate" | "rejected";
  readonly aggregateId: string;
  readonly revision: string | null;
  readonly sequence: string | null;
  readonly error: MueError | null;
}

export interface CreateActivityCommand {
  readonly userId: string;
  /** FR-SYNC-006 and section 14.6: replaying this id repeats no effect. */
  readonly mutationId: string;
  readonly originId: string;
  readonly payload: ActivitySessionPayload;
  /** The agent's own clock, for display and audit only (section 12.3). */
  readonly clientOccurredAt: string;
}

export interface CreateActivityResult {
  readonly activity: ActivitySessionView;
  /** False when the mutation id had already been applied and this is the stored result. */
  readonly created: boolean;
}

/**
 * Section 14.7, verbatim: agent identity, tool name, server instant, mutation id,
 * aggregates touched, result, revision created, and any error.
 *
 * There is no field for a prompt or a conversation, and that is the point -- the
 * section says they are not wanted, and a shape that cannot hold them cannot grow
 * one by accident.
 */
export interface AgentAuditEntry {
  readonly agentId: string;
  readonly toolName: string;
  readonly mutationId: string | null;
  readonly aggregates: readonly { readonly type: string; readonly id: string }[];
  readonly result: "ok" | "error";
  readonly revision: string | null;
  readonly error: MueError | null;
}

export interface MueMcpServices {
  // --- section 14.2, reads -----------------------------------------------------------

  syncStatus(userId: string): Promise<SyncStatus>;

  /**
   * FR-SYNC-008: the last moment the server saw the Android phone synchronise, so no
   * agent infers a freshness guarantee the server cannot give. Null when it never has.
   */
  lastAndroidSyncAt(userId: string): Promise<string | null>;

  getHealthProfile(userId: string): Promise<StoredAggregate<HealthProfilePayloadV1> | null>;

  listWeightMeasurements(query: ListWeightMeasurementsQuery): Promise<ListWeightMeasurementsResult>;

  getWeightMeasurement(
    userId: string,
    date: string,
    includeDeleted: boolean,
  ): Promise<WeightMeasurementView | null>;

  weightStatistics(
    userId: string,
    from: string | null,
    to: string | null,
  ): Promise<WeightStatistics>;

  listActivities(query: ListActivitiesQuery): Promise<ListActivitiesResult>;

  getActivity(
    userId: string,
    id: string,
    includeDeleted: boolean,
  ): Promise<ActivitySessionView | null>;

  activityStatistics(
    userId: string,
    from: string | null,
    to: string | null,
  ): Promise<ActivityStatistics>;

  listCustomExercises(query: ListCustomExercisesQuery): Promise<ListCustomExercisesResult>;

  getCustomExercise(
    userId: string,
    id: string,
    includeDeleted: boolean,
  ): Promise<StoredAggregate<CustomExerciseDefinitionPayloadV1> | null>;

  // --- the aggregates the write tools edit and read back ------------------------------

  getActivityPayload(
    userId: string,
    id: string,
  ): Promise<StoredAggregate<ActivitySessionPayloadV1> | null>;

  getFood(userId: string, id: string): Promise<StoredAggregate<FoodPayloadV1> | null>;

  getRecipe(userId: string, id: string): Promise<StoredAggregate<RecipePayloadV1> | null>;

  getFoodLogEntry(
    userId: string,
    id: string,
  ): Promise<StoredAggregate<FoodLogEntryPayloadV1> | null>;

  getMealPlanEntry(
    userId: string,
    plannedOn: string,
    slot: string,
  ): Promise<StoredAggregate<MealPlanEntryPayloadV1> | null>;

  // --- PRD_FOOD 21.5, the food reads --------------------------------------------------

  listFoodLogEntries(query: ListFoodLogEntriesQuery): Promise<ListFoodLogEntriesResult>;

  /**
   * Every live line of one day, oldest first.
   *
   * Unpaged, and deliberately so: `get_daily_nutrition` returns a *total*, and a total of
   * a page is not a total of a day. A day of a personal journal is a handful of rows, so
   * the whole day is read and summed at once rather than assembled across calls an agent
   * could stop making halfway.
   *
   * Ordered by the clock, which is PRD_FOOD 22's *"Les lignes d'un moment sont ordonnées
   * par heure"*.
   */
  foodLogEntriesOn(
    userId: string,
    date: string,
  ): Promise<readonly StoredAggregate<FoodLogEntryPayloadV1>[]>;

  searchFoods(query: SearchFoodsQuery): Promise<SearchFoodsResult>;

  listRecipes(query: ListRecipesQuery): Promise<ListRecipesResult>;

  /**
   * The live foods behind a set of identifiers, in one query.
   *
   * PRD_FOOD 13.1 derives a recipe's values from its ingredients' foods, so rendering a
   * page of recipes needs every food they name. One `in` beats one query per ingredient,
   * and a food that is absent is absent from the map — which [recipeTotal] reads as
   * unknown rather than as zero.
   */
  foodsByIds(userId: string, ids: readonly string[]): Promise<ReadonlyMap<string, FoodPayloadV1>>;

  listMealPlan(query: ListMealPlanQuery): Promise<ListMealPlanResult>;

  // --- sections 14.3 and 14.6, writes -------------------------------------------------

  applyAgentMutation(command: AgentMutationCommand): Promise<AgentMutationResult>;

  createActivitySession(command: CreateActivityCommand): Promise<CreateActivityResult>;

  // --- section 14.7 -------------------------------------------------------------------

  recordAudit(entry: AgentAuditEntry): Promise<void>;
}
