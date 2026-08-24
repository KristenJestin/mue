package fr.kristenjestin.mue.ui

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
