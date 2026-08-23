package fr.kristenjestin.mue.ui.progress

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.ProgressStatistics
import fr.kristenjestin.mue.domain.logic.StatisticsCalculator
import fr.kristenjestin.mue.domain.logic.categoryOrNull
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.components.MueAccentCard
import fr.kristenjestin.mue.ui.components.MueAnimatedNumber
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MuePeriodPill
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueValueChip
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate

private val ChartHeight = 176.dp
private val IndicatorCardMinHeight = 108.dp

internal const val SCREEN_EYEBROW = "Your journey"
internal const val SCREEN_TITLE = "Slowly, surely."
internal const val CURRENT_WEIGHT_LABEL = "Current weight"
internal const val CURRENT_BMI_LABEL = "Current BMI"
internal const val AVERAGE_PACE_LABEL = "Average pace"
internal const val PACE_UNIT = "kg / week"
internal const val HISTORY_TITLE = "Latest measurements"
internal const val EMPTY_PERIOD_MESSAGE = "No measurements in this period"
internal const val EMPTY_STATE_TITLE = "Your journey starts here"
internal const val EMPTY_STATE_BODY =
    "Record a first weight from the Entry tab and your curve, your pace and your history " +
        "will appear right here."

/** Handles for the Compose UI tests; the history scrolls, so it needs to be addressable. */
internal object ProgressTestTags {
    const val LIST = "progress.list"
    const val CHART = "progress.chart"
}

private val PeriodLabels = mapOf(
    Period.SEVEN_DAYS to "7 days",
    Period.THIRTY_DAYS to "30 days",
    Period.THREE_MONTHS to "3 months",
    Period.ALL to "All",
)

/**
 * The Progress tab (PRD 9.2). Renders its own header and content; the bottom navigation bar
 * belongs to the navigation host and is deliberately not drawn here.
 */
@Composable
fun ProgressScreen(modifier: Modifier = Modifier) {
    val viewModel: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ProgressContent(
        state = state,
        onSelectPeriod = viewModel::selectPeriod,
        onMeasurementClick = viewModel::openEditor,
        editorActions = remember(viewModel) {
            ProgressEditorActions(
                onDismiss = viewModel::dismissEditor,
                onWeightChange = viewModel::updateWeightInput,
                onOpenDatePicker = viewModel::openDatePicker,
                onDismissDatePicker = viewModel::dismissDatePicker,
                onDateChange = viewModel::updateDate,
                onSave = viewModel::saveEdit,
                onRequestDelete = viewModel::requestDelete,
                onCancelDelete = viewModel::cancelDelete,
                onConfirmDelete = viewModel::confirmDelete,
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun ProgressContent(
    state: ProgressUiState,
    onSelectPeriod: (Period) -> Unit,
    onMeasurementClick: (Measurement) -> Unit,
    editorActions: ProgressEditorActions,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    // Kept as a plain string so it survives a process death without a custom saver, and
    // reset whenever the filter changes because the point may not exist in the new period.
    var selectedIso by rememberSaveable(state.period) { mutableStateOf<String?>(null) }
    val selected = state.chartPoints.firstOrNull { it.date.toString() == selectedIso }

    MueScreenScaffold(
        modifier = modifier,
        trailing = {
            MueText(
                text = ProgressFormat.date(state.today),
                style = MueTheme.typography.chip,
                color = MueTheme.colors.textTertiary,
            )
        },
        topFade = MueContentTopFade,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(ProgressTestTags.LIST),
            // The top padding keeps the title clear of the header fade at rest; the bottom
            // one leaves the last history row breathing room above the tab bar.
            contentPadding = PaddingValues(top = MueContentTopFade, bottom = spacing.xxxl),
        ) {
            item(key = "title") {
                MueScreenTitle(title = SCREEN_TITLE, eyebrow = SCREEN_EYEBROW)
            }

            if (state.showEmptyState) {
                item(key = "empty") {
                    EmptyState(modifier = Modifier.padding(top = spacing.xxl))
                }
                return@LazyColumn
            }

            item(key = "periods") {
                PeriodRow(
                    selected = state.period,
                    onSelect = onSelectPeriod,
                    modifier = Modifier.padding(top = spacing.lg),
                )
            }

            item(key = "chart") {
                ChartCard(
                    state = state,
                    selected = selected,
                    onSelectedDateChange = { selectedIso = it?.toString() },
                    modifier = Modifier.padding(top = spacing.lg),
                )
            }

            item(key = "indicators") {
                IndicatorRow(state = state, modifier = Modifier.padding(top = spacing.md))
            }

            if (state.bmi is Bmi.Available) {
                item(key = "disclaimer") {
                    MueText(
                        text = BmiCalculator.DISCLAIMER,
                        style = MueTheme.typography.caption,
                        color = MueTheme.colors.textTertiary,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }

            item(key = "historyTitle") {
                MueText(
                    text = HISTORY_TITLE,
                    style = MueTheme.typography.sectionTitle,
                    modifier = Modifier.padding(top = spacing.xxl, bottom = spacing.xs),
                )
            }

            if (state.history.isEmpty() && !state.isLoading) {
                item(key = "historyEmpty") {
                    MueText(
                        text = EMPTY_PERIOD_MESSAGE,
                        style = MueTheme.typography.body,
                        color = MueTheme.colors.textTertiary,
                        modifier = Modifier.padding(vertical = spacing.md),
                    )
                }
            }

            itemsIndexed(
                items = state.history,
                key = { _, measurement -> measurement.date.toString() },
            ) { index, measurement ->
                Column {
                    if (index > 0) MueDivider()
                    HistoryRow(
                        measurement = measurement,
                        today = state.today,
                        onClick = { onMeasurementClick(measurement) },
                    )
                }
            }
        }
    }

    EditMeasurementSheet(
        editor = state.editor,
        today = state.today,
        actions = editorActions,
    )
}

@Composable
private fun PeriodRow(
    selected: Period,
    onSelect: (Period) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        Period.entries.forEach { period ->
            MuePeriodPill(
                label = PeriodLabels.getValue(period),
                selected = period == selected,
                onClick = { onSelect(period) },
            )
        }
    }
}

@Composable
private fun ChartCard(
    state: ProgressUiState,
    selected: Measurement?,
    onSelectedDateChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val statistics = state.statistics

    MueSurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                MueText(CURRENT_WEIGHT_LABEL, type.label, color = colors.textTertiary)
                MueAnimatedNumber(
                    text = ProgressFormat.weight(statistics.currentWeight),
                    style = type.metricLarge,
                    suffix = "kg",
                    suffixStyle = type.caption,
                    durationMillis = MueMotion.PeriodChangeMillis,
                    contentDescription = currentWeightDescription(statistics),
                    modifier = Modifier.padding(top = MueTheme.spacing.xxs),
                )
            }
            MueValueChip(text = ProgressFormat.signedKilograms(statistics.changeKg))
        }

        Box(
            modifier = Modifier
                .padding(top = MueTheme.spacing.lg)
                .fillMaxWidth()
                .height(ChartHeight),
        ) {
            WeightChart(
                points = state.chartPoints,
                selectedDate = selected?.date,
                onSelectedDateChange = onSelectedDateChange,
                contentDescription = chartDescription(state),
                modifier = Modifier.fillMaxSize().testTag(ProgressTestTags.CHART),
            )
            // Silent while loading: the first frame arrives before the history does, and
            // announcing an empty period there would be a lie half a frame long.
            if (state.chartPoints.isEmpty() && !state.isLoading) {
                MueText(
                    text = EMPTY_PERIOD_MESSAGE,
                    style = type.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Crossfade(
            targetState = selected,
            animationSpec = MueMotion.spec(MueMotion.PeriodChangeMillis),
            label = "chartFooter",
            modifier = Modifier.padding(top = MueTheme.spacing.xs),
        ) { point ->
            if (point != null) {
                MueText(
                    text = "${ProgressFormat.dateOrToday(point.date, state.today)} · " +
                        "${ProgressFormat.weight(point.weight)} kg",
                    style = type.micro,
                    color = colors.accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ChartAxisLabels(points = state.chartPoints, today = state.today)
            }
        }
    }
}

/**
 * Ends of the horizontal axis. A single measurement spans no time at all, so it gets one
 * centred label instead of the same date printed twice.
 */
@Composable
private fun ChartAxisLabels(points: List<Measurement>, today: LocalDate) {
    val style = MueTheme.typography.micro
    val color = MueTheme.colors.textQuiet

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (points.size) {
            0 -> Unit
            1 -> MueText(
                text = ProgressFormat.dateOrToday(points.first().date, today),
                style = style,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {
                MueText(ProgressFormat.date(points.first().date), style, color = color)
                MueText(
                    text = ProgressFormat.dateOrToday(points.last().date, today),
                    style = style,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun IndicatorRow(state: ProgressUiState, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val category = state.bmi.categoryOrNull

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        MueSurfaceCard(
            modifier = Modifier.weight(1f).heightIn(min = IndicatorCardMinHeight),
            shape = MueTheme.shapes.field,
            contentPadding = PaddingValues(MueTheme.spacing.lg),
        ) {
            MueText(CURRENT_BMI_LABEL, type.label, color = colors.textTertiary)
            MueAnimatedNumber(
                text = ProgressFormat.bmi(state.bmi.valueOrNull),
                style = type.metricMedium,
                durationMillis = MueMotion.BmiMillis,
                contentDescription = bmiDescription(state.bmi),
                modifier = Modifier.padding(top = MueTheme.spacing.sm),
            )
            // PRD FR-BMI-002: only the domain layer decides whether a band may be named.
            if (category != null) {
                MueText(category.label, type.caption, color = colors.accent)
            }
        }

        MueAccentCard(
            modifier = Modifier.weight(1f).heightIn(min = IndicatorCardMinHeight),
            shape = MueTheme.shapes.field,
            contentPadding = PaddingValues(MueTheme.spacing.lg),
        ) {
            MueText(AVERAGE_PACE_LABEL, type.label, color = colors.onAccentSecondary)
            MueAnimatedNumber(
                text = ProgressFormat.signed(state.statistics.weeklyPaceKg),
                style = type.metricMedium,
                color = colors.onAccent,
                durationMillis = MueMotion.PeriodChangeMillis,
                contentDescription = paceDescription(state.statistics),
                modifier = Modifier.padding(top = MueTheme.spacing.sm),
            )
            MueText(PACE_UNIT, type.micro, color = colors.onAccentSecondary)
        }
    }
}

@Composable
private fun HistoryRow(
    measurement: Measurement,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = ProgressFormat.dateOrToday(measurement.date, today)
    val weight = ProgressFormat.weight(measurement.weight)

    // `clickable` merges the descendants, so TalkBack announces the row as one item.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MueMinTouchTarget)
            .clickable(
                onClickLabel = "Edit the measurement of $date",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = MueTheme.spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueText(date, MueTheme.typography.body, color = MueTheme.colors.textSecondary)
        MueText("$weight kg", MueTheme.typography.bodyStrong)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    MueSurfaceCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        MueText(EMPTY_STATE_TITLE, MueTheme.typography.sectionTitle)
        MueText(
            text = EMPTY_STATE_BODY,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
        )
    }
}

private fun currentWeightDescription(statistics: ProgressStatistics): String =
    statistics.currentWeight
        ?.let { "$CURRENT_WEIGHT_LABEL ${ProgressFormat.weight(it)} kilograms" }
        ?: "$CURRENT_WEIGHT_LABEL unavailable"

private fun paceDescription(statistics: ProgressStatistics): String =
    statistics.weeklyPaceKg
        ?.let { "$AVERAGE_PACE_LABEL ${ProgressFormat.signed(it)} kilograms per week" }
        ?: "$AVERAGE_PACE_LABEL unavailable"

private fun bmiDescription(bmi: Bmi): String = when (bmi) {
    is Bmi.Unavailable -> "$CURRENT_BMI_LABEL unavailable"
    is Bmi.ValueOnly -> "$CURRENT_BMI_LABEL ${ProgressFormat.bmi(bmi.value)}"
    is Bmi.Classified -> "$CURRENT_BMI_LABEL ${ProgressFormat.bmi(bmi.value)}, ${bmi.category.label}"
}

private fun chartDescription(state: ProgressUiState): String {
    val points = state.chartPoints
    if (points.isEmpty()) return "Weight chart. $EMPTY_PERIOD_MESSAGE."
    val first = ProgressFormat.date(points.first().date)
    val last = ProgressFormat.dateOrToday(points.last().date, state.today)
    val latest = ProgressFormat.weight(points.last().weight)
    return "Weight chart, ${points.size} measurements from $first to $last. " +
        "Latest $latest kilograms."
}

// region Previews

private val PreviewToday: LocalDate = LocalDate.of(2026, 8, 23)

private fun previewMeasurement(daysAgo: Long, kilograms: Double) = Measurement(
    date = PreviewToday.minusDays(daysAgo),
    weight = requireNotNull(Weight.ofKilogramsOrNull(kilograms)),
)

private val PreviewPoints = listOf(
    previewMeasurement(29, 75.6),
    previewMeasurement(22, 75.1),
    previewMeasurement(15, 75.2),
    previewMeasurement(11, 74.8),
    previewMeasurement(5, 74.9),
    previewMeasurement(0, 74.5),
)

private fun previewState(
    points: List<Measurement>,
    bmi: Bmi = Bmi.Classified(23.0, BmiCalculator.categoryOf(23.0)),
    editor: EditorUiState? = null,
    hasAnyMeasurement: Boolean = points.isNotEmpty(),
) = ProgressUiState(
    period = Period.THIRTY_DAYS,
    today = PreviewToday,
    isLoading = false,
    hasAnyMeasurement = hasAnyMeasurement,
    chartPoints = points,
    history = points.reversed(),
    statistics = StatisticsCalculator.compute(points),
    bmi = if (points.isEmpty()) Bmi.Unavailable else bmi,
    editor = editor,
)

@Composable
private fun ProgressPreviewHost(state: ProgressUiState) {
    MueTheme {
        ProgressContent(
            state = state,
            onSelectPeriod = {},
            onMeasurementClick = {},
            editorActions = ProgressEditorActions(),
        )
    }
}

@Preview(name = "Progress — populated", showBackground = true, heightDp = 900)
@Composable
private fun ProgressPopulatedPreview() {
    ProgressPreviewHost(previewState(PreviewPoints))
}

@Preview(name = "Progress — single point", showBackground = true, heightDp = 900)
@Composable
private fun ProgressSinglePointPreview() {
    ProgressPreviewHost(
        previewState(listOf(previewMeasurement(0, 74.5)), bmi = Bmi.ValueOnly(23.0)),
    )
}

@Preview(name = "Progress — empty period", showBackground = true, heightDp = 900)
@Composable
private fun ProgressEmptyPeriodPreview() {
    ProgressPreviewHost(previewState(emptyList(), hasAnyMeasurement = true))
}

@Preview(name = "Progress — empty", showBackground = true, heightDp = 900)
@Composable
private fun ProgressEmptyPreview() {
    ProgressPreviewHost(previewState(emptyList()))
}

@Preview(name = "Progress — sheet open", showBackground = true, heightDp = 900)
@Composable
private fun ProgressSheetOpenPreview() {
    ProgressPreviewHost(
        previewState(
            points = PreviewPoints,
            editor = EditorUiState(
                originalDate = PreviewToday.minusDays(5),
                date = PreviewToday.minusDays(5),
                weightInput = "74.9",
                weightError = null,
                datePickerVisible = false,
                deleteConfirmationVisible = false,
            ),
        ),
    )
}

// endregion
