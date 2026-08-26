package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

/**
 * Mints the outbox row for a local change. It builds the row and nothing else: writing it is
 * the business DAO's job, in the transaction that writes the business row (FR-SYNC-001).
 *
 * The id and the clock are injected so a test can assert on an exact row rather than on the
 * shape of one; both defaults are what the app uses.
 *
 * A row is written whether or not a server is paired. Making it conditional would put a read of
 * `sync_state` inside every save for a table that grows by one small row a day, and would make
 * the guarantee of FR-SYNC-001 depend on a flag; the initial synchronisation of FR-SYNC-003
 * sends the whole local history anyway, so the engine is free to collapse what it finds waiting.
 */
class SyncOutbox(
    private val newMutationId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** A measurement is identified by its date on both sides, so the date is the aggregate id. */
    fun measurementUpsert(measurement: Measurement): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = measurement.date.toString(),
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(
            MeasurementPayload.serializer(),
            MeasurementPayload(
                date = measurement.date.toString(),
                weightCg = measurement.weight.hundredthsKg,
            ),
        ),
    )

    fun measurementDelete(date: LocalDate): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = date.toString(),
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * The four food aggregates of PRD_FOOD 21.2, minted here beside the measurement rather than
     * in a second outbox of their own. One mint point is what keeps `mutation_id`, the pending
     * state and the payload schema version identical for every aggregate the engine will later
     * drain, and `FoodAggregates` already names the four types the generic
     * `sync_aggregate_state` keys them by — so the Food module adds no synchronisation column to
     * any of its five tables (PRD_FOOD 20.1, answered by storage that already exists).
     */
    fun foodUpsert(food: Food): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD,
        aggregateId = food.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(FoodPayload.serializer(), food.toPayload()),
    )

    fun foodDelete(id: FoodId): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /** PRD_FOOD 21.2: the recipe **with** its ingredients, in one payload, or not at all. */
    fun recipeUpsert(detail: RecipeDetail): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_RECIPE,
        aggregateId = detail.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(RecipePayload.serializer(), detail.toPayload()),
    )

    fun recipeDelete(id: RecipeId): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_RECIPE,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    fun foodLogUpsert(entry: FoodLogEntry): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        aggregateId = entry.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(FoodLogEntryPayload.serializer(), entry.toPayload()),
    )

    fun foodLogDelete(id: FoodLogEntryId): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * A proposition is identified by `(date, moment)` on both sides (PRD_FOOD 21.3), so
     * `MealPlanKey.aggregateId` is the aggregate id and no id has to be invented for it — the
     * same argument that makes a measurement's date its own identity.
     */
    fun mealPlanUpsert(entry: MealPlanEntry): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
        aggregateId = entry.aggregateId,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(MealPlanEntryPayload.serializer(), entry.toPayload()),
    )

    fun mealPlanDelete(key: MealPlanKey): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
        aggregateId = key.aggregateId,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * `baseRevision` is left null here and filled by the DAO, which reads it inside the
     * transaction; a revision read before the transaction opened could already be stale.
     */
    private fun mutation(
        aggregateType: String,
        aggregateId: String,
        op: String,
        payload: String?,
    ): SyncMutationEntity = SyncMutationEntity(
        mutationId = newMutationId(),
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        op = op,
        baseRevision = null,
        payload = payload,
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = now(),
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )
}

/**
 * The wire shape of a `Measurement`, versioned by [PAYLOAD_SCHEMA_VERSION] as PRD 12.4
 * requires. Hundredths of a kilogram, as everywhere else in Mue: no float reaches the database
 * and none reaches the wire either, so nothing can be rounded twice.
 *
 * It lives here, next to the writer, because it is the outbox's own format. When the hand
 * written DTOs of PRD 20.4 land in `data/remote`, this is the one place that has to agree with
 * them, and the schema version is what lets the two move independently until they do.
 */
@Serializable
data class MeasurementPayload(
    val date: String,
    val weightCg: Int,
)

/** Bumped only when an older client could no longer apply a payload (PRD 12.4). */
const val PAYLOAD_SCHEMA_VERSION: Int = 1
