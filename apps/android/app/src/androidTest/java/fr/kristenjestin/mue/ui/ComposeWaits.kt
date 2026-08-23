package fr.kristenjestin.mue.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/** How long a confirmation is given to appear before the test calls it a failure. */
private const val AWAIT_TIMEOUT_MILLIS = 3_000L

/**
 * Waits for [text] to be on screen.
 *
 * The save confirmation is a sequence rather than a state: the button lets its label go
 * before the new word comes in, so nothing asserts true on the frame of the tap.
 */
fun ComposeTestRule.awaitText(text: String) {
    waitUntil(AWAIT_TIMEOUT_MILLIS) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
