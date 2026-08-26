package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import kotlinx.serialization.SerializationException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The seam between `sync_mutations` and the wire. */
class SyncWireTest {

    private val origin = OriginDto(OriginDto.TYPE_ANDROID, "device-7f3c1a04")

    private fun row(
        aggregateType: String = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId: String = "2026-08-25",
        op: String = SyncMutationEntity.OP_UPSERT,
        payload: String? = """{"date":"2026-08-25","weightCg":7845}""",
        baseRevision: Long? = 3L,
        createdAt: Long = 1_774_425_124_117L,
    ) = SyncMutationEntity(
        mutationId = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        op = op,
        baseRevision = baseRevision,
        payload = payload,
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    @Test
    fun anUpsertBecomesItsWireBranchWithTheStoredIdentifier() {
        val envelope = assertIs<MeasurementUpsertMutationDto>(SyncWire.toEnvelope(row(), origin))

        assertEquals("0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6", envelope.mutationId)
        assertEquals(WIRE_AGGREGATE_MEASUREMENT, envelope.aggregateType)
        assertEquals("2026-08-25", envelope.aggregateId)
        assertEquals("3", envelope.baseRevision)
        assertEquals(7_845, envelope.payload.weightCg)
        assertEquals(origin, envelope.origin)
    }

    /**
     * Null is PRD 12.2's "si elle existe" and not zero: zero would claim a revision the server
     * issued, and the server would refuse the mutation as a stale edit of nothing.
     */
    @Test
    fun aCreationQuotesNoBaseRevision() {
        val envelope = assertIs<MeasurementUpsertMutationDto>(
            SyncWire.toEnvelope(row(baseRevision = null), origin),
        )

        assertNull(envelope.baseRevision)
    }

    @Test
    fun aDeleteBecomesTheDeleteBranchWithANullPayload() {
        val envelope = assertIs<DeleteMutationDto>(
            SyncWire.toEnvelope(
                row(op = SyncMutationEntity.OP_DELETE, payload = null, aggregateId = "2026-08-24"),
                origin,
            ),
        )

        assertEquals("2026-08-24", envelope.aggregateId)
        assertNull(envelope.payload)
    }

    /**
     * The health profile is journalled today and has no wire branch: `AGGREGATE_TYPES` in
     * `packages/contracts` is `["measurement"]`. Null means "keep it, do not send it" — the
     * engine leaves the row `pending` rather than refusing a change the user made.
     */
    @Test
    fun anAggregateTypeTheContractHasNoBranchForMapsToNothing() {
        assertNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
                    payload = """{"heightCm":178,"birthDate":null}""",
                ),
                origin,
            ),
        )
        assertNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
                    op = SyncMutationEntity.OP_DELETE,
                    payload = null,
                ),
                origin,
            ),
        )
    }

    /** An upsert with no payload is a corrupt row, not a mutation the wire can carry. */
    @Test
    fun anUpsertWithoutAPayloadIsRefusedRatherThanSentEmpty() {
        val failure = runCatching { SyncWire.toEnvelope(row(payload = null), origin) }
            .exceptionOrNull()

        assertIs<SerializationException>(failure)
    }

    @Test
    fun aStoredPayloadThatIsNotJsonIsRefused() {
        val failure = runCatching { SyncWire.toEnvelope(row(payload = "{not json"), origin) }
            .exceptionOrNull()

        assertIs<SerializationException>(failure)
    }

    // --- counters and instants ----------------------------------------------------------

    @Test
    fun aCanonicalDecimalCounterBecomesALong() {
        assertEquals(0L, SyncWire.counterOrNull("0"))
        assertEquals(9_007_199_254_740_993L, SyncWire.counterOrNull("9007199254740993"))
        assertEquals(Long.MAX_VALUE, SyncWire.counterOrNull("9223372036854775807"))
    }

    /**
     * The contract sizes a counter as unsigned 64-bit and `sync_aggregate_state.revision` is a
     * signed SQLite integer. Above 2^63 there is no truthful local value, so there is no value.
     */
    @Test
    fun aCounterPastTheSignedRangeHasNoLocalRepresentation() {
        assertNull(SyncWire.counterOrNull("18446744073709551615"))
        assertNull(SyncWire.counterOrNull("9223372036854775808"))
    }

    /** Anything that is not canonical decimal is not a counter, including a signed one. */
    @Test
    fun anythingThatIsNotCanonicalDecimalIsNotACounter() {
        assertNull(SyncWire.counterOrNull(""))
        assertNull(SyncWire.counterOrNull("-1"))
        assertNull(SyncWire.counterOrNull("4.0"))
        assertNull(SyncWire.counterOrNull("0x10"))
        assertNull(SyncWire.counterOrNull(" 4"))
    }

    @Test
    fun instantsRoundTripThroughEpochMilliseconds() {
        assertEquals("2026-08-25T06:12:04.117Z", SyncWire.toInstantText(1_787_638_324_117L))
        assertEquals(1_787_638_324_117L, SyncWire.toEpochMillisOrNull("2026-08-25T06:12:04.117Z"))
        assertNull(SyncWire.toEpochMillisOrNull(null))
        assertNull(SyncWire.toEpochMillisOrNull("not an instant"))
    }

    /** The two vocabularies are translated rather than assumed equal. */
    @Test
    fun aWireAggregateTypeIsTranslatedToItsLocalName() {
        assertEquals(
            SyncAggregateStateEntity.TYPE_MEASUREMENT,
            SyncWire.localAggregateType(WIRE_AGGREGATE_MEASUREMENT),
        )
        assertNull(SyncWire.localAggregateType("recipe"))
    }
}
