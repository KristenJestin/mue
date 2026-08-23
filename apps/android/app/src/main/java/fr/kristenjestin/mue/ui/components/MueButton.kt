package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.delay

private val ButtonMinHeight = 56.dp

/**
 * The one loud control of the app: full-width amber slab, ink label, press contraction.
 *
 * The success state is driven by the caller ([success]) but timed here: after
 * [successDurationMillis] the component calls [onSuccessFinished] so the screen can flip its
 * flag back. This keeps FR-ENTRY-006 and FR-PROFILE-003 from re-implementing the same timer.
 * The button stays clickable throughout — PRD 13 forbids waiting on an animation.
 */
@Composable
fun MuePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    success: Boolean = false,
    successLabel: String = label,
    successDurationMillis: Int = MueMotion.SaveConfirmationMillis,
    onSuccessFinished: () -> Unit = {},
) {
    val colors = MueTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(success) {
        if (success) {
            delay(successDurationMillis.toLong())
            onSuccessFinished()
        }
    }

    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            success -> 0.985f
            pressed -> 0.975f
            else -> 1f
        },
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "primaryButtonScale",
    )

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceStrong
            // The amber flare of PRD 13's save confirmation.
            success -> lerp(colors.accent, Color.White, 0.22f)
            else -> colors.accent
        },
        animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
        label = "primaryButtonContainer",
    )

    val contentColor = if (enabled) colors.onAccent else colors.textTertiary
    val shape = MueTheme.shapes.button

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (enabled) 14.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = colors.accent.copy(alpha = 0.30f),
                spotColor = colors.accent.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = ButtonMinHeight)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = success,
            animationSpec = MueMotion.spec(MueMotion.ManualEntryMillis),
            label = "primaryButtonLabel",
        ) { showSuccess ->
            MueText(
                text = if (showSuccess) successLabel else label,
                style = MueTheme.typography.button,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * Quiet counterpart of [MuePrimaryButton] for secondary actions such as exporting or
 * deleting. [contentColor] is a parameter so a destructive action can borrow the error hue
 * without a second component.
 */
@Composable
fun MueSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MueTheme.colors.textPrimary,
) {
    val colors = MueTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.975f else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "secondaryButtonScale",
    )
    val shape = MueTheme.shapes.button

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.surfaceBorder, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = ButtonMinHeight)
            .padding(PaddingValues(horizontal = 20.dp, vertical = 16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        MueText(
            text = label,
            style = MueTheme.typography.button,
            color = if (enabled) contentColor else colors.textTertiary,
            maxLines = 1,
        )
    }
}

@Preview(name = "Buttons", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueButtonPreview() {
    MuePreviewHost {
        MuePrimaryButton(label = "Save measurement", onClick = {})
        MuePrimaryButton(
            label = "Save measurement",
            successLabel = "Saved ✓",
            success = true,
            onClick = {},
        )
        MuePrimaryButton(label = "Save profile", enabled = false, onClick = {})
        MueSecondaryButton(label = "Export weight data", onClick = {})
        MueSecondaryButton(
            label = "Delete measurement",
            contentColor = MueTheme.colors.error,
            onClick = {},
        )
    }
}
