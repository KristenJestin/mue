package fr.kristenjestin.mue.data.scale.protocol

import fr.kristenjestin.mue.domain.model.ScaleFrameEvent
import fr.kristenjestin.mue.domain.model.ScaleLinkMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le pilote de la Homebuds HB9027, éprouvé sur les trames réelles de PRD_SCALE 14 (FR-SCALE-031).
 *
 * Aucun de ces tests ne touche au Bluetooth, à un appareil ou à une permission : c'est la
 * contrepartie exigible de la mise au point sur matériel (PRD_SCALE 15).
 */
class Hb9027DriverTest {

    private fun session() = Hb9027Session()

    private fun weightFrame(stability: Int, hundredths: Int = 8575) = HbFrames.frame(
        header = HbFrames.HEADER_FROM_SCALE,
        product = 0x00,
        type = HB_TYPE_WEIGHT,
        data = byteArrayOf(
            stability.toByte(), 0x00, 0x00, 0x00,
            ((hundredths ushr 8) and 0xFF).toByte(),
            (hundredths and 0xFF).toByte(),
        ),
    )

    // region identité et reconnaissance

    @Test
    fun `le pilote décrit une liaison GATT sur le service propriétaire`() {
        assertEquals("hb9027", Hb9027Driver.id)
        assertEquals(ScaleLinkMode.GATT, Hb9027Driver.linkMode)
        assertTrue(Hb9027Driver.capabilities.providesWeight)
        assertTrue(Hb9027Driver.capabilities.providesImpedance)

        // PRD_SCALE 14.1 : service 0xFFF0, notification 0xFFF1, écriture 0xFFF2, en forme longue.
        val profile = Hb9027Driver.gattProfile
        assertEquals("0000fff0-0000-1000-8000-00805f9b34fb", profile.service.toString())
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", profile.notify.toString())
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", profile.write.toString())
    }

    @Test
    fun `le pilote reconnaît l'appareil par son nom annoncé`() {
        assertTrue(Hb9027Driver.recognises(REAL_HB9027_ADVERTISEMENT))
        assertTrue(Hb9027Driver.recognises(advertisementNamed("hb body fat")))
        assertTrue(Hb9027Driver.recognises(advertisementNamed("  HB BODY FAT  ")))
    }

    /**
     * PRD_SCALE 14.1 : « Un pilote qui reconnaîtrait les appareils par une marque commerciale ne
     * trouverait jamais cette balance. » Le nom annoncé ne contient pas « Homebuds ».
     */
    @Test
    fun `le pilote ne reconnaît ni une marque ni un appareil quelconque`() {
        assertFalse(Hb9027Driver.recognises(advertisementNamed("Homebuds HB9027")))
        assertFalse(Hb9027Driver.recognises(advertisementNamed("HB BODY FAT SCALE")))
        assertFalse(Hb9027Driver.recognises(advertisementNamed(null)))
        assertFalse(Hb9027Driver.recognises(UNRELATED_ADVERTISEMENT))
    }

    // endregion

    // region séquence d'initialisation (PRD_SCALE 14.3)

    @Test
    fun `les trois écritures d'initialisation sont produites octet pour octet`() {
        val writes = session().onSubscribed()

        assertEquals(REAL_INIT_COMMANDS, writes.map { it.bytes.toHex() })
    }

    /** « Une écriture à la fois, en attendant l'acquittement de la précédente » (PRD_SCALE 14.3). */
    @Test
    fun `chaque écriture d'initialisation attend l'acquittement de la précédente`() {
        assertTrue(session().onSubscribed().all { it.awaitAck })
    }

    @Test
    fun `la réponse d'initialisation est ignorée et confirme la prise en compte de la séquence`() {
        val session = session()
        assertFalse(session.initialisationConfirmed)

        val event = session.onFrame(hexToBytes(REAL_INIT_REPLY_FRAME))

        assertEquals(ScaleFrameEvent.Ignored, event)
        assertTrue(session.initialisationConfirmed)
    }

    // endregion

    // region trames de poids (PRD_SCALE 14.4)

    @Test
    fun `la trame de poids stable réelle donne 8575 centièmes et l'acquittement de PRD 14 point 3`() {
        val event = assertIs<ScaleFrameEvent.Weight>(
            session().onFrame(hexToBytes(REAL_STABLE_WEIGHT_FRAME)),
        )

        assertEquals(8575, event.hundredthsKg)
        assertTrue(event.stable)
        assertEquals(listOf(REAL_WEIGHT_ACK), event.replies.map { it.bytes.toHex() })
        assertTrue(event.replies.single().awaitAck)
    }

    /** BR-SCALE-001 : une mesure instable n'est jamais enregistrable, et n'acquitte rien. */
    @Test
    fun `un poids instable ne produit pas de mesure enregistrable ni d'acquittement`() {
        val session = session()

        val event = assertIs<ScaleFrameEvent.Weight>(
            session.onFrame(weightFrame(HB_STABILITY_IN_PROGRESS)),
        )

        assertEquals(8575, event.hundredthsKg)
        assertFalse(event.stable)
        assertTrue(event.replies.isEmpty())
        assertFalse(session.weightAcknowledged)
    }

    /**
     * PRD_SCALE 14.3 point 6 : **un seul acquittement par liaison**. La balance répète sa trame
     * stable ; acquitter chaque répétition empilerait des écritures dans une fenêtre déjà courte,
     * et la pile BLE d'Android n'accepte qu'une opération en vol.
     */
    @Test
    fun `l'acquittement n'est émis qu'une fois même si la trame stable est répétée cinq fois`() {
        val session = session()
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)

        val events = (1..5).map { assertIs<ScaleFrameEvent.Weight>(session.onFrame(frame)) }

        assertEquals(listOf(REAL_WEIGHT_ACK), events.first().replies.map { it.bytes.toHex() })
        events.drop(1).forEachIndexed { index, event ->
            assertTrue(event.stable, "la répétition ${index + 2} reste un poids stable")
            assertEquals(8575, event.hundredthsKg)
            assertTrue(event.replies.isEmpty(), "la répétition ${index + 2} ne doit rien réémettre")
        }
    }

    /** Position 4 à `0x03` : l'état que la balance adopte après notre acquittement. */
    @Test
    fun `l'état postérieur à l'acquittement reste un poids stable sans nouvelle écriture`() {
        val event = assertIs<ScaleFrameEvent.Weight>(
            session().onFrame(weightFrame(HB_STABILITY_ACKNOWLEDGED)),
        )

        assertTrue(event.stable)
        assertTrue(event.replies.isEmpty())
    }

    /**
     * BR-SCALE-002 appartient à la couche supérieure : le décodage rend ce que la balance a dit,
     * pour qu'une valeur hors domaine reste distinguable d'une trame illisible.
     */
    @Test
    fun `aucune borne métier n'est appliquée au décodage`() {
        val event = assertIs<ScaleFrameEvent.Weight>(
            session().onFrame(weightFrame(HB_STABILITY_STABLE, hundredths = 1_000)),
        )

        assertEquals(1_000, event.hundredthsKg)
    }

    @Test
    fun `une trame de poids trop courte pour porter le poids est rejetée`() {
        val truncated = HbFrames.frame(
            header = HbFrames.HEADER_FROM_SCALE,
            product = 0x00,
            type = HB_TYPE_WEIGHT,
            data = hexToBytes("02 00 00 21"),
        )

        val rejected = assertIs<ScaleFrameEvent.Rejected>(session().onFrame(truncated))

        assertTrue(rejected.reason.contains("position 8"), rejected.reason)
    }

    // endregion

    // region trames d'impédance (PRD_SCALE 14.5)

    @Test
    fun `la trame d'impédance réelle donne 545 ohms et son acquittement`() {
        val event = assertIs<ScaleFrameEvent.Impedance>(
            session().onFrame(hexToBytes(REAL_IMPEDANCE_FRAME)),
        )

        assertEquals(545, event.ohm)
        assertEquals(listOf(REAL_IMPEDANCE_ACK), event.replies.map { it.bytes.toHex() })
    }

    /** BR-SCALE-005 : `0xFFFF` est une absence de mesure, jamais 65535 ohms. */
    @Test
    fun `le marqueur FFFF est une absence de mesure et non 65535 ohms`() {
        val event = assertIs<ScaleFrameEvent.Impedance>(
            session().onFrame(hexToBytes(REAL_IMPEDANCE_ABSENT_FRAME)),
        )

        assertNull(event.ohm)
    }

    @Test
    fun `l'acquittement d'impédance n'est émis qu'une fois`() {
        val session = session()
        val frame = hexToBytes(REAL_IMPEDANCE_FRAME)

        val first = assertIs<ScaleFrameEvent.Impedance>(session.onFrame(frame))
        val second = assertIs<ScaleFrameEvent.Impedance>(session.onFrame(frame))

        assertEquals(1, first.replies.size)
        assertTrue(second.replies.isEmpty())
        assertEquals(545, second.ohm)
    }

    @Test
    fun `une trame d'impédance trop courte est rejetée`() {
        val truncated = HbFrames.frame(
            header = HbFrames.HEADER_FROM_SCALE,
            product = 0x00,
            type = HB_TYPE_IMPEDANCE,
            data = hexToBytes("00 00 00 FF FF"),
        )

        val rejected = assertIs<ScaleFrameEvent.Rejected>(session().onFrame(truncated))

        assertTrue(rejected.reason.contains("position 9"), rejected.reason)
    }

    // endregion

    // region validation (PRD_SCALE 17)

    /** BR-SCALE-003 : une trame au contrôle faux est rejetée, jamais interprétée. */
    @Test
    fun `une trame dont le contrôle est faux est rejetée et jamais interprétée`() {
        val session = session()
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)
        frame[frame.size - 2] = 0x00

        val rejected = assertIs<ScaleFrameEvent.Rejected>(session.onFrame(frame))

        assertTrue(rejected.reason.contains("checksum"), rejected.reason)
        assertFalse(session.weightAcknowledged, "une trame rejetée n'acquitte rien")
    }

    /**
     * BR-SCALE-004 : l'octet de produit ne participe jamais à la validation. Le spike validait
     * `0x26` alors que l'appareil réel émet `0x00` ; les deux doivent décoder identiquement.
     */
    @Test
    fun `la variante de la trame réelle avec l'octet de produit 0x26 décode identiquement`() {
        val variant = HbFrames.frame(
            header = HbFrames.HEADER_FROM_SCALE,
            product = 0x26,
            type = HB_TYPE_WEIGHT,
            data = hexToBytes("02 00 00 21 21 7F"),
        )

        val fromReal = assertIs<ScaleFrameEvent.Weight>(
            session().onFrame(hexToBytes(REAL_STABLE_WEIGHT_FRAME)),
        )
        val fromVariant = assertIs<ScaleFrameEvent.Weight>(session().onFrame(variant))

        assertEquals(fromReal.hundredthsKg, fromVariant.hundredthsKg)
        assertEquals(fromReal.stable, fromVariant.stable)
        assertEquals(
            fromReal.replies.map { it.bytes.toHex() },
            fromVariant.replies.map { it.bytes.toHex() },
        )
    }

    @Test
    fun `une trame d'un type inconnu est ignorée sans rien émettre`() {
        val unknown = HbFrames.frame(
            header = HbFrames.HEADER_FROM_SCALE,
            product = 0x00,
            type = 0x21,
            data = byteArrayOf(0x00, 0x01),
        )

        assertEquals(ScaleFrameEvent.Ignored, session().onFrame(unknown))
    }

    /** Deux pesées ne partagent jamais leur état : une session est jetable (PRD_SCALE 9.2). */
    @Test
    fun `une nouvelle session recommence la séquence à zéro`() {
        val first = session()
        first.onFrame(hexToBytes(REAL_STABLE_WEIGHT_FRAME))
        assertTrue(first.weightAcknowledged)

        val second = assertIs<Hb9027Session>(Hb9027Driver.newSession())

        assertFalse(second.weightAcknowledged)
        val event = assertIs<ScaleFrameEvent.Weight>(
            second.onFrame(hexToBytes(REAL_STABLE_WEIGHT_FRAME)),
        )
        assertEquals(listOf(REAL_WEIGHT_ACK), event.replies.map { it.bytes.toHex() })
    }

    // endregion
}
