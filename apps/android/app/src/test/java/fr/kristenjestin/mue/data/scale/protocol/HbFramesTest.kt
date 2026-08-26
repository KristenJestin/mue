package fr.kristenjestin.mue.data.scale.protocol

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le codec d'enveloppe de la famille HB, éprouvé sur les trames réelles de PRD_SCALE 14
 * (FR-SCALE-031).
 *
 * Ces tests ne connaissent aucun pilote : ils portent sur la forme des trames, pas sur leur sens.
 * C'est la moitié du protocole qui peut être vérifiée sans jamais parler de poids ni d'ohms.
 */
class HbFramesTest {

    @Test
    fun `la trame de poids réelle de PRD 14 point 4 a une enveloppe cohérente`() {
        val decoded = assertIs<HbFrame.Valid>(HbFrames.decode(hexToBytes(REAL_STABLE_WEIGHT_FRAME)))

        assertEquals(0x10, decoded.type)
        assertEquals(0x00, decoded.product)
        assertEquals(0x02, decoded.byteAt(4))
        assertEquals(8575, decoded.uint16At(8))
    }

    @Test
    fun `la trame d'impédance réelle de PRD 14 point 5 a une enveloppe cohérente`() {
        val decoded = assertIs<HbFrame.Valid>(HbFrames.decode(hexToBytes(REAL_IMPEDANCE_FRAME)))

        assertEquals(0x11, decoded.type)
        assertEquals(0x00, decoded.product)
        assertEquals(0x0221, decoded.uint16At(9))
    }

    @Test
    fun `la réponse d'initialisation est valide et ne porte aucune donnée`() {
        val decoded = assertIs<HbFrame.Valid>(HbFrames.decode(hexToBytes(REAL_INIT_REPLY_FRAME)))

        assertEquals(0x17, decoded.type)
        assertNull(decoded.byteAt(4), "une trame de six octets n'a pas de position 4")
    }

    /** Le contrôle va de la longueur au dernier octet de données, bornes comprises (PRD 14.2). */
    @Test
    fun `le contrôle est le OU exclusif de la longueur au dernier octet de données`() {
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)

        assertEquals(0x67, HbFrames.checksum(frame, 1, frame.size - 2))
    }

    @Test
    fun `la longueur compte les octets qui suivent le champ de longueur`() {
        val frame = hexToBytes(REAL_IMPEDANCE_FRAME)

        assertEquals(13, frame.size)
        assertEquals(0x0B, frame[1].toInt() and 0xFF)
        assertEquals(frame.size - 2, frame[1].toInt() and 0xFF)
    }

    @Test
    fun `une trame dont le contrôle est faux est rejetée`() {
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)
        frame[frame.size - 2] = 0x66 // 0x67 attendu

        val rejected = assertIs<HbFrame.Malformed>(HbFrames.decode(frame))

        assertTrue(
            rejected.reason.contains("checksum"),
            "le motif doit désigner le contrôle, reçu : ${rejected.reason}",
        )
    }

    /**
     * BR-SCALE-004 : l'octet de produit ne participe **jamais** à la validation.
     *
     * Le spike de mise au point exigeait `0x26` et rejetait donc toutes les trames de l'appareil
     * réel, qui émet `0x00`. La variante `0x26` de la trame de poids réelle — contrôle recalculé,
     * puisque le contrôle couvre l'octet de produit — doit décoder **identiquement**.
     */
    @Test
    fun `une trame dont l'octet de produit diffère est acceptée et décode identiquement`() {
        val real = assertIs<HbFrame.Valid>(HbFrames.decode(hexToBytes(REAL_STABLE_WEIGHT_FRAME)))
        val variant = HbFrames.frame(
            header = HbFrames.HEADER_FROM_SCALE,
            product = 0x26,
            type = 0x10,
            data = hexToBytes("02 00 00 21 21 7F"),
        )

        val decoded = assertIs<HbFrame.Valid>(HbFrames.decode(variant))

        assertEquals("5A 0A 26 10 02 00 00 21 21 7F 41 AA", variant.toHex())
        assertEquals(0x26, decoded.product)
        assertEquals(real.type, decoded.type)
        assertEquals(real.byteAt(4), decoded.byteAt(4))
        assertEquals(real.uint16At(8), decoded.uint16At(8))
    }

    @Test
    fun `une trame trop courte est rejetée sans être interprétée`() {
        val rejected = assertIs<HbFrame.Malformed>(HbFrames.decode(hexToBytes("5A 0A 00 10 02")))

        assertTrue(rejected.reason.contains("too short"), rejected.reason)
    }

    @Test
    fun `un en-tête inattendu est rejeté`() {
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)
        frame[0] = 0xA5.toByte()

        val rejected = assertIs<HbFrame.Malformed>(HbFrames.decode(frame))

        assertTrue(rejected.reason.contains("header"), rejected.reason)
    }

    @Test
    fun `une fin inattendue est rejetée`() {
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)
        frame[frame.size - 1] = 0xAB.toByte()

        val rejected = assertIs<HbFrame.Malformed>(HbFrames.decode(frame))

        assertTrue(rejected.reason.contains("trailer"), rejected.reason)
    }

    @Test
    fun `une longueur qui ne correspond pas à la taille est rejetée`() {
        val frame = hexToBytes(REAL_STABLE_WEIGHT_FRAME)
        frame[1] = 0x09

        val rejected = assertIs<HbFrame.Malformed>(HbFrames.decode(frame))

        assertTrue(rejected.reason.contains("length"), rejected.reason)
    }

    @Test
    fun `une commande encodée se relit telle qu'elle a été écrite`() {
        val command = HbFrames.command(type = 0x33, data = byteArrayOf(0x00))

        val decoded = assertIs<HbFrame.Valid>(
            HbFrames.decode(command, expectedHeader = HbFrames.HEADER_FROM_PHONE),
        )

        assertEquals(0x33, decoded.type)
        assertEquals(HbFrames.PRODUCT_IN_COMMANDS, decoded.product)
        assertEquals(0x00, decoded.byteAt(4))
    }

    @Test
    fun `une commande sans données fait la taille minimale`() {
        val command = HbFrames.command(type = 0x44)

        assertEquals(HbFrames.MIN_FRAME_SIZE, command.size)
        assertEquals("A5 04 26 44 66 AA", command.toHex())
    }

    /** Les positions du PRD sont absolues : lire au-delà des données rend `null`, pas le contrôle. */
    @Test
    fun `une position qui tombe sur le contrôle ou la fin ne rend aucune donnée`() {
        val decoded = assertIs<HbFrame.Valid>(HbFrames.decode(hexToBytes(REAL_STABLE_WEIGHT_FRAME)))

        assertEquals(9, decoded.lastDataIndex)
        assertNull(decoded.byteAt(10), "position 10 : c'est le contrôle")
        assertNull(decoded.byteAt(11), "position 11 : c'est la fin")
        assertNull(decoded.uint16At(9), "positions 9-10 : la seconde est le contrôle")
        assertNull(decoded.byteAt(3), "position 3 : c'est le type, pas une donnée")
    }
}
