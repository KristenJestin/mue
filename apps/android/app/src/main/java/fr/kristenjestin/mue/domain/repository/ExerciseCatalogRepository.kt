package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.TrackingMode
import kotlinx.coroutines.flow.Flow

/**
 * The exercise catalogue (PRD 9.2).
 *
 * The seventeen provided definitions are installed with the database and cannot be removed;
 * a custom one is kept for good, including once no session uses it any more. The V1 offers no
 * screen to rename or delete a definition, so this contract has no update and no delete.
 */
interface ExerciseCatalogRepository {

    /**
     * What the picker of PRD FR-ACTIVITY-009 lists under `Recent & common`: the definitions
     * used most recently first, then the rest of the catalogue by name.
     */
    fun observeCatalogue(): Flow<List<ExerciseDefinition>>

    suspend fun findById(id: ExerciseDefinitionId): ExerciseDefinition?

    /** Matches on the folded name, so case and surrounding spaces do not create a twin. */
    suspend fun findByName(name: String): ExerciseDefinition?

    /**
     * PRD 9.2: creating an exercise whose name is already in the catalogue reuses the existing
     * definition instead of adding a second one, whatever its case or padding. The tracking
     * mode and equipment given here therefore only apply to a definition that is really new.
     */
    suspend fun findOrCreate(
        name: String,
        trackingMode: TrackingMode,
        equipment: EquipmentType? = null,
    ): ExerciseDefinition
}
