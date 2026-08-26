package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.MeasurementDao
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.data.sync.SyncOutbox
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

/**
 * [outbox] is defaulted rather than required so every existing construction site — the
 * container, and the tests that build a repository straight onto an in-memory database — keeps
 * compiling and keeps journalling. Making the journal opt-in would leave the shipped path
 * untested by the very tests that exercise this class.
 */
class RoomMeasurementRepository(
    private val dao: MeasurementDao,
    private val outbox: SyncOutbox = SyncOutbox(),
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

    /**
     * The write and its outbox row go in together (sync FR-SYNC-001). A second call after
     * `upsert` would be a second transaction, and a process death in between would keep the
     * measurement and lose every trace that it still has to be sent.
     */
    override suspend fun save(measurement: Measurement) = withContext(ioDispatcher) {
        dao.upsertWithMutation(measurement.toEntity(), outbox.measurementUpsert(measurement))
    }

    /**
     * Moving a measurement to another date changes two aggregates, because the date is the
     * aggregate id: the old one is deleted and the new one written. Hence two mutations, of
     * which the delete is spent only when the date really moved.
     */
    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) =
        withContext(ioDispatcher) {
            dao.replaceWithMutation(
                originalDate = originalDate.toString(),
                entity = measurement.toEntity(),
                deleteMutation = outbox.measurementDelete(originalDate),
                upsertMutation = outbox.measurementUpsert(measurement),
            )
        }

    override suspend fun delete(date: LocalDate) = withContext(ioDispatcher) {
        dao.deleteWithMutation(date.toString(), outbox.measurementDelete(date))
    }
}
