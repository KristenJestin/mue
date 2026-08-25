package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.advanceToTheQuietButton
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
private const val START_HUNDREDTHS = 7_405

/**
 * Screen-level behaviour of PRD FR-ENTRY-001 to 007.
 *
 * The state is hoisted into the test so every assertion is about what the user sees and does,
 * with the ViewModel's own rules covered separately by the JVM tests.
 */
class EntryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(
        EntryUiState(weight = Weight.ofHundredthsClamped(START_HUNDREDTHS), date = TODAY, today = TODAY)
    )

    /** How often the scale has handed a value back to the screen. */
    private var published = 0

    /** How often the screen has been rebuilt from that value. */
    private var rebuilds = 0

    @Composable
    private fun Harness(reduceMotion: Boolean) {
        rebuilds++
        MueTheme(reduceMotion = reduceMotion) {
            EntryContent(
                state = state,
                onWeightChange = {
                    published++
                    state = state.copy(weight = it)
                },
                onStep = { steps ->
                    state = state.withExternalWeight(
                        Weight.ofHundredthsClamped(
                            RulerPhysics.step(state.weight.hundredthsKg, steps)
                        )
                    )
                },
                onOpenManualEntry = {
                    state = state.copy(
                        manualEntry = true,
                        manualInput = EntryFormat.weight(state.weight),
                    )
                },
                onDismissManualEntry = {
                    state = state.copy(manualEntry = false, manualError = null)
                },
                onManualInputChange = { raw -> state = state.withManualInput(raw) },
                onConfirmManualEntry = {
                    val valid = state.manualError == null && state.manualInput.isNotBlank()
                    if (valid) state = state.copy(manualEntry = false)
                    valid
                },
                onOpenDatePicker = { state = state.copy(datePickerVisible = true) },
                onDismissDatePicker = { state = state.copy(datePickerVisible = false) },
                onDateSelected = { state = state.copy(date = it, datePickerVisible = false) },
                onSave = {
                    state = state.copy(
                        justSaved = true,
                        saveFlareCount = state.saveFlareCount + 1,
                    )
                },
                onSaveConfirmationFinished = { state = state.copy(justSaved = false) },
            )
        }
    }

    private fun start(reduceMotion: Boolean = false) {
        composeRule.setContent { Harness(reduceMotion) }
    }

    private fun scale(): SemanticsNodeInteraction =
        composeRule.onNodeWithContentDescription("Weight scale")

    private fun readout(weight: Weight = state.weight): SemanticsNodeInteraction =
        composeRule.onNodeWithContentDescription(EntryFormat.spokenWeight(weight))

    /** Starts at the centre so the drag can never begin on one of the step controls. */
    private fun SemanticsNodeInteraction.dragBy(dx: Float) = performTouchInput {
        swipe(start = center, end = Offset(center.x + dx, center.y), durationMillis = 120L)
    }

    /** Where the ruler lands on the finger's travel alone, with no inertia on top of it. */
    private fun landingAfterDragOf(dx: Float): Int {
        val pixelsPerHundredth =
            with(composeRule.density) { RulerPhysics.DP_PER_HUNDREDTH.dp.toPx() }
        return RulerPhysics.snapToStep(
            START_HUNDREDTHS + RulerPhysics.dragToHundredths(dx, pixelsPerHundredth)
        )
    }

    // --- FR-ENTRY-002, the readout and the scale -------------------------------------

    @Test
    fun the_hero_readout_shows_the_weight_and_the_slide_hint() {
        start()
        readout().assertIsDisplayed()
        composeRule.onNodeWithText("SLIDE TO ADJUST").assertIsDisplayed()
    }

    @Test
    fun the_scale_is_exposed_as_an_adjustable_control() {
        start()
        scale().assertRangeInfoEquals(
            ProgressBarRangeInfo(
                current = START_HUNDREDTHS.toFloat(),
                range = RulerPhysics.LOWER_STOP..RulerPhysics.UPPER_STOP,
                steps = (Weight.MAX_HUNDREDTHS - Weight.MIN_HUNDREDTHS) /
                    Weight.STEP_HUNDREDTHS - 1,
            )
        )
        scale().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(START_HUNDREDTHS)),
            )
        )
    }

    @Test
    fun dragging_left_increases_the_weight() {
        start()
        scale().dragBy(-200f)
        composeRule.waitForIdle()

        assertTrue(
            "expected the weight to grow, got ${state.weight.hundredthsKg}",
            state.weight.hundredthsKg > START_HUNDREDTHS,
        )
    }

    @Test
    fun dragging_right_decreases_the_weight() {
        start()
        scale().dragBy(200f)
        composeRule.waitForIdle()

        assertTrue(
            "expected the weight to fall, got ${state.weight.hundredthsKg}",
            state.weight.hundredthsKg < START_HUNDREDTHS,
        )
    }

    @Test
    fun the_scale_stops_dead_at_the_upper_end_stop() {
        state = state.copy(weight = Weight.ofHundredthsClamped(Weight.MAX_HUNDREDTHS - 10))
        start()
        repeat(3) { scale().dragBy(-300f) }
        composeRule.waitForIdle()

        assertEquals(Weight.MAX_HUNDREDTHS, state.weight.hundredthsKg)
    }

    @Test
    fun the_scale_stops_dead_at_the_lower_end_stop() {
        state = state.copy(weight = Weight.ofHundredthsClamped(Weight.MIN_HUNDREDTHS + 10))
        start()
        repeat(3) { scale().dragBy(300f) }
        composeRule.waitForIdle()

        assertEquals(Weight.MIN_HUNDREDTHS, state.weight.hundredthsKg)
    }

    /**
     * PRD 16.2: the scale must follow the finger with no perceptible lag.
     *
     * The way it fails is not obvious from the outside, so it is pinned here. Publishing the
     * position on every step put a state round trip and a rebuild of the whole screen between
     * two frames of a drag — around sixty of each for one sweep. The scale reports once, when
     * it stops, and the readout follows the finger from the scale's own state instead.
     */
    @Test
    fun a_drag_reports_its_value_once_and_rebuilds_the_screen_once() {
        start()
        val before = rebuilds
        published = 0

        scale().dragBy(-200f)
        composeRule.waitForIdle()

        assertEquals("the scale reported mid-drag", 1, published)
        assertTrue(
            "the screen was rebuilt ${rebuilds - before} times for one drag",
            rebuilds - before <= 2,
        )
    }

    /** A flick with animations on glides on past the finger — the inertia of FR-ENTRY-002. */
    @Test
    fun a_flick_carries_the_scale_past_the_finger() {
        start()
        scale().dragBy(-200f)
        composeRule.waitForIdle()

        assertTrue(
            "expected inertia beyond ${landingAfterDragOf(-200f)}, got ${state.weight.hundredthsKg}",
            state.weight.hundredthsKg > landingAfterDragOf(-200f),
        )
    }

    /**
     * PRD 14: reduced animations drop the inertia, but the magnetism is an input aid and
     * stays. The exact landing is what proves the reduced path ran — the same flick with
     * animations on overshoots it.
     */
    @Test
    fun the_magnetism_survives_reduced_animations() {
        start(reduceMotion = true)
        scale().dragBy(-200f)
        composeRule.waitForIdle()

        assertEquals(landingAfterDragOf(-200f), state.weight.hundredthsKg)
        readout().assertIsDisplayed()
    }

    // --- FR-ENTRY-003, the accessible controls ---------------------------------------

    @Test
    fun the_plus_and_minus_controls_are_always_visible() {
        start()
        composeRule.onNodeWithContentDescription(INCREASE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DECREASE).assertIsDisplayed()
    }

    /**
     * They flank the readout, not the graduations: the eye is already on the number while
     * adjusting, and the scale keeps the whole width.
     */
    @Test
    fun the_controls_sit_level_with_the_readout() {
        start()
        val value = readout().getUnclippedBoundsInRoot()

        listOf(DECREASE, INCREASE).forEach { label ->
            val control = composeRule.onNodeWithContentDescription(label)
                .getUnclippedBoundsInRoot()
            val centre = control.top + control.height / 2
            assertTrue(
                "$label is at $centre, outside the readout's ${value.top}..${value.bottom}",
                centre >= value.top && centre <= value.bottom,
            )
        }
    }

    @Test
    fun the_controls_keep_a_full_touch_target() {
        start()
        listOf(DECREASE, INCREASE).forEach { label ->
            val size = composeRule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue(
                "$label is only ${size.width} x ${size.height}",
                size.width >= 48.dp && size.height >= 48.dp,
            )
        }
    }

    /** Nothing crowds the scale any more, so every pixel of width is a pixel of ruler. */
    @Test
    fun the_scale_runs_the_full_width_of_the_screen() {
        start()
        val screen = composeRule.onRoot().getUnclippedBoundsInRoot().width
        val ruler = scale().getUnclippedBoundsInRoot().width
        assertEquals(screen.value, ruler.value, 1f)
    }

    @Test
    fun one_press_of_plus_moves_the_weight_one_step() {
        start()
        composeRule.onNodeWithContentDescription(INCREASE).performClick()
        composeRule.waitForIdle()

        assertEquals(START_HUNDREDTHS + Weight.STEP_HUNDREDTHS, state.weight.hundredthsKg)
    }

    @Test
    fun one_press_of_minus_moves_the_weight_one_step() {
        start()
        composeRule.onNodeWithContentDescription(DECREASE).performClick()
        composeRule.waitForIdle()

        assertEquals(START_HUNDREDTHS - Weight.STEP_HUNDREDTHS, state.weight.hundredthsKg)
    }

    /**
     * The readout carries the second decimal a 0.05 kg step needs (PRD FR-ENTRY-002).
     *
     * Asserted through the description rather than the glyphs: while the number rolls, each
     * digit is its own node and no single node holds `74.05`.
     */
    @Test
    fun the_readout_shows_two_decimals() {
        start()
        composeRule.onNodeWithContentDescription("74.05 kilograms").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(INCREASE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("74.10 kilograms").assertIsDisplayed()
    }

    @Test
    fun the_controls_stop_at_the_end_stop() {
        state = state.copy(weight = Weight.ofHundredthsClamped(Weight.MIN_HUNDREDTHS))
        start()
        composeRule.onNodeWithContentDescription(DECREASE).performClick()
        composeRule.waitForIdle()

        assertEquals(Weight.MIN_HUNDREDTHS, state.weight.hundredthsKg)
    }

    // --- FR-ENTRY-004, manual entry --------------------------------------------------

    @Test
    fun touching_the_value_opens_the_keyboard_field() {
        start()
        readout().performClick()
        composeRule.waitForIdle()

        assertTrue(state.manualEntry)
        composeRule.onNodeWithText("TYPE YOUR WEIGHT").assertIsDisplayed()
        composeRule.onNodeWithText("Weight in kilograms").assertIsDisplayed()
    }

    @Test
    fun the_scale_leaves_the_screen_during_manual_entry() {
        state = state.copy(manualEntry = true, manualInput = "74.05")
        start()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Weight scale").assertDoesNotExist()
    }

    @Test
    fun a_comma_is_accepted_whatever_the_phone_language_is() {
        state = state.copy(manualEntry = true, manualInput = "")
        start()
        composeRule.onNode(hasSetTextAction()).performTextInput("81,3")
        composeRule.waitForIdle()

        assertEquals(8_130, state.weight.hundredthsKg)
    }

    @Test
    fun a_dot_is_accepted_whatever_the_phone_language_is() {
        state = state.copy(manualEntry = true, manualInput = "")
        start()
        composeRule.onNode(hasSetTextAction()).performTextInput("81.3")
        composeRule.waitForIdle()

        assertEquals(8_130, state.weight.hundredthsKg)
    }

    @Test
    fun an_out_of_range_value_shows_the_exact_message_and_is_kept() {
        state = state.copy(manualEntry = true, manualInput = "")
        start()
        composeRule.onNode(hasSetTextAction()).performTextInput("999")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(MueValidation.WEIGHT_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("999").assertIsDisplayed()
        assertEquals(START_HUNDREDTHS, state.weight.hundredthsKg)
    }

    @Test
    fun touching_the_value_again_returns_to_the_scale() {
        state = state.copy(manualEntry = true, manualInput = "74.05")
        start()
        readout().performClick()
        composeRule.waitForIdle()

        assertTrue(!state.manualEntry)
        composeRule.onNodeWithText("SLIDE TO ADJUST").assertIsDisplayed()
    }

    @Test
    fun correcting_a_rejected_value_clears_the_message() {
        state = state.copy(manualEntry = true, manualInput = "")
        start()
        composeRule.onNode(hasSetTextAction()).performTextInput("999")
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("99.9")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(MueValidation.WEIGHT_ERROR).assertDoesNotExist()
        assertEquals(9_990, state.weight.hundredthsKg)
    }

    // --- FR-ENTRY-005, the date ------------------------------------------------------

    @Test
    fun the_date_row_shows_the_date_and_opens_the_picker() {
        start()
        composeRule.onNodeWithText("Measurement date").assertIsDisplayed()
        composeRule.onNodeWithText(EntryFormat.date(TODAY)).assertIsDisplayed()

        composeRule.onNodeWithText(EntryFormat.date(TODAY)).performClick()
        composeRule.waitForIdle()

        assertTrue(state.datePickerVisible)
    }

    @Test
    fun the_header_chip_says_Today_only_on_today() {
        start()
        composeRule.onNodeWithText("Today").assertIsDisplayed()

        state = state.copy(date = TODAY.minusDays(3))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Today").assertDoesNotExist()
    }

    @Test
    fun choosing_another_date_leaves_the_weight_untouched() {
        start()
        val before = state.weight
        state = state.copy(date = TODAY.minusDays(5))
        composeRule.waitForIdle()

        assertEquals(before, state.weight)
        readout(before).assertIsDisplayed()
    }

    // --- FR-ENTRY-006, saving --------------------------------------------------------

    @Test
    fun saving_confirms_without_leaving_the_screen() {
        start()
        // The confirmation runs on the frame clock, so the test drives that clock rather
        // than letting an idle wait run the whole second in one go.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Save measurement").performClick()
        composeRule.advanceToTheQuietButton()

        composeRule.onNodeWithText(MueSaveConfirmationLabel).assertIsDisplayed()
        composeRule.onNodeWithText("Where are you today?").assertIsDisplayed()
    }

    /** PRD 14 drops the halo and the ruler echo, never the confirmation itself. */
    @Test
    fun saving_still_confirms_with_animations_reduced() {
        start(reduceMotion = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Save measurement").performClick()
        composeRule.advanceToTheQuietButton()

        composeRule.onNodeWithText(MueSaveConfirmationLabel).assertIsDisplayed()
    }

    // --- FR-ENTRY-007, the greeting --------------------------------------------------

    @Test
    fun the_greeting_appears_with_a_display_name() {
        state = state.copy(greeting = "Hello Kris,")
        start()

        composeRule.onNodeWithText("Hello Kris,").assertIsDisplayed()
        composeRule.onNodeWithText("Where are you today?").assertIsDisplayed()
    }

    @Test
    fun the_greeting_line_disappears_without_a_display_name() {
        start()

        composeRule.onNodeWithText("Hello Kris,").assertDoesNotExist()
        composeRule.onNodeWithText("Where are you today?").assertIsDisplayed()
    }

    private companion object {
        const val INCREASE = "Increase weight by 0.05 kilograms"
        const val DECREASE = "Decrease weight by 0.05 kilograms"
    }
}

/** Mirrors the ViewModel's live parsing closely enough to exercise the screen's wiring. */
private fun EntryUiState.withManualInput(raw: String): EntryUiState {
    if (raw.isBlank()) return copy(manualInput = raw, manualError = null)
    val parsed = Weight.ofKilogramsOrNull(raw.replace(',', '.').toDoubleOrNull() ?: Double.NaN)
    val base = if (parsed == null) this else withExternalWeight(parsed)
    return base.copy(
        manualInput = raw,
        manualError = if (parsed == null) MueValidation.WEIGHT_ERROR else null,
    )
}

/** Anything that is not the scale itself has to announce itself, exactly as the ViewModel does. */
private fun EntryUiState.withExternalWeight(weight: Weight): EntryUiState =
    copy(weight = weight, weightRevision = weightRevision + 1)
