package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.data.scale.protocol.HbFrames
import fr.kristenjestin.mue.domain.model.ScaleWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * La sérialisation des écritures, éprouvée **sans Bluetooth et sans Android** (PRD_SCALE 21.3, 23).
 *
 * **Pourquoi ce fichier existe.** PRD_SCALE 23 range « les écritures de la séquence sont sérialisées
 * et attendent leur acquittement » dans sa checklist, et c'était jusqu'ici la seule case sans aucune
 * assertion derrière elle : `Hb9027DriverTest` vérifie que le *pilote déclare* `awaitAck == true`,
 * et le transport factice de `BleScaleSessionSourceTest` a un `write()` synchrone qui n'a jamais
 * rien à attendre. On pouvait donc retirer le verrou, le chien de garde et la corrélation sans
 * qu'une seule assertion ne vire au rouge — sur la partie du système qui a le plus coûté à mettre
 * au point sur matériel réel, et dont la panne est silencieuse : la balance se connecte, et aucun
 * poids n'arrive jamais (PRD_SCALE 14.3).
 *
 * L'horloge est virtuelle : les quatre secondes du chien de garde s'écoulent en quelques
 * microsecondes, ce qui est la seule façon de les couvrir dans une suite qu'on relance à chaque
 * modification.
 */
class ScaleWriteQueueTest {

    private val log = RecordingLog()
    private val emitter = RecordingEmitter()
    private val queue = ScaleWriteQueue(log, emitter)

    private fun writeOf(marker: Int, awaitAck: Boolean = true) =
        ScaleWrite(byteArrayOf(marker.toByte()), awaitAck = awaitAck)

    // region sérialisation

    /**
     * PRD_SCALE 14.3 : « la pile d'Android n'accepte qu'une opération GATT en vol et rejette les
     * suivantes sans erreur ». La deuxième commande ne doit donc pas même être remise à la radio
     * avant que la première soit acquittée — attendre son *retour* ne suffirait pas.
     */
    @Test
    fun `la seconde écriture n'est pas émise avant l'acquittement de la première`() = runTest {
        val first = launch { queue.write(writeOf(0x01)) }
        val second = launch { queue.write(writeOf(0x02)) }
        runCurrent()

        assertEquals(listOf("01"), emitter.emissions)
        assertFalse(first.isCompleted)
        assertTrue(queue.isAwaitingAck)

        queue.acknowledge()
        runCurrent()

        assertTrue(first.isCompleted)
        assertEquals(listOf("01", "02"), emitter.emissions)
        assertFalse(second.isCompleted)

        queue.acknowledge()
        runCurrent()
        assertTrue(second.isCompleted)
        assertFalse(queue.isAwaitingAck)
    }

    /**
     * **Le défaut que ce fichier existe pour interdire.**
     *
     * L'écriture n°1 atteint le chien de garde, le verrou se libère, l'écriture n°2 installe son
     * attente, puis l'acquittement **tardif** de la n°1 arrive. Corrélé par la seule caractéristique
     * — que les deux écritures partagent, `0xFFF2` — il débloquerait la n°2, et la séquence
     * d'initialisation avancerait sur une commande jamais confirmée : exactement le mode de panne
     * que PRD_SCALE 14.3 appelle « le plus probable, et il est silencieux ».
     */
    @Test
    fun `un acquittement tardif de la première n'acquitte jamais la seconde`() = runTest {
        val first = async { runCatching { queue.write(writeOf(0x01)) } }
        runCurrent()
        advanceTimeBy(SCALE_WRITE_TIMEOUT_MS + 1)
        runCurrent()
        assertIs<ScaleTransportException>(first.await().exceptionOrNull())

        val second = launch { queue.write(writeOf(0x02)) }
        runCurrent()
        assertEquals(listOf("01", "02"), emitter.emissions)

        // L'acquittement en retard de la n°1, remonté par la pile bien après son abandon.
        queue.acknowledge()
        runCurrent()

        assertFalse(second.isCompleted)
        assertTrue(queue.isAwaitingAck)
        assertTrue(
            log.lines.any { it.startsWith("stale write acknowledgement #1") },
            "un acquittement orphelin doit être journalisé, pas interprété (PRD_SCALE 18.5)",
        )

        // Le sien finit par arriver, et c'est lui qui la libère.
        queue.acknowledge()
        runCurrent()
        assertTrue(second.isCompleted)
    }

    /**
     * Une écriture sans acquittement attendu consomme quand même son rang : la pile rappelle
     * `onCharacteristicWrite` même pour un `WRITE_TYPE_NO_RESPONSE`, et laisser ce rappel sans rang
     * décalerait d'un cran tous les acquittements suivants.
     */
    @Test
    fun `une écriture sans acquittement attendu consomme son rang`() = runTest {
        val first = launch { queue.write(writeOf(0x01, awaitAck = false)) }
        runCurrent()
        assertTrue(first.isCompleted)
        assertFalse(queue.isAwaitingAck)

        val second = launch { queue.write(writeOf(0x02)) }
        runCurrent()
        assertEquals(listOf("01", "02"), emitter.emissions)

        // Le rappel que la pile émet pour la première : il ne vaut pas pour la seconde.
        queue.acknowledge()
        runCurrent()
        assertFalse(second.isCompleted)

        queue.acknowledge()
        runCurrent()
        assertTrue(second.isCompleted)
    }

    /**
     * Une écriture que la pile refuse ne produira aucun rappel : elle ne doit donc pas consommer de
     * rang, sans quoi l'écriture suivante attendrait un acquittement qui ne lui est pas destiné.
     */
    @Test
    fun `une écriture refusée par la pile échoue sans consommer de rang`() = runTest {
        emitter.accepts = false
        val refused = async { runCatching { queue.write(writeOf(0x01)) } }
        runCurrent()

        val failure = assertIs<ScaleTransportException>(refused.await().exceptionOrNull())
        assertEquals("write refused by the stack", failure.message)
        assertFalse(queue.isAwaitingAck)

        emitter.accepts = true
        val next = launch { queue.write(writeOf(0x02)) }
        runCurrent()
        queue.acknowledge()
        runCurrent()
        assertTrue(next.isCompleted)
    }

    // endregion

    // region chien de garde et pannes

    /**
     * FR-SCALE-021 et PRD_SCALE 18.5 : le chien de garde de quatre secondes doit produire une panne
     * de liaison, **jamais** une annulation. Une `CancellationException` remonterait jusqu'à la
     * coroutine de session et fermerait la fenêtre de deux minutes, là où une écriture perdue ne
     * justifie qu'une nouvelle tentative sur le temps restant.
     */
    @Test
    fun `le chien de garde produit une panne de liaison et non une annulation`() = runTest {
        val outcome = async { runCatching { queue.write(writeOf(0x01)) } }
        runCurrent()

        advanceTimeBy(SCALE_WRITE_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(outcome.isCompleted, "le chien de garde ne se déclenche pas avant son terme")

        advanceTimeBy(2)
        runCurrent()

        val failure = outcome.await().exceptionOrNull()
        assertFalse(
            failure is CancellationException,
            "une annulation fermerait la fenêtre de deux minutes (FR-SCALE-021)",
        )
        assertIs<ScaleTransportException>(failure)
        assertTrue(isActive, "la portée de la session survit à une écriture perdue")
        assertFalse(queue.isAwaitingAck, "le chien de garde ne laisse pas d'attente orpheline")
    }

    /**
     * FR-SCALE-021 : la balance de référence coupe la liaison quelques secondes après la mesure,
     * ce qui est la norme et non une anomalie. Si cela arrive pendant une écriture, la continuation
     * doit être libérée **tout de suite** — sinon le verrou reste pris quatre secondes de plus, et
     * la reprise de scan sur le temps restant les paie pour rien.
     */
    @Test
    fun `une déconnexion pendant une attente ne laisse pas de continuation en suspens`() = runTest {
        val outcome = async { runCatching { queue.write(writeOf(0x01)) } }
        runCurrent()
        assertTrue(queue.isAwaitingAck)

        queue.fail("disconnected, status=8")
        runCurrent()

        val failure = assertIs<ScaleTransportException>(outcome.await().exceptionOrNull())
        assertEquals("disconnected, status=8", failure.message)
        assertFalse(queue.isAwaitingAck)

        // Et la liaison morte le reste : plus rien ne part vers la radio, plus rien n'attend.
        val next = async { runCatching { queue.write(writeOf(0x02)) } }
        runCurrent()
        assertIs<ScaleTransportException>(next.await().exceptionOrNull())
        assertEquals(listOf("01"), emitter.emissions)
    }

    /** Une balance qui rejette l'écriture est une panne de liaison, pas un message à l'écran. */
    @Test
    fun `un acquittement en échec fait échouer l'écriture qu'il désigne`() = runTest {
        val outcome = async { runCatching { queue.write(writeOf(0x01)) } }
        runCurrent()

        queue.acknowledge(failure = "write rejected by the scale, status=133")
        runCurrent()

        val failure = assertIs<ScaleTransportException>(outcome.await().exceptionOrNull())
        assertEquals("write rejected by the scale, status=133", failure.message)
        assertFalse(queue.isAwaitingAck)
    }

    // endregion

    /** Ce que la pile a réellement reçu, en hexadécimal, et ce qu'elle en fait. */
    private class RecordingEmitter : ScaleWriteEmitter {

        val emissions: MutableList<String> = mutableListOf()

        /** `false` reproduit le refus d'une pile saturée : aucun rappel ne suivra. */
        var accepts: Boolean = true

        override fun emit(bytes: ByteArray): Boolean {
            emissions += HbFrames.hex(bytes)
            return accepts
        }
    }

    /** Le journal technique de PRD_SCALE 18.5, retenu pour qu'un test puisse l'affirmer. */
    private class RecordingLog : ScaleLog {
        val lines: MutableList<String> = mutableListOf()
        override fun log(message: String) {
            lines += message
        }
    }
}
