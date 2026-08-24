package fr.kristenjestin.mue.ui.timer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The chassis banner of PRD_ACTIVITY_TIMER 6.4 and contract decision 1.
 *
 * The banner's *placement* — outside the animated content, above the tab bar — belongs to the
 * shell and is proved in `MueNavigationHostTest`; what it says and what a tap does is here.
 */
class TimerBannerTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * PRD 6.4: the icon, the label and the elapsed time.
     *
     * Read off the **unmerged** tree, because the merge is the point: PRD 6.4 makes the whole
     * strip one implicit `Open`, so `clickable` folds the label and the value into a single
     * node and a screen reader stops on the strip once instead of four times. The two pieces
     * are still asserted separately here — merged, they would only be a concatenation nobody
     * could pin a rule to.
     */
    @Test
    fun aRunningTimerShowsItsLabelAndItsElapsedTime() {
        setBanner()

        compose.onNodeWithTag(TimerTestTags.BANNER).assertIsDisplayed()
        compose.onNodeWithTag(TimerTestTags.BANNER_LABEL, useUnmergedTree = true)
            .assertTextEquals("Treadmill walk")
        compose.onNodeWithTag(TimerTestTags.BANNER_VALUE, useUnmergedTree = true)
            .assertTextEquals(TimerFormat.elapsed(duration()))
    }

    /** PRD 6.4 and 11: the word `Paused` takes the value's place, never a colour alone. */
    @Test
    fun aPausedTimerReadsPausedInsteadOfATime() {
        setBanner(status = TimedDraftStatus.PAUSED)

        compose.onNodeWithTag(TimerTestTags.BANNER_VALUE, useUnmergedTree = true)
            .assertTextEquals(TimerMessages.PAUSED)
    }

    /**
     * The same rule as the chronometer's: this value changes once a second while it is on
     * screen, so it is never a live region (PRD 11).
     *
     * Asserted on the strip as well as on the value, and the strip is the one that matters:
     * it is the node TalkBack focuses, and a live region there would read the whole banner out
     * again every single second.
     */
    @Test
    fun theBannerValueIsNeverALiveRegion() {
        setBanner()

        compose.onNodeWithTag(TimerTestTags.BANNER).assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion),
        )
        compose.onNodeWithTag(TimerTestTags.BANNER_VALUE, useUnmergedTree = true).assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion),
        )
    }

    /** PRD 6.4: the whole surface is one implicit `Open`. */
    @Test
    fun theWholeStripOpensTheTimer() {
        var opened = 0
        setBanner(onOpen = { opened++ })

        compose.onNodeWithTag(TimerTestTags.BANNER).performClick()

        assertEquals(1, opened)
    }

    /** No timer, no banner — it exists exactly while one does. */
    @Test
    fun noTimerMeansNoBanner() {
        compose.setContent {
            MueTheme {
                TimerBanner(timer = null, notice = null, visible = true, onOpen = {})
            }
        }

        compose.onNodeWithTag(TimerTestTags.BANNER).assertDoesNotExist()
    }

    /** It hides while the timer's own screen is showing the very same timer. */
    @Test
    fun theBannerGoesWhileTheTimerScreenIsOnTop() {
        setBanner(visible = false)

        compose.onNodeWithTag(TimerTestTags.BANNER).assertDoesNotExist()
    }

    // region the notice (contract decision 1)

    /**
     * FR-TIMER-002 lands here whenever the timer screen is not the surface on show.
     *
     * Deliberately on the **merged** tree, unlike the label and the value above: the notice has
     * to survive the strip's merge to be announced at all, so finding it here is the assertion.
     */
    @Test
    fun aNoticeIsCarriedByTheBanner() {
        setBanner(notice = TimerNotice.ALREADY_IN_PROGRESS)

        compose.onNodeWithTag(TimerTestTags.NOTICE)
            .assertTextEquals(TimerMessages.ALREADY_IN_PROGRESS)
    }

    /**
     * And the live region survives with it.
     *
     * `LiveRegion` merges by the default policy — the parent's value wins, and an absent one
     * drops the property — so a notice folded into the `clickable` strip would be drawn and
     * never spoken. The merged tree is what TalkBack reads, which is why neither assertion here
     * is allowed to fall back to the unmerged one.
     */
    @Test
    fun theNoticeIsAnnouncedRatherThanRead() {
        setBanner(notice = TimerNotice.CHECK_ACTIVITY_TIME)

        compose.onNodeWithTag(TimerTestTags.NOTICE).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
        )
    }

    @Test
    fun noNoticeMeansNoLine() {
        setBanner()

        compose.onNodeWithTag(TimerTestTags.NOTICE).assertDoesNotExist()
    }

    // endregion

    private fun setBanner(
        status: TimedDraftStatus = TimedDraftStatus.RUNNING,
        notice: TimerNotice? = null,
        visible: Boolean = true,
        onOpen: () -> Unit = {},
    ) {
        compose.setContent {
            MueTheme {
                TimerBanner(
                    timer = previewBannerTimer(
                        status = status,
                        movement = Movement.WALKING,
                        equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
                    ),
                    notice = notice,
                    visible = visible,
                    onOpen = onOpen,
                )
            }
        }
    }

    private fun duration(): ActivityDuration =
        requireNotNull(ActivityDuration.ofSecondsOrNull(1_543))
}
