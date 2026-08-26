package fr.kristenjestin.mue.ui.sync

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.profile.ProfileScreen
import fr.kristenjestin.mue.ui.profile.ProfileUiState
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Sync PRD 9.1 puts `Data & sync` **in `Profile`**, and nowhere else in the app.
 *
 * This test asserts both halves of that sentence: the section is on the screen the PRD names, and
 * the state it shows is the state it was given — including the `Not connected` case, which 9.1
 * explicitly forbids from raising an alert anywhere. The other four screens are left with nothing
 * to assert *about*, which is the point; a badge on Entry would have to be added on purpose
 * before any test could catch it.
 */
@RunWith(AndroidJUnit4::class)
class ProfileDataSyncSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun theSectionIsOnTheProfileScreenUnderItsOwnHeading() {
        setContent(DataSyncUiState())

        composeRule.onNodeWithText(SyncMessages.SECTION_TITLE).performScrollTo().assertExists()
        composeRule.onNodeWithTag(SyncTestTags.SECTION).assertExists()
    }

    @Test
    fun theSectionShowsTheStateItWasGiven() {
        setContent(
            DataSyncUiState(
                status = SyncStatus.CHANGES_PENDING,
                serverName = "mue.home.arpa",
                lastSuccessAt = null,
                outstandingChanges = 2,
            ),
        )

        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE)
            .assertContentDescriptionEquals(
                "Changes pending. mue.home.arpa. ${SyncMessages.NEVER_SYNCED}.",
            )
        composeRule.onNodeWithText("2 changes waiting to be sent").performScrollTo().assertExists()
    }

    /** 9.1's last line: a phone with no server is a phone with nothing to worry about. */
    @Test
    fun anUnpairedPhoneIsToldOnceHereAndNowhereElse() {
        setContent(DataSyncUiState())

        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE)
            .assertContentDescriptionEquals("Not connected. No server.")
        composeRule.onNodeWithText(SyncMessages.NOT_CONNECTED_BODY).performScrollTo().assertExists()
    }

    @Test
    fun serverSettingsOpensFromTheSection() {
        var opened = 0
        setContent(DataSyncUiState(), onOpenServerSettings = { opened++ })

        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performScrollTo().performClick()

        assertEquals(1, opened)
    }

    private fun setContent(
        syncState: DataSyncUiState,
        onSyncNow: () -> Unit = {},
        onOpenServerSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            MueTheme {
                ProfileScreen(
                    state = ProfileUiState(displayName = "Kris", heightInput = "180"),
                    onDisplayNameChange = {},
                    onHeightChange = {},
                    onBirthDateChange = {},
                    onSave = {},
                    onSaveConfirmationFinished = {},
                    onHapticsEnabledChange = {},
                    onExport = {},
                    syncState = syncState,
                    onSyncNow = onSyncNow,
                    onOpenServerSettings = onOpenServerSettings,
                    today = today,
                )
            }
        }
    }
}
