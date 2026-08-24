package fr.kristenjestin.mue.ui.activity

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.timer.TimerFormat
import fr.kristenjestin.mue.ui.timer.TimerMessages
import fr.kristenjestin.mue.ui.timer.TimerTestTags

/** The same glyph tile the session cards carry, which also clears the touch minimum. */
private val IconTileSize: Dp = 48.dp

/**
 * What TalkBack calls the action, as `ActivitySessionCard` names its own. The card's label and
 * date are read first; this says what a tap does with them.
 */
private const val REVIEW_CLICK_LABEL = "Review this activity"

/**
 * The timed drafts waiting on the dashboard (PRD_ACTIVITY_TIMER FR-TIMER-008).
 *
 * Three cards at most, then one compact line that rolls the rest out **in place** — no second
 * screen, and nothing is ever removed on its own: Mue does not destroy a measured duration
 * without being asked.
 *
 * The expansion is `rememberSaveable` and purely of the interface. It belongs to the visit
 * rather than to the data, so it survives a rotation and a trip through another tab and is
 * forgotten when the tab's stack gives its slot up — which is right, because three is what the
 * PRD wants the block to be worth on arrival.
 */
@Composable
internal fun ReviewDraftsBlock(
    drafts: List<ReviewDraftUiState>,
    onOpenReview: (TimedDraftId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drafts.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) drafts else drafts.take(TimerFormat.REVIEW_CARD_LIMIT)
    val hidden = drafts.size - shown.size
    val spacing = MueTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(MueMotion.spec(MueMotion.ActivityRowExpandMillis))
            .testTag(TimerTestTags.REVIEW_LIST),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        MueText(
            text = TimerMessages.READY_TO_REVIEW,
            style = MueTheme.typography.sectionTitle,
            modifier = Modifier
                .padding(start = spacing.sm, bottom = spacing.xs)
                .semantics { heading() },
        )

        shown.forEach { draft ->
            ReviewDraftCard(draft = draft, onClick = { onOpenReview(draft.id) })
        }

        /*
         * FR-TIMER-008 names one control and one direction, so this is what it does: the line
         * exists exactly while something is still hidden, and rolling the rest out retires it.
         * There is no wording in the PRD for folding them back, and the block is at the top of
         * a dashboard that scrolls, so nothing is trapped behind the choice.
         */
        if (hidden > 0) {
            MueText(
                text = TimerMessages.moreToReview(hidden),
                style = MueTheme.typography.chip,
                color = MueTheme.colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MueTheme.shapes.field)
                    .clickable(role = Role.Button) { expanded = true }
                    .heightIn(min = MueMinTouchTarget)
                    .padding(horizontal = spacing.lg, vertical = spacing.md)
                    .testTag(TimerTestTags.MORE_TO_REVIEW),
            )
        }
    }
}

/** One draft: what it was, when it was measured, and how long for (FR-TIMER-008). */
@Composable
private fun ReviewDraftCard(
    draft: ReviewDraftUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = modifier.testTag(TimerTestTags.reviewCard(draft.id.value)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
        onClick = onClick,
        onClickLabel = REVIEW_CLICK_LABEL,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(IconTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the label beside it already names the activity.
                MueIcon(iconName = draft.iconName, tint = colors.onAccentSoft)
            }

            /*
             * `md` and not the session card's `lg`. The meta line is the longest thing on this
             * dashboard: measured off device at 390 dp in the real face,
             * `Yesterday · 7:05 AM · 1 h 42 min 18 sec` runs to 209 dp, and the wider inset
             * leaves the column only 206.
             */
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.md),
            ) {
                MueText(draft.label, MueTheme.typography.bodyStrong, maxLines = 1)
                MueText(
                    text = draft.meta,
                    style = MueTheme.typography.micro,
                    color = colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            MueIcon(
                iconName = MueIcons.CHEVRON_RIGHT,
                tint = colors.textQuiet,
                size = 16.dp,
            )
        }
    }
}

// region Previews

private fun previewDraft(
    id: String,
    label: String,
    icon: String,
    meta: String,
): ReviewDraftUiState = ReviewDraftUiState(
    id = TimedDraftId(id),
    label = label,
    meta = meta,
    iconName = icon,
)

internal fun previewReviewDrafts(count: Int): List<ReviewDraftUiState> = List(count) { index ->
    previewDraft(
        id = "draft-$index",
        label = if (index == 0) "Treadmill walk" else "Yoga",
        icon = if (index == 0) ActivityIcons.FOOTPRINTS else ActivityIcons.FLOWER,
        meta = "Today · 18:32 · 42 min 18 sec",
    )
}

@Preview(name = "Drafts to review", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun ReviewDraftsPreview() {
    MuePreviewHost(padding = 28) {
        ReviewDraftsBlock(drafts = previewReviewDrafts(5), onOpenReview = {})
    }
}

// endregion
