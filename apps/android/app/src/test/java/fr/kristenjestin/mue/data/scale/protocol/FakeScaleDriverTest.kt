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
 * Le pilote fictif (PRD_SCALE 23, FR-SCALE-030).
 *
 * Il est testé comme un vrai pilote, et c'est le but : s'il fallait le traiter à part, il ne
 * prouverait rien de l'abstraction. Son protocole n'a rien de commun avec celui de la famille HB,
 * et pourtant rien en dehors de ce fichier et de sa ligne au registre ne le connaît.
 */
class FakeScaleDriverTest {

    @Test
    fun `le pilote fictif se reconnaît par son nom annoncé`() {
        assertTrue(FakeScaleDriver.recognises(advertisementNamed("MUE FAKE SCALE")))
        assertTrue(FakeScaleDriver.recognises(advertisementNamed("mue fake scale")))
        assertFalse(FakeScaleDriver.recognises(advertisementNamed("MUE FAKE SCALE LITE")))
        assertFalse(FakeScaleDriver.recognises(REAL_HB9027_ADVERTISEMENT))
        assertEquals(ScaleLinkMode.GATT, FakeScaleDriver.linkMode)
    }

    @Test
    fun `le scénario de démonstration produit une pesée complète`() {
        val session = FakeScaleDriver.newSession()

        val events = FakeScaleFrames.DEMO_SCRIPT.map(session::onFrame)

        val weights = events.filterIsInstance<ScaleFrameEvent.Weight>()
        assertEquals(listOf(3_240, 7_910, 8_575, 8_575, 8_575), weights.map { it.hundredthsKg })
        assertEquals(listOf(false, false, false, true, true), weights.map { it.stable })
        assertEquals(545, assertIs<ScaleFrameEvent.Impedance>(events.last()).ohm)
    }

    /**
     * Le pilote fictif reproduit l'unicité de l'acquittement de PRD_SCALE 14.3 point 6 : sans
     * cela, il validerait sur émulateur un enchaînement que le matériel réel ne suit pas.
     */
    @Test
    fun `le pilote fictif n'acquitte un poids stable qu'une fois`() {
        val session = FakeScaleDriver.newSession()
        val stable = FakeScaleFrames.weight(hundredthsKg = 8_575, stable = true)

        val events = (1..5).map { assertIs<ScaleFrameEvent.Weight>(session.onFrame(stable)) }

        assertEquals(1, events.first().replies.size)
        assertTrue(events.drop(1).all { it.replies.isEmpty() })
    }

    @Test
    fun `le pilote fictif émet son écriture d'amorçage à l'abonnement`() {
        assertEquals(
            listOf("FA 10 00 00 10"),
            FakeScaleDriver.newSession().onSubscribed().map { it.bytes.toHex() },
        )
    }

    /** BR-SCALE-005 s'applique à tout pilote, fictif compris. */
    @Test
    fun `une impédance non mesurable est une absence chez le pilote fictif aussi`() {
        val event = assertIs<ScaleFrameEvent.Impedance>(
            FakeScaleDriver.newSession().onFrame(FakeScaleFrames.impedance(ohm = null)),
        )

        assertNull(event.ohm)
    }

    /** BR-SCALE-003 : même un protocole fictif rejette une trame au contrôle faux. */
    @Test
    fun `une trame fictive au contrôle faux est rejetée`() {
        val frame = FakeScaleFrames.weight(hundredthsKg = 8_575, stable = true)
        frame[frame.size - 1] = 0x00

        val rejected = assertIs<ScaleFrameEvent.Rejected>(
            FakeScaleDriver.newSession().onFrame(frame),
        )

        assertTrue(rejected.reason.contains("checksum"), rejected.reason)
    }

    @Test
    fun `une trame fictive de mauvaise taille est rejetée sans exception`() {
        val session = FakeScaleDriver.newSession()

        assertIs<ScaleFrameEvent.Rejected>(session.onFrame(ByteArray(0)))
        assertIs<ScaleFrameEvent.Rejected>(session.onFrame(byteArrayOf(0xFA.toByte(), 0x02)))
        assertIs<ScaleFrameEvent.Rejected>(session.onFrame(hexToBytes(REAL_STABLE_WEIGHT_FRAME)))
    }

    /**
     * Un pilote qui déclare ne pas fournir l'impédance n'en produit jamais, même si des octets
     * plausibles arrivent sur la liaison (FR-SCALE-030).
     */
    @Test
    fun `le pilote sans impédance ignore une trame d'impédance`() {
        val session = FakeWeightOnlyScaleDriver.newSession()

        val weight = assertIs<ScaleFrameEvent.Weight>(
            session.onFrame(FakeScaleFrames.weight(hundredthsKg = 6_010, stable = true)),
        )
        val impedance = session.onFrame(FakeScaleFrames.impedance(ohm = 545))

        assertEquals(6_010, weight.hundredthsKg)
        assertEquals(ScaleFrameEvent.Ignored, impedance)
    }
}
