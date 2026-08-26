package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleCapabilities
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleDriver
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry
import fr.kristenjestin.mue.domain.model.ScaleDriverSession
import fr.kristenjestin.mue.domain.model.ScaleFrameEvent
import fr.kristenjestin.mue.domain.model.ScaleGattProfile
import fr.kristenjestin.mue.domain.model.ScaleLinkMode
import fr.kristenjestin.mue.domain.model.ScaleWrite
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.Locale

/**
 * Les doubles à la main du module balance côté interface.
 *
 * Écrits plutôt que simulés, comme partout dans ce dépôt : ce qu'un test veut assurer ici, c'est
 * qu'une balance oubliée ne fasse rien d'autre que disparaître, et qu'un rattachement soit proposé
 * plutôt qu'appliqué — deux affirmations sur des données, pas sur des appels.
 */
internal class FakeScaleRepository(
    initial: List<ScaleDevice> = emptyList(),
) : ScaleRepository {

    private val state = MutableStateFlow(initial)

    /** Ce que la base contient, pour l'assertion finale. */
    val stored: List<ScaleDevice> get() = state.value

    /**
     * Chaque écriture, nommée, dans l'ordre.
     *
     * C'est ce qui permet d'affirmer BR-SCALE-010 pour de bon : oublier une balance appelle
     * `forget` **et rien d'autre**. Un test qui se contenterait de vérifier que la balance a
     * disparu passerait aussi bien avec un ViewModel qui aurait, au passage, supprimé des mesures.
     */
    val writes: MutableList<String> = mutableListOf()

    override fun observeAll(): Flow<List<ScaleDevice>> = state

    override suspend fun getAll(): List<ScaleDevice> = state.value

    override suspend fun findById(id: String): ScaleDevice? = state.value.firstOrNull { it.id == id }

    override suspend fun save(device: ScaleDevice) {
        writes += "save:${device.id}"
        state.value = state.value.filterNot { it.id == device.id } + device
    }

    override suspend fun rename(id: String, displayName: String) {
        writes += "rename:$id:$displayName"
        state.value = state.value.map {
            if (it.id == id) it.copy(displayName = displayName) else it
        }
    }

    override suspend fun markSeen(
        id: String,
        address: String,
        advertisedName: String,
        at: Instant,
    ) {
        writes += "markSeen:$id:$address"
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(address = address, advertisedName = advertisedName, lastSeenAt = at)
            } else {
                it
            }
        }
    }

    override suspend fun forget(id: String) {
        writes += "forget:$id"
        state.value = state.value.filterNot { it.id == id }
    }
}

/**
 * Un scanner en mémoire.
 *
 * Il compte ses ouvertures et ses fermetures parce que c'est précisément ce que le contrat de
 * [ScaleDiscovery] promet : le scan tourne tant qu'au moins un appelant l'a ouvert, et deux écrans
 * composés en même temps pendant une transition ne doivent pas s'éteindre l'un l'autre.
 */
internal class FakeScaleDiscovery : ScaleDiscovery {

    private val flow = MutableSharedFlow<ScaleAdvertisement>(extraBufferCapacity = 64)

    /** Le nombre d'appelants qui ont ouvert le scan sans l'avoir refermé. */
    var openCount: Int = 0
        private set

    /** Combien de fois le scan est passé de fermé à ouvert. */
    var startCount: Int = 0
        private set

    val isScanning: Boolean get() = openCount > 0

    override val advertisements: Flow<ScaleAdvertisement> = flow

    override fun start() {
        if (openCount == 0) startCount++
        openCount++
    }

    override fun stop() {
        openCount = (openCount - 1).coerceAtLeast(0)
    }

    /** Émet une annonce. Renvoie `false` si personne n'écoute, ce qui est déjà un échec de test. */
    fun emit(advertisement: ScaleAdvertisement): Boolean = flow.tryEmit(advertisement)
}

/** Une annonce nue : le nom suffit à tous les pilotes de ces tests. */
internal fun advertisementOf(address: String, name: String?): ScaleAdvertisement =
    ScaleAdvertisement(
        address = address,
        name = name,
        serviceUuids = emptyList(),
        manufacturerData = emptyMap(),
    )

/**
 * Un pilote qui reconnaît un nom annoncé, et rien d'autre.
 *
 * Assez pour ce que ces écrans ont à prouver : ils ne décodent aucune trame, ils se contentent de
 * savoir si un appareil découvert a un pilote et lequel.
 */
internal class FakeUiScaleDriver(
    override val id: String,
    override val modelName: String,
    private val advertisedName: String,
) : ScaleDriver {

    override val linkMode: ScaleLinkMode get() = ScaleLinkMode.GATT

    override val capabilities: ScaleCapabilities =
        ScaleCapabilities(providesWeight = true, providesImpedance = true)

    override val gattProfile: ScaleGattProfile? get() = null

    override fun recognises(advertisement: ScaleAdvertisement): Boolean =
        advertisement.name?.trim()?.uppercase(Locale.ROOT) == advertisedName.uppercase(Locale.ROOT)

    override fun newSession(): ScaleDriverSession = object : ScaleDriverSession {
        override fun onSubscribed(): List<ScaleWrite> = emptyList()
        override fun onFrame(frame: ByteArray): ScaleFrameEvent = ScaleFrameEvent.Ignored
    }
}

/** Le registre de ces tests : les pilotes qu'on lui donne, interrogés dans l'ordre. */
internal class FakeScaleDriverRegistry(
    override val drivers: List<ScaleDriver>,
) : ScaleDriverRegistry {

    override fun byId(id: String): ScaleDriver? = drivers.firstOrNull { it.id == id }

    override fun recognise(advertisement: ScaleAdvertisement): ScaleDriver? =
        drivers.firstOrNull { it.recognises(advertisement) }
}

internal fun scaleDeviceOf(
    id: String,
    driverId: String = "fake-driver",
    address: String = "AA:BB:CC:DD:EE:01",
    advertisedName: String = "Fake Scale",
    displayName: String = "Bathroom scale",
    lastSeenAt: Instant? = null,
    createdAt: Instant = Instant.parse("2026-08-01T09:00:00Z"),
): ScaleDevice = ScaleDevice(
    id = id,
    driverId = driverId,
    address = address,
    advertisedName = advertisedName,
    displayName = displayName,
    lastSeenAt = lastSeenAt,
    createdAt = createdAt,
)
