package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.MeasurementDao
import fr.kristenjestin.mue.data.local.database.toCompositionEntity
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
 *
 * Depuis le module balance, ce que ce repository lit et écrit n'est plus une ligne mais l'agrégat
 * de PRD_SCALE 21.1 : poids, provenance, impédance et composition facultative. Les signatures de
 * [MeasurementRepository] n'ont pas bougé — c'est la sémantique qui s'est étendue — de sorte
 * qu'aucun écran, aucun `ViewModel` et aucun double de test n'a eu à changer pour que les règles
 * BR-SCALE-006, BR-SCALE-007 et BR-SCALE-010 deviennent vraies.
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
     *
     * [Measurement.bodyComposition] valant `null` n'est pas une absence d'instruction mais une
     * instruction : la transaction retire la composition que cette date portait (BR-SCALE-007).
     * C'est ce qui fait qu'une retouche manuelle d'un poids reçu ne laisse pas derrière elle une
     * masse grasse calculée à partir d'une valeur que plus personne ne peut lire.
     */
    override suspend fun save(measurement: Measurement) = withContext(ioDispatcher) {
        dao.upsertWithMutation(
            entity = measurement.toEntity(),
            composition = measurement.toCompositionEntity(),
            mutation = outbox.measurementUpsert(measurement),
        )
    }

    /**
     * Moving a measurement to another date changes two aggregates, because the date is the
     * aggregate id: the old one is deleted and the new one written. Hence two mutations, of
     * which the delete is spent only when the date really moved.
     *
     * La composition ne fait pas un troisième agrégat : celle de l'ancienne date part avec elle
     * par la cascade du schéma, celle de la nouvelle est écrite — ou effacée — par l'upsert.
     */
    override suspend fun replace(originalDate: LocalDate, measurement: Measurement) =
        withContext(ioDispatcher) {
            dao.replaceWithMutation(
                originalDate = originalDate.toString(),
                entity = measurement.toEntity(),
                composition = measurement.toCompositionEntity(),
                deleteMutation = outbox.measurementDelete(originalDate),
                upsertMutation = outbox.measurementUpsert(measurement),
            )
        }

    /** Supprime le poids ; sa composition suit par `ON DELETE CASCADE` (BR-SCALE-007). */
    override suspend fun delete(date: LocalDate) = withContext(ioDispatcher) {
        dao.deleteWithMutation(date.toString(), outbox.measurementDelete(date))
    }
}
