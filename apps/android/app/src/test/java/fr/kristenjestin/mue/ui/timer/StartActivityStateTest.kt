package fr.kristenjestin.mue.ui.timer

import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityPreset
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.ui.activity.CatalogTarget
import fr.kristenjestin.mue.ui.activity.LogActivityMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the start screen is holding while it is open (PRD_ACTIVITY_TIMER 6.2), driven as plain
 * Kotlin: the holder is snapshot state and nothing else, so none of this needs a device.
 */
class StartActivityStateTest {

    // region what `Start timer` sends

    /** FR-TIMER-001: a preset carries its own axes, which is what rebuilds its label later. */
    @Test
    fun aPresetSendsItsOwnAxes() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.TREADMILL_WALK)

        val request = state.request
        assertEquals(Movement.WALKING, request.movement)
        assertEquals(ActivityEnvironment.INDOOR, request.environment)
        assertEquals(
            listOf(EquipmentType.TREADMILL),
            request.equipment.map { it.equipmentType },
        )
        assertNull(request.customMovementName)
    }

    /** The axes have to survive the round trip, or the timer would name itself differently. */
    @Test
    fun aPresetRequestRebuildsThatPreset() {
        ActivityPreset.entries.filterNot { it == ActivityPreset.OTHER }.forEach { preset ->
            val state = StartActivityState()
            state.selectPreset(preset)
            val request = state.request
            assertEquals(
                "$preset should rebuild itself",
                preset,
                ActivityPreset.of(request.movement, request.equipment),
            )
        }
    }

    /** The builder's own values are only sent while the builder is what is on screen. */
    @Test
    fun aPresetIgnoresWhatTheBuilderHolds() {
        val state = StartActivityState()
        state.openMovementPicker()
        state.onCatalogEntrySelected(Movement.YOGA.id)
        state.selectEnvironment(ActivityEnvironment.OUTDOOR)
        state.selectPreset(ActivityPreset.RUN)

        assertEquals(Movement.RUNNING, state.request.movement)
        assertEquals(ActivityEnvironment.OUTDOOR, state.environment)
    }

    @Test
    fun theBuilderSendsWhatItWasGiven() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openMovementPicker()
        state.onCatalogEntrySelected(Movement.YOGA.id)
        state.selectEnvironment(ActivityEnvironment.INDOOR)
        state.openEquipmentPicker()
        state.onCatalogEntrySelected(EquipmentType.YOGA_MAT.id)

        val request = state.request
        assertEquals(Movement.YOGA, request.movement)
        assertEquals(ActivityEnvironment.INDOOR, request.environment)
        assertEquals(listOf(EquipmentType.YOGA_MAT), request.equipment.map { it.equipmentType })
    }

    /** FR-ACTIVITY-008: the created name is the only path to `movement = other`. */
    @Test
    fun aCreatedNameBecomesTheCustomMovement() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openMovementPicker()
        state.onPickerQueryChange("  Padel  ")
        state.onCreateFromSearch()

        assertEquals(Movement.OTHER, state.request.movement)
        assertEquals("Padel", state.request.customMovementName)
        assertEquals("Padel", state.mainActivityLabel)
        assertNull("the sheet closes on a movement", state.picker)
    }

    // endregion

    // region `Start again` (contract decision 4)

    @Test
    fun startAgainWithNoLastSessionOpensTheDefaultPreset() {
        val state = StartActivityState.of(null)

        assertEquals(ActivityPreset.DEFAULT, state.preset)
        assertTrue(state.canStart)
    }

    /** A preset only wins when it reproduces the copied request exactly. */
    @Test
    fun startAgainOnATreadmillWalkReopensThatPreset() {
        val request = StartTimerRequest(
            movement = Movement.WALKING,
            environment = ActivityEnvironment.INDOOR,
            equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
        )

        val state = StartActivityState.of(request)

        assertEquals(ActivityPreset.TREADMILL_WALK, state.preset)
        assertEquals(request.movement, state.request.movement)
        assertEquals(request.environment, state.request.environment)
    }

    /** Everything the six presets do not cover reopens the builder, already filled. */
    @Test
    fun startAgainOnACatalogueActivityReopensTheBuilderFilled() {
        val request = StartTimerRequest(
            movement = Movement.YOGA,
            environment = ActivityEnvironment.INDOOR,
            equipment = listOf(SessionEquipment(EquipmentType.YOGA_MAT)),
        )

        val state = StartActivityState.of(request)

        assertEquals(ActivityPreset.OTHER, state.preset)
        assertEquals(Movement.YOGA, state.movement)
        assertEquals(ActivityEnvironment.INDOOR, state.environment)
        assertEquals(listOf(EquipmentType.YOGA_MAT), state.equipment.map { it.equipmentType })
        assertEquals(request, state.request)
    }

    @Test
    fun startAgainKeepsAFreeName() {
        val request = StartTimerRequest(
            movement = Movement.OTHER,
            customMovementName = "Padel",
            environment = ActivityEnvironment.OUTDOOR,
        )

        val state = StartActivityState.of(request)

        assertEquals(ActivityPreset.OTHER, state.preset)
        assertEquals("Padel", state.request.customMovementName)
        assertEquals(request, state.request)
    }

    /**
     * A walk recorded outdoors on a treadmill is not `Treadmill walk`: the preset would move it
     * indoors, so the builder keeps it instead. Nothing measured once is changed on the way to
     * measuring it again.
     */
    @Test
    fun startAgainRefusesAPresetThatWouldChangeTheRequest() {
        val request = StartTimerRequest(
            movement = Movement.WALKING,
            environment = ActivityEnvironment.OUTDOOR,
            equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
        )

        val state = StartActivityState.of(request)

        assertEquals(ActivityPreset.OTHER, state.preset)
        assertEquals(request, state.request)
    }

    // endregion

    // region the builder's validation and its catalogue

    @Test
    fun theBuilderRefusesToStartWithNoActivity() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)

        assertFalse(state.canStart)
        assertFalse(state.validate())
        assertEquals(LogActivityMessages.MOVEMENT_REQUIRED, state.movementError)
        assertEquals(LogActivityMessages.CHOOSE_FROM_CATALOGUE, state.mainActivityLabel)
    }

    @Test
    fun choosingAnActivityClearsTheRefusal() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.validate()

        state.openMovementPicker()
        state.onCatalogEntrySelected(Movement.HIKING.id)

        assertNull(state.movementError)
        assertTrue(state.validate())
    }

    /** FR-ACTIVITY-008: equipment is `Select one or more`, so a row toggles and the sheet stays. */
    @Test
    fun anEquipmentRowToggles() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openEquipmentPicker()

        state.onCatalogEntrySelected(EquipmentType.KETTLEBELL.id)
        assertEquals(1, state.equipment.size)
        assertNotNull("the sheet stays open for a second choice", state.picker)

        state.onCatalogEntrySelected(EquipmentType.KETTLEBELL.id)
        assertTrue(state.equipment.isEmpty())
    }

    /** PRD FR-ACTIVITY-008: names fold, so `Treadmill` and `treadmill` are the same item. */
    @Test
    fun aCreatedNameAlreadyOnTheActivityIsRefused() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openEquipmentPicker()
        state.onCatalogEntrySelected(EquipmentType.TREADMILL.id)

        state.onPickerQueryChange("treadmill")
        state.onCreateFromSearch()

        assertEquals(1, state.equipment.size)
        assertEquals(LogActivityMessages.ALREADY_ADDED, state.picker?.notice)
    }

    @Test
    fun removingAnEquipmentRenumbersTheRest() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openEquipmentPicker()
        state.onCatalogEntrySelected(EquipmentType.BARBELL.id)
        state.onCatalogEntrySelected(EquipmentType.DUMBBELLS.id)

        state.removeEquipment(0)

        assertEquals(listOf(EquipmentType.DUMBBELLS), state.equipment.map { it.equipmentType })
        assertEquals(listOf(0), state.equipment.map { it.position })
    }

    /** The catalogue is filtered in the holder, never in composition. */
    @Test
    fun theCatalogueFiltersOnTheQuery() {
        val state = StartActivityState()
        state.openMovementPicker()
        val all = requireNotNull(state.picker).results.size

        state.onPickerQueryChange("yog")

        val picker = requireNotNull(state.picker)
        assertEquals(CatalogTarget.MOVEMENT, picker.target)
        assertTrue(picker.results.size < all)
        assertTrue(picker.results.all { it.name.contains("Yog", ignoreCase = true) })
    }

    /** PRD 6.2: the six presets are the shortcut, so the catalogue never repeats them. */
    @Test
    fun theCatalogueNeverOffersAPreset() {
        val state = StartActivityState()
        state.openMovementPicker()

        val offered = requireNotNull(state.picker).results.map { it.id }
        ActivityPreset.entries.mapNotNull { it.movement }.forEach { movement ->
            assertFalse("$movement is already a preset", offered.contains(movement.id))
        }
    }

    @Test
    fun theChosenMovementIsMarkedInTheCatalogue() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openMovementPicker()
        state.onCatalogEntrySelected(Movement.ROWING.id)
        state.openMovementPicker()

        val rowing = requireNotNull(state.picker).results.single { it.id == Movement.ROWING.id }
        assertTrue(rowing.selected)
    }

    // endregion

    // region what the summary card says

    @Test
    fun theSummaryNamesWhatIsAboutToStart() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.TREADMILL_WALK)

        assertEquals("Treadmill walk", state.activityLabel)
        assertEquals("Indoor · Treadmill", state.contextLabel)
    }

    @Test
    fun theSummaryFollowsTheBuilder() {
        val state = StartActivityState()
        state.selectPreset(ActivityPreset.OTHER)
        state.openMovementPicker()
        state.onCatalogEntrySelected(Movement.YOGA.id)
        state.selectEnvironment(ActivityEnvironment.INDOOR)

        assertEquals("Yoga", state.activityLabel)
        assertEquals("Indoor", state.contextLabel)
    }

    // endregion
}
