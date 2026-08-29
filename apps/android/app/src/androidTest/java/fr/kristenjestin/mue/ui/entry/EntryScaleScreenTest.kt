package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
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

    private val actions = mutableListOf<EntryScaleAction>()

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
                onScaleAction = { actions += it },
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

        // Les cinq poignées que ce module peut poser sur `Entry`, et pas une de plus : un tag mort
        // asserté ici passerait en n'observant rien, ce qui est la façon exacte dont ce test
        // pourrait cesser de vouloir dire quelque chose.
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.OUT_OF_RANGE_NOTICE).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.BAREFOOT_HINT).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.SAVE_BLOCKED_REASON).assertDoesNotExist()
        assertTrue("rien n'est annoncé sans balance", announcedArrivals() == 0)

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

    /**
     * FR-SCALE-022, **écart assumé** : la provenance est indiquée par la pastille, et par elle
     * seule.
     *
     * La ligne `From your scale` sous la valeur a disparu. Elle répétait ce que l'ambre de l'en-tête
     * dit déjà — cette pastille n'est allumée que pour cette raison — au seul endroit où
     * PRD_SCALE 19 interdit qu'on concurrence le poids. Ce que ce test vérifie est donc l'ensemble
     * du repli : la valeur atterrit, la pastille la porte et la dit, et la légende sous le chiffre
     * revient à `SLIDE TO ADJUST` au lieu de laisser un trou — ce qui est aussi ce que
     * FR-SCALE-022 veut faire comprendre d'une valeur reçue : elle est à vous, tout de suite.
     */
    @Test
    fun a_received_measurement_lands_on_the_ruler_and_the_chip_carries_its_provenance() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start()

        receive(8_120)

        composeRule
            .onNodeWithContentDescription(
                EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_120))
            )
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertExists()
        composeRule.onNodeWithText("SLIDE TO ADJUST").assertIsDisplayed()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_INDICATOR).assertDoesNotExist()
    }

    /**
     * PRD_SCALE 20 : l'arrivée d'une mesure stable est annoncée **avec sa valeur**, une fois.
     *
     * Le test qui manquait, et qui rendait le retrait de la marque de provenance dangereux : la
     * marque était la région vivante qui portait cette annonce, et les tests qui la vérifiaient
     * interrogeaient son tag. La supprimer sans replanter l'annonce aurait fait perdre l'exigence
     * en laissant la suite verte.
     *
     * Il ne nomme donc **aucun porteur**. Il cherche les nœuds qui annoncent une arrivée, où qu'ils
     * soient, et exige qu'il y en ait exactement un, que sa description contienne la valeur
     * affichée, et qu'il soit une région active polie. Un déménagement de l'annonce le laisse vert ;
     * sa disparition, sa duplication ou sa répétition le font échouer.
     */
    @Test
    fun the_arrival_is_announced_once_with_its_value_whoever_carries_it() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)

        // Une trame n'annonce rien : sinon le lecteur d'écran parlerait plusieurs fois par seconde
        // pendant qu'on monte sur la balance (PRD_SCALE 20, « jamais une trame »).
        stream(8_490)
        assertTrue("une trame instable n'annonce rien", announcedArrivals() == 0)
        stream(8_575)
        assertTrue("une trame instable n'annonce rien", announcedArrivals() == 0)

        receive(8_120)

        val spoken = EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_120))
        val announced = ScaleMessages.measurementReceivedThenTryAgain(spoken)

        assertEquals("une arrivée, un seul porteur", 1, announcedArrivals())
        composeRule.onNodeWithContentDescription(announced).assertExists()
        composeRule.onNodeWithContentDescription(announced).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )

        /*
         * Et une recomposition ne reparle pas. Ce qui fait taire une région active est que sa
         * sémantique ne bouge pas : le seul moyen de l'éprouver est donc de forcer une
         * recomposition qui change autre chose — le salut, ici — et de retrouver la même annonce,
         * une seule fois et au mot près.
         */
        state = state.copy(greeting = "Good evening,")
        composeRule.waitForIdle()

        assertEquals("une recomposition ne réannonce rien", 1, announcedArrivals())
        composeRule.onNodeWithContentDescription(announced).assertExists()
    }

    /** FR-SCALE-022 : la valeur reçue reste modifiable, et la provenance part avec l'annonce. */
    @Test
    fun taking_the_value_back_ends_the_provenance_and_its_announcement() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)
        receive(8_120)

        composeRule.onNodeWithContentDescription(INCREASE).performClick()
        composeRule.waitForIdle()

        assertEquals(8_125, state.weight.hundredthsKg)
        assertTrue("la valeur reprise n'annonce plus une arrivée", announcedArrivals() == 0)
        // La pastille redevient ce qu'elle offre, et rien d'autre (FR-SCALE-023).
        composeRule.onNodeWithContentDescription(ScaleMessages.LINK_SEARCH_AGAIN).assertExists()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).not()
        )
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
     * FR-SCALE-023 : une fois le poids enregistré, la pastille **montre** ce qu'elle offre.
     *
     * Elle perdait son libellé en arrivant ici, ce qui allait tant qu'elle n'était que la couleur
     * et le point de PRD_SCALE 19. Elle relance une recherche depuis que `Try again` ne dépend plus
     * du délai de deux minutes, et une cible tactile invisible n'aide personne : le libellé, la
     * taille de la cible et le nom accessible sont les trois faces de la même affordance, donc les
     * trois assertions de ce test.
     *
     * L'enregistrement passe par le vrai bouton et se compte avec [saveCount] : `justSaved` est un
     * drapeau que `MuePrimaryButton` éteint lui-même, et le `waitForIdle` d'après le clic laisse
     * l'horloge l'atteindre. L'état de la balance, lui, ne bouge pas — `EntryViewModel.onSave`
     * clôt la session sans toucher à la provenance, ce que le harnais reproduit en ne touchant à
     * rien.
     */
    @Test
    fun the_link_chip_offers_another_search_once_the_weight_is_saved() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)

        stream(8_100)
        composeRule.onNodeWithText(ScaleMessages.MEASURING).assertIsDisplayed()

        receive(8_120)
        composeRule.onNodeWithText("Save measurement").performClick()
        composeRule.waitForIdle()
        assertEquals(1, saveCount)

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertExists()
        composeRule.onNodeWithText(ScaleMessages.MEASURING).assertDoesNotExist()
        composeRule.onNodeWithText(ScaleMessages.LINK_TRY_AGAIN).assertIsDisplayed()
        assertTheChipIsATouchTarget()

        /*
         * La provenance n'a pas quitté l'écran pour autant (FR-SCALE-022) : elle est sur la
         * pastille, avec l'annonce de PRD_SCALE 20 — et l'offre est dite dans la même phrase, sans
         * quoi le seul chemin vers une nouvelle pesée serait un bouton sans nom pour un lecteur
         * d'écran, ce que FR-SCALE-023 interdit.
         */
        val spoken = EntryFormat.spokenWeight(Weight.ofHundredthsClamped(8_120))
        composeRule
            .onNodeWithContentDescription(ScaleMessages.measurementReceivedThenTryAgain(spoken))
            .assertExists()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(EntryScaleAction.RESTART_SEARCH), actions)
    }

    /**
     * FR-SCALE-023 : les deux autres culs-de-sac, vus de l'écran.
     *
     * La reprise en main et la mesure hors bornes laissent la même pastille que l'enregistrement,
     * à la couleur près — la valeur affichée n'appartient plus à la balance — et surtout à la même
     * taille de cible. Les deux sont dans le même test parce que ce qui est vérifié est justement
     * qu'ils se ressemblent : trois chemins, une seule offre.
     */
    @Test
    fun the_link_chip_offers_another_search_after_a_take_back_and_out_of_range() {
        state = state.copy(scale = EntryScaleUiState(paired = true))
        start(reduceMotion = true)
        receive(8_120)

        composeRule.onNodeWithContentDescription(INCREASE).performClick()
        composeRule.waitForIdle()

        assertTrue("la valeur reprise n'annonce plus une arrivée", announcedArrivals() == 0)
        composeRule.onNodeWithText(ScaleMessages.LINK_TRY_AGAIN).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ScaleMessages.LINK_SEARCH_AGAIN).assertExists()
        assertTheChipIsATouchTarget()

        // FR-SCALE-024 : le refus s'affiche, l'écran ne change pas, et la pastille reste une offre.
        state = state.copy(scale = state.scale.copy(outOfRange = true))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ScaleTestTags.OUT_OF_RANGE_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.LINK_TRY_AGAIN).assertIsDisplayed()
        assertTheChipIsATouchTarget()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(EntryScaleAction.RESTART_SEARCH), actions)
    }

    /**
     * L'autre moitié : pendant une session, la pastille n'est pas un bouton.
     *
     * Rien à relancer, donc rien à toucher — et surtout pas de cible tactile posée sur ce qui est
     * en train d'aboutir. L'absence d'action se vérifie par l'action manquante et non par une
     * hauteur : c'est elle que le doigt et le lecteur d'écran rencontrent, à toute taille de police.
     */
    @Test
    fun a_live_session_offers_nothing_to_tap() {
        state = state.copy(
            scale = EntryScaleUiState(
                paired = true,
                indicator = EntryScaleIndicator.SEARCHING,
            ),
        )
        start(reduceMotion = true)

        composeRule.onNodeWithText(ScaleMessages.LINK_SEARCHING).assertIsDisplayed()
        composeRule.onNodeWithText(ScaleMessages.LINK_TRY_AGAIN).assertDoesNotExist()
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertHasNoClickAction()

        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).performClick()
        composeRule.waitForIdle()
        assertTrue("une session en cours ne propose rien", actions.isEmpty())
    }

    /**
     * Ce que l'écran annonce comme une arrivée, **sans nommer le nœud qui le fait**.
     *
     * C'est la forme même de ces assertions qui compte. L'annonce de PRD_SCALE 20 vivait sur la
     * marque de provenance, et tous les tests qui la vérifiaient passaient par le tag de cette
     * marque : le jour où elle a été retirée, ils auraient pu être supprimés « avec elle » sans que
     * personne ne voie partir l'exigence. En cherchant la phrase plutôt que son support, ce
     * helper survit au prochain déménagement et fait échouer la prochaine disparition.
     *
     * Le fragment cherché est dérivé de [ScaleMessages.measurementReceived] au lieu d'être recopié,
     * pour que ce fichier échoue si la phrase change et non s'il perd le fil d'une copie.
     */
    private fun announcedArrivals(): Int =
        composeRule
            .onAllNodesWithContentDescription(RECEIVED, substring = true)
            .fetchSemanticsNodes()
            .size

    /** La cible tactile de FR-SCALE-025, offerte à la relance comme aux quatre états système. */
    private fun assertTheChipIsATouchTarget() {
        composeRule.onNodeWithTag(ScaleTestTags.ENTRY_STATUS).assertHasClickAction()
        val height = composeRule
            .onNodeWithTag(ScaleTestTags.ENTRY_STATUS)
            .getUnclippedBoundsInRoot()
            .height
        assertTrue("the link chip is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
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
        // Un flux repris n'a jamais rien posé : il n'y a pas eu d'arrivée, donc rien à annoncer.
        assertTrue("un flux repris n'annonce aucune arrivée", announcedArrivals() == 0)
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
        // Une mesure refusée n'est pas une arrivée : rien n'est posé, donc rien n'est annoncé.
        assertTrue("une mesure hors bornes n'annonce pas d'arrivée", announcedArrivals() == 0)
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

        assertEquals(listOf(EntryScaleAction.ENABLE_BLUETOOTH), actions)
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
        assertTrue("rien ne s'ouvre sans un geste (FR-SCALE-025)", actions.isEmpty())
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

        assertEquals(listOf(EntryScaleAction.RESTART_SEARCH), actions)
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

        /** `received from your scale` : la phrase de l'arrivée, sans le poids qui la précède. */
        val RECEIVED: String = ScaleMessages.measurementReceived("").trim()
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
private fun EntryScaleUiState.taken(): EntryScaleUiState = copy(
    indicator = null,
    liveHundredths = null,
    fromScale = false,
    outOfRange = false,
    // L'annonce part avec la provenance, et pour la même raison : elle parle d'une arrivée dont la
    // valeur n'est plus à l'écran. L'omettre ici aurait laissé le harnais dans un état que le
    // `ViewModel` ne produit jamais.
    announcement = null,
)
