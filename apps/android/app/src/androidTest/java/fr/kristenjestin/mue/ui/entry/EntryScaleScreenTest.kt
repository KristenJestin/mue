package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
                // Un doigt sur la règle est une reprise en main : le `ViewModel` coupe le flux,
                // retire la provenance et clôt la session (FR-SCALE-022, BR-SCALE-013). Le harnais
                // en fait autant, sans quoi l'écran testé ne serait pas celui de l'application.
                onWeightChange = { state = state.copy(weight = it, scale = state.scale.taken()) },
                onStep = { steps ->
                    state = state.copy(
                        weight = Weight.ofHundredthsClamped(
                            RulerPhysics.step(state.weight.hundredthsKg, steps)
                        ),
                        weightRevision = state.weightRevision + 1,
                        scale = state.scale.taken(),
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
                indicator = null,
                liveHundredths = null,
                status = null,
                fromScale = true,
                arrivalRevision = state.weightRevision + 1,
                announcement = EntryScaleAnnouncement.MEASUREMENT_RECEIVED,
            ),
        )
        composeRule.waitForIdle()
    }

    /** Le flux instable, tel que le `ViewModel` le publie : hors de la valeur enregistrable. */
    private fun stream(hundredths: Int) {
        state = state.copy(
            scale = state.scale.copy(
                paired = true,
                indicator = EntryScaleIndicator.MEASURING,
                liveHundredths = hundredths,
                status = null,
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
        composeRule.onNodeWithTag(ScaleTestTags.SAVE_BLOCKED_REASON).assertDoesNotExist()

        // Et l'écran du PRD socle est intact, jusqu'à ses trois contrôles, qui restent actifs.
        composeRule.onNodeWithText("Where are you today?").assertIsDisplayed()
        composeRule.onNodeWithText("SLIDE TO ADJUST").assertIsDisplayed()
        composeRule.onNodeWithText("Save measurement").assertIsEnabled()
        composeRule.onNodeWithContentDescription(INCREASE).assertIsEnabled()
        composeRule.onNodeWithContentDescription(DECREASE).assertIsEnabled()
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
     * Exposé veut dire nommé : sans libellé de zone, une pastille qui dit `Measuring` ne dit pas de
     * quoi elle parle. Et il ne s'agit que d'un libellé — la pastille change à chaque état de la
     * session, donc elle n'est **pas** une région active. C'est la seconde moitié de la même phrase
     * du PRD : jamais à chaque trame reçue.
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

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                ScaleMessages.SCALE_STATUS_LABEL,
            )
        )
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
        )
    }

    /** PRD_SCALE 20 : l'état actionnable est la même pastille, donc la même zone nommée. */
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

    /**
     * PRD_SCALE 11 : la valeur suit le flux, et le flux ne devient jamais la valeur enregistrable.
     *
     * Les deux moitiés se vérifient dans le même test parce qu'elles ne valent que l'une par
     * l'autre : le grand chiffre affiche bien la trame — sans quoi l'écran paraîtrait gelé
     * pendant qu'on est debout sur la balance —, et `state.weight`, qui est ce que
     * `Save measurement` enregistre, n'a pas bougé d'un centième.
     */
    @Test
    fun the_unstable_stream_moves_the_readout_and_not_the_savable_value() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)

        stream(8_490)
        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_490))
            )
            .assertIsDisplayed()

        stream(8_575)
        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_575))
            )
            .assertIsDisplayed()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.NOT_FINAL_YET).assertIsDisplayed()
        assertEquals(START_HUNDREDTHS, state.weight.hundredthsKg)
    }

    /**
     * PRD_SCALE 19 : une fois le poids reçu, la pastille perd son libellé et ne garde que sa
     * couleur et son point — mais elle continue de se dire en entier à un lecteur d'écran.
     */
    @Test
    fun the_link_chip_drops_its_label_once_the_weight_has_landed() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)

        stream(8_100)
        composeRule.onNodeWithText(ScaleMessages.MEASURING).assertIsDisplayed()

        receive(8_120)

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertExists()
        composeRule.onNodeWithText(ScaleMessages.MEASURING).assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(ScaleMessages.LINK_WEIGHT_RECEIVED)
            .assertExists()
    }

    /**
     * BR-SCALE-011 et §7.3 : exactement trois choses s'éteignent pendant le flux, et rien d'autre.
     *
     * Les trois sont celles qui se battraient avec la balance pour la même valeur. Verrouiller le
     * reste ferait de la balance un maître, ce que ces deux règles interdisent — et la seconde
     * moitié du test est donc aussi importante que la première.
     */
    @Test
    fun only_the_three_controls_that_fight_the_stream_go_quiet() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)

        stream(8_575)

        composeRule.onNodeWithContentDescription(INCREASE).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DECREASE).assertIsNotEnabled()
        composeRule.onNodeWithText("Save measurement").assertIsNotEnabled()
        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_575))
            )
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(ScaleTestTags.SAVE_BLOCKED_REASON).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.WAITING_TO_SETTLE).assertIsDisplayed()

        // Et le reste vit. La règle d'abord : c'est par elle qu'on reprend la valeur.
        composeRule.onNodeWithContentDescription("Weight scale").assertIsEnabled()
        composeRule.onNodeWithText(EntryFormat.date(TODAY)).performClick()
        composeRule.waitForIdle()
        assertTrue(state.datePickerVisible)
        assertEquals(0, saveCount)
    }

    /**
     * FR-SCALE-022 et BR-SCALE-013 : un glissement pendant la mesure reprend la valeur, et l'écran
     * rend aussitôt les trois contrôles.
     */
    @Test
    fun a_drag_during_the_stream_takes_the_value_back() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)
        stream(8_575)

        composeRule.onNodeWithContentDescription("Weight scale").performTouchInput {
            swipe(start = center, end = Offset(center.x + 200f, center.y), durationMillis = 120L)
        }
        composeRule.waitForIdle()

        assertTrue(
            "expected the finger's value, got ${state.weight.hundredthsKg}",
            state.weight.hundredthsKg < 8_575,
        )
        composeRule.onNodeWithTag(ScaleTestTags.SAVE_BLOCKED_REASON).assertDoesNotExist()
        composeRule.onNodeWithText("Save measurement").assertIsEnabled()
        composeRule.onNodeWithContentDescription(INCREASE).assertIsEnabled()
        composeRule.onNodeWithTag(ScaleTestTags.SOURCE_MARK).assertDoesNotExist()
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

    /**
     * PRD_SCALE 18.5 : la phrase existe toujours mot pour mot, point médian compris.
     *
     * Elle a changé de rôle en montant dans l'en-tête : la pastille *montre* deux mots, parce
     * qu'un coin d'écran n'est pas une ligne de texte, et *dit* la phrase entière — c'est son nom
     * accessible, donc ce qu'un lecteur d'écran lit et ce qu'un test peut exiger.
     */
    @Test
    fun bluetooth_off_is_offered_word_for_word_and_is_actionable() {
        state = state.copy(
            scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.BLUETOOTH_OFF),
        )
        start()

        composeRule.onNodeWithText(ScaleMessages.LINK_BLUETOOTH_OFF).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Bluetooth is off · Enable").assertExists()
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

        composeRule.onNodeWithText(ScaleMessages.LINK_UNAVAILABLE).assertIsDisplayed()
        // PRD_SCALE 20 : ce qui est annoncé rassure, parce que rien n'est bloqué.
        composeRule
            .onNodeWithContentDescription(ScaleMessages.UNAVAILABLE_ANNOUNCEMENT)
            .assertExists()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            )
        )
        assertTrue("rien ne s'ouvre sans un geste (FR-SCALE-025)", statusActions.isEmpty())
    }

    @Test
    fun scale_not_found_offers_another_session() {
        state = state.copy(
            scale = EntryScaleUiState(paired = true, status = EntryScaleStatus.NOT_FOUND),
        )
        start()

        composeRule.onNodeWithText(ScaleMessages.LINK_TRY_AGAIN).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Scale not found · Try again").assertExists()
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

/**
 * La reprise en main de BR-SCALE-013, telle que `EntryViewModel.takeValueBack` l'écrit.
 *
 * Recopiée ici plutôt qu'appelée, parce que ces tests pilotent `EntryContent` sans `ViewModel` :
 * ce qui est vérifié est ce que l'écran fait de l'état, pas comment l'état a été produit. Ce que
 * cette fonction doit rendre fidèlement est la seule chose dont l'écran dépend — le flux s'arrête
 * et la provenance part, ensemble.
 */
private fun EntryScaleUiState.taken(): EntryScaleUiState =
    copy(indicator = null, liveHundredths = null, fromScale = false, outOfRange = false)
