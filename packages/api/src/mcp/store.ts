import type {
  ActivitySessionPayloadV1,
  CustomExerciseDefinitionPayloadV1,
  FoodLogEntryPayloadV1,
  FoodPayloadV1,
  HealthProfilePayloadV1,
  OriginType,
  RecipePayloadV1,
} from "@mue/contracts";
import type { DatabaseHandle } from "@mue/db";
import { schema } from "@mue/db";
import { and, asc, count, desc, eq, gt, gte, isNull, lt, lte, max, or } from "drizzle-orm";
import type { ActivitySessionView } from "./activity";
import { decodePairKey } from "./cursor";
import { createActivitySessionService, createAgentMutationService } from "./domain-bridge";
import type {
  ActivityStatistics,
  AgentAuditEntry,
  AgentMutationCommand,
  AgentMutationResult,
  AggregateMetadata,
  CreateActivityCommand,
  CreateActivityResult,
  ListActivitiesQuery,
  ListActivitiesResult,
  ListCustomExercisesQuery,
  ListCustomExercisesResult,
  ListWeightMeasurementsQuery,
  ListWeightMeasurementsResult,
  MovementTotal,
  MueMcpServices,
  StoredAggregate,
  SyncStatus,
  WeightMeasurementView,
  WeightStatistics,
} from "./services";

/** `origin_type` is a text column; anything unrecognised is reported as `server`. */
function asOriginType(value: string): OriginType {
  return value === "android" || value === "agent" ? value : "server";
}

/** The section 12.1 columns every synchronised table carries, read back as strings. */
interface MetadataRow {
  revision: bigint;
  createdAt: Date;
  updatedAt: Date;
  deletedAt: Date | null;
  originType: string;
  originId: string | null;
  lastMutationId: string;
}

function metaOf(row: MetadataRow): AggregateMetadata {
  return {
    revision: row.revision.toString(),
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
    deletedAt: row.deletedAt?.toISOString() ?? null,
    originType: asOriginType(row.originType),
    originId: row.originId,
    lastMutationId: row.lastMutationId,
  };
}

/**
 * A nullable column, turned back into the *absent* key the payload states.
 *
 * `food-aggregates.ts` writes `payload.energyMilliKcal ?? null` on the way in; this is the
 * exact inverse, and it has to exist because `foodPayloadV1Schema` declares those keys
 * `.optional()` and not `.nullable()`. A rebuilt payload carrying `energyMilliKcal: null`
 * is refused by the contract before the handler sees it -- the same class of failure as a
 * UUIDv4 where a v7 was required: the right shape, the wrong content, invisible to anything
 * that only compares shapes.
 *
 * PRD_FOOD 13.1 is the reason the distinction exists at all: an unknown nutrient is absent,
 * never a zero and never a stated emptiness.
 */
function optional<T>(
  value: T | null | undefined,
): { present: false } | { present: true; value: T } {
  return value === null || value === undefined ? { present: false } : { present: true, value };
}

/** Spreads a nullable column into an object only when it has a value. */
function ifPresent<K extends string, T>(
  key: K,
  value: T | null | undefined,
): Record<K, T> | Record<string, never> {
  const state = optional(value);
  return state.present ? ({ [key]: state.value } as Record<K, T>) : {};
}

export function createMueMcpServices(database: DatabaseHandle): MueMcpServices {
  const createActivity = createActivitySessionService();
  const applyMutation = createAgentMutationService();

  const {
    activitySessions,
    customExercises,
    foodLogEntries,
    foods,
    healthProfile,
    measurements,
    recipes,
    syncJournal,
  } = schema;

  function activityViewOf(row: typeof activitySessions.$inferSelect): ActivitySessionView {
    return { ...activityPayloadOf(row), ...metaOf(row) };
  }

  function activityPayloadOf(row: typeof activitySessions.$inferSelect): ActivitySessionPayloadV1 {
    return {
      id: row.id,
      movement: row.movement as ActivitySessionPayloadV1["movement"],
      customMovementName: row.customMovementName,
      environment: row.environment as ActivitySessionPayloadV1["environment"],
      startedOn: row.startedOn,
      startedAtTime: row.startedAtTime,
      durationSeconds: row.durationSeconds,
      perceivedEffort: row.perceivedEffort,
      notes: row.notes,
      source: row.source as ActivitySessionPayloadV1["source"],
      metrics: row.metrics as ActivitySessionPayloadV1["metrics"],
      equipment: row.equipment as ActivitySessionPayloadV1["equipment"],
      exercises: row.exercises as ActivitySessionPayloadV1["exercises"],
    };
  }

  return {
    // --- section 14.2, reads ---------------------------------------------------------

    /**
     * Section 12.3's journal head and the civil clocks around it.
     *
     * `journalSequence` is the only ordering this system has, and it is per account: it is
     * what a client's cursor is compared against and what makes "has anything happened
     * since?" answerable without reading a page.
     */
    async syncStatus(userId: string): Promise<SyncStatus> {
      const rows = await database.db
        .select({
          head: max(syncJournal.sequence),
          changes: count(),
          lastChangeAt: max(syncJournal.recordedAt),
        })
        .from(syncJournal)
        .where(eq(syncJournal.userId, userId));
      const totals = rows[0];

      const agentRows = await database.db
        .select({ at: max(syncJournal.recordedAt) })
        .from(syncJournal)
        .where(and(eq(syncJournal.userId, userId), eq(syncJournal.originType, "agent")));

      return {
        journalSequence: (totals?.head ?? 0n).toString(),
        changeCount: totals?.changes ?? 0,
        lastChangeAt: instantOrNull(totals?.lastChangeAt),
        lastAndroidSyncAt: await this.lastAndroidSyncAt(userId),
        lastAgentChangeAt: instantOrNull(agentRows[0]?.at),
      };
    },

    /**
     * FR-SYNC-008, from the only evidence the server holds today: the most recent
     * change the phone itself authored. It is a lower bound on the phone's freshness,
     * and erring low is the safe direction -- an agent is told the data may be older
     * than it is, never newer.
     *
     * TODO(sync-agent): a pull leaves no trace, so a phone that only receives never
     * moves this instant. When the sync engine records per-device state, read the last
     * successful exchange instead and this becomes exact.
     */
    async lastAndroidSyncAt(userId: string): Promise<string | null> {
      const rows = await database.db
        .select({ at: max(syncJournal.recordedAt) })
        .from(syncJournal)
        .where(and(eq(syncJournal.userId, userId), eq(syncJournal.originType, "android")));
      return instantOrNull(rows[0]?.at);
    },

    async getHealthProfile(
      userId: string,
    ): Promise<StoredAggregate<HealthProfilePayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(healthProfile)
        .where(eq(healthProfile.userId, userId));
      const row = rows[0];
      if (row === undefined) return null;
      return {
        payload: { heightCm: row.heightCm, birthDate: row.birthDate },
        meta: metaOf(row),
      };
    },

    async listWeightMeasurements(
      query: ListWeightMeasurementsQuery,
    ): Promise<ListWeightMeasurementsResult> {
      const filters = [eq(measurements.userId, query.userId)];
      if (query.from !== null) filters.push(gte(measurements.date, query.from));
      if (query.to !== null) filters.push(lte(measurements.date, query.to));
      if (!query.includeDeleted) filters.push(isNull(measurements.deletedAt));
      // Keyset, not offset: the page after a date is well defined even when rows are
      // inserted between two calls, which is what makes section 14.2's unbounded walk
      // terminate without repeating or skipping a day.
      if (query.afterDate !== null) filters.push(lt(measurements.date, query.afterDate));

      const rows = await database.db
        .select()
        .from(measurements)
        .where(and(...filters))
        .orderBy(desc(measurements.date))
        // One extra row answers `hasMore` without a second count query.
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        measurements: page.map((row): WeightMeasurementView => ({
          date: row.date,
          weightCg: row.weightCg,
          ...metaOf(row),
        })),
        hasMore: rows.length > query.limit,
      };
    },

    async getWeightMeasurement(
      userId: string,
      date: string,
      includeDeleted: boolean,
    ): Promise<WeightMeasurementView | null> {
      const rows = await database.db
        .select()
        .from(measurements)
        .where(and(eq(measurements.userId, userId), eq(measurements.date, date)));
      const row = rows[0];
      if (row === undefined) return null;
      if (row.deletedAt !== null && !includeDeleted) return null;
      return { date: row.date, weightCg: row.weightCg, ...metaOf(row) };
    },

    /**
     * Computed at read time from the live measurements alone, and stored nowhere.
     *
     * Reading the rows and folding them in JavaScript rather than pushing five aggregates
     * into SQL is deliberate: `first` and `last` are *not* aggregates -- they are the
     * weights at the ends of the range -- and expressing them as window functions next to
     * `min` and `max` would be three round trips and one more place for the range filter
     * to be spelled differently. A personal weight history is one row per day.
     */
    async weightStatistics(
      userId: string,
      from: string | null,
      to: string | null,
    ): Promise<WeightStatistics> {
      const filters = [eq(measurements.userId, userId), isNull(measurements.deletedAt)];
      if (from !== null) filters.push(gte(measurements.date, from));
      if (to !== null) filters.push(lte(measurements.date, to));

      const rows = await database.db
        .select({ date: measurements.date, weightCg: measurements.weightCg })
        .from(measurements)
        .where(and(...filters))
        .orderBy(asc(measurements.date));

      const first = rows[0];
      const last = rows.at(-1);
      if (first === undefined || last === undefined) {
        return {
          count: 0,
          firstDate: null,
          firstWeightCg: null,
          lastDate: null,
          lastWeightCg: null,
          minWeightCg: null,
          minDate: null,
          maxWeightCg: null,
          maxDate: null,
          meanWeightCg: null,
          changeCg: null,
        };
      }

      let lowest = first;
      let highest = first;
      let total = 0;
      for (const row of rows) {
        if (row.weightCg < lowest.weightCg) lowest = row;
        if (row.weightCg > highest.weightCg) highest = row;
        total += row.weightCg;
      }

      return {
        count: rows.length,
        firstDate: first.date,
        firstWeightCg: first.weightCg,
        lastDate: last.date,
        lastWeightCg: last.weightCg,
        minWeightCg: lowest.weightCg,
        minDate: lowest.date,
        maxWeightCg: highest.weightCg,
        maxDate: highest.date,
        meanWeightCg: total / rows.length,
        // One measurement is not a change. A zero here would state that the weight held
        // steady over a period nobody measured twice.
        changeCg: rows.length > 1 ? last.weightCg - first.weightCg : null,
      };
    },

    async listActivities(query: ListActivitiesQuery): Promise<ListActivitiesResult> {
      const filters = [eq(activitySessions.userId, query.userId)];
      if (query.from !== null) filters.push(gte(activitySessions.startedOn, query.from));
      if (query.to !== null) filters.push(lte(activitySessions.startedOn, query.to));
      if (query.movement !== null) filters.push(eq(activitySessions.movement, query.movement));
      if (!query.includeDeleted) filters.push(isNull(activitySessions.deletedAt));
      if (query.afterKey !== null) {
        // A day holds more than one session, so the keyset is the pair the index is on:
        // `(started_on desc, id)`. A cursor on the day alone would drop every other
        // session of the day a page happened to end inside.
        const { first: day, second: id } = decodePairKey(query.afterKey);
        const after = or(
          lt(activitySessions.startedOn, day),
          and(eq(activitySessions.startedOn, day), gt(activitySessions.id, id)),
        );
        if (after !== undefined) filters.push(after);
      }

      const rows = await database.db
        .select()
        .from(activitySessions)
        .where(and(...filters))
        .orderBy(desc(activitySessions.startedOn), asc(activitySessions.id))
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        activities: page.map(activityViewOf),
        hasMore: rows.length > query.limit,
      };
    },

    async getActivity(
      userId: string,
      id: string,
      includeDeleted: boolean,
    ): Promise<ActivitySessionView | null> {
      const row = await readActivityRow(database, userId, id);
      if (row === undefined) return null;
      if (row.deletedAt !== null && !includeDeleted) return null;
      return activityViewOf(row);
    },

    async activityStatistics(
      userId: string,
      from: string | null,
      to: string | null,
    ): Promise<ActivityStatistics> {
      const filters = [eq(activitySessions.userId, userId), isNull(activitySessions.deletedAt)];
      if (from !== null) filters.push(gte(activitySessions.startedOn, from));
      if (to !== null) filters.push(lte(activitySessions.startedOn, to));

      const rows = await database.db
        .select({
          startedOn: activitySessions.startedOn,
          movement: activitySessions.movement,
          durationSeconds: activitySessions.durationSeconds,
        })
        .from(activitySessions)
        .where(and(...filters))
        .orderBy(asc(activitySessions.startedOn));

      const totals = new Map<string, { sessionCount: number; totalDurationSeconds: number }>();
      let totalDurationSeconds = 0;
      for (const row of rows) {
        totalDurationSeconds += row.durationSeconds;
        const current = totals.get(row.movement) ?? { sessionCount: 0, totalDurationSeconds: 0 };
        totals.set(row.movement, {
          sessionCount: current.sessionCount + 1,
          totalDurationSeconds: current.totalDurationSeconds + row.durationSeconds,
        });
      }

      const byMovement: MovementTotal[] = [...totals.entries()]
        .map(([movement, total]) => ({ movement, ...total }))
        .sort((a, b) => b.totalDurationSeconds - a.totalDurationSeconds);

      return {
        sessionCount: rows.length,
        totalDurationSeconds,
        firstDate: rows[0]?.startedOn ?? null,
        lastDate: rows.at(-1)?.startedOn ?? null,
        byMovement,
      };
    },

    async listCustomExercises(query: ListCustomExercisesQuery): Promise<ListCustomExercisesResult> {
      const filters = [eq(customExercises.userId, query.userId)];
      if (!query.includeDeleted) filters.push(isNull(customExercises.deletedAt));
      if (query.afterKey !== null) {
        const { first: nameFolded, second: id } = decodePairKey(query.afterKey);
        const after = or(
          gt(customExercises.nameFolded, nameFolded),
          and(eq(customExercises.nameFolded, nameFolded), gt(customExercises.id, id)),
        );
        if (after !== undefined) filters.push(after);
      }

      const rows = await database.db
        .select()
        .from(customExercises)
        .where(and(...filters))
        .orderBy(asc(customExercises.nameFolded), asc(customExercises.id))
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        exercises: page.map((row) => ({
          payload: customExercisePayloadOf(row),
          meta: metaOf(row),
        })),
        hasMore: rows.length > query.limit,
      };
    },

    async getCustomExercise(
      userId: string,
      id: string,
      includeDeleted: boolean,
    ): Promise<StoredAggregate<CustomExerciseDefinitionPayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(customExercises)
        .where(and(eq(customExercises.userId, userId), eq(customExercises.id, id)));
      const row = rows[0];
      if (row === undefined) return null;
      if (row.deletedAt !== null && !includeDeleted) return null;
      return { payload: customExercisePayloadOf(row), meta: metaOf(row) };
    },

    // --- the aggregates the write tools edit and read back ----------------------------

    async getActivityPayload(
      userId: string,
      id: string,
    ): Promise<StoredAggregate<ActivitySessionPayloadV1> | null> {
      const row = await readActivityRow(database, userId, id);
      return row === undefined ? null : { payload: activityPayloadOf(row), meta: metaOf(row) };
    },

    async getFood(userId: string, id: string): Promise<StoredAggregate<FoodPayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(foods)
        .where(and(eq(foods.userId, userId), eq(foods.id, id)));
      const row = rows[0];
      if (row === undefined) return null;
      const payload: FoodPayloadV1 = {
        id: row.id,
        name: row.name,
        source: row.source as FoodPayloadV1["source"],
        referenceUnit: row.referenceUnit as FoodPayloadV1["referenceUnit"],
        rawLabel: row.rawLabel,
        cookedLabel: row.cookedLabel,
        ...ifPresent("energyMilliKcal", row.energyMilliKcal),
        ...ifPresent("proteinMilligrams", row.proteinMilligrams),
        ...ifPresent("carbsMilligrams", row.carbsMilligrams),
        ...ifPresent("fatMilligrams", row.fatMilligrams),
        ...ifPresent("fibreMilligrams", row.fibreMilligrams),
        ...ifPresent("brand", row.brand),
        ...ifPresent("barcode", row.barcode),
        ...ifPresent("sourceId", row.sourceId),
        ...ifPresent("sourceVersion", row.sourceVersion),
        ...ifPresent("servingLabel", row.servingLabel),
        ...ifPresent("servingThousandths", row.servingThousandths),
        ...ifPresent("cookedRatioThousandths", row.cookedRatioThousandths),
        ...ifPresent("imageRef", row.imageRef),
      };
      return { payload, meta: metaOf(row) };
    },

    async getRecipe(userId: string, id: string): Promise<StoredAggregate<RecipePayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(recipes)
        .where(and(eq(recipes.userId, userId), eq(recipes.id, id)));
      const row = rows[0];
      if (row === undefined) return null;
      const steps = row.steps as string[];
      const payload: RecipePayloadV1 = {
        id: row.id,
        name: row.name,
        type: row.type as RecipePayloadV1["type"],
        baseServings: row.baseServings,
        isFavourite: row.isFavourite,
        ingredients: row.ingredients as RecipePayloadV1["ingredients"],
        ...ifPresent("description", row.description),
        ...ifPresent("prepTimeMinutes", row.prepTimeMinutes),
        // An empty list is the *absence* of steps, which is the shape the phone journals:
        // `RecipePayload.steps` defaults to the empty list and the encoder omits defaults.
        ...ifPresent("steps", steps.length === 0 ? null : steps),
        ...ifPresent("imageRef", row.imageRef),
      };
      return { payload, meta: metaOf(row) };
    },

    async getFoodLogEntry(
      userId: string,
      id: string,
    ): Promise<StoredAggregate<FoodLogEntryPayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(foodLogEntries)
        .where(and(eq(foodLogEntries.userId, userId), eq(foodLogEntries.id, id)));
      const row = rows[0];
      if (row === undefined) return null;
      const payload: FoodLogEntryPayloadV1 = {
        id: row.id,
        consumedOn: row.consumedOn,
        consumedAt: row.consumedAt,
        slot: row.slot as FoodLogEntryPayloadV1["slot"],
        kind: row.kind as FoodLogEntryPayloadV1["kind"],
        title: row.title,
        estimation: row.estimation as FoodLogEntryPayloadV1["estimation"],
        weighedCooked: row.weighedCooked,
        ...ifPresent("energyMilliKcal", row.energyMilliKcal),
        ...ifPresent("proteinMilligrams", row.proteinMilligrams),
        ...ifPresent("carbsMilligrams", row.carbsMilligrams),
        ...ifPresent("fatMilligrams", row.fatMilligrams),
        ...ifPresent("fibreMilligrams", row.fibreMilligrams),
        ...ifPresent("sourceRef", row.sourceRef),
        ...ifPresent("amountLabel", row.amountLabel),
        ...ifPresent("quantityThousandths", row.quantityThousandths),
        ...ifPresent("quantityUnit", row.quantityUnit as FoodLogEntryPayloadV1["quantityUnit"]),
        ...ifPresent("portionsThousandths", row.portionsThousandths),
        ...ifPresent("fromPlan", row.fromPlan),
      };
      return { payload, meta: metaOf(row) };
    },

    // --- sections 14.3 and 14.6, writes -----------------------------------------------

    applyAgentMutation(command: AgentMutationCommand): Promise<AgentMutationResult> {
      return applyMutation(database, command);
    },

    createActivitySession(command: CreateActivityCommand): Promise<CreateActivityResult> {
      return createActivity(database, command);
    },

    /**
     * Section 14.7's eight fields. `occurred_at` is the column default, so the server
     * instant is the database's own clock and not one an agent could influence.
     */
    async recordAudit(entry: AgentAuditEntry): Promise<void> {
      await database.db.insert(schema.agentAudit).values({
        id: crypto.randomUUID(),
        agentId: entry.agentId,
        toolName: entry.toolName,
        mutationId: entry.mutationId,
        aggregates: entry.aggregates,
        result: entry.result,
        revision: entry.revision === null ? null : BigInt(entry.revision),
        error: entry.error,
      });
    },
  };
}

function instantOrNull(value: Date | string | null | undefined): string | null {
  return value === null || value === undefined ? null : new Date(value).toISOString();
}

function customExercisePayloadOf(row: {
  id: string;
  name: string;
  trackingMode: string;
  equipment: string | null;
}): CustomExerciseDefinitionPayloadV1 {
  return {
    id: row.id,
    name: row.name,
    trackingMode: row.trackingMode as CustomExerciseDefinitionPayloadV1["trackingMode"],
    equipment: row.equipment as CustomExerciseDefinitionPayloadV1["equipment"],
  };
}

async function readActivityRow(
  database: DatabaseHandle,
  userId: string,
  id: string,
): Promise<typeof schema.activitySessions.$inferSelect | undefined> {
  const rows = await database.db
    .select()
    .from(schema.activitySessions)
    .where(and(eq(schema.activitySessions.userId, userId), eq(schema.activitySessions.id, id)));
  return rows[0];
}
