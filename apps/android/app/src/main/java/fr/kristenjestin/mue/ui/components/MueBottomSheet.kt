package fr.kristenjestin.mue.ui.components

import android.view.WindowManager
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlinx.coroutines.launch

/** Drag distance past which releasing dismisses the sheet. */
private val DismissThreshold = 120.dp

object MueBottomSheetDefaults {

    /**
     * How much of the window a panel may take. The prototypes cap the exercise picker at 82 %
     * and the catalogue picker at 78 %; the taller of the two is the default because the cap
     * only ever bites on content that would otherwise cover the screen whole.
     */
    const val MaxHeightFraction: Float = 0.82f

    /** The catalogue picker's own cap, per `log-activity.html`. */
    const val CatalogueMaxHeightFraction: Float = 0.78f

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
 *
 * [bodyScrolls] is what makes a long catalogue usable. It is opt-in rather than always on
 * because it moves the dismissing drag from the whole panel to the handle and title: a panel
 * that both drags and scrolls on the same gesture would swallow every list flick. The three
 * sheets that predate the catalogues hold a calendar or four rows and keep the panel-wide drag.
 */
@Composable
fun MueBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    scrimContentDescription: String = "Close",
    contentPadding: PaddingValues = MueBottomSheetDefaults.contentPadding(),
    maxHeightFraction: Float = MueBottomSheetDefaults.MaxHeightFraction,
    bodyScrolls: Boolean = false,
    header: @Composable (ColumnScope.() -> Unit)? = null,
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
        /*
         * A dialog gets its own window, and that window defaults to `adjust=pan`: Android
         * shoves the whole panel up by just enough to reveal the caret and leaves the actions
         * below it under the keyboard. Panning also fights the inset padding below, which then
         * lifts an already-lifted panel.
         *
         * Opting the window out of decor fitting turns both off and leaves the IME as a plain
         * inset, so the panel is positioned by the same rule as the tab bar. `ADJUST_RESIZE`
         * covers API 26 to 29, where there is no IME inset to report and the window has to
         * resize instead.
         */
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        val dismissDrag = Modifier.draggable(
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

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelMaxHeight = maxHeight * maxHeightFraction

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
                    .heightIn(max = panelMaxHeight)
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
                    .then(if (bodyScrolls) Modifier else dismissDrag)
                    // A panel holding a text field has to clear the keyboard it raises. Same
                    // union as the tab bar: the IME inset already covers the navigation bar,
                    // so chaining the two would lift the panel a navigation bar too far.
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
            ) {
                Column(
                    modifier = if (bodyScrolls) dismissDrag else Modifier,
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
                    header?.invoke(this)
                }

                if (bodyScrolls) {
                    Column(
                        // `fill = false` so a short catalogue still lets the panel wrap its
                        // content instead of standing at the full cap with a gap under it.
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
                        content = content,
                    )
                } else {
                    content()
                }
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
