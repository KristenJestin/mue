package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.equipmentOf
import fr.kristenjestin.mue.domain.model.sessionOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The five rules of PRD 11.1, tried most specific first. */
class ActivityLabelTest {

    @Test
    fun `rule one names an other session by the name it was given`() {
        val session = sessionOf(
            movement = Movement.OTHER,
            customMovementName = "Padel",
            environment = ActivityEnvironment.OUTDOOR,
        )
        assertEquals("Padel", ActivityLabel.of(session))
    }

    @Test
    fun `rule one trims the name it was given and ignores a blank one`() {
        assertEquals(
            "Padel",
            ActivityLabel.of(sessionOf(movement = Movement.OTHER, customMovementName = "  Padel ")),
        )
        assertEquals(
            ActivityLabel.OTHER_ACTIVITY,
            ActivityLabel.of(sessionOf(movement = Movement.OTHER, customMovementName = "   ")),
        )
    }

    @Test
    fun `rule two lets a titling machine name the session`() {
        val walk = sessionOf(movement = Movement.WALKING, environment = ActivityEnvironment.INDOOR)
        assertEquals(
            "Treadmill walk",
            ActivityLabel.of(walk, listOf(equipmentOf(EquipmentType.TREADMILL))),
        )
        assertEquals(
            "Stationary bike ride",
            ActivityLabel.of(
                sessionOf(movement = Movement.CYCLING),
                listOf(equipmentOf(EquipmentType.STATIONARY_BIKE)),
            ),
        )
        assertEquals(
            "Indoor rowing",
            ActivityLabel.of(
                sessionOf(movement = Movement.ROWING),
                listOf(equipmentOf(EquipmentType.ROWING_MACHINE)),
            ),
        )
        assertEquals(
            "Elliptical session",
            ActivityLabel.of(
                sessionOf(movement = Movement.ELLIPTICAL),
                listOf(equipmentOf(EquipmentType.ELLIPTICAL_MACHINE)),
            ),
        )
    }

    @Test
    fun `a machine only titles a session that carries it alone`() {
        val session = sessionOf(movement = Movement.WALKING, environment = ActivityEnvironment.INDOOR)
        assertEquals(
            "Indoor walk",
            ActivityLabel.of(
                session,
                listOf(
                    equipmentOf(EquipmentType.TREADMILL, position = 0),
                    equipmentOf(EquipmentType.YOGA_MAT, position = 1),
                ),
            ),
        )
    }

    @Test
    fun `gear that does not change what the activity is stays out of the label`() {
        assertEquals(
            "Indoor yoga",
            ActivityLabel.of(
                sessionOf(movement = Movement.YOGA, environment = ActivityEnvironment.INDOOR),
                listOf(equipmentOf(EquipmentType.YOGA_MAT)),
            ),
        )
    }

    @Test
    fun `a machine with no pairing of its own falls through to the next rule`() {
        assertEquals(
            "Indoor strength training",
            ActivityLabel.of(
                sessionOf(
                    movement = Movement.STRENGTH_TRAINING,
                    environment = ActivityEnvironment.INDOOR,
                ),
                listOf(equipmentOf(EquipmentType.TREADMILL)),
            ),
        )
    }

    @Test
    fun `rule three names the session by its place`() {
        assertEquals(
            "Outdoor run",
            ActivityLabel.of(
                sessionOf(movement = Movement.RUNNING, environment = ActivityEnvironment.OUTDOOR),
            ),
        )
        assertEquals(
            "Outdoor walk",
            ActivityLabel.of(
                sessionOf(movement = Movement.WALKING, environment = ActivityEnvironment.OUTDOOR),
            ),
        )
        assertEquals(
            "Indoor swim",
            ActivityLabel.of(
                sessionOf(movement = Movement.SWIMMING, environment = ActivityEnvironment.INDOOR),
            ),
        )
    }

    @Test
    fun `rule four falls back on the movement alone when nothing else is known`() {
        assertEquals(
            "Strength training",
            ActivityLabel.of(sessionOf(movement = Movement.STRENGTH_TRAINING)),
        )
        assertEquals("Cycling", ActivityLabel.of(sessionOf(movement = Movement.CYCLING)))
        assertEquals("Team sport", ActivityLabel.of(sessionOf(movement = Movement.TEAM_SPORT)))
    }

    @Test
    fun `rule five covers an other session that never got a name`() {
        assertEquals(
            "Other activity",
            ActivityLabel.of(sessionOf(movement = Movement.OTHER, customMovementName = null)),
        )
    }

    @Test
    fun `a movement this build cannot read still produces a label`() {
        assertEquals(
            "Other activity",
            ActivityLabel.of(sessionOf(movement = Movement.fromId("padel"))),
        )
    }

    @Test
    fun `every movement of PRD 8-2 has a label under every place`() {
        Movement.entries.forEach { movement ->
            ActivityEnvironment.entries.forEach { environment ->
                val label = ActivityLabel.of(sessionOf(movement = movement, environment = environment))
                assertEquals(label.trim(), label)
                assertTrue(label.isNotEmpty(), "$movement in $environment has no label")
            }
        }
    }
}
