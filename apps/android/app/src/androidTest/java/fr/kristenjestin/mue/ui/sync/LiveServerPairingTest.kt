package fr.kristenjestin.mue.ui.sync

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.MainActivity
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.field
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Pairs with a Mue Platform that is actually running, over TLS, and proves a weight left the
 * phone.
 *
 * Every other test in `ui/sync` drives a state-hoisted Composable or a `MockEngine`, which is
 * the right shape for a rule and proves nothing about a socket. This one is the opposite and is
 * the only test in the suite that is: real `MainActivity`, real `AppContainer`, real Room, real
 * OkHttp, real certificate verification, a real Better Auth session and a real row in
 * PostgreSQL at the end of it. It is what says the three pieces added for the home network fit
 * together — the server's `tls:` block, the certificate authority installed on the device, and
 * `app/src/debug/res/xml/network_security_config.xml` that lets a debug build look at it.
 *
 * ## It skips unless it is told where to go
 *
 * No address is hard-coded and there is no default. Without `mueLiveServer` the test assumes
 * itself out, so `connectedDebugAndroidTest` on a machine with no server running stays green
 * and this file never becomes the reason a suite is red. Run it deliberately:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=fr.kristenjestin.mue.ui.sync.LiveServerPairingTest \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLiveServer=https://192.168.1.100:3000 \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLiveEmail=you@example.org \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLivePassword=…
 * ```
 *
 * The account must already exist: the Android client has no sign-up path (PRD 9.2 signs in with
 * an account the server already holds), so it is created against `/api/auth/sign-up/email` once,
 * by hand, before the first run.
 *
 * ## What it does not assert
 *
 * It does not read PostgreSQL — an instrumented test has no business holding a database
 * credential, and a phone that reports `Synced` while the server stored nothing is exactly the
 * lie this whole exercise exists to catch. So this half proves the phone got a `2xx` for its
 * push, and the row itself is read out of band, by hand, against `mue_app.measurements`. The
 * weight below is deliberately odd so it can be found there with certainty.
 */
@RunWith(AndroidJUnit4::class)
class LiveServerPairingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * A date and a weight nothing else would produce, so the row in `mue_app.measurements` can
     * only have come from this test. 73.45 kg lands on the 0.05 kg step of BR-003.
     */
    private val date = LocalDate.of(2019, 3, 14)
    private val weight = requireNotNull(Weight.ofHundredthsOrNull(7_345))

    @Test
    fun pairsOverTlsAndSendsTheWeightItAlreadyHad() {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("mueLiveServer")
        val email = arguments.getString("mueLiveEmail")
        val password = arguments.getString("mueLivePassword")
        assumeTrue(
            "set -e mueLiveServer/mueLiveEmail/mueLivePassword to run this against a server",
            !server.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank(),
        )

        /*
         * Written before the pairing, on purpose. `SyncOutbox` journals a row whether or not a
         * server is paired, and FR-SYNC-003 has the first synchronisation send the local history
         * it finds waiting — so this is the shape of the thing that actually matters: a history
         * that existed on the phone first and reached the server afterwards.
         */
        val application = ApplicationProvider.getApplicationContext<MueApplication>()
        runBlocking {
            application.container.measurementRepository.save(Measurement(date, weight))
        }

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performScrollTo().performClick()

        composeRule.field(SyncTestTags.ADDRESS_FIELD).performTextReplacement(server!!)
        composeRule.field(SyncTestTags.EMAIL_FIELD).performTextReplacement(email!!)
        composeRule.field(SyncTestTags.PASSWORD_FIELD).performTextReplacement(password!!)
        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).performScrollTo().performClick()

        /*
         * Polled rather than `waitUntil`, and the whole reason is the failure message.
         *
         * `waitUntil` raises a `ComposeTimeoutException` that says "condition still not satisfied"
         * and not one word more — and here the interesting outcomes are all silent ones. An
         * untrusted certificate, a refused password, a server that answered the sign-in and then
         * refused the push: three unrelated faults, one blank timeout. So this loop ends by
         * reading the phone's own `sync_state` row and putting it in the assertion, because that
         * row is where the engine writes what actually went wrong.
         */
        val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (synced() || pairingFailures().isNotEmpty()) break
            Thread.sleep(POLL_MILLIS)
        }

        val failures = pairingFailures()
        assertTrue("pairing refused: ${failures.joinToString(" | ")} — ${diagnosis()}", failures.isEmpty())

        /*
         * `Synced` and not merely "connected". `SyncStatuses.derive` only reaches it with nothing
         * pending, nothing failed and a `lastSuccessAt` on the row — which is to say the push was
         * acknowledged. `Changes pending` here would mean the phone paired and the weight is
         * still sitting in the outbox, which is the failure this test exists to tell apart from
         * success.
         */
        assertTrue(
            "the status line never reached `${SyncMessages.STATE_SYNCED}`: " +
                "${statusDescriptions()} — ${diagnosis()}",
            synced(),
        )
    }

    /** Whether the status row is announcing `Synced`, wherever on screen it is. */
    private fun synced(): Boolean =
        statusDescriptions().any { it.startsWith(SyncMessages.STATE_SYNCED) }

    /** What a screen reader would announce for the status row. */
    private fun statusDescriptions(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.STATUS_LINE)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    /** The text under `sync:pairingFailure`, empty while nothing has gone wrong. */
    private fun pairingFailures(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.PAIRING_FAILURE)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }

    /**
     * The phone's own account of the exchange, for the failure message.
     *
     * Read straight out of Room through the shipped container — `sync_state.last_error_code` and
     * `last_error_message` are what `SyncEngine` writes when a run fails, and the outbox counts
     * say whether the weight left. `connectedAndroidTest` uninstalls the app when it finishes,
     * so this is the only chance anyone gets to look.
     */
    private fun diagnosis(): String = runBlocking {
        val sync = ApplicationProvider.getApplicationContext<MueApplication>().container.sync
        val state = sync.syncDao.syncState()
        "sync_state(server=${state?.serverUrl}, account=${state?.accountId}, " +
            "cursor=${state?.cursor}, lastSuccessAt=${state?.lastSuccessAt}, " +
            "lastErrorCode=${state?.lastErrorCode}, lastErrorMessage=${state?.lastErrorMessage}), " +
            "pending=${sync.syncDao.countInState("pending")}, " +
            "inflight=${sync.syncDao.countInState("inflight")}, " +
            "failed=${sync.syncDao.countInState("failed")}"
    }

    private companion object {
        /**
         * Ninety seconds: a DNS lookup, a TLS handshake, a Better Auth sign-in, a session check
         * and a full push/pull exchange, over a WiFi link an emulator reaches through NAT.
         */
        const val PAIRING_TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 250L
    }
}
