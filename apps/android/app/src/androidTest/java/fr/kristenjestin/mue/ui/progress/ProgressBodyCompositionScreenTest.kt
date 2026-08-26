package fr.kristenjestin.mue.ui.progress

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BodyCompositionCalculator
import fr.kristenjestin.mue.domain.logic.BodyCompositionFormula
import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.domain.logic.StatisticsCalculator
import fr.kristenjestin.mue.domain.logic.compositionOrNull
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.components.MueBmiCardTags
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/**
 * Couverture Compose de la section de composition corporelle (PRD_SCALE FR-BODY-003, FR-BODY-005,
 * FR-BODY-006, 18.4, 20).
 *
 * L'écran est piloté par sa version **sans état**, [ProgressContent], comme le reste de la suite
 * de cet écran : ce qui est vérifié est ce que la période et le profil mettent à l'écran, jamais
 * la façon dont le ViewModel y est arrivé.
 *
 * Les cartes vivent loin dans un `LazyColumn` qui ne compose que ce qu'il montre, d'où le
 * `performScrollToNode(hasTestTag(...))` avant chaque assertion : sans lui, une carte absente et
 * une carte simplement pas encore composée seraient indiscernables.
 */
class ProgressBodyCompositionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // region les quatre cartes

    /** FR-BODY-003 : quatre estimations, et pas une de plus. */
    @Test
    fun theFourEstimatesAreOnScreen() {
        setContent(state(composition = populatedComposition()))

        listOf(
            ScaleTestTags.BODY_FAT_CARD to ScaleMessages.BODY_FAT,
            ScaleTestTags.FAT_FREE_MASS_CARD to ScaleMessages.FAT_FREE_MASS,
            ScaleTestTags.BODY_WATER_CARD to ScaleMessages.BODY_WATER,
            ScaleTestTags.RESTING_ENERGY_CARD to ScaleMessages.RESTING_ENERGY,
        ).forEach { (tag, label) ->
            scrollTo(tag)
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    /**
     * FR-BODY-003 : **aucune barre de référence à repère**, y compris sans libellé de zone. La
     * carte de l'IMC juste au-dessus en dessine une ; celle-ci est donc mise hors jeu
     * ([Bmi.ValueOnly] n'en a pas) pour que l'assertion globale ne puisse pas être satisfaite par
     * accident, et chaque carte est ensuite fouillée par ascendance.
     */
    @Test
    fun noReferenceBarIsRenderedInTheCompositionSection() {
        setContent(state(composition = populatedComposition(), bmi = Bmi.ValueOnly(23.5)))

        BodyCompositionMetric.entries.forEach { metric ->
            scrollTo(metric.testTag)
            compose.onNode(
                hasTestTag(MueBmiCardTags.REFERENCE_BAR) and
                    hasAnyAncestor(hasTestTag(metric.testTag)),
            ).assertDoesNotExist()
        }

        scrollTo(ScaleTestTags.COMPOSITION_SECTION)
        compose.onNodeWithTag(MueBmiCardTags.REFERENCE_BAR).assertDoesNotExist()
    }

    /**
     * FR-BODY-003 : la seule mise en perspective autorisée est l'écart avec la composition
     * précédente, affiché avec son signe. Le libellé porte la date de cette composition-là.
     */
    @Test
    fun eachCardShowsTheSignedChangeAgainstThePreviousComposition() {
        val composition = populatedComposition()
        setContent(state(composition = composition))

        val previousDate = ProgressFormat.date(requireNotNull(composition.previous).date)
        scrollTo(ScaleTestTags.BODY_FAT_CARD)
        compose.onNodeWithText(BodyCompositionMessages.changeSince(previousDate)).assertExists()

        BodyCompositionMetric.entries.forEach { metric ->
            scrollTo(metric.testTag)
            val change = metric.change(composition.latest, composition.previous)
            // Le signe est dans la chaîne elle-même, jamais dans une couleur (PRD_SCALE 20).
            val minus = ProgressFormat.signedEstimate(-1.0).first()
            assertTrue("$metric: $change", change.first() == '+' || change.first() == minus)
            compose.onNodeWithContentDescription(
                BodyCompositionMessages.changeDescription(metric, change, previousDate),
            ).assertExists()
        }
    }

    /** PRD_SCALE 20 : la valeur principale est annoncée avec son unité en toutes lettres. */
    @Test
    fun eachValueIsReadOutWithItsUnitAndItsDate() {
        val composition = populatedComposition()
        setContent(state(composition = composition))

        val date = ProgressFormat.date(requireNotNull(composition.latest).date)
        BodyCompositionMetric.entries.forEach { metric ->
            scrollTo(metric.testTag)
            compose.onNodeWithContentDescription(
                BodyCompositionMessages.valueDescription(
                    metric,
                    metric.value(composition.latest),
                    date,
                ),
            ).assertExists()
        }
    }

    /** FR-BODY-005 : sans seconde composition dans la période, l'écart affiche `—`. */
    @Test
    fun aSingleCompositionInThePeriodShowsNoChange() {
        val single = populatedComposition().copy(previous = null)
        setContent(state(composition = single))

        scrollTo(ScaleTestTags.BODY_FAT_CARD)
        compose.onNodeWithText(BodyCompositionMessages.CHANGE_LABEL).assertExists()
        compose.onNodeWithContentDescription(
            BodyCompositionMessages.NO_PREVIOUS_DESCRIPTION,
        ).assertExists()
    }

    /**
     * FR-BODY-005 : une période sans composition affiche `—` sur les quatre cartes et n'emprunte
     * jamais une valeur hors période.
     */
    @Test
    fun anEmptyPeriodShowsDashesOnAllFourCards() {
        val empty = populatedComposition().copy(latest = null, previous = null)
        setContent(state(composition = empty))

        BodyCompositionMetric.entries.forEach { metric ->
            scrollTo(metric.testTag)
            compose.onNodeWithContentDescription(
                BodyCompositionMessages.valueUnavailableDescription(metric),
            ).assertExists()
        }
    }

    /**
     * FR-BODY-005 : la date de la valeur affichée reste visible, pour ne pas la faire passer pour
     * la dernière pesée de poids.
     */
    @Test
    fun theDateOfTheDisplayedValueStaysVisible() {
        val composition = populatedComposition()
        setContent(state(composition = composition))

        scrollTo(ScaleTestTags.COMPOSITION_SECTION)
        compose.onNodeWithText(
            BodyCompositionMessages.measuredOn(
                ProgressFormat.date(requireNotNull(composition.latest).date),
            ),
        ).assertIsDisplayed()
    }

    /** FR-BODY-003 : toute grandeur dérivée se présente comme une estimation. */
    @Test
    fun everyCardSaysItIsAnEstimate() {
        val composition = populatedComposition()
        setContent(state(composition = composition))

        BodyCompositionMetric.entries.forEach { metric ->
            scrollTo(metric.testTag)
            compose.onNode(
                hasText(ScaleMessages.ESTIMATE) and hasAnyAncestor(hasTestTag(metric.testTag)),
            ).assertExists()
        }
    }

    /** FR-BODY-005 : la prudence accompagne la présentation, et le texte détaillé est à un geste. */
    @Test
    fun theCautionIsShownAndOpensTheDetailedText() {
        setContent(state(composition = populatedComposition()))

        scrollTo(ScaleTestTags.COMPOSITION_SECTION)
        compose.onNodeWithText(ScaleMessages.ESTIMATES_CAUTION).assertIsDisplayed()

        compose.onNodeWithTag(ProgressTestTags.COMPOSITION_CAUTION).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(BodyCompositionFormula.DETAILED_CAUTION_PARAGRAPHS.first())
            .assertExists()
    }

    // endregion

    // region quand la section n'existe pas

    /** PRD_SCALE 18.1 : sans historique de composition, la section est absente. */
    @Test
    fun withoutAnyCompositionTheSectionIsAbsent() {
        setContent(state(composition = BodyCompositionUiState.ABSENT))

        compose.onNodeWithTag(ScaleTestTags.COMPOSITION_SECTION).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.BODY_FAT_CARD).assertDoesNotExist()
    }

    /** BR-SCALE-010 : oublier la balance ne masque pas des compositions déjà enregistrées. */
    @Test
    fun theSectionSurvivesAForgottenScale() {
        setContent(state(composition = populatedComposition().copy(hasPairedScale = false)))

        scrollTo(ScaleTestTags.COMPOSITION_SECTION)
        compose.onNodeWithText(ScaleMessages.BODY_COMPOSITION).assertIsDisplayed()
        compose.onNodeWithTag(ScaleTestTags.INCOMPLETE_PROFILE).assertDoesNotExist()
    }

    // endregion

    // region profil incomplet et hors domaine

    /** PRD_SCALE 18.4 : ce qui manque est nommé, et `Profile` est proposé — jamais imposé. */
    @Test
    fun anIncompleteProfileNamesWhatIsMissingAndOffersProfile() {
        var opened = false
        setContent(
            state(
                composition = BodyCompositionUiState.ABSENT.copy(
                    hasPairedScale = true,
                    missingProfileInputs = setOf(BodyCompositionResult.ProfileInput.SEX),
                ),
            ),
            onOpenProfile = { opened = true },
        )

        scrollTo(ScaleTestTags.INCOMPLETE_PROFILE)
        compose.onNodeWithText(ScaleMessages.PROFILE_INCOMPLETE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(
            BodyCompositionMessages.profileIncompleteBody(
                setOf(BodyCompositionResult.ProfileInput.SEX),
            ),
        ).assertIsDisplayed()

        compose.onNodeWithText(ScaleMessages.OPEN_PROFILE).performClick()
        assertEquals(true, opened)
    }

    /**
     * FR-BODY-001 : le profil est complet mais hors du domaine. La phrase est celle du PRD, et
     * **ni l'IMC ni l'âge** ne sont montrés en retour.
     */
    @Test
    fun anOutOfDomainProfileIsExplainedWithoutShowingTheBmiOrTheAge() {
        setContent(
            state(
                composition = populatedComposition().copy(isOutOfDomain = true),
                bmi = Bmi.ValueOnly(48.0),
            ),
        )

        scrollTo(ProgressTestTags.COMPOSITION_UNAVAILABLE)
        compose.onNodeWithText(ScaleMessages.ESTIMATES_UNAVAILABLE).assertIsDisplayed()
        // La phrase se suffit : aucun nombre de profil n'est répété à côté d'elle.
        compose.onNode(
            hasText("48.0", substring = true) and
                hasAnyAncestor(hasTestTag(ProgressTestTags.COMPOSITION_UNAVAILABLE)),
        ).assertDoesNotExist()
    }

    // endregion

    // region le calcul rétroactif

    /**
     * FR-BODY-006 : la proposition dit **combien** de pesées peuvent être complétées, explique
     * l'approximation assumée, et n'agit que sur un geste explicite.
     */
    @Test
    fun theRetroactiveOfferStatesItsCountAndItsApproximation() {
        var completed = 0
        setContent(
            state(composition = populatedComposition().copy(retroactiveCount = 7)),
            onCompletePastWeighIns = { completed++ },
        )

        scrollTo(ScaleTestTags.RETROACTIVE_PROPOSAL)
        compose.onNodeWithText(ScaleMessages.pastWeighInsToComplete(7)).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.RETROACTIVE_EXPLANATION).assertIsDisplayed()

        assertEquals(0, completed)
        compose.onNodeWithTag(ScaleTestTags.RETROACTIVE_CONFIRM).performClick()
        assertEquals(1, completed)
    }

    /** PRD_SCALE 18.4 : sans aucune pesée à compléter, la proposition ne s'affiche pas. */
    @Test
    fun withNothingToCompleteTheOfferIsAbsent() {
        setContent(state(composition = populatedComposition()))

        scrollTo(ScaleTestTags.COMPOSITION_SECTION)
        compose.onNodeWithTag(ScaleTestTags.RETROACTIVE_PROPOSAL).assertDoesNotExist()
    }

    // endregion

    // region harness

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(ProgressTestTags.LIST).performScrollToNode(hasTestTag(tag))
    }

    private fun setContent(
        state: ProgressUiState,
        onOpenProfile: () -> Unit = {},
        onCompletePastWeighIns: () -> Unit = {},
    ) {
        compose.setContent {
            MueTheme {
                ProgressContent(
                    state = state,
                    onSelectPeriod = {},
                    onMeasurementClick = {},
                    editorActions = ProgressEditorActions(),
                    onOpenProfile = onOpenProfile,
                    onCompletePastWeighIns = onCompletePastWeighIns,
                )
            }
        }
    }

    // endregion
}

private val PROFILE = UserProfile(
    heightCm = 178,
    birthDate = LocalDate.of(1990, 1, 1),
    sex = Sex.MALE,
)

private fun kilograms(value: Double): Weight = requireNotNull(Weight.ofKilogramsOrNull(value))

private fun weighIn(daysAgo: Long, value: Double): Measurement = Measurement(
    date = TODAY.minusDays(daysAgo),
    weight = kilograms(value),
    source = MeasurementSource.SCALE,
    impedanceOhm = 500,
)

private fun compositionOf(daysAgo: Long, value: Double): BodyComposition = requireNotNull(
    BodyCompositionCalculator.calculate(weighIn(daysAgo, value), PROFILE).compositionOrNull,
)

/** Deux compositions dans la période : de quoi montrer une valeur et un écart. */
private fun populatedComposition(): BodyCompositionUiState = BodyCompositionUiState(
    latest = compositionOf(3, 74.5),
    previous = compositionOf(10, 75.5),
    hasHistory = true,
    missingProfileInputs = emptySet(),
    isOutOfDomain = false,
    hasPairedScale = true,
    retroactiveCount = 0,
)

private fun points(): List<Measurement> = listOf(weighIn(10, 75.5), weighIn(3, 74.5))

private fun state(
    composition: BodyCompositionUiState,
    bmi: Bmi = Bmi.ValueOnly(23.5),
): ProgressUiState {
    val measurements = points()
    return ProgressUiState(
        period = Period.THIRTY_DAYS,
        today = TODAY,
        isLoading = false,
        hasAnyMeasurement = true,
        chartPoints = measurements,
        history = measurements.reversed(),
        statistics = StatisticsCalculator.compute(measurements),
        bmi = bmi,
        editor = null,
        composition = composition,
    )
}
