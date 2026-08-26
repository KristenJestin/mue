package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.Weight
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * PRD_SCALE 22 : la provenance métier peut se synchroniser, l'impédance aussi — mais
     * `sourceScaleId`, l'adresse et le nom de la balance **ne quittent jamais le téléphone**
     * (PRD_SCALE 16.2). L'assertion porte sur le JSON entier et pas sur l'absence du seul mot
     * `scale-1` : un champ ajouté par mégarde y serait visible, quel que soit son nom.
     */
    @Test
    fun aScaleMeasurementCarriesItsProvenanceButNeverTheScaleItself() {
        val row = outbox.measurementUpsert(
            Measurement(
                date = LocalDate.parse("2026-08-25"),
                weight = Weight.ofHundredthsClamped(7_845),
                source = MeasurementSource.SCALE,
                sourceScaleId = "scale-1",
                impedanceOhm = 512,
            ),
        )

        assertEquals(
            """{"date":"2026-08-25","weightCg":7845,"sourceType":"scale","impedanceOhm":512}""",
            row.payload,
        )
    }

    /**
     * BR-SCALE-008 : une impédance exploitable est conservée même quand aucune composition n'a pu
     * être calculée, et se synchronise avec le poids pour que le calcul rétroactif de FR-BODY-006
     * dispose de la même matière sur tous les clients.
     */
    @Test
    fun anImpedanceTravelsWithoutACompositionWhenTheProfileIsIncomplete() {
        val row = outbox.measurementUpsert(
            Measurement(
                date = LocalDate.parse("2026-08-25"),
                weight = Weight.ofHundredthsClamped(7_845),
                source = MeasurementSource.SCALE,
                impedanceOhm = 512,
            ),
        )

        assertEquals(
            """{"date":"2026-08-25","weightCg":7845,"sourceType":"scale","impedanceOhm":512}""",
            row.payload,
        )
    }

    /**
     * PRD_SCALE 22 : la composition voyage **dans** le payload de la mesure, jamais seule. Sa date
     * n'y est pas — elle serait toujours celle du parent — et l'impédance non plus, parce qu'elle
     * appartient à la mesure (FR-BODY-004).
     */
    @Test
    fun aCompositionTravelsInsideItsMeasurement() {
        val row = outbox.measurementUpsert(
            Measurement(
                date = LocalDate.parse("2026-08-25"),
                weight = Weight.ofHundredthsClamped(7_845),
                source = MeasurementSource.SCALE,
                sourceScaleId = "scale-1",
                impedanceOhm = 512,
                bodyComposition = BodyComposition(
                    date = LocalDate.parse("2026-08-25"),
                    formulaId = "mue-foot-to-foot-v1",
                    formulaVersion = 1,
                    inputWeightCg = 7_845,
                    inputHeightCm = 178,
                    inputAgeYears = 36,
                    inputSex = Sex.MALE,
                    bodyFatDeciPercent = 183,
                    fatFreeMassCg = 6_409,
                    bodyWaterDeciPercent = 552,
                    restingEnergyKcal = 1_742,
                ),
            ),
        )

        assertEquals(
            """{"date":"2026-08-25","weightCg":7845,"sourceType":"scale","impedanceOhm":512,""" +
                """"bodyComposition":{"formulaId":"mue-foot-to-foot-v1","formulaVersion":1,""" +
                """"inputWeightCg":7845,"inputHeightCm":178,"inputAgeYears":36,""" +
                """"inputSex":"male","bodyFatDeciPercent":183,"fatFreeMassCg":6409,""" +
                """"bodyWaterDeciPercent":552,"restingEnergyKcal":1742}}""",
            row.payload,
        )
        assertFalse(row.payload.orEmpty().contains("scale-1"), "PRD_SCALE 16.2")
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
        val row = outbox.healthProfileUpsert(
            heightCm = 178,
            birthDate = LocalDate.of(1990, 4, 12),
            sex = null,
        )

        assertEquals(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, row.aggregateType)
        assertEquals(HealthProfileEntity.ROW_ID, row.aggregateId)
        assertEquals(SyncMutationEntity.OP_UPSERT, row.op)
        assertEquals("""{"heightCm":178,"birthDate":"1990-04-12","sex":null}""", row.payload)
    }

    /**
     * A cleared field states itself. Omitting the null would make "the user deleted their birth
     * date" indistinguishable from "this client does not send birth dates", and PRD 13.4's
     * field-by-field merge would then never clear anything.
     */
    @Test
    fun aClearedProfileFieldTravelsAsAnExplicitNull() {
        val row = outbox.healthProfileUpsert(heightCm = 178, birthDate = null, sex = null)

        assertEquals("""{"heightCm":178,"birthDate":null,"sex":null}""", row.payload)
    }

    @Test
    fun anEmptyProfileIsStillAWholeAggregate() {
        val row = outbox.healthProfileUpsert(heightCm = null, birthDate = null, sex = null)

        assertEquals("""{"heightCm":null,"birthDate":null,"sex":null}""", row.payload)
    }

    /** PRD_SCALE 22 : le sexe rejoint l'agrégat `HealthProfile`, par sa forme de fil. */
    @Test
    fun theSexJoinsTheHealthProfileAggregate() {
        val row = outbox.healthProfileUpsert(heightCm = 178, birthDate = null, sex = Sex.FEMALE)

        assertEquals("""{"heightCm":178,"birthDate":null,"sex":"female"}""", row.payload)
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
