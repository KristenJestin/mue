package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val BAND = "test:stickyAction"
private const val ROWS = 40

/** Left of the gutter, so nothing a caller puts in the band can colour the probe. */
private const val PROBE_X = 4

/** Inside the band's bottom padding: solid canvas, and nothing to press. */
private val SolidProbeInset: Dp = 4.dp

/**
 * The band's two halves and the promise each of them makes.
 *
 * The ramp is a fade painted over content that is still there: it may draw, and it may not
 * answer. The solid block is the opposite — it is chrome you can see, so it swallows what
 * lands on it rather than scrolling something invisible behind. A form was shipped with the
 * first half wrong, and nothing here to notice.
 */
class MueStickyBottomActionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var list: LazyListState
    private var taps = 0

    /** The defect: a thumb resting in the fade above `Save` still scrolls what is under it. */
    @Test
    fun aDragThatStartsInTheRampScrollsTheListBehindIt() {
        listWithBand()

        dragUpFrom(rampCentre())

        assertTrue("the list did not move", scrolled())
    }

    @Test
    fun aDragThatStartsOnTheSolidBlockLeavesTheListWhereItIs() {
        listWithBand()

        dragUpFrom(solidBlockPoint())

        assertFalse("the list moved under chrome that hides it", scrolled())
    }

    @Test
    fun aDragThatStartsOnTheActionItselfLeavesTheListWhereItIs() {
        listWithBand()

        dragUpFrom(saveButtonCentre())

        assertFalse("the list moved under the action", scrolled())
    }

    /** The ramp must cost the action nothing: same target, same tap. */
    @Test
    fun theActionKeepsItsTouchTargetAndItsTap() {
        listWithBand()

        val height = compose.onNodeWithText(SAVE_LABEL).getBoundsInRoot().height
        assertTrue("$height is under $MueMinTouchTarget", height >= MueMinTouchTarget)

        compose.onNodeWithText(SAVE_LABEL).performClick()
        assertEquals(1, taps)
    }

    /** An edge while it is hiding something, nothing at all while it is not. */
    @Test
    fun theBandDrawsItsEdgeOnlyWhileItCoversContent() {
        var covers by mutableStateOf(false)
        compose.setContent {
            MueTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MueTheme.colors.canvas),
                ) {
                    MueStickyBottomAction(
                        modifier = Modifier.align(Alignment.BottomCenter).testTag(BAND),
                        coversContent = covers,
                    ) {
                        MuePrimaryButton(label = SAVE_LABEL, onClick = {})
                    }
                }
            }
        }

        assertEquals("an edge over nothing", canvasColour(), edgeColour())

        covers = true
        compose.waitForIdle()

        assertNotEquals("no edge over hidden content", canvasColour(), edgeColour())
    }

    // region harness

    private fun listWithBand() {
        compose.setContent {
            list = rememberLazyListState()
            MueTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MueTheme.colors.canvas),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = list) {
                        items(ROWS) { index ->
                            MueText(
                                text = "Row $index",
                                style = MueTheme.typography.body,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MueTheme.spacing.lg),
                            )
                        }
                    }
                    MueStickyBottomAction(
                        modifier = Modifier.align(Alignment.BottomCenter).testTag(BAND),
                        coversContent = list.canScrollForward,
                    ) {
                        MuePrimaryButton(label = SAVE_LABEL, onClick = { taps++ })
                    }
                }
            }
        }
    }

    private fun scrolled(): Boolean =
        list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0

    private fun dragUpFrom(y: Float) {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, y),
                end = Offset(centerX, y - DRAG_PIXELS),
                durationMillis = DRAG_MILLIS,
            )
        }
        compose.waitForIdle()
    }

    private fun rampCentre(): Float = with(compose.density) {
        (bandTop() + MueStickyActionRamp / 2f).toPx()
    }

    private fun solidBlockPoint(): Float = with(compose.density) {
        (compose.onNodeWithTag(BAND).getBoundsInRoot().bottom - SolidProbeInset).toPx()
    }

    private fun saveButtonCentre(): Float = with(compose.density) {
        val button = compose.onNodeWithText(SAVE_LABEL).getBoundsInRoot()
        (button.top + button.height / 2f).toPx()
    }

    private fun bandTop() = compose.onNodeWithTag(BAND).getBoundsInRoot().top

    /** The row the hairline occupies when there is one, read in the band's own image. */
    private fun edgeColour() = bandPixel(rampPixels() + 1)

    /** A row far enough below it to be plain canvas whatever the band is doing. */
    private fun canvasColour() = bandPixel(rampPixels() + CANVAS_PROBE_OFFSET)

    private fun bandPixel(y: Int) =
        compose.onNodeWithTag(BAND).captureToImage().toPixelMap()[PROBE_X, y]

    private fun rampPixels() = with(compose.density) { MueStickyActionRamp.roundToPx() }

    // endregion

    private companion object {
        const val SAVE_LABEL = "Save activity"
        const val DRAG_PIXELS = 400f
        const val DRAG_MILLIS = 200L

        /** Clear of the hairline, still inside the band's top padding. */
        const val CANVAS_PROBE_OFFSET = 12
    }
}
