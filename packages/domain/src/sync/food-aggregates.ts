import type {
  FoodLogEntryPayloadV1,
  FoodPayloadV1,
  MealPlanEntryPayloadV1,
  MutationEnvelope,
  RecipePayloadV1,
} from "@mue/contracts";
import { type Transaction, appendToJournal, schema } from "@mue/db";
import { and, eq, inArray } from "drizzle-orm";
import { invalidRequest } from "./errors";
import {
  type OpaqueState,
  deletedRejection,
  misroutedRejection,
  nextRevision,
  refusesResurrection,
} from "./opaque";
import type { AggregateHandler, ApplyOutcome, SyncContext } from "./types";

const { foods, recipes, foodLogEntries, mealPlanEntries } = schema;

/**
 * The four aggregates of PRD_FOOD 21.2, in one file because they follow one rule.
 *
 * PRD_FOOD 21.3 states it four times over: a custom food is *"dernière mutation acceptée"*, a
 * recipe is *"dernière mutation acceptée, agrégat entier"*, a concurrent edit of one journal line
 * *"applique la dernière mutation acceptée par le serveur"*, and two proposals on the same moment
 * *"se résolvent par la dernière mutation acceptée ; la précédente est remplacée, jamais
 * dupliquée"*. `opaque.ts` carries the reasoning; these four handlers are what it looks like
 * against four sets of columns.
 *
 * Two of the four then say something the other two do not, and both are visible below:
 *
 * - a **recipe** keeps its ingredients as `jsonb` inside its own row, because PRD_FOOD 21.2 makes
 *   it atomic and 21.3 forbids merging the list line by line;
 * - a **proposal** is keyed by `(user_id, planned_on, slot)`, its business key, so *"une
 *   proposition maximum par date et moment"* is a shape the table cannot violate. Its wire
 *   identifier is derived from those two columns and stored nowhere.
 */

/** `<planned_on>:<slot>`, split back into the two columns the table is keyed by. */
function splitMealPlanId(aggregateId: string): { plannedOn: string; slot: string } {
  const separator = aggregateId.lastIndexOf(":");
  return {
    plannedOn: aggregateId.slice(0, separator),
    slot: aggregateId.slice(separator + 1),
  };
}

// --- food ---------------------------------------------------------------------------------

async function readFoodState(
  tx: Transaction,
  userId: string,
  id: string,
): Promise<OpaqueState | undefined> {
  const rows = await tx
    .select({ revision: foods.revision, deletedAt: foods.deletedAt })
    .from(foods)
    .where(and(eq(foods.userId, userId), eq(foods.id, id)));
  return rows[0];
}

/**
 * A payload's optional keys become nullable columns.
 *
 * `?? null` is the whole conversion, and it is the one place absence and emptiness meet. On the
 * wire an unknown nutrient has no key at all (PRD_FOOD 13.1: never a zero); in PostgreSQL it is
 * NULL. Both mean "nobody knows", and neither is ever `0` — which the pull path reverses by
 * rebuilding the payload from the journal snapshot rather than from these columns, so a value
 * that was absent when it was accepted is still absent when it is handed back.
 */
function foodColumns(payload: FoodPayloadV1) {
  return {
    name: payload.name,
    source: payload.source,
    referenceUnit: payload.referenceUnit,
    rawLabel: payload.rawLabel,
    cookedLabel: payload.cookedLabel,
    energyMilliKcal: payload.energyMilliKcal ?? null,
    proteinMilligrams: payload.proteinMilligrams ?? null,
    carbsMilligrams: payload.carbsMilligrams ?? null,
    fatMilligrams: payload.fatMilligrams ?? null,
    fibreMilligrams: payload.fibreMilligrams ?? null,
    brand: payload.brand ?? null,
    barcode: payload.barcode ?? null,
    sourceId: payload.sourceId ?? null,
    sourceVersion: payload.sourceVersion ?? null,
    servingLabel: payload.servingLabel ?? null,
    servingThousandths: payload.servingThousandths ?? null,
    cookedRatioThousandths: payload.cookedRatioThousandths ?? null,
    imageRef: payload.imageRef ?? null,
  };
}

const FOOD_TOMBSTONE = {
  name: "",
  source: "custom",
  referenceUnit: "gram",
  rawLabel: "",
  cookedLabel: "",
} as const;

async function applyFood(
  tx: Transaction,
  context: SyncContext,
  mutation: MutationEnvelope,
  now: Date,
): Promise<ApplyOutcome> {
  const state = await readFoodState(tx, context.userId, mutation.aggregateId);

  if (mutation.op === "delete") {
    const revision = nextRevision(state);
    await tx
      .insert(foods)
      .values({
        userId: context.userId,
        id: mutation.aggregateId,
        ...FOOD_TOMBSTONE,
        revision,
        createdAt: now,
        updatedAt: now,
        deletedAt: now,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      })
      .onConflictDoUpdate({
        target: [foods.userId, foods.id],
        set: {
          revision,
          updatedAt: now,
          deletedAt: now,
          originType: mutation.origin.type,
          originId: mutation.origin.id,
          lastMutationId: mutation.mutationId,
          payloadSchemaVersion: mutation.payloadSchemaVersion,
        },
      });
    const sequence = await appendToJournal(tx, {
      userId: context.userId,
      aggregateType: "food",
      aggregateId: mutation.aggregateId,
      operation: "delete",
      revision,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
      payload: null,
      deletedAt: now,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      mutationId: mutation.mutationId,
    });
    return { status: "applied", revision, sequence };
  }

  if (mutation.aggregateType !== "food") {
    return misroutedRejection("food", mutation.aggregateId);
  }
  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return deletedRejection("food", mutation.aggregateId, state, "food");
  }

  const payload: FoodPayloadV1 = mutation.payload;
  const revision = nextRevision(state);
  const columns = foodColumns(payload);

  await tx
    .insert(foods)
    .values({
      userId: context.userId,
      id: payload.id,
      ...columns,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [foods.userId, foods.id],
      set: {
        ...columns,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "food",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });
  return { status: "applied", revision, sequence };
}

export const foodHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return applyFood(tx, context, mutation, now);
  },
  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ id: foods.id, createdAt: foods.createdAt })
      .from(foods)
      .where(and(eq(foods.userId, context.userId), inArray(foods.id, [...aggregateIds])));
    for (const row of rows) found.set(row.id, row.createdAt);
    return found;
  },
};

// --- recipe -------------------------------------------------------------------------------

async function readRecipeState(
  tx: Transaction,
  userId: string,
  id: string,
): Promise<OpaqueState | undefined> {
  const rows = await tx
    .select({ revision: recipes.revision, deletedAt: recipes.deletedAt })
    .from(recipes)
    .where(and(eq(recipes.userId, userId), eq(recipes.id, id)));
  return rows[0];
}

function recipeColumns(payload: RecipePayloadV1) {
  return {
    name: payload.name,
    type: payload.type,
    baseServings: payload.baseServings,
    isFavourite: payload.isFavourite,
    description: payload.description ?? null,
    prepTimeMinutes: payload.prepTimeMinutes ?? null,
    imageRef: payload.imageRef ?? null,
    // The whole list, replaced. PRD_FOOD 21.3 forbids merging it line by line, and the ids it
    // would have to merge on are re-minted by Room on every save.
    ingredients: payload.ingredients,
    steps: payload.steps ?? [],
  };
}

const RECIPE_TOMBSTONE = {
  name: "",
  type: "main",
  baseServings: 0,
  isFavourite: false,
} as const;

async function applyRecipe(
  tx: Transaction,
  context: SyncContext,
  mutation: MutationEnvelope,
  now: Date,
): Promise<ApplyOutcome> {
  const state = await readRecipeState(tx, context.userId, mutation.aggregateId);

  if (mutation.op === "delete") {
    const revision = nextRevision(state);
    await tx
      .insert(recipes)
      .values({
        userId: context.userId,
        id: mutation.aggregateId,
        ...RECIPE_TOMBSTONE,
        revision,
        createdAt: now,
        updatedAt: now,
        deletedAt: now,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      })
      .onConflictDoUpdate({
        target: [recipes.userId, recipes.id],
        set: {
          revision,
          updatedAt: now,
          deletedAt: now,
          originType: mutation.origin.type,
          originId: mutation.origin.id,
          lastMutationId: mutation.mutationId,
          payloadSchemaVersion: mutation.payloadSchemaVersion,
        },
      });
    const sequence = await appendToJournal(tx, {
      userId: context.userId,
      aggregateType: "recipe",
      aggregateId: mutation.aggregateId,
      operation: "delete",
      revision,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
      payload: null,
      deletedAt: now,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      mutationId: mutation.mutationId,
    });
    return { status: "applied", revision, sequence };
  }

  if (mutation.aggregateType !== "recipe") {
    return misroutedRejection("recipe", mutation.aggregateId);
  }
  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return deletedRejection("recipe", mutation.aggregateId, state, "recipe");
  }

  const payload: RecipePayloadV1 = mutation.payload;
  const revision = nextRevision(state);
  const columns = recipeColumns(payload);

  await tx
    .insert(recipes)
    .values({
      userId: context.userId,
      id: payload.id,
      ...columns,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [recipes.userId, recipes.id],
      set: {
        ...columns,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "recipe",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });
  return { status: "applied", revision, sequence };
}

export const recipeHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return applyRecipe(tx, context, mutation, now);
  },
  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ id: recipes.id, createdAt: recipes.createdAt })
      .from(recipes)
      .where(and(eq(recipes.userId, context.userId), inArray(recipes.id, [...aggregateIds])));
    for (const row of rows) found.set(row.id, row.createdAt);
    return found;
  },
};

// --- food log entry -----------------------------------------------------------------------

async function readLineState(
  tx: Transaction,
  userId: string,
  id: string,
): Promise<OpaqueState | undefined> {
  const rows = await tx
    .select({ revision: foodLogEntries.revision, deletedAt: foodLogEntries.deletedAt })
    .from(foodLogEntries)
    .where(and(eq(foodLogEntries.userId, userId), eq(foodLogEntries.id, id)));
  return rows[0];
}

function lineColumns(payload: FoodLogEntryPayloadV1) {
  return {
    consumedOn: payload.consumedOn,
    consumedAt: payload.consumedAt,
    slot: payload.slot,
    kind: payload.kind,
    title: payload.title,
    estimation: payload.estimation,
    weighedCooked: payload.weighedCooked,
    energyMilliKcal: payload.energyMilliKcal ?? null,
    proteinMilligrams: payload.proteinMilligrams ?? null,
    carbsMilligrams: payload.carbsMilligrams ?? null,
    fatMilligrams: payload.fatMilligrams ?? null,
    fibreMilligrams: payload.fibreMilligrams ?? null,
    sourceRef: payload.sourceRef ?? null,
    amountLabel: payload.amountLabel ?? null,
    quantityThousandths: payload.quantityThousandths ?? null,
    quantityUnit: payload.quantityUnit ?? null,
    portionsThousandths: payload.portionsThousandths ?? null,
    fromPlan: payload.fromPlan ?? null,
  };
}

const LINE_TOMBSTONE = {
  consumedOn: "1970-01-01",
  consumedAt: "00:00",
  slot: "snack",
  kind: "quick",
  title: "",
  estimation: "approximate",
  weighedCooked: false,
} as const;

async function applyLine(
  tx: Transaction,
  context: SyncContext,
  mutation: MutationEnvelope,
  now: Date,
): Promise<ApplyOutcome> {
  const state = await readLineState(tx, context.userId, mutation.aggregateId);

  if (mutation.op === "delete") {
    const revision = nextRevision(state);
    await tx
      .insert(foodLogEntries)
      .values({
        userId: context.userId,
        id: mutation.aggregateId,
        ...LINE_TOMBSTONE,
        revision,
        createdAt: now,
        updatedAt: now,
        deletedAt: now,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      })
      .onConflictDoUpdate({
        target: [foodLogEntries.userId, foodLogEntries.id],
        set: {
          revision,
          updatedAt: now,
          deletedAt: now,
          originType: mutation.origin.type,
          originId: mutation.origin.id,
          lastMutationId: mutation.mutationId,
          payloadSchemaVersion: mutation.payloadSchemaVersion,
        },
      });
    const sequence = await appendToJournal(tx, {
      userId: context.userId,
      aggregateType: "foodLogEntry",
      aggregateId: mutation.aggregateId,
      operation: "delete",
      revision,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
      payload: null,
      deletedAt: now,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      mutationId: mutation.mutationId,
    });
    return { status: "applied", revision, sequence };
  }

  if (mutation.aggregateType !== "foodLogEntry") {
    return misroutedRejection("foodLogEntry", mutation.aggregateId);
  }
  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return deletedRejection("foodLogEntry", mutation.aggregateId, state, "journal line");
  }

  const payload: FoodLogEntryPayloadV1 = mutation.payload;
  const revision = nextRevision(state);
  const columns = lineColumns(payload);

  await tx
    .insert(foodLogEntries)
    .values({
      userId: context.userId,
      id: payload.id,
      ...columns,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [foodLogEntries.userId, foodLogEntries.id],
      set: {
        ...columns,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "foodLogEntry",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });
  return { status: "applied", revision, sequence };
}

export const foodLogEntryHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return applyLine(tx, context, mutation, now);
  },
  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ id: foodLogEntries.id, createdAt: foodLogEntries.createdAt })
      .from(foodLogEntries)
      .where(
        and(
          eq(foodLogEntries.userId, context.userId),
          inArray(foodLogEntries.id, [...aggregateIds]),
        ),
      );
    for (const row of rows) found.set(row.id, row.createdAt);
    return found;
  },
};

// --- meal plan entry ----------------------------------------------------------------------

async function readPlanState(
  tx: Transaction,
  userId: string,
  plannedOn: string,
  slot: string,
): Promise<OpaqueState | undefined> {
  const rows = await tx
    .select({ revision: mealPlanEntries.revision, deletedAt: mealPlanEntries.deletedAt })
    .from(mealPlanEntries)
    .where(
      and(
        eq(mealPlanEntries.userId, userId),
        eq(mealPlanEntries.plannedOn, plannedOn),
        eq(mealPlanEntries.slot, slot),
      ),
    );
  return rows[0];
}

function planColumns(payload: MealPlanEntryPayloadV1) {
  return {
    recipeId: payload.recipeId,
    plannedServingsThousandths: payload.plannedServingsThousandths,
    consumedLogEntryId: payload.consumedLogEntryId ?? null,
  };
}

/**
 * `recipe_id` is `''` and the serving count is `0`, both outside anything a real proposal can
 * hold — a recipe id is a UUID and `Servings.CONSUMED_RANGE` starts at 250. Same device as
 * `measurement.ts`'s zero weight, same reason: the primary key has to be able to hold a
 * tombstone, and no reader ever sees these values.
 */
const PLAN_TOMBSTONE = { recipeId: "", plannedServingsThousandths: 0 } as const;

async function applyPlan(
  tx: Transaction,
  context: SyncContext,
  mutation: MutationEnvelope,
  now: Date,
): Promise<ApplyOutcome> {
  const { plannedOn, slot } = splitMealPlanId(mutation.aggregateId);
  if (plannedOn === "" || slot === "") {
    // Unreachable through `/api/v1`: `mealPlanAggregateIdSchema` pins the shape on the upsert
    // branch. A *delete* carries the opaque `aggregateIdSchema`, so this is the one door left.
    throw invalidRequest("A meal plan identifier is `<date>:<slot>`.", {
      aggregateType: "mealPlanEntry",
      aggregateId: mutation.aggregateId,
    });
  }
  const state = await readPlanState(tx, context.userId, plannedOn, slot);

  if (mutation.op === "delete") {
    const revision = nextRevision(state);
    await tx
      .insert(mealPlanEntries)
      .values({
        userId: context.userId,
        plannedOn,
        slot,
        ...PLAN_TOMBSTONE,
        revision,
        createdAt: now,
        updatedAt: now,
        deletedAt: now,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      })
      .onConflictDoUpdate({
        target: [mealPlanEntries.userId, mealPlanEntries.plannedOn, mealPlanEntries.slot],
        set: {
          revision,
          updatedAt: now,
          deletedAt: now,
          originType: mutation.origin.type,
          originId: mutation.origin.id,
          lastMutationId: mutation.mutationId,
          payloadSchemaVersion: mutation.payloadSchemaVersion,
        },
      });
    const sequence = await appendToJournal(tx, {
      userId: context.userId,
      aggregateType: "mealPlanEntry",
      aggregateId: mutation.aggregateId,
      operation: "delete",
      revision,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
      payload: null,
      deletedAt: now,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      mutationId: mutation.mutationId,
    });
    return { status: "applied", revision, sequence };
  }

  if (mutation.aggregateType !== "mealPlanEntry") {
    return misroutedRejection("mealPlanEntry", mutation.aggregateId);
  }
  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return deletedRejection("mealPlanEntry", mutation.aggregateId, state, "proposal");
  }

  const payload: MealPlanEntryPayloadV1 = mutation.payload;
  const revision = nextRevision(state);
  const columns = planColumns(payload);

  await tx
    .insert(mealPlanEntries)
    .values({
      userId: context.userId,
      plannedOn: payload.plannedOn,
      slot: payload.slot,
      ...columns,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      // The business key, which is what makes "at most one proposal per date and moment" a
      // property of the table rather than a rule this function remembers (PRD_FOOD 21.3).
      target: [mealPlanEntries.userId, mealPlanEntries.plannedOn, mealPlanEntries.slot],
      set: {
        ...columns,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "mealPlanEntry",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });
  return { status: "applied", revision, sequence };
}

export const mealPlanEntryHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return applyPlan(tx, context, mutation, now);
  },
  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({
        plannedOn: mealPlanEntries.plannedOn,
        slot: mealPlanEntries.slot,
        createdAt: mealPlanEntries.createdAt,
      })
      .from(mealPlanEntries)
      .where(eq(mealPlanEntries.userId, context.userId));
    const wanted = new Set(aggregateIds);
    for (const row of rows) {
      const id = `${row.plannedOn}:${row.slot}`;
      if (wanted.has(id)) found.set(id, row.createdAt);
    }
    return found;
  },
};
