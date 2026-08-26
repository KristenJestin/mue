package fr.kristenjestin.mue.ui.entry

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.ScaleReading
import fr.kristenjestin.mue.domain.model.ScaleSessionState
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.ScaleSessionSource
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * In-memory history that keeps the one-measurement-per-date rule of PRD BR-001, so a test
 * asserting a silent replacement is asserting the real behaviour rather than the fake's.
 */
class FakeMeasurementRepository(
    initial: List<Measurement> = emptyList(),
) : MeasurementRepository {

    private val entries = MutableStateFlow(initial.sortedBy { it.date })

    /** Set to make the next write fail, for the storage-error path of PRD 15.4. */
    var failWrites: Boolean = false

    val stored: List<Measurement> get() = entries.value

    override fun observeAll(): Flow<List<Measurement>> = entries.asStateFlow()

    override fun observeIn(window: DateWindow): Flow<List<Measurement>> =
        entries.map { list -> list.filter { it.date in window } }

    override fun observeLatest(): Flow<Measurement?> = entries.map { it.lastOrNull() }

    override suspend fun getAll(): List<Measurement> = entries.value

    override suspend fun findByDate(date: LocalDate): Measurement? =
        entries.value.firstOrNull { it.date == date }

    override suspend fun save(measurement: Measurement) {
        if (failWrites) throw IllegalStateException("storage unavailable")
        entries.update { list ->
            (list.filterNot { it.date == measurement.date } + measurement).sortedBy { it.date }
        }
    }

    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) {
        delete(originalDate)
        save(measurement)
    }

    override suspend fun delete(date: LocalDate) {
        entries.update { list -> list.filterNot { it.date == date } }
    }
}

class FakeUserProfileRepository(initial: UserProfile = UserProfile.EMPTY) : UserProfileRepository {
    private val state = MutableStateFlow(initial)
    override val profile: Flow<UserProfile> = state.asStateFlow()
    override suspend fun save(profile: UserProfile) {
        state.value = profile
    }
}

class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences.DEFAULT,
) : UserPreferencesRepository {
    private val state = MutableStateFlow(initial)
    override val preferences: Flow<UserPreferences> = state.asStateFlow()
    override suspend fun setHapticsEnabled(enabled: Boolean) {
        state.value = state.value.copy(hapticsEnabled = enabled)
    }
    override suspend fun setShowEnergy(enabled: Boolean) {
        state.value = state.value.copy(showEnergy = enabled)
    }
    override suspend fun setScalePermissionRequested(requested: Boolean) {
        state.value = state.value.copy(scalePermissionRequested = requested)
    }
}

/**
 * The Bluetooth layer, replaced by a hand-driven state flow (PRD_SCALE 21.3).
 *
 * The whole point of `ScaleSessionSource` being the single seam between `Entry` and the radio is
 * that this class can exist: every rule of PRD_SCALE 12.2 — the frame arriving, the late frame
 * being ignored, the two-minute timeout, the missing permission — is reachable by assigning a
 * value here, with no Android, no adapter and no device.
 *
 * The four calls are counted rather than recorded, because what the requirements are about is
 * *whether* the screen asked, not with what: FR-SCALE-020 wants a scan started when `Entry`
 * appears and stopped when it leaves, and BR-SCALE-012 wants the session closed on the save.
 */
class FakeScaleSessionSource(
    initial: ScaleSessionState = ScaleSessionState.Absent,
) : ScaleSessionSource {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ScaleSessionState> = _state.asStateFlow()

    var starts: Int = 0
        private set
    var stops: Int = 0
        private set
    var retries: Int = 0
        private set
    var closes: Int = 0
        private set

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }

    override fun retry() {
        retries++
    }

    override fun closeSession() {
        closes++
    }

    /** What the driver would have produced. The screen cannot tell the difference. */
    fun emit(state: ScaleSessionState) {
        _state.value = state
    }
}

/**
 * A reading the way the link layer hands it over (PRD_SCALE 9.4).
 *
 * [sessionId] carries the default that most tests want — one session — and the tests about late
 * frames are exactly the ones that pass a second value, which is what makes them readable.
 */
fun scaleReadingOf(
    kilograms: Double,
    impedanceOhm: Int? = null,
    sessionId: String = "session-1",
    scaleId: String = "scale-1",
    isStable: Boolean = true,
): ScaleReading = ScaleReading(
    sessionId = sessionId,
    weightHundredthsKg = (kilograms * 100).roundToInt(),
    isStable = isStable,
    impedanceOhm = impedanceOhm,
    receivedAt = Instant.parse("2026-08-23T07:12:00Z"),
    scaleId = scaleId,
)

/** `viewModelScope` runs on the main dispatcher, which does not exist on the JVM. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
