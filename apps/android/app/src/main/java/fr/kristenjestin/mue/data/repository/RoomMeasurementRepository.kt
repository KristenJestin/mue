package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.MeasurementDao
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

class RoomMeasurementRepository(
    private val dao: MeasurementDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MeasurementRepository {

    override fun observeAll(): Flow<List<Measurement>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observeIn(window: DateWindow): Flow<List<Measurement>> =
        dao.observeInWindow(window.start?.toString(), window.endInclusive?.toString())
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun observeLatest(): Flow<Measurement?> =
        dao.observeLatest().map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun getAll(): List<Measurement> = withContext(ioDispatcher) {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun findByDate(date: LocalDate): Measurement? = withContext(ioDispatcher) {
        dao.findByDate(date.toString())?.toDomain()
    }

    override suspend fun save(measurement: Measurement) = withContext(ioDispatcher) {
        dao.upsert(measurement.toEntity())
    }

    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) =
        withContext(ioDispatcher) {
            dao.replace(originalDate.toString(), measurement.toEntity())
        }

    override suspend fun delete(date: LocalDate) = withContext(ioDispatcher) {
        dao.deleteByDate(date.toString())
    }
}
