package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rows a local write journals, payload by payload. */
class SyncOutboxTest {

    private var next = 0
    private val outbox = SyncOutbox(
        newMutationId = { "mutation-${next++}" },
        now = { 1_770_000_000_000L },
    )

    @Test
    fun aMeasurementIsIdentifiedByItsDateOnBothSides() {
        val row = outbox.measurementUpsert(
            Measurement(LocalDate.parse("2026-08-25"), Weight.ofHundredthsClamped(7_845)),
        )

        assertEquals(SyncAggregateStateEntity.TYPE_MEASUREMENT, row.aggregateType)
        assertEquals("2026-08-25", row.aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, row.op)
        assertEquals("""{"date":"2026-08-25","weightCg":7845}""", row.payload)
        assertEquals(PAYLOAD_SCHEMA_VERSION, row.payloadSchemaVersion)
        assertEquals(SyncMutationEntity.STATE_PENDING, row.state)
        assertNull(row.baseRevision, "the DAO reads it inside the transaction")
    }

    @Test
    fun aDeleteCarriesNoPayload() {
        val row = outbox.measurementDelete(LocalDate.parse("2026-08-24"))

        assertEquals(SyncMutationEntity.OP_DELETE, row.op)
        assertNull(row.payload)
    }

    /**
     * Gap 2: PRD 13.4 makes the health profile a synchronised aggregate, and until this existed
     * `health_profile` journalled nothing at all.
     */
    @Test
    fun theHealthProfileIsOneAggregateWithOneIdentity() {
        val row = outbox.healthProfileUpsert(heightCm = 178, birthDate = LocalDate.of(1990, 4, 12))

        assertEquals(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, row.aggregateType)
        assertEquals(HealthProfileEntity.ROW_ID, row.aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, row.op)
        assertEquals("""{"heightCm":178,"birthDate":"1990-04-12"}""", row.payload)
    }

    /**
     * A cleared field states itself. Omitting the null would make "the user deleted their birth
     * date" indistinguishable from "this client does not send birth dates", and PRD 13.4's
     * field-by-field merge would then never clear anything.
     */
    @Test
    fun aClearedProfileFieldTravelsAsAnExplicitNull() {
        val row = outbox.healthProfileUpsert(heightCm = 178, birthDate = null)

        assertEquals("""{"heightCm":178,"birthDate":null}""", row.payload)
    }

    @Test
    fun anEmptyProfileIsStillAWholeAggregate() {
        val row = outbox.healthProfileUpsert(heightCm = null, birthDate = null)

        assertEquals("""{"heightCm":null,"birthDate":null}""", row.payload)
    }

    /** Every mutation gets its own id, and the id is the only idempotency key there is. */
    @Test
    fun everyMutationCarriesItsOwnIdentifier() {
        val first = outbox.measurementDelete(LocalDate.parse("2026-08-24"))
        val second = outbox.measurementDelete(LocalDate.parse("2026-08-24"))

        assertEquals("mutation-0", first.mutationId)
        assertEquals("mutation-1", second.mutationId)
    }
}
