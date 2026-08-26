package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.data.scale.protocol.HB_STABILITY_ACKNOWLEDGED
import fr.kristenjestin.mue.data.scale.protocol.HB_STABILITY_IN_PROGRESS
import fr.kristenjestin.mue.data.scale.protocol.HB_STABILITY_STABLE
import fr.kristenjestin.mue.data.scale.protocol.HB_TYPE_IMPEDANCE
import fr.kristenjestin.mue.data.scale.protocol.HB_TYPE_WEIGHT
import fr.kristenjestin.mue.data.scale.protocol.HbFrames
import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleGattProfile
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import fr.kristenjestin.mue.domain.model.ScaleWrite
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/*
 * Les doubles de la machine à états de session (PRD_SCALE 21.3).
 *
 * « Unitaire, sans Bluetooth : machine à états de mesure, y compris déconnexion en cours, timeout
 * de recherche, enregistrement avant impédance, trame tardive d'une ancienne session, mesure hors
 * bornes et impédance absente. » Aucune de ces situations n'est reproductible à la demande sur du
 * matériel : une balance ne se déconnecte pas au moment où le test le voudrait, et attendre deux
 * minutes réelles par cas ne serait pas une suite de tests. Elles deviennent des appels de méthode
 * dès que la liaison est une interface.
 *
 * Écrits à la main, sans bibliothèque de simulacres, comme tout le reste du dépôt.
 */

/** L'horloge des tests : un instant, fixe, pour que `receivedAt` soit une valeur assertable. */
internal val TEST_NOW: Instant = Instant.parse("2026-08-26T07:30:00Z")

/** Une balance appairée, sur le pilote réel et non sur le pilote fictif. */
internal fun pairedScale(
    id: String = "scale-hb",
    address: String = "FF:10:00:1F:52:C3",
    advertisedName: String = "HB BODY FAT",
    driverId: String = "hb9027",
) = ScaleDevice(
    id = id,
    driverId = driverId,
    address = address,
    advertisedName = advertisedName,
    displayName = "Bathroom scale",
    lastSeenAt = null,
    createdAt = Instant.parse("2026-08-01T10:00:00Z"),
)

internal fun advertisementOf(
    address: String = "FF:10:00:1F:52:C3",
    name: String? = "HB BODY FAT",
) = ScaleAdvertisement(
    address = address,
    name = name,
    serviceUuids = listOf("0000fff0-0000-1000-8000-00805f9b34fb"),
    manufacturerData = emptyMap(),
)

/**
 * Une trame de poids HB9027 (PRD_SCALE 14.4) : stabilité en position 4, poids en 8–9.
 *
 * Construite par [HbFrames] plutôt que recopiée en hexadécimal, pour que le contrôle par OU
 * exclusif soit celui du pilote et non celui du test. Le jeu de trames relevé sur matériel reste,
 * lui, verbatim dans `ScaleProtocolFixtures` (FR-SCALE-031), et les tests s'y confrontent.
 */
internal fun weightFrame(hundredthsKg: Int, stable: Boolean, acknowledged: Boolean = false) =
    HbFrames.frame(
        header = HbFrames.HEADER_FROM_SCALE,
        product = 0x00,
        type = HB_TYPE_WEIGHT,
        data = byteArrayOf(
            when {
                acknowledged -> HB_STABILITY_ACKNOWLEDGED
                stable -> HB_STABILITY_STABLE
                else -> HB_STABILITY_IN_PROGRESS
            }.toByte(),
            0x00, 0x00, 0x00,
            ((hundredthsKg ushr 8) and 0xFF).toByte(),
            (hundredthsKg and 0xFF).toByte(),
        ),
    )

/** Une trame d'impédance (PRD_SCALE 14.5) ; `null` produit le marqueur `0xFFFF` (BR-SCALE-005). */
internal fun impedanceFrame(ohm: Int?): ByteArray {
    val value = ohm ?: 0xFFFF
    return HbFrames.frame(
        header = HbFrames.HEADER_FROM_SCALE,
        product = 0x00,
        type = HB_TYPE_IMPEDANCE,
        data = byteArrayOf(
            0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        ),
    )
}

/**
 * Le dépôt des balances appairées, en mémoire.
 *
 * Retient les [markSeen] plutôt que de se contenter de les appliquer : « `markSeen` sur la balance
 * à chaque contact réussi » est une exigence (FR-SCALE-001), et une exigence qui ne se voit que par
 * son effet de bord se teste par cet effet de bord.
 */
internal class FakeScaleRepository(initial: List<ScaleDevice> = emptyList()) : ScaleRepository {

    data class Contact(
        val id: String,
        val address: String,
        val advertisedName: String,
        val at: Instant,
    )

    private val devices = MutableStateFlow(initial)

    val contacts: MutableList<Contact> = mutableListOf()

    override fun observeAll(): Flow<List<ScaleDevice>> = devices.asStateFlow()

    override suspend fun getAll(): List<ScaleDevice> = devices.value

    override suspend fun findById(id: String): ScaleDevice? = devices.value.firstOrNull { it.id == id }

    override suspend fun save(device: ScaleDevice) {
        devices.update { list -> list.filterNot { it.id == device.id } + device }
    }

    override suspend fun rename(id: String, displayName: String) {
        devices.update { list -> list.map { if (it.id == id) it.copy(displayName = displayName) else it } }
    }

    override suspend fun markSeen(id: String, address: String, advertisedName: String, at: Instant) {
        contacts += Contact(id, address, advertisedName, at)
        devices.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(address = address, advertisedName = advertisedName, lastSeenAt = at)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun forget(id: String) {
        devices.update { list -> list.filterNot { it.id == id } }
    }
}

/**
 * Le transport factice : une radio qui fait exactement ce que le test lui dit.
 *
 * Il compte ce que la machine lui demande, ce qui permet d'écrire l'exigence négative de
 * FR-SCALE-020 — « aucune balance enregistrée : aucun scan, aucune permission demandée » — comme
 * une assertion sur [untouched] plutôt que comme un espoir.
 */
internal class FakeScaleTransport : ScaleTransport {

    /** Ce que la radio répond quand on lui demande si elle peut travailler. */
    var unavailable: ScaleUnavailableReason? = null

    /** Nombre de connexions qui échoueront avant la première réussite (FR-SCALE-021). */
    var connectionsToRefuse: Int = 0

    /** Durée d'une connexion, en temps virtuel. Sert à éprouver la fenêtre de deux minutes. */
    var connectDelayMs: Long = 0L

    var availabilityChecks: Int = 0
        private set

    var scanStarts: Int = 0
        private set

    var scanStops: Int = 0
        private set

    val connectRequests: MutableList<String> = mutableListOf()

    val links: MutableList<FakeScaleLink> = mutableListOf()

    private val scanners = mutableListOf<SendChannel<ScaleAdvertisement>>()

    /** Un scan est en cours. Le contraire, après une candidate trouvée, est une exigence. */
    val isScanning: Boolean get() = scanners.isNotEmpty()

    /** La machine ne lui a strictement rien demandé — pas même l'état des permissions. */
    val untouched: Boolean
        get() = availabilityChecks == 0 && scanStarts == 0 && connectRequests.isEmpty()

    override fun availability(): ScaleUnavailableReason? {
        availabilityChecks += 1
        return unavailable
    }

    override fun scan(): Flow<ScaleAdvertisement> = callbackFlow {
        scanStarts += 1
        scanners += channel
        awaitClose {
            scanners -= channel
            scanStops += 1
        }
    }

    override suspend fun connect(
        advertisement: ScaleAdvertisement,
        profile: ScaleGattProfile,
    ): ScaleLink {
        connectRequests += advertisement.address
        if (connectDelayMs > 0L) delay(connectDelayMs)
        if (connectionsToRefuse > 0) {
            connectionsToRefuse -= 1
            throw ScaleTransportException("connection refused by the fake transport")
        }
        return FakeScaleLink(advertisement.address).also { links += it }
    }

    /** Une balance passe devant la radio. Sans scan en cours, l'annonce est perdue, comme en vrai. */
    fun advertise(vararg advertisements: ScaleAdvertisement) {
        for (advertisement in advertisements) {
            scanners.toList().forEach { it.trySend(advertisement) }
        }
    }
}

/**
 * Une liaison factice.
 *
 * [deliver] reste appelable **après** [close], et c'est délibéré : une pile BLE réelle peut encore
 * livrer une notification d'une liaison qu'on vient de fermer, et c'est précisément le scénario que
 * BR-SCALE-012 interdit de laisser aboutir. Les trames arrivées trop tard sont retenues dans
 * [lateFrames] pour qu'un test puisse affirmer qu'elles ont bien été présentées, et pas seulement
 * que rien ne s'est passé.
 */
internal class FakeScaleLink(val address: String) : ScaleLink {

    private val frames = Channel<ByteArray>(Channel.UNLIMITED)

    /** Les écritures émises, en hexadécimal, pour que l'échec d'une assertion se lise comme le PRD. */
    val writes: MutableList<String> = mutableListOf()

    /** Les trames présentées après la fermeture de la liaison. */
    val lateFrames: MutableList<String> = mutableListOf()

    var closed: Boolean = false
        private set

    private var lost: Boolean = false

    override suspend fun nextFrame(): ByteArray? = frames.receiveCatching().getOrNull()

    override suspend fun write(write: ScaleWrite) {
        if (closed || lost) throw ScaleTransportException("write on a dead link")
        writes += HbFrames.hex(write.bytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        frames.close()
    }

    /** Une notification de la balance. */
    fun deliver(frame: ByteArray) {
        if (frames.trySend(frame).isFailure) lateFrames += HbFrames.hex(frame)
    }

    /**
     * La balance s'endort ou sort de portée (FR-SCALE-021).
     *
     * Ce n'est **pas** une erreur : c'est le comportement normal de la balance de référence, qui
     * interrompt la liaison quelques secondes après une mesure.
     */
    fun dropLink() {
        lost = true
        frames.close()
    }
}
