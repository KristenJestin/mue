package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)
private const val START_HUNDREDTHS = 7_405

/**
 * Ce que la balance ajoute — ou n'ajoute pas — à l'écran `Entry` (PRD_SCALE 12.2, 18, 19, 20).
 *
 * L'état est hissé dans le test et [EntryContent] est piloté sans `ViewModel`, comme
 * `EntryScreenTest` : les règles de décision sont couvertes en JVM par `EntryScaleTest`, et ce
 * qui se vérifie ici est ce que quelqu'un voit et peut toucher.
 *
 * Le tout premier test est le plus important du fichier. PRD_SCALE 18.1 exige que l'écran de
 * quelqu'un sans balance soit *strictement* celui du PRD socle, et un module qui ajoute quatre
 * surfaces à l'application se juge d'abord à ce qu'il n'ajoute pas.
 */
class EntryScaleScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(
        EntryUiState(
            weight = Weight.ofHundredthsClamped(START_HUNDREDTHS),
            date = TODAY,
            today = TODAY,
        )
    )

    private val statusActions = mutableListOf<EntryScaleStatus>()

    /**
     * Combien de fois `Save measurement` a été activé.
     *
     * `EntryUiState.justSaved` ne peut pas répondre à cette question : c'est un drapeau
     * transitoire que `MuePrimaryButton` éteint de lui-même au bout de
     * `MueMotion.SaveConfirmationMillis`, et le `waitForIdle` qui suit un clic laisse l'horloge
     * l'atteindre. Le lire après coup revient à demander « la confirmation est-elle *encore*
     * affichée ? », ce qui n'est pas ce que ces tests veulent savoir.
     */
    private var saveCount = 0

    @Composable
    private fun Harness(reduceMotion: Boolean) {
        MueTheme(reduceMotion = reduceMotion) {
            EntryContent(
                state = state,
                onWeightChange = { state = state.copy(weight = it) },
                onStep = { steps ->
                    state = state.copy(
                        weight = Weight.ofHundredthsClamped(
                            RulerPhysics.step(state.weight.hundredthsKg, steps)
                        ),
                        weightRevision = state.weightRevision + 1,
                        scale = state.scale.copy(fromScale = false, outOfRange = false),
                    )
                },
                onOpenManualEntry = { state = state.copy(manualEntry = true) },
                onDismissManualEntry = { state = state.copy(manualEntry = false) },
                onManualInputChange = {},
                onConfirmManualEntry = { true },
                onOpenDatePicker = { state = state.copy(datePickerVisible = true) },
                onDismissDatePicker = { state = state.copy(datePickerVisible = false) },
                onDateSelected = { state = state.copy(date = it, datePickerVisible = false) },
                onSave = {
                    saveCount += 1
                    state = state.copy(justSaved = true)
                },
                onSaveConfirmationFinished = { state = state.copy(justSaved = false) },
                onScaleStatusAction = { statusActions += it },
            )
        }
    }

    private fun start(reduceMotion: Boolean = false) {
        composeRule.setContent { Harness(reduceMotion) }
    }

    /** Pose une valeur reçue comme le `ViewModel` la poserait : provenance et révision comprises. */
    private fun receive(hundredths: Int) {
        state = state.copy(
            weight = Weight.ofHundredthsClamped(hundredths),
            weightRevision = state.weightRevision + 1,
            scale = state.scale.copy(
                paired = true,
                fromScale = true,
                arrivalRevision = state.weightRevision + 1,
                announcement = EntryScaleAnnouncement.MEASUREMENT_RECEIVED,
            ),
        )
        composeRule.waitForIdle()
    }

    // --- PRD_SCALE 18.1, sans balance -------------------------------------------------

    @Test
    fun without_a_paired_scale_the_screen_adds_nothing_at_all() {
        start()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.SOURCE_MARK).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.OUT_OF_RANGE_NOTICE).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.BAREFOOT_HINT).assertDoesNotExist()

        // Et l'écran du PRD socle est intact.
        composeRule.onNodeWithText("Where are you today?").assertIsDisplayed()
        composeRule.onNodeWithText("SLIDE TO ADJUST").assertIsDisplayed()
        composeRule.onNodeWithText("Save measurement").assertIsDisplayed()
    }

    /**
     * PRD FR-ENTRY-003, dette du PRD socle : les contrôles `−` et `+` sont exigés en permanence
     * et ne dépendent d'aucune balance. Ce test les verrouille là où on les oublierait.
     */
    @Test
    fun the_step_controls_are_there_with_or_without_a_scale() {
        start()
        composeRule.onNodeWithContentDescription(DECREASE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCREASE).assertIsDisplayed()

        state = state.copy(scale = EntryScaleUiState(paired = true))
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(DECREASE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCREASE).assertIsDisplayed()
    }

    // --- FR-SCALE-022, la mesure reçue ------------------------------------------------

    @Test
    fun a_received_measurement_lands_on_the_ruler_with_its_provenance() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start()

        receive(8_120)

        composeRule.onNodeWithTag(ScaleTestTags.SOURCE_MARK).assertExists()
        composeRule.onNodeWithText(ScaleMessages.FROM_YOUR_SCALE).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_120))
            )
            .assertIsDisplayed()
    }

    /** PRD_SCALE 20 : l'arrivée d'une mesure stable est annoncée **avec sa valeur**. */
    @Test
    fun the_arrival_is_announced_with_its_value() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start()

        receive(8_120)

        val announcement = ScaleMessages.measurementReceived(
            EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_120))
        )
        composeRule.onNodeWithTag(ScaleTestTags.SOURCE_MARK).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            )
        )
        composeRule.onNodeWithContentDescription(announcement).assertExists()
    }

    /** FR-SCALE-022 : la valeur reçue reste entièrement modifiable, et la marque part avec. */
    @Test
    fun taking_the_value_back_removes_the_mark() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)
        receive(8_120)

        composeRule.onNodeWithContentDescription(INCREASE).performClick()
        composeRule.waitForIdle()

        assertEquals(8_125, state.weight.hundredthsKg)
        composeRule.onNodeWithTag(ScaleTestTags.SOURCE_MARK).assertDoesNotExist()
    }

    @Test
    fun a_received_value_can_still_be_typed_over() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start()
        receive(8_120)

        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_120))
            )
            .performClick()
        composeRule.waitForIdle()

        assertTrue(state.manualEntry)
    }

    // --- PRD_SCALE 11, l'indication discrète ------------------------------------------

    @Test
    fun the_search_says_so_quietly_and_the_weight_stays_the_subject() {
        state = state.copy(
            scale = EntryScaleUiState(
                paired = true,
                indicator = EntryScaleIndicator.STEP_ON,
            ),
        )
        start()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.STEP_ON_THE_SCALE).assertIsDisplayed()
        // La valeur reste l'élément principal : elle est plus haute que l'indication
        // (PRD_SCALE 19, « il ne doit jamais concurrencer la valeur du poids »).
        val readout = composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(START_HUNDREDTHS))
            )
            .fetchSemanticsNode()
            .size
        val indicator = composeRule
            .onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR)
            .fetchSemanticsNode()
            .size
        assertTrue(
            "expected the readout to dwarf the indicator, got $readout vs $indicator",
            readout.height > indicator.height,
        )
    }

    /**
     * PRD_SCALE 20 : « l'état de la balance est exposé aux services d'accessibilité ».
     *
     * Exposé veut dire nommé : sans libellé de zone, une ligne qui dit `Connecting` ne dit pas de
     * quoi elle parle. Et il ne s'agit que d'un libellé — cette ligne-ci porte le flux instable,
     * qui change plusieurs fois par seconde, donc elle n'est **pas** une région active. C'est la
     * seconde moitié de la même phrase du PRD : jamais à chaque trame reçue.
     */
    @Test
    fun the_scale_state_is_a_named_region_that_does_not_speak_on_every_frame() {
        state = state.copy(
            scale = EntryScaleUiState(
                paired = true,
                indicator = EntryScaleIndicator.MEASURING,
                liveHundredths = 6_600,
            ),
        )
        start()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                ScaleMessages.SCALE_STATUS_LABEL,
            )
        )
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
        )
    }

    /** PRD_SCALE 20 : la ligne qui prend sa place porte le même libellé de zone. */
    @Test
    fun the_actionable_status_carries_the_same_region_label() {
        state = state.copy(
            scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.BLUETOOTH_OFF),
        )
        start()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                ScaleMessages.SCALE_STATUS_LABEL,
            )
        )
    }

    /** BR-SCALE-001 : le flux instable est visible et n'est jamais posé sur la règle. */
    @Test
    fun an_unstable_stream_never_reaches_the_ruler() {
        state = state.copy(
            scale = EntryScaleUiState(
                paired = true,
                indicator = EntryScaleIndicator.MEASURING,
                liveHundredths = 6_600,
            ),
        )
        start()

        composeRule.onNodeWithText(ScaleMessages.MEASURING).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(START_HUNDREDTHS))
            )
            .assertIsDisplayed()
        assertEquals(START_HUNDREDTHS, state.weight.hundredthsKg)
    }

    // --- FR-SCALE-024 et 18.3 ---------------------------------------------------------

    @Test
    fun an_out_of_range_measurement_says_so_and_changes_nothing() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start()
        val before = state.weight

        state = state.copy(scale = state.scale.copy(outOfRange = true))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ScaleTestTags.OUT_OF_RANGE_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.MEASUREMENT_OUT_OF_RANGE).assertIsDisplayed()
        assertEquals(before, state.weight)
        composeRule.onNodeWithTag(ScaleTestTags.SOURCE_MARK).assertDoesNotExist()
    }

    @Test
    fun the_barefoot_hint_only_shows_when_the_driver_refused_the_impedance() {
        state = state.copy(scale = EntryScaleUiState(paired = true, fromScale = true))
        start()
        composeRule.onNodeWithTag(ScaleTestTags.BAREFOOT_HINT).assertDoesNotExist()

        state = state.copy(scale = state.scale.copy(barefootHint = true))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ScaleTestTags.BAREFOOT_HINT).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.BAREFOOT_HINT).assertIsDisplayed()
    }

    // --- FR-SCALE-025 et PRD_SCALE 18.5, les états actionnables ------------------------

    @Test
    fun bluetooth_off_is_offered_word_for_word_and_is_actionable() {
        state = state.copy(
            scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.BLUETOOTH_OFF),
        )
        start()

        composeRule.onNodeWithText("Bluetooth is off · Enable").assertIsDisplayed()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(EntryScaleStatus.BLUETOOTH_OFF), statusActions)
    }

    @Test
    fun a_missing_permission_points_at_the_settings_without_opening_anything() {
        state = state.copy(
            scale = EntryScaleUiState(
                paired = true,
                status = EntryScaleStatus.PERMISSION_MISSING,
                announcement = EntryScaleAnnouncement.UNAVAILABLE,
            ),
        )
        start()

        composeRule.onNodeWithText("Scale unavailable · Open settings").assertIsDisplayed()
        // PRD_SCALE 20 : ce qui est annoncé rassure, parce que rien n'est bloqué.
        composeRule
            .onNodeWithContentDescription(ScaleMessages.UNAVAILABLE_ANNOUNCEMENT)
            .assertExists()
    }

    @Test
    fun scale_not_found_offers_another_session() {
        state = state.copy(
            scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.NOT_FOUND),
        )
        start()

        composeRule.onNodeWithText("Scale not found · Try again").assertIsDisplayed()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(EntryScaleStatus.NOT_FOUND), statusActions)
    }

    /** BR-SCALE-011 : rien de tout cela ne bloque une pesée saisie à la main. */
    @Test
    fun no_state_of_the_scale_blocks_typing_a_weight_by_hand() {
        state = state.copy(
            scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.NOT_FOUND),
        )
        start()

        composeRule.onNodeWithContentDescription(INCREASE).performClick()
        composeRule.waitForIdle()
        assertEquals(START_HUNDREDTHS + Weight.STEP_HUNDREDTHS, state.weight.hundredthsKg)

        composeRule.onNodeWithText("Save measurement").performClick()
        composeRule.waitForIdle()
        assertEquals(1, saveCount)
    }

    private companion object {
        const val INCREASE = "Increase weight by 0.05 kilograms"
        const val DECREASE = "Decrease weight by 0.05 kilograms"
    }
}
