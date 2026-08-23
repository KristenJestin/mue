package fr.kristenjestin.mue.ui.progress

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.logic.ProgressStatistics
import fr.kristenjestin.mue.domain.logic.StatisticsCalculator
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/**
 * Compose coverage of PRD 9.2. The screen is driven through its stateless [ProgressContent]
 * so the assertions are about what the period, the indicators and the edit panel put on
 * screen, not about how the ViewModel got there.
 *
 * Expected strings go through [ProgressFormat] rather than being spelled out, because the
 * numbers and dates follow the language of the device running the test (PRD BR-010).
 */
class ProgressScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyPeriodFilterIsOffered() {
        setContent(state(populated()))

        listOf("7 days", "30 days", "3 months", "All").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun tappingAPeriodReportsIt() {
        var selected: Period? = null
        setContent(state(populated()), onSelectPeriod = { selected = it })

        compose.onNodeWithText("7 days").performClick()

        assertEquals(Period.SEVEN_DAYS, selected)
    }

    @Test
    fun anEmptyHistoryInvitesAFirstMeasurement() {
        setContent(state(emptyList()))

        compose.onNodeWithText(EMPTY_STATE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(HISTORY_TITLE).assertDoesNotExist()
        compose.onNodeWithText(CURRENT_BMI_LABEL).assertDoesNotExist()
    }

    @Test
    fun anEmptyPeriodShowsDashesEverywhereAndKeepsTheChart() {
        setContent(state(emptyList(), hasAnyMeasurement = true))

        compose.onNodeWithContentDescription("$CURRENT_WEIGHT_LABEL unavailable").assertExists()
        compose.onNodeWithContentDescription("$AVERAGE_PACE_LABEL unavailable").assertExists()
        compose.onNodeWithContentDescription("$CURRENT_BMI_LABEL unavailable").assertExists()
        compose.onNodeWithTag(ProgressTestTags.CHART).assertExists()
        compose.onNodeWithText(EMPTY_STATE_TITLE).assertDoesNotExist()
    }

    @Test
    fun theCurrentWeightAndThePaceAreReadOut() {
        setContent(state(populated()))

        compose.onNodeWithContentDescription(
            "$CURRENT_WEIGHT_LABEL ${ProgressFormat.weight(kilograms(74.5))} kilograms",
        ).assertExists()
        compose.onNodeWithContentDescription(
            "$AVERAGE_PACE_LABEL ${ProgressFormat.signedPace(-0.3)} kilograms per week",
        ).assertExists()
    }

    /**
     * PRD FR-PROGRESS-003 and 004: a weight carries two decimals wherever it appears, while
     * the pace stays a one-decimal derived value.
     */
    @Test
    fun weightsShowTwoDecimalsAndThePaceStaysAtOne() {
        setContent(state(halfSteps()))

        compose.onNodeWithContentDescription(
            "$CURRENT_WEIGHT_LABEL 74.05 kilograms",
        ).assertExists()
        compose.onNodeWithContentDescription(
            "$AVERAGE_PACE_LABEL −0.3 kilograms per week",
        ).assertExists()

        compose.onNodeWithTag(ProgressTestTags.LIST).performScrollToNode(hasText("74.05 kg"))
        compose.onNodeWithText("74.05 kg").assertIsDisplayed()
        compose.onNodeWithText("−0.45 kg").assertExists()
    }

    @Test
    fun theBmiBandIsNamedOnlyWhenTheDomainLayerAllowsIt() {
        val current = mutableStateOf(
            state(populated(), bmi = Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT)),
        )
        setContent(current)

        compose.onNodeWithText(BmiCategory.HEALTHY_WEIGHT.label).assertExists()

        current.value = state(populated(), bmi = Bmi.ValueOnly(23.0))
        compose.waitForIdle()

        compose.onNodeWithText(BmiCategory.HEALTHY_WEIGHT.label).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "$CURRENT_BMI_LABEL ${ProgressFormat.bmi(23.0)}",
        ).assertExists()
    }

    @Test
    fun theHistoryListsEveryMeasurementOfThePeriod() {
        setContent(state(populated()))

        listOf(74.5, 74.9, 74.8).forEach { value ->
            val row = rowWeight(value)
            compose.onNodeWithTag(ProgressTestTags.LIST).performScrollToNode(hasText(row))
            compose.onNodeWithText(row).assertIsDisplayed()
        }
    }

    @Test
    fun tappingAHistoryRowOpensItForEditing() {
        var clicked: Measurement? = null
        setContent(state(populated()), onMeasurementClick = { clicked = it })

        compose.onNodeWithTag(ProgressTestTags.LIST)
            .performScrollToNode(hasText(rowWeight(74.9)))
        compose.onNodeWithText(rowWeight(74.9)).performClick()

        assertEquals(74.9, requireNotNull(clicked).weight.kilograms, 1e-9)
    }

    @Test
    fun tappingACurvePointShowsItsDateAndWeight() {
        setContent(state(populated()))

        compose.onNodeWithTag(ProgressTestTags.CHART).performTouchInput {
            click(Offset(width - 20f, height / 2f))
        }
        compose.waitForIdle()

        compose.onNodeWithText(
            "${ProgressFormat.TODAY} · ${ProgressFormat.weight(kilograms(74.5))} kg",
        ).assertExists()
    }

    @Test
    fun theChartIsDescribedForTalkBack() {
        setContent(state(populated()))

        compose.onNodeWithContentDescription("Weight chart", substring = true).assertExists()
    }

    @Test
    fun theEditPanelOffersTheDateTheWeightAndBothActions() {
        setContent(state(populated(), editor = editor()))

        compose.onNodeWithText(EDIT_SHEET_TITLE).assertExists()
        compose.onNodeWithText(DATE_LABEL).assertExists()
        compose.onNodeWithText(WEIGHT_LABEL).assertExists()
        compose.onNodeWithText(SAVE_CHANGES).assertExists()
        compose.onNodeWithText(DELETE_MEASUREMENT).assertExists()
    }

    @Test
    fun typingInThePanelIsReported() {
        var typed: String? = null
        setContent(
            state(populated(), editor = editor()),
            editorActions = ProgressEditorActions(onWeightChange = { typed = it }),
        )

        compose.onNodeWithText("74.90").performTextReplacement("73.25")

        assertEquals("73.25", typed)
    }

    @Test
    fun savingIsReported() {
        var saved = false
        setContent(
            state(populated(), editor = editor()),
            editorActions = ProgressEditorActions(onSave = { saved = true }),
        )

        compose.onNodeWithText(SAVE_CHANGES).performClick()

        assertTrue(saved)
    }

    @Test
    fun anOutOfRangeWeightShowsTheEntryMessage() {
        setContent(
            state(
                populated(),
                editor = editor(weightInput = "312.0", weightError = MueValidation.WEIGHT_ERROR),
            ),
        )

        compose.onNodeWithText(MueValidation.WEIGHT_ERROR).assertExists()
    }

    @Test
    fun deletingAsksForAConfirmationFirst() {
        var requested = false
        var deleted = false
        setContent(
            state(populated(), editor = editor()),
            editorActions = ProgressEditorActions(
                onRequestDelete = { requested = true },
                onConfirmDelete = { deleted = true },
            ),
        )

        compose.onNodeWithText(DELETE_MEASUREMENT).performClick()

        assertTrue(requested)
        assertTrue(!deleted)
    }

    @Test
    fun theConfirmationDialogCarriesBothWaysOut() {
        var confirmed = false
        var cancelled = false
        setContent(
            state(populated(), editor = editor(deleteConfirmationVisible = true)),
            editorActions = ProgressEditorActions(
                onConfirmDelete = { confirmed = true },
                onCancelDelete = { cancelled = true },
            ),
        )

        compose.onNodeWithText(DELETE_CONFIRMATION_TITLE).assertExists()

        compose.onNodeWithText(CANCEL).performClick()
        assertTrue(cancelled)

        compose.onNodeWithText(DELETE_CONFIRM).performClick()
        assertTrue(confirmed)
    }

    // region harness

    private fun setContent(
        state: ProgressUiState,
        onSelectPeriod: (Period) -> Unit = {},
        onMeasurementClick: (Measurement) -> Unit = {},
        editorActions: ProgressEditorActions = ProgressEditorActions(),
    ) = setContent(mutableStateOf(state), onSelectPeriod, onMeasurementClick, editorActions)

    private fun setContent(
        state: MutableState<ProgressUiState>,
        onSelectPeriod: (Period) -> Unit = {},
        onMeasurementClick: (Measurement) -> Unit = {},
        editorActions: ProgressEditorActions = ProgressEditorActions(),
    ) {
        compose.setContent {
            MueTheme {
                ProgressContent(
                    state = state.value,
                    onSelectPeriod = onSelectPeriod,
                    onMeasurementClick = onMeasurementClick,
                    editorActions = editorActions,
                )
            }
        }
    }

    // endregion
}

private fun kilograms(value: Double): Weight = requireNotNull(Weight.ofKilogramsOrNull(value))

private fun rowWeight(value: Double): String = "${ProgressFormat.weight(kilograms(value))} kg"

private fun measurement(daysAgo: Long, value: Double) =
    Measurement(TODAY.minusDays(daysAgo), kilograms(value))

/** Six measurements losing 1.2 kg over 28 days, so the pace reads `−0.3` a week. */
private fun populated(): List<Measurement> = listOf(
    measurement(28, 75.7),
    measurement(22, 75.1),
    measurement(15, 75.2),
    measurement(11, 74.8),
    measurement(5, 74.9),
    measurement(0, 74.5),
)

/** Ten days losing 0.45 kg: a change no single decimal can show, at a pace of `−0.3` a week. */
private fun halfSteps(): List<Measurement> = listOf(
    measurement(10, 74.5),
    measurement(0, 74.05),
)

private fun state(
    points: List<Measurement>,
    hasAnyMeasurement: Boolean = points.isNotEmpty(),
    bmi: Bmi = if (points.isEmpty()) {
        Bmi.Unavailable
    } else {
        Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT)
    },
    editor: EditorUiState? = null,
): ProgressUiState = ProgressUiState(
    period = Period.THIRTY_DAYS,
    today = TODAY,
    isLoading = false,
    hasAnyMeasurement = hasAnyMeasurement,
    chartPoints = points,
    history = points.reversed(),
    statistics = if (points.isEmpty()) {
        ProgressStatistics.UNAVAILABLE
    } else {
        StatisticsCalculator.compute(points)
    },
    bmi = bmi,
    editor = editor,
)

private fun editor(
    weightInput: String = "74.90",
    weightError: String? = null,
    deleteConfirmationVisible: Boolean = false,
): EditorUiState = EditorUiState(
    originalDate = TODAY.minusDays(5),
    date = TODAY.minusDays(5),
    weightInput = weightInput,
    weightError = weightError,
    datePickerVisible = false,
    deleteConfirmationVisible = deleteConfirmationVisible,
)
