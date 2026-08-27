import { sql } from "drizzle-orm";
import {
  bigint,
  boolean,
  check,
  date,
  index,
  integer,
  jsonb,
  pgSchema,
  primaryKey,
  text,
  timestamp,
  uniqueIndex,
} from "drizzle-orm/pg-core";
import { user } from "./auth";

/**
 * Everything Mue owns lives in `mue_app`. The schema itself is created once by
 * the DBA (infra/README.md); PRD section 20.3 forbids the application from
 * creating a database, a role or a schema, and the `mue` role could not anyway.
 *
 * Known coupling, also recorded in infra/README.md: the two schema names are
 * literals here and environment variables there. Changing one means changing
 * the other. `assertSchemaNamesMatchEnvironment()` in ../config.ts turns that
 * into a startup failure instead of a silent mismatch.
 */
export const mueApp = pgSchema("mue_app");

/**
 * The section 12.1 metadata every synchronised aggregate carries. A function,
 * not a shared object: a Drizzle column builder is stateful, so each table
 * gets its own instances.
 */
function aggregateMetadata() {
  return {
    /** Server revision of this aggregate, bumped by each accepted mutation (section 13.3). */
    revision: bigint("revision", { mode: "bigint" }).notNull(),
    /** Business instant of creation, from the author (section 12.1). */
    createdAt: timestamp("created_at", { withTimezone: true }).notNull(),
    /** Business instant of last known modification. */
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull(),
    /** Set instead of deleting the row: the tombstone of FR-SYNC-005. */
    deletedAt: timestamp("deleted_at", { withTimezone: true }),
    originType: text("origin_type").notNull(),
    originId: text("origin_id"),
    lastMutationId: text("last_mutation_id").notNull(),
    payloadSchemaVersion: integer("payload_schema_version").notNull(),
  };
}

/**
 * The sync cursor source, one row per user.
 *
 * A `bigserial` cannot be the cursor. Two transactions take 100 and 101; 101
 * commits first; a client pulling at that instant reads to 101 and advances
 * past 100, which commits a millisecond later and is never delivered. It
 * passes every test and loses a change in production, which is the opposite of
 * section 12.3 requiring *all* changes since a given cursor.
 *
 * Reading the sequence from this row inside the mutation transaction takes a
 * row lock that is held until commit, so appends for one user are serialised
 * and a visible sequence implies every lower one is already visible. Section 6
 * puts multi-user out of scope, so there is a single writer stream and the
 * contention this costs is nil.
 */
export const syncCounter = mueApp.table("sync_counter", {
  userId: text("user_id")
    .primaryKey()
    .references(() => user.id, { onDelete: "cascade" }),
  seq: bigint("seq", { mode: "bigint" })
    .notNull()
    .default(sql`0`),
});

/**
 * The append-only journal a client pulls from.
 *
 * The payload is a snapshot, not a pointer to the current row: it is what
 * makes a pull at sequence N return what was accepted at N, what lets section
 * 12.4 reject an unsupported `payload_schema_version` for the exact change
 * that carries it, and what keeps the replaced version auditable as sections
 * 13.2 and 13.3 require.
 */
export const syncJournal = mueApp.table(
  "sync_journal",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    sequence: bigint("sequence", { mode: "bigint" }).notNull(),
    aggregateType: text("aggregate_type").notNull(),
    aggregateId: text("aggregate_id").notNull(),
    operation: text("operation").notNull(),
    revision: bigint("revision", { mode: "bigint" }).notNull(),
    payloadSchemaVersion: integer("payload_schema_version").notNull(),
    /** Null for a delete: the tombstone is the change. */
    payload: jsonb("payload"),
    deletedAt: timestamp("deleted_at", { withTimezone: true }),
    originType: text("origin_type").notNull(),
    originId: text("origin_id"),
    mutationId: text("mutation_id").notNull(),
    /** Server clock, for display and audit only. Never for ordering (section 12.3). */
    recordedAt: timestamp("recorded_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [
    // This is the (user_id, sequence) index section 12.3 needs. Declaring it as
    // the primary key rather than a plain index also makes a duplicated
    // sequence a constraint violation instead of a silently doubled change.
    primaryKey({ columns: [t.userId, t.sequence] }),
    index("sync_journal_aggregate_idx").on(t.userId, t.aggregateType, t.aggregateId),
    index("sync_journal_recorded_at_idx").on(t.recordedAt),
    check("sync_journal_operation_check", sql`${t.operation} in ('upsert', 'delete')`),
    check("sync_journal_sequence_check", sql`${t.sequence} > 0`),
  ],
);

/**
 * FR-SYNC-006. A mutation is written here exactly once, with
 * `insert ... on conflict (mutation_id) do nothing returning *`. An empty
 * result means the mutation already ran: the caller reads the stored `result`
 * and returns it verbatim, which is what "le rejeu retourne le resultat
 * existant" means. A rejection is stored too, so a replayed bad mutation gets
 * the same structured error rather than a second attempt.
 */
export const mutationLog = mueApp.table(
  "mutation_log",
  {
    mutationId: text("mutation_id").primaryKey(),
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    aggregateType: text("aggregate_type").notNull(),
    aggregateId: text("aggregate_id").notNull(),
    operation: text("operation").notNull(),
    status: text("status").notNull(),
    /** The journal sequence this mutation produced; null when it was rejected. */
    sequence: bigint("sequence", { mode: "bigint" }),
    revision: bigint("revision", { mode: "bigint" }),
    /** The wire result, replayed byte for byte. */
    result: jsonb("result").notNull(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [
    // Retention sweeps by age; the user prefix keeps the scan per account.
    index("mutation_log_retention_idx").on(t.userId, t.createdAt),
    check("mutation_log_operation_check", sql`${t.operation} in ('upsert', 'delete')`),
    check("mutation_log_status_check", sql`${t.status} in ('applied', 'rejected')`),
  ],
);

/**
 * One measurement per local date, replaced without warning (section 13.2).
 * The primary key is the business key Android already enforces, so the two
 * sides merge on the same thing and no identity conversion is needed.
 * `weight_cg` is centigrams, exactly as Room stores it.
 */
export const measurements = mueApp.table(
  "measurements",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    date: date("date", { mode: "string" }).notNull(),
    weightCg: integer("weight_cg").notNull(),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.date] }),
    index("measurements_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/**
 * Section 13.4: one aggregate, one row per user. Both fields are nullable
 * because the profile exists before it is filled in, and each may be merged
 * separately when they were not modified concurrently.
 */
export const healthProfile = mueApp.table("health_profile", {
  userId: text("user_id")
    .primaryKey()
    .references(() => user.id, { onDelete: "cascade" }),
  heightCm: integer("height_cm"),
  birthDate: date("birth_date", { mode: "string" }),
  ...aggregateMetadata(),
});

/**
 * Section 10.2 makes a finished session one atomic aggregate, and
 * PLATFORM-CONTRACT decision 1 makes it opaque: one payload, one revision,
 * replaced wholesale. So the scalars an agent or a list query filters on are
 * indexed columns, and the four child collections are `jsonb`.
 *
 * Six mirror tables would duplicate Room for zero V1 capability, and they
 * would pretend the child ids are stable merge keys, which they are not:
 * `StrengthDraftEditor.persistableExercises` mints fresh ids on every save and
 * `ActivityDao.saveDetail` deletes and reinserts all children.
 */
export const activitySessions = mueApp.table(
  "activity_sessions",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    id: text("id").notNull(),
    movement: text("movement").notNull(),
    customMovementName: text("custom_movement_name"),
    environment: text("environment").notNull(),
    startedOn: date("started_on", { mode: "string" }).notNull(),
    /** Local wall time as Room holds it. A `time` column would rewrite 07:30 as 07:30:00. */
    startedAtTime: text("started_at_time"),
    durationSeconds: integer("duration_seconds").notNull(),
    perceivedEffort: integer("perceived_effort"),
    notes: text("notes"),
    source: text("source").notNull(),
    metrics: jsonb("metrics")
      .notNull()
      .default(sql`'[]'::jsonb`),
    equipment: jsonb("equipment")
      .notNull()
      .default(sql`'[]'::jsonb`),
    exercises: jsonb("exercises")
      .notNull()
      .default(sql`'[]'::jsonb`),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.id] }),
    // Keyset pagination for the MCP history tools: the id breaks ties so a page
    // boundary inside one day cannot repeat or skip a session.
    index("activity_sessions_cursor_idx").on(t.userId, t.startedOn.desc(), t.id),
    index("activity_sessions_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/**
 * Custom exercise definitions, unique per user on the folded name exactly as
 * Room is unique on `name_folded`. The uniqueness is partial: a tombstoned
 * definition keeps its row so a deletion cannot be resurrected (FR-SYNC-005),
 * but it must not block re-creating the same name afterwards.
 */
export const customExercises = mueApp.table(
  "custom_exercises",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    id: text("id").notNull(),
    name: text("name").notNull(),
    nameFolded: text("name_folded").notNull(),
    trackingMode: text("tracking_mode").notNull(),
    equipment: text("equipment"),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.id] }),
    uniqueIndex("custom_exercises_name_folded_key")
      .on(t.userId, t.nameFolded)
      .where(sql`deleted_at is null`),
    index("custom_exercises_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/**
 * The four Food aggregates of PRD_FOOD 21.2, and the one decision they share
 * with `activity_sessions`: a scalar an agent filters on is a column, a child
 * collection is `jsonb`, and the aggregate is replaced whole.
 *
 * PRD_FOOD 21.3 is what licenses that. A custom food, a recipe and a journal
 * line are all "derniere mutation acceptee, agregat entier"; the recipe adds
 * "les ingredients ne sont pas fusionnes ligne a ligne" in as many words. So an
 * ingredient id is a marker inside a snapshot rather than a merge key -- and it
 * could not be one anyway, since `RecipeDao.saveDetailWithMutation` deletes and
 * reinserts the whole list on every save. Mirror tables would duplicate Room
 * for no V1 capability and would advertise a stability the ids do not have.
 *
 * Every number is a whole count of its canonical unit, exactly as Room stores
 * it and exactly as the wire carries it: thousandths of a kilocalorie,
 * milligrams, thousandths of a gram. No float enters this database, so nothing
 * can be rounded a second time.
 *
 * An unknown nutrient is a NULL column, never a zero. PRD_FOOD 13.1 forbids
 * inventing a value, and a zero here would be handed back to the phone on the
 * next pull as a fact it never stated.
 */
export const foods = mueApp.table(
  "foods",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    id: text("id").notNull(),
    name: text("name").notNull(),
    /** `custom` or `open_food_facts`. The Ciqual catalogue is not synchronised. */
    source: text("source").notNull(),
    referenceUnit: text("reference_unit").notNull(),
    rawLabel: text("raw_label").notNull(),
    cookedLabel: text("cooked_label").notNull(),
    energyMilliKcal: integer("energy_milli_kcal"),
    proteinMilligrams: integer("protein_milligrams"),
    carbsMilligrams: integer("carbs_milligrams"),
    fatMilligrams: integer("fat_milligrams"),
    fibreMilligrams: integer("fibre_milligrams"),
    brand: text("brand"),
    barcode: text("barcode"),
    sourceId: text("source_id"),
    sourceVersion: text("source_version"),
    servingLabel: text("serving_label"),
    servingThousandths: integer("serving_thousandths"),
    cookedRatioThousandths: integer("cooked_ratio_thousandths"),
    imageRef: text("image_ref"),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.id] }),
    index("foods_barcode_idx")
      .on(t.userId, t.barcode)
      .where(sql`barcode is not null and deleted_at is null`),
    index("foods_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/** A recipe with its ingredients and its steps, replaced whole (PRD_FOOD 21.2). */
export const recipes = mueApp.table(
  "recipes",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    id: text("id").notNull(),
    name: text("name").notNull(),
    type: text("type").notNull(),
    baseServings: integer("base_servings").notNull(),
    isFavourite: boolean("is_favourite").notNull(),
    description: text("description"),
    prepTimeMinutes: integer("prep_time_minutes"),
    imageRef: text("image_ref"),
    ingredients: jsonb("ingredients")
      .notNull()
      .default(sql`'[]'::jsonb`),
    steps: jsonb("steps")
      .notNull()
      .default(sql`'[]'::jsonb`),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.id] }),
    index("recipes_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/**
 * One consumption, self-contained (PRD section 10.2).
 *
 * `source_ref` is a plain text column and carries no foreign key onto `foods`
 * or `recipes`, which is the storage half of "autoportante": a line whose food
 * has never been synchronised is still a complete row, and it renders from its
 * own snapshot. The same is true of `from_plan`.
 */
export const foodLogEntries = mueApp.table(
  "food_log_entries",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    id: text("id").notNull(),
    consumedOn: date("consumed_on", { mode: "string" }).notNull(),
    /** Local wall time as Room holds it. A `time` column would rewrite 20:15 as 20:15:00. */
    consumedAt: text("consumed_at").notNull(),
    slot: text("slot").notNull(),
    kind: text("kind").notNull(),
    title: text("title").notNull(),
    estimation: text("estimation").notNull(),
    weighedCooked: boolean("weighed_cooked").notNull(),
    energyMilliKcal: integer("energy_milli_kcal"),
    proteinMilligrams: integer("protein_milligrams"),
    carbsMilligrams: integer("carbs_milligrams"),
    fatMilligrams: integer("fat_milligrams"),
    fibreMilligrams: integer("fibre_milligrams"),
    sourceRef: text("source_ref"),
    amountLabel: text("amount_label"),
    quantityThousandths: integer("quantity_thousandths"),
    quantityUnit: text("quantity_unit"),
    portionsThousandths: integer("portions_thousandths"),
    fromPlan: text("from_plan"),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.id] }),
    // Keyset pagination for `list_food_logs` and `get_daily_nutrition`: the id
    // breaks ties so a page boundary inside one day cannot repeat or skip a line.
    index("food_log_entries_day_idx").on(t.userId, t.consumedOn, t.consumedAt, t.id),
    index("food_log_entries_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/**
 * One proposal per date and moment (PRD_FOOD 21.3).
 *
 * The primary key is the business key -- `(user_id, planned_on, slot)` -- for
 * the same reason `measurements` is keyed by its date: convergence has to be
 * structural. Two devices planning the same evening address one row, and "une
 * proposition maximum par date et moment" is a constraint the table cannot
 * violate rather than a rule a handler remembers.
 *
 * The wire identifier `<planned_on>:<slot>` is therefore derived from these two
 * columns and stored nowhere. There is no second place for it to be wrong, and
 * no column to migrate the day its spelling changes again.
 */
export const mealPlanEntries = mueApp.table(
  "meal_plan_entries",
  {
    userId: text("user_id")
      .notNull()
      .references(() => user.id, { onDelete: "cascade" }),
    plannedOn: date("planned_on", { mode: "string" }).notNull(),
    slot: text("slot").notNull(),
    recipeId: text("recipe_id").notNull(),
    plannedServingsThousandths: integer("planned_servings_thousandths").notNull(),
    consumedLogEntryId: text("consumed_log_entry_id"),
    ...aggregateMetadata(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.plannedOn, t.slot] }),
    index("meal_plan_entries_tombstone_idx")
      .on(t.userId, t.deletedAt)
      .where(sql`deleted_at is not null`),
  ],
);

/**
 * Section 14.7, the eight fields it lists and nothing else beyond the
 * surrogate key a row needs. This is an audit trail, so it is never purged by
 * the sync retention sweep.
 */
export const agentAudit = mueApp.table(
  "agent_audit",
  {
    id: text("id").primaryKey(),
    agentId: text("agent_id").notNull(),
    toolName: text("tool_name").notNull(),
    occurredAt: timestamp("occurred_at", { withTimezone: true }).notNull().defaultNow(),
    mutationId: text("mutation_id"),
    /** `[{ type, id }]`: one write can touch more than one aggregate. */
    aggregates: jsonb("aggregates")
      .notNull()
      .default(sql`'[]'::jsonb`),
    result: text("result").notNull(),
    revision: bigint("revision", { mode: "bigint" }),
    error: jsonb("error"),
  },
  (t) => [
    index("agent_audit_agent_idx").on(t.agentId, t.occurredAt.desc()),
    check("agent_audit_result_check", sql`${t.result} in ('ok', 'error')`),
  ],
);
