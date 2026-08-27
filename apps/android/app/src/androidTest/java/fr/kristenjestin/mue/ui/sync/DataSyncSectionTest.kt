package fr.kristenjestin.mue.ui.sync

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.Locale

/**
 * Sync PRD 9.1, drawn from a fixed state so all four of its states can be shown on demand.
 *
 * ## Which tree each assertion is in
 *
 * The status row publishes one content description and **clears its descendants**, exactly as
 * `announcedAs` does elsewhere in this app. So `Synced`, the server name and the timestamp are
 * *not* in the merged tree as text, and `onNodeWithText("Synced")` would find nothing. Every
 * assertion about the state therefore goes through
 * [SyncTestTags.STATUS_LINE] and `assertContentDescriptionEquals`.
 *
 * The count lines, the error line and the two buttons are ordinary text nodes and are matched by
 * their words — which are the semantics strings, whole, however the glyphs fall at a large font
 * scale.
 */
@RunWith(AndroidJUnit4::class)
class DataSyncSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val locale: Locale = Locale.UK
    private val lastSync = 1_756_240_000_000L

    private val paired = DataSyncUiState(
        status = SyncStatus.SYNCED,
        serverName = "mue.home.arpa",
        account = "kris@example.org",
        lastSuccessAt = lastSync,
    )

    @Test
    fun anUnpairedPhoneSaysNotConnectedAndNamesNoServer() {
        setContent(DataSyncUiState())

        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE)
            .assertContentDescriptionEquals("Not connected. No server.")
        composeRule.onNodeWithText(SyncMessages.NOT_CONNECTED_BODY).assertExists()
    }

    @Test
    fun aSyncedPhoneNamesTheServerAndWhenItLastSucceeded() {
        setContent(paired)

        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE)
            .assertContentDescriptionEquals(
                "Synced. mue.home.arpa. ${SyncMessages.lastSync(lastSync, locale, zone)}.",
            )
    }

    /**
     * The line the owner lost a history for. A queued row and `Synced` on the same card is the
     * one contradiction the section may never render.
     */
    @Test
    fun aQueuedChangeIsCountedAndTheStateIsNotSynced() {
        setContent(
            paired.copy(status = SyncStatus.CHANGES_PENDING, outstandingChanges = 3),
        )

        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE)
            .assertContentDescriptionEquals(
                "Changes pending. mue.home.arpa. ${SyncMessages.lastSync(lastSync, locale, zone)}.",
            )
        composeRule.onNodeWithText("3 changes waiting to be sent").assertExists()
    }

    /** PRD 9.1: the count is shown "lorsqu'il est non nul", and therefore not when it is zero. */
    @Test
    fun aCountOfZeroIsNotDrawnAtAll() {
        setContent(paired)

        composeRule.onNodeWithText("0 changes waiting to be sent").assertDoesNotExist()
    }

    /** FR-SYNC-007: a refused mutation is counted, named and never folded into the queue. */
    @Test
    fun aRefusedChangeIsNamedSeparatelyFromTheQueue() {
        setContent(
            paired.copy(
                status = SyncStatus.SYNC_ISSUE,
                outstandingChanges = 3,
                refusedChanges = 1,
                lastErrorMessage = "The server could not be reached.",
            ),
        )

        composeRule.onNodeWithText("3 changes waiting to be sent").assertExists()
        composeRule.onNodeWithText(SyncMessages.refused(1)!!).assertExists()
        composeRule.onNodeWithText("The server could not be reached.").assertExists()
    }

    /** PRD 13.4's rows never drain, so a number that never falls is explained rather than shown. */
    @Test
    fun undeliverableChangesSayTheyAreNotLost() {
        setContent(
            paired.copy(
                status = SyncStatus.CHANGES_PENDING,
                outstandingChanges = 2,
                undeliverableChanges = 2,
            ),
        )

        composeRule.onNodeWithText(SyncMessages.undeliverable(2)!!).assertExists()
    }

    /** A stale message from a run that later succeeded must not be shown beside `Synced`. */
    @Test
    fun aLastErrorIsOnlyShownWhileTheStateIsASyncIssue() {
        setContent(paired.copy(lastErrorMessage = "The server could not be reached."))

        composeRule.onNodeWithText("The server could not be reached.").assertDoesNotExist()
    }

    @Test
    fun bothActionsOfTheSectionArePresent() {
        setContent(paired)

        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).assertIsEnabled()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).assertIsEnabled()
    }

    /** Pressing `Sync now` with no server would run an engine that returns `NotPaired`. */
    @Test
    fun syncNowIsInertWithNoServerAndServerSettingsIsNot() {
        setContent(DataSyncUiState())

        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).assertIsNotEnabled()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).assertIsEnabled()
    }

    @Test
    fun syncNowIsInertWhileASynchronisationIsRunning() {
        setContent(paired.copy(syncing = true))

        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).assertIsNotEnabled()
    }

    @Test
    fun theTwoActionsCallBack() {
        var syncs = 0
        var settings = 0
        setContent(paired, onSyncNow = { syncs++ }, onOpenServerSettings = { settings++ })

        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).performClick()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performClick()

        assertEquals(1, syncs)
        assertEquals(1, settings)
    }

    /** The result of a `Sync now` is shown where it was pressed, whichever way it went. */
    @Test
    fun theOutcomeOfTheLastRunIsShown() {
        setContent(
            paired.copy(
                syncNote = SyncNote("Synchronised: 2 changes sent, 0 changes received.", false),
            ),
        )

        composeRule.onNodeWithText("Synchronised: 2 changes sent, 0 changes received.")
            .assertExists()
    }

    /**
     * A callback that is not given is a button that is not drawn.
     *
     * The section is embedded in `Server settings`, which can perform neither action — its
     * `Server settings` would re-open the screen it is on, and it is the one control there named
     * after what people arrive wanting to do, so drawing it inert was a false path placed exactly
     * where the eye lands. The nullable callback is what makes rendering it impossible rather
     * than merely discouraged.
     */
    @Test
    fun anActionWithNoCallbackIsNotDrawnAtAll() {
        setContent(paired, onSyncNow = null, onOpenServerSettings = null)

        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).assertDoesNotExist()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).assertDoesNotExist()
        // Everything PRD 9.1 asks the section to *say* is still said.
        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE).assertExists()
    }

    /** Each is independent: a screen may keep one and drop the other. */
    @Test
    fun theTwoActionsAreDroppedSeparately() {
        setContent(paired, onOpenServerSettings = null)

        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).assertIsEnabled()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).assertDoesNotExist()
    }

    private fun setContent(
        state: DataSyncUiState,
        onSyncNow: (() -> Unit)? = {},
        onOpenServerSettings: (() -> Unit)? = {},
    ) {
        composeRule.setContent {
            MueTheme {
                DataSyncSection(
                    state = state,
                    onSyncNow = onSyncNow,
                    onOpenServerSettings = onOpenServerSettings,
                    locale = locale,
                    zone = zone,
                )
            }
        }
    }
}
