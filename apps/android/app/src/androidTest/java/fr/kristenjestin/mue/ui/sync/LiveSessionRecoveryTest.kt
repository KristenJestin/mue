package fr.kristenjestin.mue.ui.sync

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.ui.field
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The way back from a session the server has stopped accepting, against a server that actually
 * stops accepting it.
 *
 * ## What was wrong
 *
 * An account recreated on the server leaves the phone holding a bearer nothing will honour.
 * `/api/v1/sync/pull` answers `401` with `auth.unauthenticated` and the message
 * `Sign in to synchronise.`; `SyncEngine` records both, `Data & sync` reads `Sync issue` and
 * prints the server's sentence verbatim (FR-SYNC-008). And on `Server settings` the only control
 * was `Disconnect server` — so the single instruction the screen gave had no control behind it,
 * and obeying it meant destroying a `sync_state` row whose address, account and device id were
 * all still correct, then retyping them.
 *
 * ## Why this test is live and not a `MockEngine`
 *
 * Because the thing being proved is that a **real** Better Auth revocation is survivable. The
 * revocation here is the real one: the phone's own bearer, read out of the shipped Keystore
 * store, posted to `/api/auth/sign-out`, which is what `scripts/admin.ts sessions revoke` and
 * PRD 15.3's future Web admin do to a device. Everything after that — the 401, the recorded
 * code, the recovery, the acknowledged push — is the server's answer and not a fixture's.
 *
 * ## It skips unless it is told where to go
 *
 * Same rule as [LiveServerPairingTest], for the same reason: no address is hard-coded, and a
 * machine with no server keeps a green suite.
 *
 * ```
 * ./gradlew :app:assembleDebugAndroidTest
 * adb -s emulator-5554 shell am instrument -w \
 *   -e class fr.kristenjestin.mue.ui.sync.LiveSessionRecoveryTest \
 *   -e mueLiveServer https://192.168.1.100:3000 \
 *   -e mueLiveEmail you@example.org -e mueLivePassword … \
 *   fr.kristenjestin.mue.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveSessionRecoveryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** A weight nothing else would write, saved *after* the revocation so it cannot go out. */
    private val date = LocalDate.of(2018, 7, 21)
    private val weight = requireNotNull(Weight.ofHundredthsOrNull(6_805))

    @Test
    fun aSessionRevokedOnTheServerIsRecoveredWithoutLosingThePairing() {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("mueLiveServer")
        val email = arguments.getString("mueLiveEmail")
        val password = arguments.getString("mueLivePassword")
        assumeTrue(
            "set -e mueLiveServer/mueLiveEmail/mueLivePassword to run this against a server",
            !server.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank(),
        )

        // --- paired, and synchronising ---------------------------------------------------------

        openServerSettings()
        composeRule.field(SyncTestTags.ADDRESS_FIELD).performTextReplacement(server!!)
        composeRule.field(SyncTestTags.EMAIL_FIELD).performTextReplacement(email!!)
        composeRule.field(SyncTestTags.PASSWORD_FIELD).performTextReplacement(password!!)
        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).performScrollTo().performClick()

        awaitSynced("the initial pairing never reached `${SyncMessages.STATE_SYNCED}`")
        val paired = requireNotNull(syncState())

        // --- and then the server stops accepting it --------------------------------------------

        revokeThisPhonesSessionOnTheServer(requireNotNull(paired.serverUrl))
        runBlocking { application().container.measurementRepository.save(Measurement(date, weight)) }

        composeRule.onNodeWithContentDescription("Back").performClick()
        // `Profile` and `Server settings` are two states of one `AnimatedContent`, so both are
        // composed until the transition ends; waiting is what makes `Sync now` a single node.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SyncTestTags.SYNC_NOW).performScrollTo().performClick()
        awaitState("the refusal was never recorded") {
            it?.lastErrorCode == SyncErrorCodes.AUTH_UNAUTHENTICATED
        }

        val refused = requireNotNull(syncState())
        // FR-SYNC-008: the server's own words, kept, and shown. This is the sentence that had
        // no control behind it.
        assertEquals("Sign in to synchronise.", refused.lastErrorMessage)
        // Nothing was given up because a request was refused: the pairing is intact and the
        // weight is still here.
        assertEquals(paired.serverUrl, refused.serverUrl)
        assertEquals(paired.accountId, refused.accountId)
        assertEquals(paired.deviceId, refused.deviceId)
        assertTrue(pendingCount() > 0)

        // --- the way back ----------------------------------------------------------------------

        openServerSettings()
        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).performScrollTo().assertExists()
        // The address came back by itself. Nobody has to remember it, which was half the cost of
        // the old route through `Disconnect server`.
        composeRule.field(SyncTestTags.ADDRESS_FIELD)
            .assertTextContains(requireNotNull(paired.serverUrl))

        composeRule.field(SyncTestTags.PASSWORD_FIELD).performTextReplacement(password)
        composeRule.onNodeWithTag(SyncTestTags.SIGN_IN_BUTTON).performScrollTo().performClick()

        awaitSynced("signing in again never reached `${SyncMessages.STATE_SYNCED}`")

        val recovered = requireNotNull(syncState())
        assertEquals(paired.serverUrl, recovered.serverUrl)
        assertEquals(paired.accountId, recovered.accountId)
        // The server still knows this phone as the same device (PRD 12.1's `origin.id`).
        assertEquals(paired.deviceId, recovered.deviceId)
        // And the change that was waiting when the session died went out on its own.
        assertEquals(0, pendingCount())
    }

    // --- the server side of it ---------------------------------------------------------------

    /**
     * Ends this phone's session where it lives, using the phone's own bearer.
     *
     * `POST /api/auth/sign-out` is what Better Auth exposes and what the local administration
     * command of PRD 15.3 reaches for. The token is read from the shipped `SyncTokenStore`, so
     * the session being destroyed is exactly the one the app would present on its next request —
     * not a second one minted for the test.
     */
    private fun revokeThisPhonesSessionOnTheServer(origin: String) = runBlocking {
        val sync = application().container.sync
        val token = requireNotNull(sync.tokenStore.read()) { "the phone kept no bearer to revoke" }
        val response = sync.httpClient.post("$origin/api/auth/sign-out") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(200, response.status.value)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun openServerSettings() {
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun application(): MueApplication =
        ApplicationProvider.getApplicationContext<MueApplication>()

    private fun syncState(): SyncStateEntity? =
        runBlocking { application().container.sync.syncDao.syncState() }

    private fun pendingCount(): Int = runBlocking {
        val dao = application().container.sync.syncDao
        dao.countInState("pending") + dao.countInState("inflight") + dao.countInState("failed")
    }

    private fun awaitSynced(what: String) {
        awaitState(what) { it?.lastSuccessAt != null && it.lastErrorCode == null }
        assertTrue(
            "$what: ${statusDescriptions()} — ${diagnosis()}",
            statusDescriptions().any { it.startsWith(SyncMessages.STATE_SYNCED) },
        )
    }

    /**
     * Polled rather than `waitUntil`, and for [LiveServerPairingTest]'s reason: a
     * `ComposeTimeoutException` says "condition still not satisfied" and nothing else, while every
     * interesting outcome here is silent. So the failure carries the phone's own row.
     */
    private fun awaitState(what: String, done: (SyncStateEntity?) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (done(syncState())) return
            Thread.sleep(POLL_MILLIS)
        }
        assertTrue("$what — ${diagnosis()}", done(syncState()))
    }

    private fun statusDescriptions(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.STATUS_LINE)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    private fun diagnosis(): String = runBlocking {
        val sync = application().container.sync
        val state = sync.syncDao.syncState()
        "sync_state(server=${state?.serverUrl}, account=${state?.accountId}, " +
            "device=${state?.deviceId}, cursor=${state?.cursor}, " +
            "lastSuccessAt=${state?.lastSuccessAt}, lastErrorCode=${state?.lastErrorCode}, " +
            "lastErrorMessage=${state?.lastErrorMessage}), " +
            "pending=${sync.syncDao.countInState("pending")}, " +
            "inflight=${sync.syncDao.countInState("inflight")}, " +
            "failed=${sync.syncDao.countInState("failed")}"
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 250L
    }
}
