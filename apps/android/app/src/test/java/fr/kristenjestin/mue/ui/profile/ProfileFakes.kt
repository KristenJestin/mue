package fr.kristenjestin.mue.ui.profile

import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.time.LocalDate

class FakeUserProfileRepository(initial: UserProfile = UserProfile.EMPTY) : UserProfileRepository {

    private val state = MutableStateFlow(initial)

    /** Simulates the storage failure of PRD 15.4. */
    var failOnSave: Boolean = false

    var saveCount: Int = 0
        private set

    val stored: UserProfile get() = state.value

    override val profile: Flow<UserProfile> = state

    override suspend fun save(profile: UserProfile) {
        if (failOnSave) throw IOException("storage unavailable")
        saveCount++
        state.value = profile
    }
}

class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences.DEFAULT,
) : UserPreferencesRepository {

    private val state = MutableStateFlow(initial)

    val stored: UserPreferences get() = state.value

    override val preferences: Flow<UserPreferences> = state

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        state.value = state.value.copy(hapticsEnabled = enabled)
    }
}

class FakeMeasurementRepository(measurements: List<Measurement> = emptyList()) :
    MeasurementRepository {

    private val state = MutableStateFlow(measurements.sortedBy { it.date })

    var failOnGetAll: Boolean = false

    override fun observeAll(): Flow<List<Measurement>> = state

    override fun observeIn(window: DateWindow): Flow<List<Measurement>> =
        state.map { all -> all.filter { it.date in window } }

    override fun observeLatest(): Flow<Measurement?> =
        state.map { all -> all.maxByOrNull { it.date } }

    override suspend fun getAll(): List<Measurement> {
        if (failOnGetAll) throw IOException("database unavailable")
        return state.value
    }

    override suspend fun findByDate(date: LocalDate): Measurement? =
        state.value.firstOrNull { it.date == date }

    override suspend fun save(measurement: Measurement) {
        state.value = (state.value.filterNot { it.date == measurement.date } + measurement)
            .sortedBy { it.date }
    }

    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) {
        state.value = (
            state.value.filterNot { it.date == originalDate || it.date == measurement.date } +
                measurement
            ).sortedBy { it.date }
    }

    override suspend fun delete(date: LocalDate) {
        state.value = state.value.filterNot { it.date == date }
    }
}

class FakeWeightDataExporter(
    private val file: File = File("mue-weight-2026-08-23.csv"),
) : WeightDataExporter {

    var failure: Throwable? = null

    var exportedMeasurements: List<Measurement>? = null
        private set

    var exportedOn: LocalDate? = null
        private set

    var callCount: Int = 0
        private set

    override suspend fun export(measurements: List<Measurement>, exportDate: LocalDate): File {
        callCount++
        exportedMeasurements = measurements
        exportedOn = exportDate
        failure?.let { throw it }
        return file
    }
}
