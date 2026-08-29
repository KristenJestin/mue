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
import fr.kristenjestin.mue.data.remote.sync.HealthProfilePayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.MutationAppliedDto
import fr.kristenjestin.mue.data.remote.sync.MutationDuplicateDto
import fr.kristenjestin.mue.data.remote.sync.OriginDto
import fr.kristenjestin.mue.data.remote.sync.PushRequestDto
import fr.kristenjestin.mue.data.remote.sync.PushResponseDto
import fr.kristenjestin.mue.data.remote.sync.SyncJson
import fr.kristenjestin.mue.data.sync.MutationIds
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.profile.ProfileTestTags
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The *pull* half of the health profile, against a Mue Platform that is actually running — and
 * the case every other live test skips.
 *
 * `LiveHealthProfileSyncTest` saves a profile on the phone and proves it reaches the server. Both
 * of its tests therefore start from a phone that already holds one, which is the one shape the
 * owner's failure could not take: he had cleared the app. On a phone with no profile at all, the
 * server's copy has to arrive and land in `health_profile` with its *values* intact — and it did
 * not. The row was written, its revision was recorded in `sync_aggregate_state`, and both columns
 * were null.
 *
 * Nothing here asserts a type or a count. Every type along that path was already right: the
 * change decoded as `HealthProfileUpsertChangeDto`, `canApply` accepted it, `applyPage` opened a
 * transaction and `HealthProfileDao.upsert` replaced the row. What was wrong was 171 becoming
 * null, so 171 is what is asserted.
 *
 * ## It skips unless it is told where to go
 *
 * As in `LiveServerPairingTest`, no address is hard-coded and there is no default:
 *
 * ```
 * adb -s emulator-5554 shell am instrument -w \
 *   -e class fr.kristenjestin.mue.ui.sync.LiveProfilePullTest \
 *   -e mueLiveServer https://192.168.1.100:3000 \
 *   -e mueLiveEmail you@example.org -e mueLivePassword … \
 *   fr.kristenjestin.mue.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveProfilePullTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application
        get() = ApplicationProvider.getApplicationContext<MueApplication>()

    /**
     * The owner's exact case: a phone that has never held a profile, pairing with a server that
     * holds his.
     *
     * The phases are one test rather than several because they are one story about one row, and
     * because each depends on the phone the phase before it left behind:
     *
     * 1. **It arrives.** Pair, and `health_profile` holds 171 cm and 1998-11-18 — the values,
     *    not merely a row and a revision.
     * 2. **The screen does not erase it.** A real tap on the real save button, and the row is
     *    still the owner's. This is the phase that was red: the form had been seeded empty
     *    before pairing and saving wrote that snapshot back over what the pull had applied.
     * 3. **A second change replaces it.** Another origin pushes a different profile and the
     *    phone pulls it. A `REPLACE` that silently no-opped would be indistinguishable from
     *    phase 1 on a fresh install, so the assertion is that the row holds the *new* values.
     *
     * Putting the owner's real profile back is [restoreTheOwnersProfile], an `@After`, so it
     * happens even when one of the above fails.
     */
    @Test
    fun theProfileTheServerHoldsLandsWithItsValuesOnAPhoneThatNeverHadOne() {
        val server = liveServer() ?: return

        // The precondition that makes this test the one the others are not: nothing local.
        assertNull(
            "this phone must start with no profile at all — clear the app data first",
            runBlocking { application.container.sync.healthProfileDao.get() },
        )

        pair(server)
        assertTrue(
            "pairing refused: ${pairingFailures().joinToString(" | ")} — ${diagnosis()}",
            pairingFailures().isEmpty(),
        )
        assertTrue(
            "the status line never reached `${SyncMessages.STATE_SYNCED}`: " +
                "${statusDescriptions()} — ${diagnosis()}",
            synced(),
        )

        // --- phase 1: the value the server holds is the value that lands -------------------
        val arrived = runBlocking { application.container.sync.healthProfileDao.get() }
        assertEquals("the row is the single profile row", "me", arrived?.id)
        assertEquals("the height the server holds — ${diagnosis()}", HEIGHT_CM, arrived?.heightCm)
        assertEquals(
            "the birth date the server holds — ${diagnosis()}",
            BIRTH_DATE,
            arrived?.birthDate,
        )

        // --- phase 2: the screen must not save an empty profile over what arrived -------------
        /*
         * The defect itself, at the level it actually happened.
         *
         * The Profile screen was opened before pairing — it had to be, `Server settings` is on
         * it — so its form was seeded from a profile that was still empty. Saving now wrote that
         * stale snapshot back through `UserProfileRepository.save`, which replaces the row *and*
         * journals a mutation: both columns became null locally and the nulls were pushed, so
         * `health_profile` lost them too. The row below is read after a real tap on a
         * real save button, and it has to still be the owner's profile.
         */
        // Back off `Server settings`, which is where pairing left the app, onto the Profile
        // screen the form belongs to. The system back is used rather than the bottom bar because
        // `Server settings` is a destination pushed over it, so the bar is not on screen.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProfileTestTags.SAVE_BUTTON).performScrollTo().performClick()
        composeRule.waitForIdle()

        val afterSave = runBlocking { application.container.sync.healthProfileDao.get() }
        assertEquals(
            "saving the screen must not write an empty height over the pulled one — " +
                diagnosis(),
            HEIGHT_CM,
            afterSave?.heightCm,
        )
        assertEquals(
            "nor an empty birth date — ${diagnosis()}",
            BIRTH_DATE,
            afterSave?.birthDate,
        )

        // --- phase 3: a second change lands over the row that is already there ---------------
        runBlocking { application.container.sync.engine.sync() }
        val revision = runBlocking {
            application.container.sync.syncDao.aggregateState("healthProfile", "me")?.revision
        }
        pushAsSecondClient(
            server,
            baseRevision = revision?.toString(),
            payload = HealthProfilePayloadV1Dto(
                heightCm = OTHER_HEIGHT_CM,
                birthDate = OTHER_BIRTH_DATE,
            ),
        )
        runBlocking { application.container.sync.engine.sync() }

        val replaced = runBlocking { application.container.sync.healthProfileDao.get() }
        assertEquals(
            "a second change has to replace the row, not no-op over it — ${diagnosis()}",
            OTHER_HEIGHT_CM,
            replaced?.heightCm,
        )
        assertEquals(
            "and it has to replace both columns — ${diagnosis()}",
            OTHER_BIRTH_DATE,
            replaced?.birthDate,
        )

        // Phase 4 — putting the owner's real profile back — is [restoreTheOwnersProfile], so it
        // runs even when an assertion above fails. This is his account, not a fixture.
    }

    /**
     * The owner's own profile, restored on the server, whatever this test did to it.
     *
     * It is an `@After` and not a fourth phase on purpose: a failing assertion aborts the test
     * method, and the one thing that must not depend on the test passing is leaving his account
     * holding a height this test invented.
     */
    @After
    fun restoreTheOwnersProfile() {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("mueLiveServer")
        if (server.isNullOrBlank()) return
        val sync = application.container.sync
        if (runBlocking { sync.tokenStore.read() } == null) return

        // Drain first, and only then restore. A failing run can leave a mutation of its own in
        // the outbox — that is exactly what the defect did — and a restore pushed before it
        // would be overwritten by it moments later, which is how this net first failed.
        runBlocking { sync.engine.sync() }
        val revision = runBlocking {
            sync.syncDao.aggregateState("healthProfile", "me")?.revision
        }
        pushAsSecondClient(
            server,
            baseRevision = revision?.toString(),
            payload = HealthProfilePayloadV1Dto(heightCm = HEIGHT_CM, birthDate = BIRTH_DATE),
        )
        runBlocking { sync.engine.sync() }

        val restored = runBlocking { sync.healthProfileDao.get() }
        assertEquals("the owner's height is back", HEIGHT_CM, restored?.heightCm)
        assertEquals("the owner's birth date is back", BIRTH_DATE, restored?.birthDate)
    }

    // --- the server, and the address this run was given ---------------------------------------

    private fun liveServer(): String? {
        val arguments = InstrumentationRegistry.getArguments()
        val server = arguments.getString("mueLiveServer")
        assumeTrue(
            "set -e mueLiveServer/mueLiveEmail/mueLivePassword to run this against a server",
            !server.isNullOrBlank() &&
                !arguments.getString("mueLiveEmail").isNullOrBlank() &&
                !arguments.getString("mueLivePassword").isNullOrBlank(),
        )
        return server
    }

    /** Pairing, driven exactly as `LiveHealthProfileSyncTest` drives it. */
    private fun pair(server: String) {
        val arguments = InstrumentationRegistry.getArguments()
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performScrollTo().performClick()

        composeRule.field(SyncTestTags.ADDRESS_FIELD).performTextReplacement(server)
        composeRule.field(SyncTestTags.EMAIL_FIELD)
            .performTextReplacement(requireNotNull(arguments.getString("mueLiveEmail")))
        composeRule.field(SyncTestTags.PASSWORD_FIELD)
            .performTextReplacement(requireNotNull(arguments.getString("mueLivePassword")))
        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).performScrollTo().performClick()

        val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (synced() || pairingFailures().isNotEmpty()) break
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * One mutation authored by another origin, on the client the app already trusts — the same
     * device the phone would actually be converging with.
     *
     * The identifier is minted per run rather than fixed: this test needs each push to *apply*,
     * where `LiveHealthProfileSyncTest` needs a re-run to replay as a duplicate (FR-SYNC-006).
     */
    private fun pushAsSecondClient(
        server: String,
        baseRevision: String?,
        payload: HealthProfilePayloadV1Dto,
    ): PushResponseDto = runBlocking {
        val sync = application.container.sync
        val token = requireNotNull(sync.tokenStore.read()) { "the phone holds no bearer" }

        val body = PushRequestDto(
            listOf(
                HealthProfileUpsertMutationDto(
                    mutationId = MutationIds.random(),
                    baseRevision = baseRevision,
                    payloadSchemaVersion = 1,
                    payload = payload,
                    origin = OriginDto(OriginDto.TYPE_AGENT, "instrumented-second-client"),
                    clientOccurredAt = Instant.now().toString(),
                ),
            ),
        )

        val response = sync.httpClient.post("${server.trimEnd('/')}/api/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val decoded = SyncJson.instance
            .decodeFromString(serializer<PushResponseDto>(), response.bodyAsText())
        assertTrue(
            "the second client's push was refused: $decoded",
            decoded.results.single().let { it is MutationAppliedDto || it is MutationDuplicateDto },
        )
        decoded
    }

    // --- what the screen is saying -------------------------------------------------------------

    private fun synced(): Boolean =
        statusDescriptions().any { it.startsWith(SyncMessages.STATE_SYNCED) }

    private fun statusDescriptions(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.STATUS_LINE)
            .fetchSemanticsNodes()
            .mapNotNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            }

    private fun pairingFailures(): List<String> =
        composeRule.onAllNodesWithTag(SyncTestTags.PAIRING_FAILURE)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }

    /** The phone's own account of the exchange, for a failure that would otherwise be silent. */
    private fun diagnosis(): String = runBlocking {
        val sync = application.container.sync
        val state = sync.syncDao.syncState()
        val profile = sync.healthProfileDao.get()
        "sync_state(server=${state?.serverUrl}, cursor=${state?.cursor}, " +
            "lastErrorCode=${state?.lastErrorCode}, lastErrorMessage=${state?.lastErrorMessage}), " +
            "profileRevision=${sync.syncDao.aggregateState("healthProfile", "me")?.revision}, " +
            "profileFields=(height=${profile?.heightCm}, birthDate=${profile?.birthDate}), " +
            "pending=${sync.syncDao.countInState("pending")}, " +
            "failed=${sync.syncDao.countInState("failed")}"
    }

    private companion object {
        /** The owner's own, which is what makes the row identifiable with certainty. */
        const val HEIGHT_CM = 171
        const val BIRTH_DATE = "1998-11-18"

        /** Deliberately different in *both* columns, so a partial replace cannot pass. */
        const val OTHER_HEIGHT_CM = 168
        const val OTHER_BIRTH_DATE = "1996-08-12"

        const val PAIRING_TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 250L
    }
}
