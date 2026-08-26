package fr.kristenjestin.mue.ui.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.pairing.PairingFailure
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.Locale

/**
 * Sync PRD 9.2's fallback and 9.3's disconnection, rendered from a fixed state.
 *
 * The tests that matter most are the ones about *naming*: a person who has typed a private
 * hostname into a phone cannot tell a DNS failure from a certificate refusal from a wrong
 * password, so each of those must reach the glass as its own sentence. Every assertion below
 * therefore matches the exact `PairingFailure.message`, which is the string the data layer built
 * and the screen must not have summarised.
 */
@RunWith(AndroidJUnit4::class)
class ServerSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val paired = DataSyncUiState(
        status = SyncStatus.SYNCED,
        serverName = "mue.home.arpa",
        account = "kris@example.org",
        lastSuccessAt = 1_756_240_000_000L,
    )

    @Test
    fun anUnpairedPhoneShowsTheFormAndNotTheDisconnection() {
        setContent(DataSyncUiState(), PairingFormState())

        composeRule.onNodeWithTag(SyncTestTags.ADDRESS_FIELD).assertExists()
        composeRule.onNodeWithTag(SyncTestTags.EMAIL_FIELD).assertExists()
        composeRule.onNodeWithTag(SyncTestTags.PASSWORD_FIELD).assertExists()
        composeRule.onNodeWithTag(SyncTestTags.DISCONNECT_BUTTON).assertDoesNotExist()
    }

    @Test
    fun aPairedPhoneShowsTheServerAndNotTheForm() {
        setContent(paired, PairingFormState())

        composeRule.onNodeWithTag(SyncTestTags.DISCONNECT_BUTTON).assertExists()
        composeRule.onNodeWithTag(SyncTestTags.ADDRESS_FIELD).assertDoesNotExist()
        composeRule.onNodeWithText("${SyncMessages.ACCOUNT_LABEL} kris@example.org").assertExists()
    }

    /** PRD 9.2 says the password is used once; the screen says so before it is typed. */
    @Test
    fun theFormStatesThatThePasswordIsNotStored() {
        setContent(DataSyncUiState(), PairingFormState())

        composeRule.onNodeWithText(SyncMessages.CONNECT_BODY).assertExists()
    }

    /** PRD 9.2's QR path is absent from this build, and the screen does not pretend otherwise. */
    @Test
    fun theAbsenceOfTheQrPathIsStatedRatherThanHidden() {
        setContent(DataSyncUiState(), PairingFormState())

        composeRule.onNodeWithText(SyncMessages.QR_NOTE).performScrollTo().assertExists()
    }

    @Test
    fun typingReachesTheCallbacks() {
        val typed = mutableListOf<String>()
        setContent(
            DataSyncUiState(),
            PairingFormState(),
            onAddressChange = { typed += it },
            onEmailChange = { typed += it },
            onPasswordChange = { typed += it },
        )

        composeRule.onNodeWithTag(SyncTestTags.ADDRESS_FIELD)
            .performTextReplacement("https://mue.home.arpa")
        composeRule.onNodeWithTag(SyncTestTags.EMAIL_FIELD)
            .performTextReplacement("kris@example.org")
        composeRule.onNodeWithTag(SyncTestTags.PASSWORD_FIELD)
            .performTextReplacement("correct horse")

        assertEquals(
            listOf("https://mue.home.arpa", "kris@example.org", "correct horse"),
            typed,
        )
    }

    @Test
    fun theFormIsFrozenWhileAnAttemptIsInFlight() {
        setContent(DataSyncUiState(), PairingFormState(connecting = true))

        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithText(SyncMessages.CONNECTING).assertExists()
    }

    // --- every failure reaches the glass by name -----------------------------------------------------

    @Test
    fun anUnresolvableHostSaysSoAndNotSomethingWentWrong() {
        assertNamed(PairingFailure.HostNotFound("mue.home.arpa"))
    }

    @Test
    fun aServerThatDidNotAnswerIsToldApartFromOneAndroidRefused() {
        assertNamed(PairingFailure.Unreachable("mue.home.arpa"))
        assertNamed(PairingFailure.UntrustedCertificate("mue.home.arpa", "hostname not verified"))
    }

    @Test
    fun aWrongPasswordSaysNothingOnThisPhoneWasChanged() {
        assertNamed(PairingFailure.CredentialsRejected)
    }

    @Test
    fun aServerTooOldForThisBuildIsNotReportedAsAWrongPassword() {
        assertNamed(PairingFailure.SignInUnsupported("mue.home.arpa"))
    }

    @Test
    fun somethingThatIsNotAMueServerSaysWhatItAnswered() {
        assertNamed(PairingFailure.NotAMueServer("mue.home.arpa", "It answered 404."))
    }

    /** PRD 9.3's trap, named in full so the way out is on the screen with the problem. */
    @Test
    fun asecondAccountIsRefusedInWordsThatNameBothAccounts() {
        val failure = PairingFailure.DifferentAccount("kris@example.org", "someone@example.org")
        assertNamed(failure)

        composeRule.onNodeWithTag(SyncTestTags.PAIRING_FAILURE).assertExists()
    }

    // --- PRD 9.3's confirmation -----------------------------------------------------------------------

    @Test
    fun disconnectingAsksFirst() {
        var confirmed = 0
        setContent(
            paired,
            PairingFormState(disconnectConfirmationVisible = true),
            onConfirmDisconnect = { confirmed++ },
        )

        composeRule.onNodeWithText(SyncMessages.DISCONNECT_TITLE).assertExists()
        composeRule.onNodeWithText(SyncMessages.DISCONNECT_CONFIRM).performClick()

        assertEquals(1, confirmed)
    }

    /** The first thing the question answers is the one thing anyone is afraid of. */
    @Test
    fun theConfirmationSaysWhatIsNotDeletedBeforeAnythingElse() {
        setContent(paired, PairingFormState(disconnectConfirmationVisible = true))

        composeRule.onNodeWithText(
            "${SyncMessages.DISCONNECT_BODY} This phone will stop synchronising with " +
                "mue.home.arpa.",
        ).assertExists()
    }

    @Test
    fun cancellingTheConfirmationChangesNothing() {
        var cancelled = 0
        setContent(
            paired,
            PairingFormState(disconnectConfirmationVisible = true),
            onCancelDisconnect = { cancelled++ },
        )

        composeRule.onNodeWithText(SyncMessages.CANCEL).performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun theDisconnectionIsRequestedRatherThanPerformedByTheButton() {
        var requested = 0
        var confirmed = 0
        setContent(
            paired,
            PairingFormState(),
            onRequestDisconnect = { requested++ },
            onConfirmDisconnect = { confirmed++ },
        )

        composeRule.onNodeWithTag(SyncTestTags.DISCONNECT_BUTTON).performScrollTo().performClick()

        assertEquals(1, requested)
        assertEquals(0, confirmed)
    }

    @Test
    fun theBackControlLeavesTheScreen() {
        var back = 0
        setContent(paired, PairingFormState(), onNavigateBack = { back++ })

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, back)
    }

    /** The section is repeated here read-only, so the state is visible while pairing is decided. */
    @Test
    fun theSectionIsRepeatedAboveTheForm() {
        setContent(DataSyncUiState(), PairingFormState())

        composeRule.onNodeWithTag(SyncTestTags.STATUS_LINE).assertExists()
        // ...but its `Sync now` is inert on a screen that has no server to sync with.
        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).assertIsNotEnabled()
    }

    @Test
    fun aSuccessfulPairingSaysWhatItConnectedTo() {
        setContent(
            paired,
            PairingFormState(success = "Connected to mue.home.arpa as kris@example.org."),
        )

        composeRule.onNodeWithText("Connected to mue.home.arpa as kris@example.org.")
            .performScrollTo()
            .assertExists()
    }

    /**
     * The account survives a disconnect so the guard does. Saying so *before* the sign-in is what
     * turns PRD 9.3's rule from a surprise at the end of a form into something discoverable.
     */
    @Test
    fun theFormNamesTheAccountThisPhonesDataAlreadyBelongsTo() {
        setContent(DataSyncUiState(account = "kris@example.org"), PairingFormState())

        composeRule.onNodeWithText(
            "The data on this phone is already synchronised with kris@example.org. " +
                "Sign in as kris@example.org to carry on.",
        ).assertExists()
    }

    private fun assertNamed(failure: PairingFailure) {
        setContent(DataSyncUiState(), PairingFormState(failure = failure.message))

        composeRule.onNodeWithText(failure.message).performScrollTo().assertExists()
        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).assertIsEnabled()
    }

    private fun setContent(
        state: DataSyncUiState,
        form: PairingFormState,
        onNavigateBack: () -> Unit = {},
        onAddressChange: (String) -> Unit = {},
        onEmailChange: (String) -> Unit = {},
        onPasswordChange: (String) -> Unit = {},
        onConnect: () -> Unit = {},
        onRequestDisconnect: () -> Unit = {},
        onCancelDisconnect: () -> Unit = {},
        onConfirmDisconnect: () -> Unit = {},
    ) {
        composeRule.setContent {
            MueTheme {
                ServerSettingsScreen(
                    state = state,
                    form = form,
                    onNavigateBack = onNavigateBack,
                    onAddressChange = onAddressChange,
                    onEmailChange = onEmailChange,
                    onPasswordChange = onPasswordChange,
                    onConnect = onConnect,
                    onRequestDisconnect = onRequestDisconnect,
                    onCancelDisconnect = onCancelDisconnect,
                    onConfirmDisconnect = onConfirmDisconnect,
                    locale = Locale.UK,
                    zone = ZoneId.of("Europe/Paris"),
                )
            }
        }
    }
}
