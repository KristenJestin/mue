package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ButtonMinHeight = 56.dp

/** The single word both save buttons show while they are quiet (PRD 13). */
const val MueSaveConfirmationLabel: String = "Saved"

/**
 * The one loud control of the app: full-width amber slab, ink label, press contraction.
 *
 * The success state is driven by the caller ([success]) but timed here: after
 * [successDurationMillis] the component calls [onSuccessFinished] so the screen can flip its
 * flag back. This keeps FR-ENTRY-006 and FR-PROFILE-003 from re-implementing the same timer.
 * The button stays clickable throughout — PRD 13 forbids waiting on an animation.
 *
 * The confirmation itself is *éclat + repos*: the button discharges its light and then goes
 * quiet. The halo leaves as the fill drops to soft amber, so the energy is handed over to the
 * screen rather than stacked on top of the button. The screen-specific echo — the ruler on
 * Entry, the BMI readout on Profile — is triggered by the screen on the same beat, not here.
 */
@Composable
fun MuePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    success: Boolean = false,
    successLabel: String = MueSaveConfirmationLabel,
    successDurationMillis: Int = MueMotion.SaveConfirmationMillis,
    onSuccessFinished: () -> Unit = {},
) {
    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val currentOnSuccessFinished by rememberUpdatedState(onSuccessFinished)

    // The two states the confirmation moves through, and the only ones it recomposes on.
    var quiet by remember { mutableStateOf(false) }
    var contracted by remember { mutableStateOf(false) }

    // Read inside draw and layer blocks only, so the halo and the label fade never
    // recompose the button — the halo runs for a second next to the Entry ruler.
    val halo = remember { Animatable(HaloSpent) }
    val labelAlpha = remember { Animatable(1f) }

    LaunchedEffect(success, reduceMotion, successDurationMillis) {
        if (!success) {
            contracted = false
            quiet = false
            halo.snapTo(HaloSpent)
            if (labelAlpha.value != 1f) {
                labelAlpha.animateTo(1f, tween(MueMotion.SaveLabelFadeMillis, easing = LinearEasing))
            }
            return@LaunchedEffect
        }

        // Reduced motion keeps the confirmation but drops every trace of travel: no halo, and
        // the word swaps through a single short cross-fade (PRD 14).
        val fade = if (reduceMotion) MueMotion.ReducedMillis / 2 else MueMotion.SaveLabelFadeMillis
        val quietOnset = if (reduceMotion) fade else MueMotion.SaveQuietOnsetMillis
        // The word starts leaving one fade before the end — the prototype's 830 ms mark.
        val quietHold = successDurationMillis - fade - quietOnset - fade

        contracted = true
        launch {
            delay(MueMotion.SavePressHoldMillis.toLong())
            contracted = false
        }
        if (!reduceMotion) {
            launch {
                halo.snapTo(0f)
                halo.animateTo(1f, tween(MueMotion.SaveHaloMillis, easing = LinearEasing))
            }
        }

        launch { labelAlpha.animateTo(0f, tween(fade, easing = LinearEasing)) }
        delay(quietOnset.toLong())
        quiet = true
        labelAlpha.snapTo(0f)
        labelAlpha.animateTo(1f, tween(fade, easing = LinearEasing))

        delay(quietHold.coerceAtLeast(0).toLong())
        labelAlpha.animateTo(0f, tween(fade, easing = LinearEasing))
        quiet = false
        currentOnSuccessFinished()
        labelAlpha.animateTo(1f, tween(fade, easing = LinearEasing))
    }

    val scale by animateFloatAsState(
        targetValue = if (enabled && (pressed || contracted)) PressedScale else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "primaryButtonScale",
    )

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceStrong
            quiet -> colors.accentSoft
            else -> colors.accent
        },
        animationSpec = MueMotion.spec(MueMotion.SaveLabelFadeMillis),
        label = "primaryButtonContainer",
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textTertiary
            quiet -> colors.onAccentSoft
            else -> colors.onAccent
        },
        animationSpec = MueMotion.spec(MueMotion.SaveLabelFadeMillis),
        label = "primaryButtonContent",
    )

    val shape = MueTheme.shapes.button

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Outside the scale layer on purpose: the light has left the button, so it must
            // not shrink with it.
            .mueSaveHalo(shape = shape, color = colors.accent, progress = halo::value)
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
            // `Saved` is the announcement: the button's own name changes, so TalkBack reports
            // the confirmation without a parallel status node to keep in step.
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        MueText(
            text = if (quiet) successLabel else label,
            style = MueTheme.typography.button,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha.value },
        )
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
        targetValue = if (pressed && enabled) PressedScale else 1f,
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

// region Halo

/** Contraction depth on touch, shared by both buttons. */
private const val PressedScale = 0.975f

/** A halo that has finished travelling. Also the resting value, so nothing is drawn at rest. */
private const val HaloSpent = 1f

/** Opacity of the band the instant it leaves the button. */
private const val HaloPeakAlpha = 0.40f

/**
 * Rings the band is built from, and how far each one overlaps its neighbours. Seven rings at
 * twice their spacing is where the ramp stops banding on an amber this saturated.
 */
private const val HaloRings = 7
private const val HaloRingOverlap = 2f

/** Distance from the button edge to the middle of the band, at the start and at the end. */
private val HaloNearOffset: Dp = 2.dp
private val HaloFarOffset: Dp = 34.dp

/** Thickness of the band, which spreads as it travels — the light diffusing as it leaves. */
private val HaloNearWidth: Dp = 14.dp
private val HaloFarWidth: Dp = 38.dp

/**
 * The amber band that radiates off a button as it goes quiet.
 *
 * Built from concentric rounded-rect strokes rather than from a gradient: a halo that grows
 * needs a new shader on every frame if it is painted with one, and this is the component the
 * Entry screen fires while the ruler is one gesture away. Solid strokes allocate nothing, and
 * [progress] is read inside the draw scope so a running halo invalidates the draw phase only.
 */
private fun Modifier.mueSaveHalo(
    shape: Shape,
    color: Color,
    progress: () -> Float,
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
    val nearOffset = HaloNearOffset.toPx()
    val farOffset = HaloFarOffset.toPx()
    val nearWidth = HaloNearWidth.toPx()
    val farWidth = HaloFarWidth.toPx()

    onDrawBehind {
        val travelled = progress()
        if (travelled <= 0f || travelled >= HaloSpent) return@onDrawBehind

        val remaining = HaloSpent - travelled
        val peak = HaloPeakAlpha * remaining * remaining
        val centre = nearOffset + (farOffset - nearOffset) * travelled
        val band = nearWidth + (farWidth - nearWidth) * travelled
        val stroke = band / HaloRings * HaloRingOverlap

        repeat(HaloRings) { ring ->
            // Half a step in, so no ring lands on an end of the ramp and draws nothing.
            val place = (ring + 0.5f) / HaloRings
            val offset = centre + (place - 0.5f) * band
            if (offset <= 0f) return@repeat
            val alpha = peak * (1f - abs(place - 0.5f) * 2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(-offset, -offset),
                size = Size(size.width + offset * 2f, size.height + offset * 2f),
                cornerRadius = CornerRadius(corner + offset),
                alpha = alpha,
                style = Stroke(width = stroke),
            )
        }
    }
}

// endregion

@Preview(name = "Buttons", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueButtonPreview() {
    MuePreviewHost {
        MuePrimaryButton(label = "Save measurement", onClick = {})
        MuePrimaryButton(label = "Save measurement", success = true, onClick = {})
        MuePrimaryButton(label = "Save profile", enabled = false, onClick = {})
        MueSecondaryButton(label = "Export weight data", onClick = {})
        MueSecondaryButton(
            label = "Delete measurement",
            contentColor = MueTheme.colors.error,
            onClick = {},
        )
    }
}
