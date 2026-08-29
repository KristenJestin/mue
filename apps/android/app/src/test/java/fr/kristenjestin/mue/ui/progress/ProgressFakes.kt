package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory history behaving like the Room implementation: one measurement per date, and
 * writing an occupied date replaces what was there (PRD BR-001, BR-002).
 */
class FakeMeasurementRepository(
    initial: List<Measurement> = emptyList(),
) : MeasurementRepository {

    private val entries = MutableStateFlow(initial.sortedBy { it.date })

    val measurements: List<Measurement> get() = entries.value

    var deletedDates: List<LocalDate> = emptyList()
        private set

    override fun observeAll(): Flow<List<Measurement>> = entries.asStateFlow()

    override fun observeIn(window: DateWindow): Flow<List<Measurement>> =
        entries.map { all -> all.filter { it.date in window } }

    override fun observeLatest(): Flow<Measurement?> =
        entries.map { all -> all.maxByOrNull { it.date } }

    override suspend fun getAll(): List<Measurement> = entries.value

    override suspend fun findByDate(date: LocalDate): Measurement? =
        entries.value.firstOrNull { it.date == date }

    override suspend fun save(measurement: Measurement) {
        write(measurement.date, measurement)
    }

    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) {
        write(originalDate, measurement)
    }

    override suspend fun delete(date: LocalDate) {
        deletedDates = deletedDates + date
        entries.value = entries.value.filterNot { it.date == date }
    }

    private fun write(originalDate: LocalDate, measurement: Measurement) {
        entries.value = entries.value
            .filterNot { it.date == originalDate || it.date == measurement.date }
            .plus(measurement)
            .sortedBy { it.date }
    }
}

class FakeUserProfileRepository(initial: UserProfile = UserProfile.EMPTY) : UserProfileRepository {

    private val state = MutableStateFlow(initial)

    override val profile: Flow<UserProfile> = state.asStateFlow()

    override suspend fun save(profile: UserProfile) {
        state.value = profile
    }
}

/**
 * Les balances appairées, pour la seule question que `Progress` leur pose : y en a-t-il une
 * (PRD_SCALE 18.4) ?
 *
 * Écrit à la main, comme tous les doubles de ce dépôt. Les opérations que l'écran n'appelle jamais
 * lèvent plutôt que de rendre une valeur plausible : si l'une d'elles se met un jour à être
 * appelée depuis `Progress`, c'est une décision qui mérite d'être vue en test, pas absorbée.
 */
class FakeScaleRepository(initial: List<ScaleDevice> = emptyList()) : ScaleRepository {

    private val devices = MutableStateFlow(initial)

    override fun observeAll(): Flow<List<ScaleDevice>> = devices.asStateFlow()

    override suspend fun getAll(): List<ScaleDevice> = devices.value

    override suspend fun findById(id: String): ScaleDevice? =
        devices.value.firstOrNull { it.id == id }

    override suspend fun save(device: ScaleDevice) {
        devices.value = devices.value.filterNot { it.id == device.id } + device
    }

    override suspend fun rename(id: String, displayName: String): Unit =
        throw UnsupportedOperationException("Progress never renames a scale")

    override suspend fun markSeen(
        id: String,
        address: String,
        advertisedName: String,
        at: Instant,
    ): Unit = throw UnsupportedOperationException("Progress never opens a link to a scale")

    override suspend fun forget(id: String) {
        devices.value = devices.value.filterNot { it.id == id }
    }
}

/** Une balance appairée quelconque : seule son existence compte ici (PRD_SCALE 18.4). */
fun pairedScale(id: String = "scale-1"): ScaleDevice = ScaleDevice(
    id = id,
    driverId = "fake",
    address = "FF:10:00:1F:52:C3",
    advertisedName = "HB BODY FAT",
    displayName = "Bathroom scale",
    lastSeenAt = null,
    createdAt = Instant.EPOCH,
)
