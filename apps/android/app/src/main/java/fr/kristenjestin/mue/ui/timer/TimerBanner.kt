package fr.kristenjestin.mue.ui.timer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.time.LocalTime

/** Small enough that the bar above the tab bar reads as a strip and not as a second screen. */
private val TileSize: Dp = 32.dp
private val GlyphSize: Dp = 16.dp
private val ChevronSize: Dp = 16.dp

private val BannerHorizontal: Dp = 20.dp
private val BannerVertical: Dp = 10.dp

/**
 * The value moves once a second, so it is set in the same tabular figures PRD 6.3 asks for on
 * the timer screen. `bodyStrong` is the only line of the scale that carries a moving number and
 * is not a metric style, so the feature is turned on here rather than a metric size borrowed.
 */
private const val TabularFigures = "tnum"

/**
 * The chassis banner of PRD_ACTIVITY_TIMER 6.4.
 *
 * It belongs to the fixed frame of the app, beside the tab bar and outside the navigation's
 * animated content, so a tab change neither slides it nor drops it. Appearing and disappearing
 * are the only times it moves, in a short vertical expansion — and with animations reduced it
 * simply fades, as everything else in Mue does.
 *
 * It carries **no window inset**: `MueBottomBar` sits directly below it and already owns the
 * navigation bar and the IME through its `union`, so padding here would lift the strip a whole
 * bar too far.
 *
 * The whole surface is one implicit `Open` (PRD 6.4), and it is also where the timer's notices
 * land whenever the timer screen is not the surface on show (contract decision 1) — including
 * on the start screen, which is where a second `Start timer` is actually attempted.
 */
@Composable
internal fun TimerBanner(
    timer: LiveTimerUiState?,
    notice: TimerNotice?,
    visible: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The last live timer is kept so the strip still has something to draw while it collapses.
    var last by remember { mutableStateOf(timer) }
    timer?.let { last = it }

    AnimatedVisibility(
        visible = visible && timer != null,
        modifier = modifier,
        enter = bannerEnter(),
        exit = bannerExit(),
    ) {
        last?.let { BannerRow(timer = it, notice = notice, onOpen = onOpen) }
    }
}

@Composable
private fun BannerRow(timer: LiveTimerUiState, notice: TimerNotice?, onOpen: () -> Unit) {
    val colors = MueTheme.colors
    val type = MueTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvasElevated)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = colors.hairline,
                    start = Offset(0f, stroke / 2f),
                    end = Offset(size.width, stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .clickable(role = Role.Button, onClickLabel = TimerMessages.OPEN, onClick = onOpen)
            .testTag(TimerTestTags.BANNER)
            .padding(horizontal = BannerHorizontal, vertical = BannerVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(TileSize)
                .clip(MueTheme.shapes.small)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            // Decorative: the label right beside it names the activity.
            MueIcon(
                iconName = ActivityIcons.forMovement(timer.draft.movement),
                tint = colors.onAccentSoft,
                size = GlyphSize,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MueTheme.spacing.md),
        ) {
            MueText(
                text = timer.activityLabel,
                style = type.bodyStrong,
                maxLines = 1,
                modifier = Modifier.testTag(TimerTestTags.BANNER_LABEL),
            )
            notice?.let {
                MueText(
                    text = it.message,
                    style = type.micro,
                    color = if (it.isProblem) colors.error else colors.accent,
                    maxLines = 2,
                    modifier = Modifier
                        .padding(top = MueTheme.spacing.xxs)
                        .testTag(TimerTestTags.NOTICE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        /*
         * PRD 6.4: the elapsed time, or the word `Paused` in its place — never the accent
         * colour alone. No live region, for the same reason the chronometer has none: this
         * value changes every second while the screen it sits on is visible (PRD 11).
         */
        MueText(
            text = timer.bannerValue,
            style = type.bodyStrong.copy(fontFeatureSettings = TabularFigures),
            color = if (timer.isRunning) colors.accent else colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.testTag(TimerTestTags.BANNER_VALUE),
        )

        MueIcon(
            iconName = MueIcons.CHEVRON_RIGHT,
            tint = colors.textQuiet,
            size = ChevronSize,
            modifier = Modifier.padding(start = MueTheme.spacing.sm),
        )
    }
}

/**
 * PRD 6.4: a short vertical expansion, and nothing else about the banner ever moves.
 *
 * Reduced motion drops the travel and keeps the change, exactly as the tab and stack
 * transitions do — the strip appears, rather than growing into place.
 */
@Composable
private fun bannerEnter(): EnterTransition {
    val fade = MueMotion.spec<Float>(MueMotion.ActivityRowExpandMillis, MueMotion.Enter)
    if (LocalReduceMotion.current) return fadeIn(fade)
    val size = MueMotion.spec<IntSize>(MueMotion.ActivityRowExpandMillis, MueMotion.Enter)
    return expandVertically(size) + fadeIn(fade)
}

@Composable
private fun bannerExit(): ExitTransition {
    val fade = MueMotion.spec<Float>(MueMotion.ActivityRowExpandMillis, MueMotion.Exit)
    if (LocalReduceMotion.current) return fadeOut(fade)
    val size = MueMotion.spec<IntSize>(MueMotion.ActivityRowExpandMillis, MueMotion.Exit)
    return shrinkVertically(size) + fadeOut(fade)
}

// region Previews

internal fun previewBannerTimer(
    status: TimedDraftStatus = TimedDraftStatus.RUNNING,
    seconds: Int = 1_543,
    movement: Movement = Movement.WALKING,
    equipment: List<SessionEquipment> = listOf(SessionEquipment(EquipmentType.TREADMILL)),
): LiveTimerUiState {
    val draft = TimedActivityDraft(
        id = TimedDraftId("banner-preview"),
        status = status,
        movement = movement,
        startedAtMillis = 0L,
        startedOn = LocalDate.of(2026, 8, 24),
        startedAtLocalTime = LocalTime.of(18, 32, 47),
        environment = ActivityEnvironment.INDOOR,
        equipment = equipment,
    )
    val duration = requireNotNull(ActivityDuration.ofSecondsOrNull(seconds))
    return LiveTimerUiState(
        draft = draft,
        elapsed = duration,
        basis = null,
        isIncoherent = false,
        activityLabel = TimerFormat.activityLabel(movement, null, equipment),
        contextLabel = TimerFormat.context(draft.environment, equipment),
        elapsedText = TimerFormat.elapsed(duration),
        elapsedDescription = TimerFormat.elapsedDescription(status, duration),
        startedAtText = TimerFormat.startedAt(draft.startedAtLocalTime),
        statusLabel = TimerFormat.statusLabel(status),
        primaryActionLabel = TimerFormat.primaryAction(status),
        bannerValue = TimerFormat.bannerValue(status, duration),
    )
}

@Preview(name = "Timer banner", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun TimerBannerPreview() {
    MueTheme {
        Column {
            TimerBanner(previewBannerTimer(), notice = null, visible = true, onOpen = {})
            TimerBanner(
                timer = previewBannerTimer(TimedDraftStatus.PAUSED),
                notice = null,
                visible = true,
                onOpen = {},
            )
            TimerBanner(
                timer = previewBannerTimer(),
                notice = TimerNotice.ALREADY_IN_PROGRESS,
                visible = true,
                onOpen = {},
            )
        }
    }
}

// endregion
