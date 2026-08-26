package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.Measurement
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
