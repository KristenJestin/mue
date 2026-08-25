package fr.kristenjestin.mue.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.timer.TimerMessages
import fr.kristenjestin.mue.ui.timer.TimerTestTags

/** Smaller than a session card's: this is a shortcut, not a record. */
private val IconTileSize: Dp = 40.dp
private val ActionIconSize: Dp = 18.dp

/**
 * `Start again` (PRD_ACTIVITY_TIMER 6.1).
 *
 * It names the last session whose source is `timer` and reopens the start screen already
 * filled with it (contract decision 4). PRD 16 is explicit that it starts nothing on its own:
 * a shortcut that began measuring on a mis-tap would be a session nobody meant to record.
 *
 * It belongs to the dashboard alone and is deliberately not repeated on the screen it opens.
 */
@Composable
internal fun StartAgainCard(
    state: StartAgainUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val spacing = MueTheme.spacing

    MueSurfaceCard(
        modifier = modifier.testTag(TimerTestTags.START_AGAIN),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.md),
        onClick = onClick,
        onClickLabel = TimerMessages.START_AGAIN,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(IconTileSize)
                    .clip(MueTheme.shapes.small)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the activity is named on the line beside it.
                MueIcon(
                    iconName = state.iconName,
                    tint = colors.onAccentSoft,
                    size = ActionIconSize,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.md),
            ) {
                MueText(
                    text = TimerMessages.START_AGAIN,
                    style = MueTheme.typography.micro,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
                MueText(
                    text = state.label,
                    style = MueTheme.typography.bodyStrong,
                    maxLines = 1,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }

            // The action, not a chevron: this reopens a timer rather than opening a record.
            MueIcon(
                iconName = MueIcons.ROTATE_CW,
                tint = colors.accent,
                size = ActionIconSize,
            )
        }
    }
}

// region Previews

internal fun previewStartAgain(
    label: String = "Treadmill walk",
    movement: Movement = Movement.WALKING,
): StartAgainUiState = StartAgainUiState(
    request = StartTimerRequest(
        movement = movement,
        environment = ActivityEnvironment.INDOOR,
        equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
    ),
    label = label,
    iconName = ActivityIcons.forMovement(movement),
)

@Preview(name = "Start again", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun StartAgainPreview() {
    MuePreviewHost(padding = 28) {
        StartAgainCard(state = previewStartAgain(), onClick = {})
        StartAgainCard(
            state = previewStartAgain("Yoga", Movement.YOGA),
            onClick = {},
        )
    }
}

// endregion
