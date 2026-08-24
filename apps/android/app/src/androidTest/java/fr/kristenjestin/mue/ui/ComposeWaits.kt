package fr.kristenjestin.mue.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
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
