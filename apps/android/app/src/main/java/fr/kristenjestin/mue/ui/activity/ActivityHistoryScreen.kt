package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.time.YearMonth

internal const val HISTORY_TITLE = "Activity history"

/**
 * Reachable only from `See all`, which is itself hidden below six sessions — so this line is
 * a safety net rather than a state the dashboard can send anyone to.
 */
internal const val HISTORY_EMPTY = "No activity recorded yet."

/**
 * Every session ever recorded, grouped by month and unlimited in length
 * (PRD FR-ACTIVITY-012). The cards are the dashboard's own and open the same editor.
 */
@Composable
fun ActivityHistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (ActivityId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory)
    val state by viewModel.historyState.collectAsStateWithLifecycle()

    ActivityHistoryContent(
        state = state,
        onBack = onBack,
        onOpenSession = onOpenSession,
        modifier = modifier,
    )
}

/**
 * The history with its state handed to it.
 *
 * This is the one screen of the module reached from another rather than from a tab, so it
 * carries the sub-screen chrome — a back control and its own name — instead of the wordmark
 * (PRD 7).
 *
 * The months are laid out as flat items rather than as a column per month: PRD FR-ACTIVITY-012
 * puts no ceiling on the list, and a `Column` per month would compose a whole year of cards to
 * show the three that fit on screen.
 */
@Composable
internal fun ActivityHistoryContent(
    state: ActivityHistoryUiState,
    onBack: () -> Unit,
    onOpenSession: (ActivityId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    MueSubScreenScaffold(
        title = HISTORY_TITLE,
        onNavigateBack = onBack,
        navigationIcon = { MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textPrimary) },
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(ActivityTestTags.HISTORY_LIST),
            contentPadding = PaddingValues(top = MueContentTopFade, bottom = spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (state.isEmpty) {
                item(key = "empty") {
                    MueText(
                        text = HISTORY_EMPTY,
                        style = MueTheme.typography.body,
                        color = MueTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                }
                return@LazyColumn
            }

            state.months.forEachIndexed { index, group ->
                item(key = "month:${group.month}") {
                    MonthHeading(
                        month = group.month,
                        // The first heading sits under the header fade; the later ones open a
                        // new block and need the air a card does not give them.
                        modifier = Modifier.padding(
                            top = if (index == 0) spacing.sm else spacing.lg,
                        ),
                    )
                }

                items(
                    items = group.sessions,
                    key = { summary -> summary.id.value },
                ) { summary ->
                    ActivitySessionCard(
                        summary = summary,
                        today = state.today,
                        onClick = { onOpenSession(summary.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeading(month: YearMonth, modifier: Modifier = Modifier) {
    // A rank below `Recent activity` on the dashboard: same cut, quieter ink. A month is the
    // structure of this screen rather than a caption on it, so it needs more than a label.
    MueText(
        text = ActivityFormat.monthTitle(month),
        style = MueTheme.typography.sectionTitle,
        color = MueTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

// region Previews

private val PreviewDay: LocalDate = LocalDate.of(2026, 8, 23)

internal fun previewHistoryState(
    sessions: List<ActivitySummary>,
    today: LocalDate = PreviewDay,
): ActivityHistoryUiState = ActivityHistoryUiState(
    today = today,
    isLoading = false,
    months = sessions
        .groupBy { YearMonth.from(it.startedOn) }
        .map { (month, rows) -> ActivityMonthGroup(month, rows) },
)

@Preview(name = "Activity history", showBackground = true, heightDp = 900, widthDp = 390)
@Composable
private fun ActivityHistoryPreview() {
    MueTheme {
        Column {
            ActivityHistoryContent(
                state = previewHistoryState(
                    listOf(
                        previewSummary(daysAgo = 0),
                        previewSummary(
                            label = "Strength training",
                            movement = Movement.STRENGTH_TRAINING,
                            daysAgo = 3,
                            minutes = 55,
                            distanceMetres = null,
                            validSetCount = 12,
                            estimatedEnergyKcal = 320,
                        ),
                        previewSummary(
                            label = "Outdoor run",
                            movement = Movement.RUNNING,
                            daysAgo = 40,
                            minutes = 38,
                            distanceMetres = 7_400,
                            estimatedEnergyKcal = null,
                        ),
                    ),
                ),
                onBack = {},
                onOpenSession = {},
            )
        }
    }
}

// endregion
