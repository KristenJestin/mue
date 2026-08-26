package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import kotlinx.serialization.SerializationException
import java.time.DateTimeException
import java.time.Instant

/**
 * The seam between `sync_mutations` and the wire, and the one place either shape is converted.
 *
 * ## What this file refuses to do
 *
 * It never mints a mutation id. [SyncMutationEntity.mutationId] is minted once, by the
 * transaction that wrote the business row, and is the primary key of the outbox table; every
 * send and every retry reads that same value. FR-SYNC-006's "renvoyer la même mutation retourne
 * le même résultat métier sans répéter son effet" is a property of *where the identifier comes
 * from*, and it survives only as long as nothing downstream is allowed to generate one.
 *
 * ## Aggregate types this build cannot express
 *
 * [toEnvelope] returns null rather than throwing when the outbox holds an aggregate type that
 * `packages/contracts` has no wire shape for. That is not hypothetical: `AGGREGATE_TYPES` in
 * `primitives.ts` is `["measurement"]`, while PRD 13.4 makes the health profile a synchronised
 * aggregate and [SyncAggregateStateEntity.TYPE_HEALTH_PROFILE] already exists. Journalling those
 * changes is what FR-SYNC-001 requires today; sending them is what a later contract revision
 * will allow. A null here means "keep it, do not send it, do not fail it" — the row stays
 * `pending`, loses nothing, and goes out unchanged the day the server understands it.
 */
object SyncWire {

    /**
     * The payload versions this build can apply, per aggregate type — `PullRequest`'s
     * `supportedSchemaVersions`, and the client half of PRD 12.4.
     *
     * It is derived from [PAYLOAD_SCHEMA_VERSION], the constant the outbox stamps its rows with,
     * so the versions the client claims to understand and the versions it actually writes cannot
     * drift apart in a refactor.
     */
    val SUPPORTED_SCHEMA_VERSIONS: Map<String, List<Int>> = mapOf(
        WIRE_AGGREGATE_MEASUREMENT to listOf(PAYLOAD_SCHEMA_VERSION),
    )

    /**
     * The `sync_aggregate_state.aggregate_type` values [toEnvelope] has a wire branch for, and
     * the only ones a send may select.
     *
     * Filtering the queue on this list is not an optimisation, it is what keeps FR-SYNC-007's
     * "une mutation invalide ne bloque pas indéfiniment toutes les mutations suivantes" true of a
     * queue that now contains rows nothing can send. The health profile is journalled at every
     * save (FR-SYNC-001) and `AGGREGATE_TYPES` in `packages/contracts` is `["measurement"]`, so
     * those rows stay `pending` for as long as the contract lacks the branch — they never drain.
     * A send that simply took the oldest `WIRE_PUSH_MAX_MUTATIONS` rows would therefore, once
     * that many profile saves had accumulated, return a window containing nothing sendable, and
     * every measurement queued behind them would stop going out **permanently**, with no error
     * anywhere. Selecting by type makes that impossible however many undeliverable rows pile up.
     *
     * [toEnvelope] still answers null for a row of a sendable type it cannot shape — an
     * unrecognised `op`, say — so the two guards are not redundant: this one bounds what the
     * queue can hide, that one bounds what the wire can be handed.
     */
    val SENDABLE_LOCAL_AGGREGATE_TYPES: List<String> =
        listOf(SyncAggregateStateEntity.TYPE_MEASUREMENT)

    /**
     * One outbox row as the server reads it, or null when this build has no wire shape for it.
     *
     * @throws SerializationException if a stored payload cannot be read back. That is a local
     * corruption, not a protocol event, and the caller turns it into a rejected mutation rather
     * than a failed synchronisation, so one bad row cannot stall the queue behind it.
     */
    fun toEnvelope(
        mutation: SyncMutationEntity,
        origin: OriginDto,
    ): MutationEnvelopeDto? {
        val clientOccurredAt = toInstantText(mutation.createdAt)
        val baseRevision = mutation.baseRevision?.toString()

        return when (mutation.op) {
            SyncMutationEntity.OP_DELETE -> when (mutation.aggregateType) {
                // A delete is shaped identically for every aggregate type the server knows, so
                // the wire union accepts the enum rather than a literal. It is still gated on
                // the type: a delete of an aggregate the server cannot name is refused there
                // just as an upsert would be, and refusing it here keeps the outbox quiet.
                SyncAggregateStateEntity.TYPE_MEASUREMENT -> DeleteMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateType = WIRE_AGGREGATE_MEASUREMENT,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                else -> null
            }

            SyncMutationEntity.OP_UPSERT -> when (mutation.aggregateType) {
                SyncAggregateStateEntity.TYPE_MEASUREMENT -> MeasurementUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateType = WIRE_AGGREGATE_MEASUREMENT,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = SyncJson.instance.decodeFromString(
                        MeasurementPayloadV1Dto.serializer(),
                        mutation.payload
                            ?: throw SerializationException(
                                "an upsert of ${mutation.aggregateId} carries no payload",
                            ),
                    ),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                else -> null
            }

            else -> null
        }
    }

    /**
     * A canonical decimal counter as a [Long], or null when it does not fit.
     *
     * The contract sizes `Revision` and `Sequence` as unsigned 64-bit, and `sync_aggregate_state`
     * stores a revision in a signed SQLite integer. Everything below 2^63 round-trips exactly;
     * above it there is no truthful local representation, so this returns null and the caller
     * treats the response as unapplicable rather than silently storing a truncated revision that
     * every later mutation would quote back as its base.
     */
    fun counterOrNull(value: String): Long? =
        if (value.isEmpty() || !value.all { it in '0'..'9' }) null else value.toLongOrNull()

    /** Epoch milliseconds as the ISO-8601 UTC instant the contract's `Instant` describes. */
    fun toInstantText(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /**
     * An ISO-8601 instant as epoch milliseconds, or null when it is not one.
     *
     * Sub-millisecond precision is truncated, which is the honest conversion into a store whose
     * every instant is a millisecond count. The value is used for display and audit — PRD 12.3
     * forbids it deciding order — so the lost microseconds decide nothing.
     */
    fun toEpochMillisOrNull(text: String?): Long? {
        if (text == null) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * The `sync_aggregate_state.aggregate_type` a wire aggregate type is stored under, or null
     * when this build has no local home for it.
     *
     * The two vocabularies happen to agree today — `"measurement"` on both sides — and they are
     * translated anyway, because they are owned by different repositories and a change to
     * `AGGREGATE_TYPES` must not be able to silently repoint a Room column.
     */
    fun localAggregateType(wireType: String): String? = when (wireType) {
        WIRE_AGGREGATE_MEASUREMENT -> SyncAggregateStateEntity.TYPE_MEASUREMENT
        else -> null
    }

    /** The identity Android stamps its own mutations with (PRD 12.1). */
    fun androidOrigin(deviceId: String): OriginDto =
        OriginDto(type = OriginDto.TYPE_ANDROID, id = deviceId)
}
