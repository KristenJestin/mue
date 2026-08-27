package fr.kristenjestin.mue.ui.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.pairing.PairingFailure
import fr.kristenjestin.mue.ui.field
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
    fun aPairedPhoneShowsTheServerAndTheAccountItBelongsTo() {
        setContent(paired, PairingFormState())

        composeRule.onNodeWithTag(SyncTestTags.DISCONNECT_BUTTON).assertExists()
        composeRule.onNodeWithText("${SyncMessages.ACCOUNT_LABEL} kris@example.org").assertExists()
        // The account is the one thing this screen may not offer to change: there is no field to
        // type a second email into, which is how PRD 9.3's refusal to merge is kept here.
        composeRule.onNodeWithTag(SyncTestTags.EMAIL_FIELD).assertDoesNotExist()
    }

    // --- signing in again, without giving the pairing up ---------------------------------------

    /**
     * The defect this file was reopened for.
     *
     * A phone whose bearer the server has stopped accepting was shown `Sync issue`, the server's
     * own `Sign in to synchronise.`, and one control: `Disconnect server`. The address was right,
     * the account was right, only the token was stale — so the only way to obey the instruction
     * was to destroy a correct pairing and retype it. PRD 9.3's "se reconnecter au même compte
     * reprend la synchronisation" is a supported path, and now it has a button.
     */
    @Test
    fun aPairedPhoneCanSignInAgainWithoutDisconnectingFirst() {
        setContent(paired, PairingFormState(address = "https://mue.home.arpa"))

        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(SyncTestTags.PASSWORD_FIELD).assertExists()
        // The address travels with the sign-in: a router that hands out a new one is a field to
        // edit, not a reason to disconnect.
        composeRule.field(SyncTestTags.ADDRESS_FIELD).assertTextContains("https://mue.home.arpa")
    }

    /** A refused session is named where it can be undone, and not only in the status line. */
    @Test
    fun aRejectedSessionIsNamedInTheCardThatCanRestoreIt() {
        setContent(
            paired.copy(status = SyncStatus.SYNC_ISSUE, sessionRejected = true),
            PairingFormState(address = "https://mue.home.arpa"),
        )

        composeRule.onNodeWithText(SyncMessages.SESSION_REJECTED_BODY).performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).performScrollTo().assertIsEnabled()
    }

    /** A healthy pairing gets the same controls, described as what they also are. */
    @Test
    fun aHealthyPairingIsOfferedTheSameControlsWithoutBeingToldSomethingIsWrong() {
        setContent(paired, PairingFormState(address = "https://mue.home.arpa"))

        composeRule.onNodeWithText(SyncMessages.SIGN_IN_AGAIN_BODY).performScrollTo().assertExists()
        composeRule.onNodeWithText(SyncMessages.SESSION_REJECTED_BODY).assertDoesNotExist()
    }

    /** It reaches the sign-in callback, and not the pairing one that would adopt an account. */
    @Test
    fun signingInAgainReachesItsOwnCallback() {
        var signedIn = 0
        var connected = 0
        setContent(
            paired,
            PairingFormState(address = "https://mue.home.arpa"),
            onConnect = { connected++ },
            onSignInAgain = { signedIn++ },
        )

        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).performScrollTo().performClick()

        assertEquals(1, signedIn)
        assertEquals(0, connected)
    }

    /** PRD 9.3's rule, said out loud on the screen that could be mistaken for a way around it. */
    @Test
    fun theSignInNamesTheAccountItWillNotChangeAndWhereToChangeIt() {
        setContent(paired, PairingFormState())

        composeRule.onNodeWithText(SyncMessages.boundToAccount("kris@example.org"))
            .performScrollTo()
            .assertExists()
    }

    /** Kept, and kept second: the deliberate exit rather than the only door. */
    @Test
    fun disconnectRemainsAvailableBesideTheSignIn() {
        setContent(paired, PairingFormState())

        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).assertExists()
        composeRule.onNodeWithTag(SyncTestTags.DISCONNECT_BUTTON).assertExists()
    }

    @Test
    fun theSignInIsFrozenWhileAnAttemptIsInFlight() {
        setContent(paired, PairingFormState(address = "https://mue.home.arpa", connecting = true))

        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(SyncMessages.SIGNING_IN).assertExists()
    }

    /** A refused sign-in is named on the card that asked for it, exactly as a pairing is. */
    @Test
    fun aRefusedSignInIsNamedOnThePairedCardToo() {
        setContent(
            paired,
            PairingFormState(
                address = "https://mue.home.arpa",
                failure = PairingFailure.CredentialsRejected.message,
            ),
        )

        composeRule.onNodeWithTag(SyncTestTags.PAIRING_FAILURE).performScrollTo().assertExists()
        composeRule.onNodeWithText(PairingFailure.CredentialsRejected.message).assertExists()
    }

    /**
     * Every failure this card can show must name a control this card has.
     *
     * The paired card asks for a password and an address; it has no email field, deliberately.
     * A message telling the user to enter an email address would be the same fault as the one
     * the screen was rebuilt to remove — an instruction with nothing to obey it — so the empty
     * password is answered by [PairingFailure.PasswordMissing] and not `CredentialsMissing`.
     */
    @Test
    fun everyFailureShownOnThePairedCardNamesSomethingThatCardHas() {
        setContent(
            paired,
            PairingFormState(
                address = "https://mue.home.arpa",
                failure = PairingFailure.PasswordMissing.message,
            ),
        )

        composeRule.onNodeWithText(PairingFailure.PasswordMissing.message).performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag(SyncTestTags.PASSWORD_FIELD).assertExists()
        composeRule.onNodeWithTag(SyncTestTags.EMAIL_FIELD).assertDoesNotExist()
        composeRule.onNodeWithText(PairingFailure.CredentialsMissing.message).assertDoesNotExist()
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

        // `field()` and not `onNodeWithTag`: `MueTextField` tags the labelled group, and the node
        // that owns the text actions is the inner one. Addressing the group fails with
        // "RequestFocus is not defined", which is what this test did on every run.
        //
        composeRule.field(SyncTestTags.ADDRESS_FIELD).performTextInput("https://mue.home.arpa")
        composeRule.field(SyncTestTags.EMAIL_FIELD).performTextInput("kris@example.org")
        composeRule.field(SyncTestTags.PASSWORD_FIELD).performTextInput("correct horse")

        // The empty emissions are dropped rather than expected. The state is hoisted and this
        // test never feeds it back, so every field still reads "" after its `onValueChange` and
        // the IME ends the composing region with a second, empty call. That is an artefact of a
        // Composable driven without its state holder; what the test is about is that each field's
        // text reached its own callback, in order, unaltered.
        assertEquals(
            listOf("https://mue.home.arpa", "kris@example.org", "correct horse"),
            typed.filter(String::isNotEmpty),
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
    }

    /**
     * Split from the test above, which called [assertNamed] twice and so called `setContent`
     * twice on one activity — `IllegalStateException: has already set content`, every run. The
     * two failures it meant to tell apart were never both rendered.
     */
    @Test
    fun aCertificateAndroidRefusedIsToldApartFromAServerThatDidNotAnswer() {
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
        onSignInAgain: () -> Unit = {},
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
                    onSignInAgain = onSignInAgain,
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
