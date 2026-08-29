package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.domain.model.ScaleWrite
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plafond d'attente d'un acquittement d'écriture.
 *
 * Le spike a constaté qu'un périphérique bon marché peut ne jamais rappeler `onCharacteristicWrite`,
 * ce qui bloquerait la séquence de mesure pour toujours ; il s'en protégeait par un chien de garde
 * de quatre secondes. La valeur est reprise telle quelle, éprouvée sur l'appareil réel le
 * 26/08/2026, et ne doit pas être retouchée sans un nouveau passage sur matériel.
 *
 * `internal` et non `private` : c'est la seule constante de ce fichier qu'un test a besoin de
 * nommer, et un test du chien de garde qui recopierait `4_000` continuerait de passer le jour où
 * quelqu'un changerait la vraie valeur.
 */
internal const val SCALE_WRITE_TIMEOUT_MS = 4_000L

/**
 * Ce qui remet réellement des octets à la pile Bluetooth.
 *
 * Une fonction et non un `BluetoothGatt` : c'est tout ce que [ScaleWriteQueue] a besoin de savoir
 * de la radio, et c'est ce qui la rend éprouvable en JVM pure (PRD_SCALE 21.3).
 */
internal fun interface ScaleWriteEmitter {

    /**
     * Remet [bytes] à la pile.
     *
     * @return `false` lorsque la pile refuse l'opération. Un refus n'est **jamais** suivi d'un
     *   acquittement : c'est ce qui permet à la file de rendre son rang à l'écriture suivante.
     *   Peut lever une [ScaleTransportException] lorsque la liaison n'a plus de quoi écrire.
     */
    fun emit(bytes: ByteArray): Boolean
}

/**
 * La sérialisation des écritures d'**une** liaison (PRD_SCALE 14.3).
 *
 * **Pourquoi cette classe existe séparément de la liaison GATT.** PRD_SCALE 14.3 désigne l'écriture
 * non sérialisée comme « le mode d'échec le plus probable, et il est silencieux » : la pile
 * d'Android n'accepte qu'une opération GATT en vol et rejette les suivantes sans erreur, si bien
 * qu'une séquence d'initialisation tronquée se manifeste uniquement par une balance qui se connecte
 * et dont aucun poids n'arrive jamais. C'est aussi la partie du module qui a le plus coûté à mettre
 * au point sur matériel, et PRD_SCALE 23 en fait une case de la checklist. Tant qu'elle vivait dans
 * la même classe que `BluetoothGatt` et ses callbacks, elle n'était couverte par aucun test : on
 * pouvait en retirer le verrou et la corrélation sans qu'une seule assertion ne vire au rouge. Ici,
 * elle ne dépend ni d'`android.bluetooth`, ni d'un `Looper`, ni d'une horloge réelle — seulement
 * d'un [ScaleWriteEmitter] et d'un `delay`, donc d'une horloge virtuelle sous `runTest`.
 *
 * **Deux ordres, et un seul suffit à corrompre la séquence.**
 *
 * 1. *Une écriture à la fois.* Le [Mutex] tient sur toute la durée de l'attente, si bien que la
 *    deuxième commande d'initialisation n'est pas même remise à la pile avant que la première soit
 *    acquittée.
 * 2. *Chaque acquittement appartient à une écriture précise.* `onCharacteristicWrite` ne transporte
 *    aucun identifiant, et comparer la caractéristique ne sert à rien : les écritures successives
 *    de la séquence portent toutes la même, `0xFFF2`. La corrélation est donc un **rang** : chaque
 *    émission réussie reçoit un numéro croissant, chaque acquittement consomme le rang suivant, et
 *    l'attente en cours n'est libérée que si les deux coïncident. Sans cela, l'enchaînement suivant
 *    fait avancer la séquence sur une écriture jamais confirmée : l'écriture n°1 atteint le chien de
 *    garde, le verrou se libère, l'écriture n°2 s'installe, puis l'acquittement **tardif** de la n°1
 *    arrive et débloque la n°2. Un acquittement qui ne correspond pas est ignoré et journalisé
 *    (PRD_SCALE 18.5), jamais interprété.
 *
 * Le rang est attribué **après** que l'émetteur a accepté les octets : une écriture que la pile
 * refuse ne produira aucun callback, et lui consommer un rang décalerait définitivement tous les
 * acquittements suivants.
 *
 * **Rien ici ne s'affiche.** Toute panne sort en [ScaleTransportException], y compris le chien de
 * garde — et surtout pas en `CancellationException`, qui fermerait la session de deux minutes là où
 * une écriture perdue ne justifie qu'une nouvelle tentative (FR-SCALE-021, PRD_SCALE 18.5).
 *
 * Confinement : les écritures viennent de la coroutine de session, les acquittements d'un fil de
 * liaison quelconque. D'où les types atomiques — ce sont les seules données que deux fils se
 * partagent.
 *
 * @param log Le journal technique de PRD_SCALE 18.5, où atterrissent les acquittements orphelins.
 * @param emitter Ce qui parle à la radio.
 */
internal class ScaleWriteQueue(
    private val log: ScaleLog = ScaleLog.NONE,
    private val emitter: ScaleWriteEmitter,
) {

    /** Une écriture à la fois, sur toute la durée de la liaison (PRD_SCALE 14.3). */
    private val gate = Mutex()

    /** Rang de la dernière émission que la pile a acceptée. */
    private val emitted = AtomicLong(0L)

    /** Rang du dernier acquittement consommé. Les acquittements arrivent dans l'ordre des écritures. */
    private val acknowledged = AtomicLong(0L)

    /** L'attente en cours, s'il y en a une. `AtomicReference` : l'acquittement vient d'un autre fil. */
    private val pending = AtomicReference<Pending?>(null)

    /** Pourquoi la liaison ne peut plus écrire, ou `null` tant qu'elle le peut. */
    @Volatile
    private var dead: String? = null

    /** Une écriture attend son acquittement. Lu par les tests : une attente orpheline est un défaut. */
    val isAwaitingAck: Boolean get() = pending.get() != null

    /**
     * Émet [write] et, quand [ScaleWrite.awaitAck] est vrai, n'est rendue qu'à son acquittement.
     *
     * Lève [ScaleTransportException] si la liaison est tombée, si la pile refuse l'écriture, si la
     * balance la rejette, ou si l'acquittement n'arrive pas dans [SCALE_WRITE_TIMEOUT_MS].
     */
    suspend fun write(write: ScaleWrite) {
        dead?.let { throw ScaleTransportException(it) }
        gate.withLock {
            // Relu sous le verrou : la liaison a pu tomber pendant que cette écriture faisait la
            // queue derrière la précédente, ce qui est le cas courant en fin de pesée.
            dead?.let { throw ScaleTransportException(it) }

            if (!write.awaitAck) {
                // La pile rappelle `onCharacteristicWrite` même sans réponse attendue : le rang est
                // consommé comme pour les autres, il n'est simplement attendu par personne.
                if (!emitter.emit(write.bytes)) {
                    throw ScaleTransportException("write refused by the stack")
                }
                emitted.incrementAndGet()
                return@withLock
            }

            awaitAcknowledgement(write.bytes)
        }
    }

    /**
     * Un acquittement remonté par la pile.
     *
     * @param failure Message de diagnostic quand la pile ou la balance a rejeté l'écriture, `null`
     *   quand elle l'a acceptée. Le message n'est jamais affiché (PRD_SCALE 18.5).
     */
    fun acknowledge(failure: String? = null) {
        val rank = acknowledged.incrementAndGet()
        val awaited = pending.get()
        if (awaited == null || awaited.rank != rank) {
            // Le cas de PRD_SCALE 14.3 : un acquittement en retard d'une écriture déjà abandonnée.
            // L'interpréter ferait avancer la séquence sur une commande jamais confirmée.
            log.log("stale write acknowledgement #$rank, awaiting #${awaited?.rank}")
            return
        }
        if (!pending.compareAndSet(awaited, null)) {
            log.log("write acknowledgement #$rank raced its own write, ignored")
            return
        }
        if (failure == null) {
            awaited.continuation.resume(Unit)
        } else {
            awaited.continuation.resumeWithException(ScaleTransportException(failure))
        }
    }

    /**
     * La liaison n'existe plus : l'attente en cours est libérée, les suivantes refusées d'emblée.
     *
     * Sans cela, une déconnexion pendant une écriture — le comportement **normal** de la balance de
     * référence, qui coupe quelques secondes après la mesure (FR-SCALE-021) — laisserait une
     * continuation en suspens pendant les quatre secondes du chien de garde, verrou compris.
     */
    fun fail(reason: String) {
        dead = reason
        val awaited = pending.getAndSet(null) ?: return
        awaited.continuation.resumeWithException(ScaleTransportException(reason))
    }

    private suspend fun awaitAcknowledgement(bytes: ByteArray) {
        // Le rang est réservé avant l'émission et n'est publié qu'après : l'acquittement peut
        // arriver d'un autre fil avant même que `emit` ne soit rendue.
        val rank = emitted.get() + 1
        try {
            withTimeout(SCALE_WRITE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val entry = Pending(rank, continuation)
                    if (!pending.compareAndSet(null, entry)) {
                        // Impossible tant que le verrou tient ; le dire plutôt que de l'écraser.
                        continuation.resumeWithException(
                            ScaleTransportException("a write is already in flight"),
                        )
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { clear(rank) }

                    val accepted = try {
                        emitter.emit(bytes)
                    } catch (failure: Throwable) {
                        if (clear(rank)) continuation.resumeWithException(failure)
                        return@suspendCancellableCoroutine
                    }
                    if (accepted) {
                        emitted.set(rank)
                    } else if (clear(rank)) {
                        continuation.resumeWithException(
                            ScaleTransportException("write refused by the stack"),
                        )
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // Traduit en panne de liaison, et surtout **pas** laissé remonter comme une annulation :
            // une CancellationException fermerait la session de deux minutes, là où une écriture
            // perdue ne justifie qu'une nouvelle tentative (FR-SCALE-021).
            clear(rank)
            throw ScaleTransportException(
                "no write acknowledgement in $SCALE_WRITE_TIMEOUT_MS ms",
                timeout,
            )
        }
    }

    /** Retire l'attente de [rank] si c'est toujours elle. `true` quand c'est cet appel qui l'a retirée. */
    private fun clear(rank: Long): Boolean {
        val current = pending.get()
        return current != null && current.rank == rank && pending.compareAndSet(current, null)
    }

    /** Une écriture émise qui attend son acquittement, et le rang qui la désigne. */
    private class Pending(
        val rank: Long,
        val continuation: CancellableContinuation<Unit>,
    )
}
