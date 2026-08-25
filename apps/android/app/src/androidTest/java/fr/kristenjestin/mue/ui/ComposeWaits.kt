package fr.kristenjestin.mue.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.theme.MueMotion

private const val FRAME_MILLIS = 16

/**
 * Steps the frame clock until the save button has gone quiet and reads `Saved`.
 *
 * The confirmation is a sequence rather than the state of the frame the tap landed on: the
 * label lets go of `Save measurement` before the word comes in. That sequence is driven by
 * delays on Compose's own clock, which under test is virtual and moves only when a test
 * moves it — so the caller freezes it with `mainClock.autoAdvance = false` before the tap
 * and this walks it forward one frame at a time.
 *
 * It stops on the first frame that shows the word rather than jumping to a computed instant,
 * so it can neither land early nor run past the end of the hold.
 */
fun ComposeTestRule.advanceToTheQuietButton(label: String = MueSaveConfirmationLabel) {
    repeat(MueMotion.SaveConfirmationMillis / FRAME_MILLIS) {
        if (onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) return
        mainClock.advanceTimeByFrame()
    }
    throw AssertionError("the button never said `$label`")
}

/**
 * The editable field a screen tagged, whether the tag landed on the field or on its container.
 *
 * `MueTextField` is a labelled container wrapping a `BasicTextField`, and a `testTag` given to
 * it lands on the container — which carries no `RequestFocus`, so `performTextReplacement`
 * cannot act on it. Rather than merge the field's semantics into its label and value, which
 * would change what a screen reader announces for every form in the app, the tests reach the
 * field under the tag. Tagging the field directly keeps working, hence the `or`.
 */
fun ComposeTestRule.field(tag: String): SemanticsNodeInteraction = onNode(
    hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
)

/**
 * Moves a `MueWheelPicker` the way an assistive service moves it.
 *
 * The wheel publishes no text and no set-text action: it is an adjustable control, so a test
 * drives it through the very `SetProgress` action TalkBack's adjust gesture uses. Aiming a
 * synthetic swipe at it instead would prove the gesture works and nothing at all about the
 * contract PRD_ACTIVITIES 15 asks of it.
 */
fun ComposeTestRule.setWheel(tag: String, value: Int) {
    onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    waitForIdle()
}

/** What the wheel currently reads, taken from the range info a screen reader would announce. */
fun ComposeTestRule.wheelValue(tag: String): Int = onNodeWithTag(tag)
    .fetchSemanticsNode()
    .config[SemanticsProperties.ProgressBarRangeInfo]
    .current
    .toInt()
