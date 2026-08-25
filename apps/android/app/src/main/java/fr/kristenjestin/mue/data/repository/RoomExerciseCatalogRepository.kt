package fr.kristenjestin.mue.data.repository

import fr.kristenjestin.mue.data.local.database.ExerciseCatalogDao
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.local.database.toEntity
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The exercise catalogue on top of Room.
 *
 * The seventeen provided definitions are put there by `ExerciseCatalogSeed`, on a fresh install
 * and on a migrated one alike, so this class never has to wonder whether the catalogue exists.
 * PRD 9.2 gives the V1 no way to rename or delete a definition, which is why nothing here can.
 */
class RoomExerciseCatalogRepository(
    private val dao: ExerciseCatalogDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> ExerciseDefinitionId = ExerciseDefinitionId::random,
) : ExerciseCatalogRepository {

    override fun observeCatalogue(): Flow<List<ExerciseDefinition>> =
        dao.observeCatalogue()
            .map { rows -> rows.map { it.definition.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun findById(id: ExerciseDefinitionId): ExerciseDefinition? =
        withContext(ioDispatcher) { dao.findById(id.value)?.toDomain() }

    override suspend fun findByName(name: String): ExerciseDefinition? =
        withContext(ioDispatcher) {
            dao.findByFoldedName(ExerciseDefinition.fold(name))?.toDomain()
        }

    /**
     * The candidate is built before the lookup and thrown away when the name is already taken:
     * PRD 9.2 reuses the existing definition whatever its case or padding, so a second
     * `  bench press ` is the catalogue's `Bench press` and keeps its tracking mode.
     */
    override suspend fun findOrCreate(
        name: String,
        trackingMode: TrackingMode,
        equipment: EquipmentType?,
    ): ExerciseDefinition = withContext(ioDispatcher) {
        val candidate = ExerciseDefinition(
            id = newId(),
            name = name.trim(),
            trackingMode = trackingMode,
            equipment = equipment,
            isCustom = true,
        )
        dao.findOrCreate(candidate.toEntity()).toDomain()
    }
}
