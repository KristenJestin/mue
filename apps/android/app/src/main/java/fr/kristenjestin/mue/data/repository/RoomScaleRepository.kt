package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.ScaleDao
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Les balances appairées, en Room (PRD_SCALE 9.3, 21.1).
 *
 * **Aucune ligne d'outbox, et pas de [fr.kristenjestin.mue.data.sync.SyncOutbox] en paramètre.**
 * C'est la différence visible avec [RoomMeasurementRepository], et elle est voulue : PRD_SCALE 22
 * range les balances enregistrées hors du fil, comme le catalogue d'aliments. Ne pas accepter
 * l'outbox du tout — plutôt que l'accepter et ne pas s'en servir — rend l'omission impossible à
 * prendre pour un oubli, et impossible à réparer par erreur.
 *
 * Aucune opération ne prend l'adresse pour une identité : tout passe par [ScaleDevice.id], parce
 * que l'adresse d'une balance peut changer au remplacement des piles (PRD_SCALE 10.1).
 */
class RoomScaleRepository(
    private val dao: ScaleDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScaleRepository {

    override fun observeAll(): Flow<List<ScaleDevice>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override suspend fun getAll(): List<ScaleDevice> = withContext(ioDispatcher) {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun findById(id: String): ScaleDevice? = withContext(ioDispatcher) {
        dao.findById(id)?.toDomain()
    }

    override suspend fun save(device: ScaleDevice) = withContext(ioDispatcher) {
        dao.upsert(device.toEntity())
    }

    override suspend fun rename(id: String, displayName: String) = withContext(ioDispatcher) {
        dao.rename(id, displayName)
    }

    override suspend fun markSeen(
        id: String,
        address: String,
        advertisedName: String,
        at: Instant,
    ) = withContext(ioDispatcher) {
        dao.markSeen(id, address, advertisedName, at.toEpochMilli())
    }

    /**
     * Oublie la balance. **Ne supprime aucune mesure** (BR-SCALE-010) : c'est la contrainte
     * `ON DELETE SET NULL` de `measurements.source_scale_id` qui le garantit, dans la même
     * instruction, sans qu'une seule ligne de Kotlin ait à parcourir l'historique.
     */
    override suspend fun forget(id: String) = withContext(ioDispatcher) {
        dao.delete(id)
    }
}
