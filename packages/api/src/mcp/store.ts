import {
  MEAL_SLOTS,
  sexSchema,
  type ActivitySessionPayloadV1,
  type BodyCompositionV1,
  type CustomExerciseDefinitionPayloadV1,
  type FoodLogEntryPayloadV1,
  type FoodPayloadV1,
  type HealthProfilePayloadV1,
  type MealPlanEntryPayloadV1,
  type OriginType,
  type RecipePayloadV1,
} from "@mue/contracts";
import type { DatabaseHandle } from "@mue/db";
import { schema } from "@mue/db";
import {
  and,
  asc,
  count,
  desc,
  eq,
  gt,
  gte,
  ilike,
  inArray,
  isNull,
  lt,
  lte,
  max,
  or,
  sql,
  type SQL,
} from "drizzle-orm";
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
  ListFoodLogEntriesQuery,
  ListFoodLogEntriesResult,
  ListMealPlanQuery,
  ListMealPlanResult,
  ListRecipesQuery,
  ListRecipesResult,
  ListWeightMeasurementsQuery,
  ListWeightMeasurementsResult,
  MovementTotal,
  MueMcpServices,
  SearchFoodsQuery,
  SearchFoodsResult,
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

/**
 * A fragment a person typed, turned into a `like` pattern that matches it literally.
 *
 * `%` and `_` are wildcards to PostgreSQL and ordinary characters to the person who typed
 * them. Without this, searching for `100_g` would match anything with `100` and a `g` two
 * characters later, which is not wrong so much as inexplicable. The backslash is escaped
 * first, or escaping the other two would double-escape it.
 *
 * This is quoting, not sanitising: the pattern is still bound as a parameter, so nothing
 * here stands between a value and SQL injection. Drizzle already does that.
 */
function likeFragment(text: string): string {
  return `%${text.replaceAll("\\", "\\\\").replaceAll("%", "\\%").replaceAll("_", "\\_")}%`;
}

/**
 * Where a moment falls in the day, as the contract orders them.
 *
 * Built from `MEAL_SLOTS` rather than written out, for the reason every other list in this
 * work is: the enum is growing to a fuller set of moments, and a hand-written `case` would
 * still compile, still run, and quietly sort the two new ones last.
 *
 * A slot the enum does not hold sorts after every one it does, which is the only ordering
 * that is defensible for a value this build has never been told about.
 */
function mealSlotRank(column: SQL | typeof schema.mealPlanEntries.slot): SQL<number> {
  const whens = MEAL_SLOTS.map((slot, index) => sql`when ${slot} then ${index}`);
  return sql<number>`case ${column} ${sql.join(whens, sql` `)} else ${MEAL_SLOTS.length} end`;
}

/** The same rank, for a slot already in hand -- a cursor's, for instance. */
function mealSlotRankOf(slot: string): number {
  const index = (MEAL_SLOTS as readonly string[]).indexOf(slot);
  return index === -1 ? MEAL_SLOTS.length : index;
}

export function createMueMcpServices(database: DatabaseHandle): MueMcpServices {
  const createActivity = createActivitySessionService();
  const applyMutation = createAgentMutationService();

  const {
    activitySessions,
    bodyComposition,
    customExercises,
    foodLogEntries,
    foods,
    healthProfile,
    mealPlanEntries,
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

    /**
     * The whole profile, `sex` included (PRD_SCALE 22).
     *
     * The omission this replaces was not cosmetic. `mue.update_health_profile` reads this
     * aggregate, rebuilds a complete payload from it, and quotes `meta.revision` as the base
     * it was editing -- so a profile read without its sex produced a payload without one,
     * against a base snapshot that had one, and section 13.4's field merge correctly read
     * that as *the author removed the sex*. Editing a height cleared it, and with it every
     * future body composition, which FR-BODY-001 cannot compute without one.
     *
     * The lesson generalises past this field: for an aggregate merged field by field, a read
     * used as a merge base must return every field the wire carries, or the ones it drops are
     * deleted by anyone who edits any of the others.
     */
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
        payload: {
          heightCm: row.heightCm,
          birthDate: row.birthDate,
          // `health_profile.sex` is a nullable column and the payload key is `.optional()`:
          // an unstated sex is an *absent* key, never a `null`, because `sexSchema.optional()`
          // refuses `null` and the merged payload is journalled and re-parsed by every pull.
          // Parsed rather than cast, for the same reason `health-profile.ts` parses it: the
          // column is plain text, and a value that is neither `female` nor `male` is not a sex
          // this build can put back on the wire.
          ...ifPresent("sex", storedSex(row.sex)),
        },
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
        .select({ measurement: measurements, composition: bodyComposition })
        .from(measurements)
        .leftJoin(bodyComposition, onSameWeighing)
        .where(and(...filters))
        .orderBy(desc(measurements.date))
        // One extra row answers `hasMore` without a second count query.
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        measurements: page.map((row): WeightMeasurementView =>
          measurementViewOf(row.measurement, row.composition),
        ),
        hasMore: rows.length > query.limit,
      };
    },

    async getWeightMeasurement(
      userId: string,
      date: string,
      includeDeleted: boolean,
    ): Promise<WeightMeasurementView | null> {
      const rows = await database.db
        .select({ measurement: measurements, composition: bodyComposition })
        .from(measurements)
        .leftJoin(bodyComposition, onSameWeighing)
        .where(and(eq(measurements.userId, userId), eq(measurements.date, date)));
      const row = rows[0];
      if (row === undefined) return null;
      if (row.measurement.deletedAt !== null && !includeDeleted) return null;
      return measurementViewOf(row.measurement, row.composition);
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
      return { payload: foodPayloadOf(row), meta: metaOf(row) };
    },

    async getRecipe(userId: string, id: string): Promise<StoredAggregate<RecipePayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(recipes)
        .where(and(eq(recipes.userId, userId), eq(recipes.id, id)));
      const row = rows[0];
      if (row === undefined) return null;
      return { payload: recipePayloadOf(row), meta: metaOf(row) };
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
      return { payload: linePayloadOf(row), meta: metaOf(row) };
    },

    async getMealPlanEntry(
      userId: string,
      plannedOn: string,
      slot: string,
    ): Promise<StoredAggregate<MealPlanEntryPayloadV1> | null> {
      const rows = await database.db
        .select()
        .from(mealPlanEntries)
        .where(
          and(
            eq(mealPlanEntries.userId, userId),
            eq(mealPlanEntries.plannedOn, plannedOn),
            eq(mealPlanEntries.slot, slot),
          ),
        );
      const row = rows[0];
      if (row === undefined) return null;
      return { payload: planPayloadOf(row), meta: metaOf(row) };
    },

    // --- PRD_FOOD 21.5, the food reads ------------------------------------------------

    async listFoodLogEntries(query: ListFoodLogEntriesQuery): Promise<ListFoodLogEntriesResult> {
      const filters = [eq(foodLogEntries.userId, query.userId)];
      if (query.from !== null) filters.push(gte(foodLogEntries.consumedOn, query.from));
      if (query.to !== null) filters.push(lte(foodLogEntries.consumedOn, query.to));
      if (query.slot !== null) filters.push(eq(foodLogEntries.slot, query.slot));
      if (!query.includeDeleted) filters.push(isNull(foodLogEntries.deletedAt));
      if (query.afterKey !== null) {
        // A day holds several lines and a *minute* can hold two, so the keyset is the whole
        // of `food_log_entries_day_idx`: day, then clock, then identifier. A cursor on the
        // day alone would drop every line after the one a page happened to end on.
        const { first: dayAndTime, second: id } = decodePairKey(query.afterKey);
        const { first: day, second: time } = decodePairKey(dayAndTime);
        const after = or(
          lt(foodLogEntries.consumedOn, day),
          and(eq(foodLogEntries.consumedOn, day), lt(foodLogEntries.consumedAt, time)),
          and(
            eq(foodLogEntries.consumedOn, day),
            eq(foodLogEntries.consumedAt, time),
            lt(foodLogEntries.id, id),
          ),
        );
        if (after !== undefined) filters.push(after);
      }

      const rows = await database.db
        .select()
        .from(foodLogEntries)
        .where(and(...filters))
        .orderBy(
          desc(foodLogEntries.consumedOn),
          desc(foodLogEntries.consumedAt),
          desc(foodLogEntries.id),
        )
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        entries: page.map((row) => ({ payload: linePayloadOf(row), meta: metaOf(row) })),
        hasMore: rows.length > query.limit,
      };
    },

    async foodLogEntriesOn(
      userId: string,
      date: string,
    ): Promise<readonly StoredAggregate<FoodLogEntryPayloadV1>[]> {
      const rows = await database.db
        .select()
        .from(foodLogEntries)
        .where(
          and(
            eq(foodLogEntries.userId, userId),
            eq(foodLogEntries.consumedOn, date),
            // A tombstone is not a consumption. A deleted line must not enter a total, and
            // there is no `includeDeleted` here for that reason: the caller is asking what
            // was eaten, and the answer never includes what was taken back.
            isNull(foodLogEntries.deletedAt),
          ),
        )
        .orderBy(asc(foodLogEntries.consumedAt), asc(foodLogEntries.id));
      return rows.map((row) => ({ payload: linePayloadOf(row), meta: metaOf(row) }));
    },

    async searchFoods(query: SearchFoodsQuery): Promise<SearchFoodsResult> {
      const filters = [eq(foods.userId, query.userId)];
      if (!query.includeDeleted) filters.push(isNull(foods.deletedAt));
      if (query.source !== null) filters.push(eq(foods.source, query.source));
      if (query.barcode !== null) filters.push(eq(foods.barcode, query.barcode));
      if (query.text !== null) {
        const pattern = likeFragment(query.text);
        // The brand is searched with the name because a person looking for `Isey` is looking
        // for a food, not for a field. `ilike` is the case fold; see `SearchFoodsQuery` for
        // why the accent fold is not attempted here.
        const matches = or(ilike(foods.name, pattern), ilike(foods.brand, pattern));
        if (matches !== undefined) filters.push(matches);
      }
      if (query.afterKey !== null) {
        const { first: name, second: id } = decodePairKey(query.afterKey);
        const after = or(gt(foods.name, name), and(eq(foods.name, name), gt(foods.id, id)));
        if (after !== undefined) filters.push(after);
      }

      const rows = await database.db
        .select()
        .from(foods)
        .where(and(...filters))
        .orderBy(asc(foods.name), asc(foods.id))
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        foods: page.map((row) => ({ payload: foodPayloadOf(row), meta: metaOf(row) })),
        hasMore: rows.length > query.limit,
      };
    },

    async listRecipes(query: ListRecipesQuery): Promise<ListRecipesResult> {
      const filters = [eq(recipes.userId, query.userId)];
      if (!query.includeDeleted) filters.push(isNull(recipes.deletedAt));
      if (query.type !== null) filters.push(eq(recipes.type, query.type));
      if (query.favouritesOnly) filters.push(eq(recipes.isFavourite, true));
      if (query.text !== null) filters.push(ilike(recipes.name, likeFragment(query.text)));
      if (query.afterKey !== null) {
        const { first: name, second: id } = decodePairKey(query.afterKey);
        const after = or(gt(recipes.name, name), and(eq(recipes.name, name), gt(recipes.id, id)));
        if (after !== undefined) filters.push(after);
      }

      const rows = await database.db
        .select()
        .from(recipes)
        .where(and(...filters))
        .orderBy(asc(recipes.name), asc(recipes.id))
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        recipes: page.map((row) => ({ payload: recipePayloadOf(row), meta: metaOf(row) })),
        hasMore: rows.length > query.limit,
      };
    },

    async foodsByIds(
      userId: string,
      ids: readonly string[],
    ): Promise<ReadonlyMap<string, FoodPayloadV1>> {
      const found = new Map<string, FoodPayloadV1>();
      if (ids.length === 0) return found;
      const rows = await database.db
        .select()
        .from(foods)
        .where(
          and(
            eq(foods.userId, userId),
            inArray(foods.id, [...new Set(ids)]),
            isNull(foods.deletedAt),
          ),
        );
      for (const row of rows) found.set(row.id, foodPayloadOf(row));
      return found;
    },

    async listMealPlan(query: ListMealPlanQuery): Promise<ListMealPlanResult> {
      const rank = mealSlotRank(mealPlanEntries.slot);
      const filters = [eq(mealPlanEntries.userId, query.userId)];
      if (query.from !== null) filters.push(gte(mealPlanEntries.plannedOn, query.from));
      if (query.to !== null) filters.push(lte(mealPlanEntries.plannedOn, query.to));
      if (!query.includeDeleted) filters.push(isNull(mealPlanEntries.deletedAt));
      if (query.afterKey !== null) {
        // Ordered forwards, unlike the journal: a plan is read towards the future, and the
        // moment inside a day is ordered by the clock rather than by the spelling of its id.
        const { first: day, second: slot } = decodePairKey(query.afterKey);
        const after = or(
          gt(mealPlanEntries.plannedOn, day),
          and(eq(mealPlanEntries.plannedOn, day), gt(rank, mealSlotRankOf(slot))),
        );
        if (after !== undefined) filters.push(after);
      }

      const rows = await database.db
        .select()
        .from(mealPlanEntries)
        .where(and(...filters))
        .orderBy(asc(mealPlanEntries.plannedOn), asc(rank))
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        entries: page.map((row) => ({ payload: planPayloadOf(row), meta: metaOf(row) })),
        hasMore: rows.length > query.limit,
      };
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

/*
 * The weighing and its optional child (PRD_SCALE 21.1, 22).
 */

/**
 * The join a weighing and its composition are always read across.
 *
 * `body_composition`'s primary key *is* its foreign key onto `measurements(user_id, date)`, so
 * there is at most one child per weighing and a left join costs one row either way. Written
 * once, and used by both readers, because a condition spelled twice is a condition that can be
 * spelled differently -- and dropping `user_id` from one copy would hand one account's
 * composition to another.
 */
const onSameWeighing = and(
  eq(schema.bodyComposition.userId, schema.measurements.userId),
  eq(schema.bodyComposition.date, schema.measurements.date),
);

/** The child row, rebuilt into the shape the payload and the tools state it in. */
function compositionOf(
  row: typeof schema.bodyComposition.$inferSelect | null,
): BodyCompositionV1 | null {
  if (row === null) return null;
  return {
    formulaId: row.formulaId,
    formulaVersion: row.formulaVersion,
    inputWeightCg: row.inputWeightCg,
    inputHeightCm: row.inputHeightCm,
    inputAgeYears: row.inputAgeYears,
    // Plain text columns, narrowed by the schema that refused anything else on the way in.
    inputSex: row.inputSex as BodyCompositionV1["inputSex"],
    bodyFatDeciPercent: row.bodyFatDeciPercent,
    fatFreeMassCg: row.fatFreeMassCg,
    bodyWaterDeciPercent: row.bodyWaterDeciPercent,
    restingEnergyKcal: row.restingEnergyKcal,
  };
}

/**
 * One `WeightMeasurementView`, from the parent row and the joined child.
 *
 * One transcription for both readers, for the reason the food payloads above are also written
 * once: two copies of "which column is which key" is how a composition ends up reported by
 * `get_weight_measurement` and silently dropped by `list_weight_measurements`, which no shape
 * comparison can see and which BR-SCALE-007 then turns into a deletion on the next write.
 */
function measurementViewOf(
  row: typeof schema.measurements.$inferSelect,
  composition: typeof schema.bodyComposition.$inferSelect | null,
): WeightMeasurementView {
  return {
    date: row.date,
    weightCg: row.weightCg,
    sourceType: row.sourceType,
    impedanceOhm: row.impedanceOhm,
    bodyComposition: compositionOf(composition),
    ...metaOf(row),
  };
}

/**
 * The stored sex, read back into the shape the payload states it in.
 *
 * Parsed and not cast: `health_profile.sex` is plain text, and a value that is neither
 * `female` nor `male` is not a sex this build knows. Treating one as a sex would put it back
 * on the wire, where `sexSchema` refuses it and a journalled snapshot would stop a cursor.
 * `packages/domain/src/sync/health-profile.ts` does exactly this on the write side.
 */
function storedSex(value: string | null): HealthProfilePayloadV1["sex"] | null {
  const parsed = sexSchema.safeParse(value);
  return parsed.success ? parsed.data : null;
}

/*
 * The four food aggregates, rebuilt from their columns.
 *
 * These were inside the three `get*` methods until the read tools arrived and needed the
 * same transcription for a *page* of rows. Two copies of "which nullable column is which
 * optional key" is how a `description: null` reaches a schema that requires the key to be
 * absent -- the failure mode this file's `optional` helper already exists to prevent, and
 * one that no shape comparison can see. So there is one transcription per aggregate and
 * every reader goes through it.
 */

function foodPayloadOf(row: typeof schema.foods.$inferSelect): FoodPayloadV1 {
  return {
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
}

function recipePayloadOf(row: typeof schema.recipes.$inferSelect): RecipePayloadV1 {
  const steps = row.steps as string[];
  return {
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
}

function linePayloadOf(row: typeof schema.foodLogEntries.$inferSelect): FoodLogEntryPayloadV1 {
  return {
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
}

/**
 * A proposal. `plannedOn` and `slot` are the primary key rather than payload columns, and
 * the wire identifier is derived from them by `mealPlanAggregateId` -- it is stored nowhere,
 * so there is no second place for its spelling to be wrong.
 */
function planPayloadOf(row: typeof schema.mealPlanEntries.$inferSelect): MealPlanEntryPayloadV1 {
  return {
    plannedOn: row.plannedOn,
    slot: row.slot as MealPlanEntryPayloadV1["slot"],
    recipeId: row.recipeId,
    plannedServingsThousandths: row.plannedServingsThousandths,
    ...ifPresent("consumedLogEntryId", row.consumedLogEntryId),
  };
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
