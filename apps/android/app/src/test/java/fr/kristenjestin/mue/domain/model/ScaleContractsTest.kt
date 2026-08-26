package fr.kristenjestin.mue.domain.model

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Verrouille les contrats de domaine du module balance, sur lesquels tout le reste est écrit.
 *
 * Deux pièges y sont vissés une fois pour toutes : l'égalité par référence des `ByteArray`, qui
 * ferait échouer les tests de pilote sans rien expliquer, et les valeurs stockées des énumérations,
 * qu'un simple renommage orphelinerait silencieusement (PRD_SCALE 21.1).
 */
class ScaleContractsTest {

    @Test
    fun `deux ecritures de memes octets sont egales`() {
        val a = ScaleWrite(byteArrayOf(0x01, 0x02, 0x03.toByte()))
        val b = ScaleWrite(byteArrayOf(0x01, 0x02, 0x03.toByte()))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        // Et l'égalité structurelle doit traverser les collections : c'est sous cette forme que
        // les pilotes renvoient leurs écritures (ScaleDriverSession.onSubscribed).
        assertEquals(listOf(a), listOf(b))
    }

    @Test
    fun `deux ecritures se distinguent par leurs octets et par l attente d acquittement`() {
        val reference = ScaleWrite(byteArrayOf(0x01, 0x02))

        assertNotEquals(reference, ScaleWrite(byteArrayOf(0x01, 0x03)))
        assertNotEquals(reference, ScaleWrite(byteArrayOf(0x01, 0x02, 0x00)))
        assertNotEquals(reference, ScaleWrite(byteArrayOf(0x01, 0x02), awaitAck = false))
    }

    @Test
    fun `l attente d acquittement est vraie par defaut`() {
        assertEquals(true, ScaleWrite(byteArrayOf(0x00)).awaitAck)
    }

    @Test
    fun `deux annonces de memes donnees fabricant sont egales`() {
        val a = advertisement(manufacturerData = mapOf(0x00C0 to byteArrayOf(0x10, 0x20)))
        val b = advertisement(manufacturerData = mapOf(0x00C0 to byteArrayOf(0x10, 0x20)))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `une annonce se distingue par le contenu de ses donnees fabricant`() {
        val reference = advertisement(manufacturerData = mapOf(0x00C0 to byteArrayOf(0x10, 0x20)))

        assertNotEquals(reference, advertisement(manufacturerData = mapOf(0x00C0 to byteArrayOf(0x10, 0x21))))
        assertNotEquals(reference, advertisement(manufacturerData = mapOf(0x00C1 to byteArrayOf(0x10, 0x20))))
        assertNotEquals(reference, advertisement(manufacturerData = emptyMap()))
        assertNotEquals(reference, advertisement(name = "Other", manufacturerData = mapOf(0x00C0 to byteArrayOf(0x10, 0x20))))
    }

    @Test
    fun `les quatre provenances gardent leurs valeurs stockees`() {
        assertEquals(
            listOf("manual", "scale", "agent", "server"),
            MeasurementSource.entries.map { it.wireValue },
        )
        MeasurementSource.entries.forEach { source ->
            assertEquals(source, MeasurementSource.fromWire(source.wireValue))
        }
    }

    @Test
    fun `une provenance inconnue n est pas devinee`() {
        assertNull(MeasurementSource.fromWire("balance"))
        assertNull(MeasurementSource.fromWire("MANUAL"))
        assertNull(MeasurementSource.fromWire(""))
    }

    @Test
    fun `les deux sexes gardent leurs valeurs stockees`() {
        assertEquals(listOf("female", "male"), Sex.entries.map { it.wireValue })
        Sex.entries.forEach { sex -> assertEquals(sex, Sex.fromWire(sex.wireValue)) }
    }

    @Test
    fun `un sexe absent ou illisible vaut non renseigne`() {
        assertNull(Sex.fromWire(null))
        assertNull(Sex.fromWire("other"))
        assertNull(Sex.fromWire("FEMALE"))
        assertNull(Sex.fromWire(""))
    }

    @Test
    fun `une mesure construite avec deux arguments reste une saisie manuelle`() {
        val measurement = Measurement(LocalDate.of(2026, 8, 26), Weight.DEFAULT)

        assertEquals(MeasurementSource.MANUAL, measurement.source)
        assertNull(measurement.sourceScaleId)
        assertNull(measurement.impedanceOhm)
        assertNull(measurement.bodyComposition)
    }

    @Test
    fun `un profil construit sans sexe n en a pas`() {
        assertNull(UserProfile.EMPTY.sex)
        assertNull(UserProfile(displayName = "Ada", heightCm = 170).sex)
    }

    @Test
    fun `les accesseurs de composition derivent des entiers stockes`() {
        val composition = BodyComposition(
            date = LocalDate.of(2026, 8, 26),
            formulaId = "mue-foot-to-foot-v1",
            formulaVersion = 1,
            inputWeightCg = 7_000,
            inputHeightCm = 170,
            inputAgeYears = 34,
            inputSex = Sex.FEMALE,
            bodyFatDeciPercent = 253,
            fatFreeMassCg = 5_229,
            bodyWaterDeciPercent = 546,
            restingEnergyKcal = 1_432,
        )

        assertEquals(25.3, composition.bodyFatPercent, 1e-9)
        assertEquals(52.29, composition.fatFreeMassKg, 1e-9)
        assertEquals(54.6, composition.bodyWaterPercent, 1e-9)
    }

    private fun advertisement(
        address: String = "FF:10:00:1F:52:C3",
        name: String? = "HB9027",
        serviceUuids: List<String> = listOf("0000fff0-0000-1000-8000-00805f9b34fb"),
        manufacturerData: Map<Int, ByteArray> = emptyMap(),
    ): ScaleAdvertisement = ScaleAdvertisement(address, name, serviceUuids, manufacturerData)
}
