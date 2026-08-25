package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment

/**
 * How a session names itself on a card (PRD 11.1).
 *
 * The label is derived on every read, never stored, so the wording can change without touching
 * a single row. The five rules are tried in order, most specific first.
 */
object ActivityLabel {

    /** PRD 11.1 rule 5: an `other` session that never got a name of its own. */
    const val OTHER_ACTIVITY: String = "Other activity"

    /**
     * PRD 11.1 rule 2. A machine that changes what the activity *is* has to be spelled out:
     * `Treadmill walk` cannot be assembled from `treadmill` and `walk` by any general rule, so
     * the handful of pairings that read well are written down and everything else falls through.
     */
    private val TITLED: Map<Pair<Movement, EquipmentType>, String> = mapOf(
        (Movement.WALKING to EquipmentType.TREADMILL) to "Treadmill walk",
        (Movement.RUNNING to EquipmentType.TREADMILL) to "Treadmill run",
        (Movement.HIKING to EquipmentType.TREADMILL) to "Treadmill hike",
        (Movement.CYCLING to EquipmentType.STATIONARY_BIKE) to "Stationary bike ride",
        (Movement.ROWING to EquipmentType.ROWING_MACHINE) to "Indoor rowing",
        (Movement.ELLIPTICAL to EquipmentType.ELLIPTICAL_MACHINE) to "Elliptical session",
    )

    fun of(detail: ActivitySessionDetail): String = of(detail.session, detail.equipment)

    fun of(session: ActivitySession, equipment: List<SessionEquipment> = emptyList()): String = of(
        movement = session.movement,
        customMovementName = session.customMovementName,
        environment = session.environment,
        equipment = equipment,
    )

    fun of(
        movement: Movement,
        customMovementName: String?,
        environment: ActivityEnvironment,
        equipment: List<SessionEquipment>,
    ): String {
        val customName = customMovementName?.trim()?.takeIf { it.isNotEmpty() }
        if (movement == Movement.OTHER && customName != null) return customName

        titlingLabel(movement, equipment)?.let { return it }

        if (environment != ActivityEnvironment.UNKNOWN) {
            return "${environment.displayName} ${movement.activityNoun}"
        }

        return if (movement == Movement.OTHER) OTHER_ACTIVITY else movement.displayName
    }

    /** A machine only titles a session that carries exactly one piece of equipment (PRD 11.1). */
    private fun titlingLabel(movement: Movement, equipment: List<SessionEquipment>): String? {
        val only = equipment.singleOrNull() ?: return null
        if (!only.equipmentType.isTitling) return null
        return TITLED[movement to only.equipmentType]
    }
}
