package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import fr.kristenjestin.mue.domain.model.FoodAggregates
import kotlinx.serialization.SerializationException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
     * The health profile, with the values the owner's phone had been holding.
     *
     * This is the row that could never leave: it was journalled at every save, and
     * `AGGREGATE_TYPES` in `packages/contracts` was `["measurement"]`, so [SyncWire.toEnvelope]
     * answered null and `Data & sync` counted a change that could not fall. The assertion is on
     * the real height and the real birth date rather than on the shape, because the contract
     * constrains both values and a shape check would pass on either.
     */
    @Test
    fun theHealthProfileBecomesItsOwnUpsertBranch() {
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
                    payload = """{"heightCm":171,"birthDate":"1998-11-18"}""",
                    baseRevision = null,
                ),
                origin,
            ),
        )

        assertEquals(WIRE_AGGREGATE_HEALTH_PROFILE, envelope.aggregateType)
        assertEquals(WIRE_HEALTH_PROFILE_AGGREGATE_ID, envelope.aggregateId)
        assertEquals(WIRE_OP_UPSERT, envelope.op)
        assertNull(envelope.baseRevision)
        assertEquals(171, envelope.payload.heightCm)
        assertEquals("1998-11-18", envelope.payload.birthDate)
        assertEquals(origin, envelope.origin)
    }

    /**
     * The aggregate identifier is the contract's constant and not the outbox row's.
     *
     * PRD 13.4 gives an account one profile, so a row that somehow carried another identifier
     * must not be able to open a rival aggregate on the server. The DTO's default is the only
     * value that can appear.
     */
    @Test
    fun aProfileRowCannotSmuggleARivalAggregateIdOntoTheWire() {
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me-2",
                    payload = """{"heightCm":171,"birthDate":null}""",
                ),
                origin,
            ),
        )

        assertEquals(WIRE_HEALTH_PROFILE_AGGREGATE_ID, envelope.aggregateId)
    }

    /** A cleared field is `null` on the wire, and the key is written rather than dropped. */
    @Test
    fun aClearedProfileFieldTravelsAsAStatedNull() {
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
                    payload = """{"heightCm":null,"birthDate":"1998-11-18"}""",
                ),
                origin,
            ),
        )

        val text = SyncJson.instance.encodeToString(
            MutationEnvelopeSerializer,
            envelope as MutationEnvelopeDto,
        )
        assertTrue(text.contains("\"heightCm\":null"), "a cleared height is stated: $text")
        assertTrue(text.contains("\"op\":\"upsert\""), "op is written as a field now: $text")
        assertTrue(
            text.contains("\"aggregateType\":\"healthProfile\""),
            "the second discriminator has to be on the wire: $text",
        )
    }

    /**
     * Nothing is deferred any more, and this is what changed.
     *
     * The food journal used to map to null here — PRD 10.1 synchronised it, `SyncOutbox` wrote a
     * row at every meal, and `AGGREGATE_TYPES` had no branch for it, so the row stayed `pending`
     * for ever. It goes out now, and an activity session, which was not even journalled, goes out
     * with it.
     *
     * What still maps to null is an aggregate name no contract has ever carried. That branch is
     * the one that keeps a *future* aggregate journalled ahead of its wire shape from being
     * refused rather than held, so it is asserted rather than deleted.
     */
    @Test
    fun anAggregateTypeTheContractHasNoBranchForMapsToNothing() {
        assertNotNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
                    payload = """
                        {"id":"3d60ba59-8e12-4f41-8690-7b2c5d8e3f16","consumedOn":"2026-08-25",
                         "consumedAt":"12:30","slot":"lunch","kind":"quick","title":"Riz",
                         "estimation":"measured","weighedCooked":false}
                    """.trimIndent(),
                ),
                origin,
            ),
        )
        assertNotNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
                    op = SyncMutationEntity.OP_DELETE,
                    payload = null,
                ),
                origin,
            ),
        )
        assertNull(
            SyncWire.toEnvelope(
                row(aggregateType = "sleepSession", payload = """{"hours":7}"""),
                origin,
            ),
        )
    }

    /**
     * A `healthProfile` delete has no wire branch even though the type is sendable, which is
     * why the queue filter and [SyncWire.toEnvelope] are two guards and not one.
     *
     * PRD 13.4 gives the profile no deletion and `SyncOutbox` mints none, so this row can only
     * come from a downgrade or a hand-written database. Null keeps it, which is what the engine
     * does with anything it cannot shape.
     */
    @Test
    fun aHealthProfileDeleteHasNoWireBranchAndIsHeldRatherThanSent() {
        assertNull(
            SyncWire.toEnvelope(
                row(
                    aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    aggregateId = "me",
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
        assertEquals(
            SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
            SyncWire.localAggregateType(WIRE_AGGREGATE_HEALTH_PROFILE),
        )
        assertEquals(
            SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
            SyncWire.localAggregateType(WIRE_AGGREGATE_ACTIVITY_SESSION),
        )
        assertEquals(
            SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE,
            SyncWire.localAggregateType(WIRE_AGGREGATE_CUSTOM_EXERCISE),
        )
        assertEquals(FoodAggregates.TYPE_FOOD, SyncWire.localAggregateType(WIRE_AGGREGATE_FOOD))
        assertEquals(FoodAggregates.TYPE_RECIPE, SyncWire.localAggregateType(WIRE_AGGREGATE_RECIPE))
        assertEquals(
            FoodAggregates.TYPE_FOOD_LOG_ENTRY,
            SyncWire.localAggregateType(WIRE_AGGREGATE_FOOD_LOG_ENTRY),
        )
        assertEquals(
            FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
            SyncWire.localAggregateType(WIRE_AGGREGATE_MEAL_PLAN_ENTRY),
        )
        // `recipe` used to be the example of a type with no local home. It has one now, so the
        // example has to be a type nothing has ever synchronised.
        assertNull(SyncWire.localAggregateType("sleepSession"))
    }

    /**
     * A received profile always lands on [HealthProfileEntity.ROW_ID], whatever else is around.
     * That is the client half of "un agrégat unique": there is no branch that inserts a second
     * profile row, so a second device converges on the first one's row by construction.
     */
    @Test
    fun aReceivedProfileAlwaysLandsOnTheOneLocalRow() {
        val entity = SyncWire.healthProfileEntity(
            HealthProfilePayloadV1Dto(heightCm = 171, birthDate = "1998-11-18"),
        )

        assertEquals(HealthProfileEntity.ROW_ID, entity.id)
        assertEquals(171, entity.heightCm)
        assertEquals("1998-11-18", entity.birthDate)
    }
}
