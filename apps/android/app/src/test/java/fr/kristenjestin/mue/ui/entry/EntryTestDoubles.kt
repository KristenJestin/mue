package fr.kristenjestin.mue.ui.entry

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.LocalDate

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
}

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
