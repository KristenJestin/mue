package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.launch

/** Drag distance past which releasing dismisses the sheet. */
private val DismissThreshold = 120.dp

object MueBottomSheetDefaults {

    /** The screen gutter, kept below the panel so the last action clears the home indicator. */
    @Composable
    fun contentPadding(
        horizontal: Dp = MueTheme.spacing.screenHorizontal,
        bottom: Dp = MueTheme.spacing.xxl,
    ): PaddingValues = PaddingValues(start = horizontal, end = horizontal, bottom = bottom)
}

/**
 * Panel rising from the bottom over a dimmed canvas, host for the date picker and the
 * history edit panel. Closes on the scrim, on back, or on a downward drag (FR-ENTRY-005).
 *
 * [contentPadding] is a parameter rather than the screen gutter for everyone, because a
 * child can have a width of its own: the Material calendar already carries its own inner
 * padding and wants a narrower gutter to lay its 48 dp day cells out in. Without the knob
 * such a child has to force its width and overflow the panel instead.
 *
 * Reduced motion keeps the scrim and the panel but drops the travel: the sheet simply
 * fades in place.
 */
@Composable
fun MueBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    scrimContentDescription: String = "Close",
    contentPadding: PaddingValues = MueBottomSheetDefaults.contentPadding(),
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) mounted = true }
    if (!mounted) return

    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DismissThreshold.toPx() }

    // Starts at false even though `visible` is already true, so the first frame animates in.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val progress by animateFloatAsState(
        targetValue = if (visible && appeared) 1f else 0f,
        animationSpec = MueMotion.spec(MueMotion.SheetMillis),
        label = "sheetProgress",
        finishedListener = { settled -> if (settled == 0f) mounted = false },
    )

    val dragOffset = remember { Animatable(0f) }
    LaunchedEffect(visible) { if (visible) dragOffset.snapTo(0f) }

    // Hoisted: `onDragStopped` is a suspend lambda and cannot read composition locals.
    val settleSpec = MueMotion.spec<Float>(MueMotion.SheetMillis)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
                    .background(colors.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    )
                    .semantics { contentDescription = scrimContentDescription },
            )

            Column(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = progress
                        translationY = if (reduceMotion) {
                            dragOffset.value
                        } else {
                            (1f - progress) * size.height + dragOffset.value
                        }
                    }
                    .clip(MueTheme.shapes.sheet)
                    .background(colors.canvasElevated)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
                            }
                        },
                        onDragStopped = {
                            if (dragOffset.value > dismissThresholdPx) {
                                onDismissRequest()
                            } else {
                                dragOffset.animateTo(0f, settleSpec)
                            }
                        },
                    )
                    .navigationBarsPadding()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 2.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(MueTheme.shapes.pill)
                        .background(colors.textQuiet),
                )
                title?.let {
                    MueText(it, MueTheme.typography.sectionTitle, color = colors.textPrimary)
                }
                content()
            }
        }
    }
}

@Preview(name = "Bottom sheet body", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueBottomSheetPreview() {
    // The sheet itself needs a dialog window, so the preview shows the panel body only.
    MuePreviewHost(padding = 0) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MueTheme.shapes.sheet)
                .background(MueTheme.colors.canvasElevated)
                .padding(MueTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(MueTheme.shapes.pill)
                    .background(MueTheme.colors.textQuiet),
            )
            MueText("Edit measurement", MueTheme.typography.sectionTitle)
            MuePickerField(label = "Date", value = "August 18, 2026", onClick = {})
            MuePrimaryButton(label = "Save changes", onClick = {})
            MueSecondaryButton(
                label = "Delete measurement",
                contentColor = MueTheme.colors.error,
                onClick = {},
            )
        }
    }
}
