package fr.kristenjestin.mue.ui.sync

import android.util.Log
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
import fr.kristenjestin.mue.data.sync.MutationIds
import fr.kristenjestin.mue.ui.field
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.Random

/**
 * The question this whole exercise exists to answer: **does a weight written on the server
 * reach the phone with nobody touching the phone?**
 *
 * It is the only test in the suite that can answer it, because every part of the answer is a
 * different process: a change committed to PostgreSQL, a `sync_counter` row moving, an open
 * `text/event-stream` response, `LiveSyncChannel` reading a frame off a socket, `SyncEngine`
 * taking its gate and pulling, and a Room transaction. Nothing below fakes any of them.
 *
 * ## What it does *not* do, on purpose
 *
 * After the pairing there is **no interaction with the application at all**. No `Sync now`, no
 * navigation, no backgrounding and no foregrounding — those are PRD 9.4's existing triggers and
 * every one of them would make the result meaningless. The activity is left on screen, exactly
 * as a phone on a desk is, and the assertion is that a row appeared underneath it anyway.
 *
 * ## Why the write goes through `/api/v1/sync/push`
 *
 * Because that is what the Web interface does, and what an agent on `/mcp` ends up doing. A row
 * inserted straight into `measurements` would move no sequence, so no client would ever
 * see it — which is the trap `scripts`-level tooling falls into and the reason the journal
 * exists (PRD 12.3). The mutation carries a **UUIDv7** and an `origin.type` of `agent`: the
 * contract refuses a v4 and refuses `web` before it reads the payload, and both have already
 * cost this project a debugging session.
 *
 * ## It skips unless it is told where to go
 *
 * As `LiveServerPairingTest` does, and for the same reason: with no `mueLiveServer` the test
 * assumes itself out, so a machine with no server keeps a green suite.
 *
 * ```
 * adb -s emulator-5554 shell am instrument -w \
 *   -e class fr.kristenjestin.mue.ui.sync.LiveChannelDeliveryTest \
 *   -e mueLiveServer https://192.168.1.100:3100 \
 *   -e mueLiveEmail … -e mueLivePassword … \
 *   fr.kristenjestin.mue.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveChannelDeliveryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun aWeightWrittenOnTheServerAppearsWithoutTouchingThePhone() {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("mueLiveServer")
        val email = arguments.getString("mueLiveEmail")
        val password = arguments.getString("mueLivePassword")
        assumeTrue(
            "set mueLiveServer/mueLiveEmail/mueLivePassword to run this against a server",
            !server.isNullOrBlank() && !email.isNullOrBlank() && !password.isNullOrBlank(),
        )
        val origin = server!!.trimEnd('/')

        // --- pair, which is the last thing anybody touches -------------------------------------

        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performScrollTo().performClick()
        composeRule.field(SyncTestTags.ADDRESS_FIELD).performTextReplacement(origin)
        composeRule.field(SyncTestTags.EMAIL_FIELD).performTextReplacement(email!!)
        composeRule.field(SyncTestTags.PASSWORD_FIELD).performTextReplacement(password!!)
        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).performScrollTo().performClick()

        val paired = System.currentTimeMillis() + PAIRING_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < paired) {
            composeRule.waitForIdle()
            if (synced() || pairingFailures().isNotEmpty()) break
            Thread.sleep(POLL_MILLIS)
        }
        val failures = pairingFailures()
        assertTrue("pairing refused: ${failures.joinToString(" | ")} — ${diagnosis()}", failures.isEmpty())
        assertTrue("never reached `Synced`: ${statusDescriptions()} — ${diagnosis()}", synced())

        // --- a second client writes, and the phone is not told ---------------------------------

        val application = ApplicationProvider.getApplicationContext<MueApplication>()
        val repository = application.container.measurementRepository

        /*
         * A different date on every run, and it has to be.
         *
         * The server keeps what this test writes — that is the whole point of a journal — and a
         * fixed date would be pulled down by the *pairing* of the next run, before the live
         * channel had a chance to deliver anything. The test would then pass on the previous
         * run's work. Picking a fresh date is what makes the row below provably new: the
         * assertion right after it is the one that would have caught the mistake, and did.
         *
         * The 1980s, because nobody weighs themselves there and the range is 7000 days wide.
         * The weight lands on the 0.05 kg step of BR-003, which the contract enforces.
         */
        val date = LocalDate.of(1980, 1, 1).plusDays(Random().nextInt(7_000).toLong())
        val weightCg = 5_000 + Random().nextInt(1_000) * 5
        assertTrue(
            "the date was already on the phone before the server wrote it: $date",
            runBlocking { repository.findByDate(date) } == null,
        )

        val token = signIn(origin, email, password)
        val wroteAt = System.currentTimeMillis()
        val status = push(origin, token, date, weightCg)
        assertTrue("the server refused the write: $status", status.startsWith("applied"))

        // --- and then nothing happens, until it does --------------------------------------------

        var arrivedAt = 0L
        val deadline = wroteAt + DELIVERY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            /*
             * Read and sleep, and nothing else. No `waitForIdle`, no gesture, no recomposition
             * asked for: the channel is scoped to `ProcessLifecycleOwner` and runs on
             * `Dispatchers.IO`, so it owes nothing to this thread and this thread gives it
             * nothing. That is the point — a phone sitting on a desk pumps no frames either.
             *
             * It was not always true. While the channel was started from a `LaunchedEffect` in
             * `MueApp` it lived in the composition's coroutine context, this loop starved it,
             * and the row never came. The test was right and the wiring was wrong.
             */
            val stored = runBlocking { repository.findByDate(date) }
            if (stored != null) {
                assertTrue(
                    "the row arrived with the wrong weight: ${stored.weight.hundredthsKg}",
                    stored.weight.hundredthsKg == weightCg,
                )
                arrivedAt = System.currentTimeMillis()
                break
            }
            Thread.sleep(POLL_MILLIS)
        }

        assertTrue(
            "the weight written on the server never reached the phone within " +
                "${DELIVERY_TIMEOUT_MILLIS}ms with nobody touching it — ${diagnosis()}",
            arrivedAt != 0L,
        )

        /*
         * Reported rather than asserted against a threshold. The number is the point of the
         * exercise and it belongs in the run's output; turning it into a bound would make the
         * suite fail on a slow machine for a property that is about a design, not a deadline.
         *
         * `Log` and not `println`: an instrumented test's standard output goes nowhere anybody
         * reads, and this line is the one thing anyone running this test wants to see.
         */
        Log.i(
            "MueLiveChannel",
            "a weight written on the server ($date = $weightCg cg) reached Room in " +
                "${arrivedAt - wroteAt} ms with no interaction.",
        )
    }

    // --- the second client, in as little code as possible ------------------------------------

    private fun signIn(origin: String, email: String, password: String): String {
        val body = """{"email":${email.json()},"password":${password.json()}}"""
        val response = post("$origin/api/auth/sign-in/email", null, body)
        val token = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
        return requireNotNull(token) { "sign-in did not return a token: ${response.take(200)}" }
    }

    private fun push(origin: String, token: String, date: LocalDate, weightCg: Int): String {
        val day = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val body = """
            {"mutations":[{
              "mutationId":"${MutationIds.random()}",
              "aggregateType":"measurement",
              "aggregateId":"$day",
              "op":"upsert",
              "baseRevision":null,
              "payloadSchemaVersion":1,
              "clientOccurredAt":"${Instant.now()}",
              "origin":{"type":"agent","id":"live-channel-delivery-test"},
              "payload":{"date":"$day","weightCg":$weightCg}
            }]}
        """.trimIndent()
        val response = post("$origin/api/v1/sync/push", token, body)
        return Regex("\"status\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
            ?.plus(" / $response")
            ?: "no status / $response"
    }

    private fun post(url: String, bearer: String?, body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            if (bearer != null) connection.setRequestProperty("authorization", "Bearer $bearer")
            connection.connectTimeout = HTTP_TIMEOUT_MILLIS
            connection.readTimeout = HTTP_TIMEOUT_MILLIS
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /** Minimal JSON string escaping — enough for an address, an e-mail and a password. */
    private fun String.json(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // --- the same instruments `LiveServerPairingTest` reads ------------------------------------

    private fun synced(): Boolean =
        statusDescriptions().any { it.startsWith(SyncMessages.STATE_SYNCED) }

    private fun statusDescriptions(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.STATUS_LINE)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    private fun pairingFailures(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.PAIRING_FAILURE)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }

    private fun diagnosis(): String = runBlocking {
        val sync = ApplicationProvider.getApplicationContext<MueApplication>().container.sync
        val state = sync.syncDao.syncState()
        "sync_state(server=${state?.serverUrl}, cursor=${state?.cursor}, " +
            "lastSuccessAt=${state?.lastSuccessAt}, lastErrorCode=${state?.lastErrorCode}, " +
            "lastErrorMessage=${state?.lastErrorMessage}), " +
            "pending=${sync.syncDao.countInState("pending")}, " +
            "failed=${sync.syncDao.countInState("failed")}"
    }

    private companion object {
        const val PAIRING_TIMEOUT_MILLIS = 90_000L

        /**
         * Generous on purpose. The channel's own budget is the server's two-second poll plus one
         * pull; the rest is the emulator, and a bound tight enough to be interesting would be a
         * bound tight enough to be flaky. The measured figure is printed, not asserted.
         */
        const val DELIVERY_TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 250L
        const val HTTP_TIMEOUT_MILLIS = 20_000
    }
}
