package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
