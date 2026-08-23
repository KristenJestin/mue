package fr.kristenjestin.mue.ui.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

private val TrackWidth = 46.dp
private val TrackHeight = 28.dp
private val ThumbSize = 22.dp
private val ThumbInset = 3.dp

/**
 * A single preference on its own card. The whole card is the switch, so the target is far
 * larger than the Android minimum and TalkBack announces one `Switch` node carrying both the
 * title and its explanation.
 */
@Composable
internal fun ProfileSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MueSurfaceCard(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(MueTheme.spacing.lg),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(title, MueTheme.typography.bodyStrong)
                MueText(
                    text = description,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = MueTheme.spacing.xxs),
                )
            }
            MueSwitchTrack(checked = checked)
        }
    }
}

/** Purely visual: the click and the semantics belong to the row that hosts it. */
@Composable
private fun MueSwitchTrack(checked: Boolean) {
    val colors = MueTheme.colors
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.surfaceStrong,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "switchTrack",
    )
    val thumb by animateColorAsState(
        targetValue = if (checked) colors.onAccent else colors.textTertiary,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "switchThumb",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) TrackWidth - ThumbSize - ThumbInset else ThumbInset,
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "switchThumbOffset",
    )

    Box(
        modifier = Modifier
            .width(TrackWidth)
            .height(TrackHeight)
            .clip(MueTheme.shapes.pill)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(ThumbSize)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}
