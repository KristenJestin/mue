package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.domain.logic.WeeklyActivitySummary
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.ui.components.MueAnimatedNumber
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueDivider
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueWeekBars
import fr.kristenjestin.mue.ui.components.MueWeekDay
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.time.LocalTime

internal const val WEEK_EYEBROW = "This week"
internal const val MOVED_PREFIX = "You moved for"
internal const val RHYTHM_LABEL = "Your rhythm"
internal const val THIS_WEEK_SUFFIX = "this week"
internal const val ENERGY_LABEL = "Estimated energy"
internal const val RECENT_TITLE = "Recent activity"
internal const val SEE_ALL_LABEL = "See all"
internal const val LOG_ACTIVITY_LABEL = "Log activity"

/** PRD 13.2: the quiet week states what happened and asks for nothing. */
internal const val QUIET_WEEK_TITLE = "No activity this week."

/** PRD 13.1. */
internal const val EMPTY_HISTORY_TITLE = "Ready when you are."
internal const val EMPTY_HISTORY_BODY =
    "Any activity you have finished can be added here — a walk, a ride, a session at the gym. " +
        "Your week takes shape from the first one."
internal const val EMPTY_HISTORY_ACTION = "Log your first activity"

/** The prototype's amber glyph tile in the corner of the weekly card. */
private val RhythmTileSize = 40.dp

/**
 * The Activity tab: the current week and the five most recent sessions (PRD FR-ACTIVITY-001
 * and 002).
 *
 * [onSeeAll] opens the full history and is only wired when there are more than five sessions;
 * [onOpenSession] opens a session in the same editor the history uses.
 */
@Composable
fun ActivityScreen(
    onLogActivity: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenSession: (ActivityId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ActivityDashboardContent(
        state = state,
        onLogActivity = onLogActivity,
        onSeeAll = onSeeAll,
        onOpenSession = onOpenSession,
        modifier = modifier,
    )
}

/**
 * The dashboard with its state handed to it, so the tests drive what the week and the history
 * put on screen rather than how the ViewModel got there.
 */
@Composable
internal fun ActivityDashboardContent(
    state: ActivityUiState,
    onLogActivity: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenSession: (ActivityId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing

    MueScreenScaffold(
        modifier = modifier,
        trailing = { WeekRangeChip(state.week) },
        topFade = MueContentTopFade,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(ActivityTestTags.DASHBOARD),
            contentPadding = PaddingValues(top = MueContentTopFade, bottom = spacing.xxxl),
        ) {
            if (state.showEmptyHistory) {
                item(key = "empty") {
                    EmptyHistory(
                        onLogActivity = onLogActivity,
                        modifier = Modifier.padding(top = spacing.xl),
                    )
                }
                return@LazyColumn
            }

            item(key = "headline") { WeeklyHeadline(state) }

            item(key = "week") {
                WeeklyCard(state, modifier = Modifier.padding(top = spacing.xl))
            }

            /*
             * The prototype ends the screen with this button, under a list of two cards. PRD
             * FR-ACTIVITY-002 made that list five, which pushes the button off the first screen
             * entirely — and FR-ACTIVITY-003 asks for an action that is immediately reachable.
             * PRD 15 settles where it goes: title, summary, action, history.
             */
            item(key = "logActivity") {
                MuePrimaryButton(
                    label = LOG_ACTIVITY_LABEL,
                    onClick = onLogActivity,
                    modifier = Modifier
                        .padding(top = spacing.xl)
                        .testTag(ActivityTestTags.LOG_ACTIVITY),
                )
            }

            item(key = "recentTitle") {
                RecentHeader(
                    showSeeAll = state.showSeeAll,
                    onSeeAll = onSeeAll,
                    modifier = Modifier.padding(top = spacing.xl),
                )
            }

            // Five cards at most, so they cost nothing to compose together and the list can be
            // addressed as one thing by a test and by a screen reader.
            item(key = "recent") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.md)
                        .testTag(ActivityTestTags.RECENT_LIST),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    state.recent.forEach { summary ->
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
}

/** `Aug 17–23` beside the wordmark, as in the prototype's header. */
@Composable
private fun WeekRangeChip(week: WeeklyActivitySummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MueIcon(
            iconName = MueIcons.CALENDAR_DAYS,
            tint = MueTheme.colors.textTertiary,
            size = 14.dp,
        )
        MueText(
            text = ActivityFormat.weekRange(week.week),
            style = MueTheme.typography.chip,
            color = MueTheme.colors.textTertiary,
            maxLines = 1,
            modifier = Modifier.padding(start = MueTheme.spacing.sm),
        )
    }
}

/**
 * The editorial title of PRD FR-ACTIVITY-001, and the quiet one of PRD 13.2 in its place —
 * same position, same weight, no reformulation and no encouragement.
 */
@Composable
private fun WeeklyHeadline(state: ActivityUiState) {
    if (!state.showWeeklyDuration) {
        MueScreenTitle(title = QUIET_WEEK_TITLE, eyebrow = WEEK_EYEBROW)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        MueText(WEEK_EYEBROW, MueTheme.typography.eyebrow, color = MueTheme.colors.textSecondary)
        MueText(
            text = MOVED_PREFIX,
            style = MueTheme.typography.screenTitle,
            modifier = Modifier.padding(top = 2.dp).semantics { heading() },
        )
        MueText(
            text = "${ActivityFormat.duration(state.week.totalDuration)}.",
            style = MueTheme.typography.screenTitle,
            color = MueTheme.colors.accent,
        )
    }
}

/** `Your rhythm`, the seven rails, and the week's estimated energy when there is one. */
@Composable
private fun WeeklyCard(state: ActivityUiState, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    val spacing = MueTheme.spacing
    val sessions = ActivityFormat.sessionCount(state.week.sessionCount)

    MueSurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                MueText(RHYTHM_LABEL, type.label, color = colors.textTertiary)
                Row(
                    modifier = Modifier
                        .padding(top = spacing.xxs)
                        // One reading rather than three: the count, its noun and the period
                        // are a single sentence.
                        .clearAndSetSemantics {
                            contentDescription = "$sessions $THIS_WEEK_SUFFIX"
                        },
                    verticalAlignment = Alignment.Bottom,
                ) {
                    MueAnimatedNumber(
                        text = state.week.sessionCount.toString(),
                        style = type.metricMedium,
                    )
                    MueText(
                        text = ActivityFormat.sessionNoun(state.week.sessionCount),
                        style = type.metricMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    MueText(
                        text = THIS_WEEK_SUFFIX,
                        style = type.caption,
                        color = colors.textQuiet,
                        modifier = Modifier.padding(start = spacing.sm, bottom = 3.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(RhythmTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(iconName = ActivityIcons.TAB_ACTIVITY, tint = colors.onAccent)
            }
        }

        MueWeekBars(
            days = state.weekDays.mapIndexed { index, bar ->
                MueWeekDay(
                    label = ActivityFormat.dayInitial(bar.day),
                    value = bar.duration.seconds.toLong(),
                    accessibleText = ActivityFormat.dayDescription(
                        day = bar.day,
                        dayDuration = bar.duration,
                        isToday = bar.isToday,
                    ),
                    emphasised = bar.isToday,
                    testTag = ActivityTestTags.weeklyBar(index),
                )
            },
            modifier = Modifier
                .padding(top = spacing.xl)
                .testTag(ActivityTestTags.WEEKLY_BARS),
        )

        // PRD 13.3: an unestimated week keeps its count and its duration and simply says
        // nothing about energy. There is no zero to fall back on.
        if (state.showWeeklyEnergy) {
            Column(modifier = Modifier.padding(top = spacing.xl)) {
                MueDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MueIcon(
                            iconName = ActivityIcons.FLAME,
                            tint = colors.accent,
                            size = 16.dp,
                        )
                        MueText(
                            text = ENERGY_LABEL,
                            style = type.caption,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                    MueText(ActivityFormat.energy(state.week.energyKcal), type.bodyStrong)
                }
            }
        }
    }
}

@Composable
private fun RecentHeader(
    showSeeAll: Boolean,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = MueMinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueText(
            text = RECENT_TITLE,
            style = MueTheme.typography.sectionTitle,
            modifier = Modifier.semantics { heading() },
        )
        if (showSeeAll) {
            MueText(
                text = SEE_ALL_LABEL,
                style = MueTheme.typography.chip,
                color = MueTheme.colors.textSecondary,
                modifier = Modifier
                    .clip(MueTheme.shapes.pill)
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    // The visible word is small, so the target is grown around it rather
                    // than the type being enlarged (PRD 15).
                    .padding(horizontal = MueTheme.spacing.md, vertical = MueTheme.spacing.md)
                    .testTag(ActivityTestTags.SEE_ALL),
            )
        }
    }
}

/** PRD 13.1: nothing was ever recorded, so the screen is an invitation and nothing else. */
@Composable
private fun EmptyHistory(onLogActivity: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MueTheme.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        MueScreenTitle(title = EMPTY_HISTORY_TITLE)
        MueSurfaceCard(modifier = Modifier.padding(top = spacing.xl)) {
            MueText(
                text = EMPTY_HISTORY_BODY,
                style = MueTheme.typography.body,
                color = MueTheme.colors.textSecondary,
            )
        }
        MuePrimaryButton(
            label = EMPTY_HISTORY_ACTION,
            onClick = onLogActivity,
            modifier = Modifier
                .padding(top = spacing.xl)
                .testTag(ActivityTestTags.LOG_ACTIVITY),
        )
    }
}

// region Previews

private val PreviewDay: LocalDate = LocalDate.of(2026, 8, 23)

internal fun previewDashboardState(
    recent: List<ActivitySummary>,
    weekSummaries: List<ActivitySummary> = recent,
    totalSessionCount: Int = recent.size,
    today: LocalDate = PreviewDay,
): ActivityUiState {
    val week = WeeklyActivitySummary.of(weekSummaries, today)
    val monday = requireNotNull(week.week.start)
    return ActivityUiState(
        today = today,
        isLoading = false,
        hasAnyActivity = totalSessionCount > 0,
        week = week,
        weekDays = week.dailyDurations.mapIndexed { index, duration ->
            val date = monday.plusDays(index.toLong())
            ActivityDayBar(date.dayOfWeek, date, duration, isToday = date == today)
        },
        recent = recent.take(ActivityViewModel.RECENT_LIMIT),
        totalSessionCount = totalSessionCount,
    )
}

private fun previewSessions() = listOf(
    previewSummary(daysAgo = 0, startedAtTime = LocalTime.of(7, 30)),
    previewSummary(
        label = "Strength training",
        movement = Movement.STRENGTH_TRAINING,
        daysAgo = 2,
        minutes = 55,
        distanceMetres = null,
        validSetCount = 12,
        estimatedEnergyKcal = 320,
    ),
    previewSummary(
        label = "Outdoor run",
        movement = Movement.RUNNING,
        daysAgo = 4,
        minutes = 38,
        distanceMetres = 7_400,
        estimatedEnergyKcal = 410,
    ),
)

@Preview(name = "Activity — populated", showBackground = true, heightDp = 900, widthDp = 390)
@Composable
private fun ActivityPopulatedPreview() {
    MueTheme {
        ActivityDashboardContent(
            state = previewDashboardState(previewSessions(), totalSessionCount = 9),
            onLogActivity = {},
            onSeeAll = {},
            onOpenSession = {},
        )
    }
}

@Preview(name = "Activity — quiet week", showBackground = true, heightDp = 900, widthDp = 390)
@Composable
private fun ActivityQuietWeekPreview() {
    MueTheme {
        ActivityDashboardContent(
            state = previewDashboardState(
                recent = listOf(previewSummary(daysAgo = 20, estimatedEnergyKcal = null)),
                weekSummaries = emptyList(),
                totalSessionCount = 1,
            ),
            onLogActivity = {},
            onSeeAll = {},
            onOpenSession = {},
        )
    }
}

@Preview(name = "Activity — empty", showBackground = true, heightDp = 900, widthDp = 390)
@Composable
private fun ActivityEmptyPreview() {
    MueTheme {
        ActivityDashboardContent(
            state = previewDashboardState(recent = emptyList()),
            onLogActivity = {},
            onSeeAll = {},
            onOpenSession = {},
        )
    }
}

// endregion
