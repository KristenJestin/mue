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
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.ui.field
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * The health profile against a Mue Platform that is actually running, over TLS, and the rule of
 * PRD 13.4 exercised rather than described.
 *
 * `LiveServerPairingTest` proves a *weight* leaves the phone. This proves the aggregate that
 * could not: `healthProfile` was journalled at every save and `AGGREGATE_TYPES` in
 * `packages/contracts` was `["measurement"]`, so `SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES` never
 * selected the row. The owner's phone held one — `attempt_count 0`, never attempted — and
 * `Data & sync` counted `1 change waiting` that nothing could make fall.
 *
 * ## It skips unless it is told where to go
 *
 * No address is hard-coded and there is no default, exactly as in `LiveServerPairingTest`, so
 * `connectedDebugAndroidTest` on a machine with no server stays green:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=fr.kristenjestin.mue.ui.sync.LiveHealthProfileSyncTest \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLiveServer=https://192.168.1.100:3000 \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLiveEmail=you@example.org \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLivePassword=…
 * ```
 *
 * ## What it does not assert
 *
 * It does not read PostgreSQL. An instrumented test has no business holding a database
 * credential, and a phone reporting `Synced` while the server stored nothing is precisely the
 * lie this exercise exists to catch — so the row is read out of band, against
 * `mue_app.health_profile`. The height and the birth date below are the owner's own, which is
 * what makes that row identifiable with certainty.
 */
@RunWith(AndroidJUnit4::class)
class LiveHealthProfileSyncTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application
        get() = ApplicationProvider.getApplicationContext<MueApplication>()

    /**
     * The row that could not be sent, sent.
     *
     * It leaves the server holding exactly the owner's profile — 171 cm, 1998-11-18 — which is
     * what makes `mue_app.health_profile` readable out of band as evidence rather than as a
     * shape. This test does nothing afterwards, on purpose: a second phase would overwrite the
     * very row it exists to produce.
     */
    @Test
    fun theProfileRowTheOwnerCouldNotSendReachesTheServer() {
        val server = liveServer() ?: return
        pairAndDrain(server)

        val acknowledged = runBlocking {
            application.container.sync.syncDao.aggregateState("healthProfile", "me")
        }
        assertEquals("the server accepted it and issued a revision", 1L, acknowledged?.revision)

        val local = runBlocking { application.container.userProfileRepository.profile.first() }
        assertEquals(HEIGHT_CM, local.heightCm)
        assertEquals(BIRTH_DATE, local.birthDate)
    }

    /**
     * PRD 13.4, exercised rather than described, with a second client that is genuinely one.
     *
     * It pairs and drains for itself rather than leaning on the test above, so the two are
     * independent facts in either order — and so that the row the other one leaves behind is
     * never overwritten by a run of this one alone.
     */
    @Test
    fun aFieldThisPhoneNeverTouchedSurvivesItsOwnEdit() {
        val server = liveServer() ?: return
        pairAndDrain(server)

        val acknowledged = runBlocking {
            application.container.sync.syncDao.aggregateState("healthProfile", "me")
        }

        /*
         * A second client, and genuinely a second one: a different `origin.id` authoring against
         * the same account and the same base revision this phone holds. It moves the birth date
         * and leaves the height where it is.
         */
        val secondClient = pushAsSecondClient(
            server,
            baseRevision = acknowledged?.revision?.toString(),
            payload = HealthProfilePayloadV1Dto(heightCm = HEIGHT_CM, birthDate = "1990-04-12"),
        )
        assertTrue(
            "the second client's push was refused: $secondClient",
            secondClient.results.single().let {
                it is MutationAppliedDto || it is MutationDuplicateDto
            },
        )

        /*
         * And now the phone, which has not pulled that change and still believes the profile is
         * what it pushed. It moves the *height* and, because PRD 12.2 makes an upsert carry the
         * whole aggregate, it necessarily also re-states the birth date it last knew.
         *
         * Applied wholesale that would erase the second client's work. Section 13.4 says it must
         * not: a field its author did not touch is not a statement about that field.
         */
        runBlocking {
            application.container.userProfileRepository.save(
                UserProfile(displayName = "Kris", heightCm = 172, birthDate = BIRTH_DATE),
            )
            application.container.sync.engine.sync()
        }

        val converged = runBlocking { application.container.userProfileRepository.profile.first() }
        assertEquals("the field this phone changed is this phone's", 172, converged.heightCm)
        assertEquals(
            "and the field it never touched is the second client's — PRD 13.4 — ${diagnosis()}",
            LocalDate.of(1990, 4, 12),
            converged.birthDate,
        )
        assertEquals(
            "the queue is empty again",
            0,
            runBlocking { application.container.sync.syncDao.countInState("pending") },
        )
    }

    // --- the shared first phase --------------------------------------------------------------

    /** The address to test against, or null when this run was not given one. */
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

    /**
     * Save the profile, pair, and prove the outbox drained.
     *
     * The save happens **before** the pairing, like the weight in `LiveServerPairingTest`,
     * because that is the shape of the thing that actually matters: a profile that existed on
     * the phone first and reached the server afterwards (FR-SYNC-003).
     */
    private fun pairAndDrain(server: String) {
        val arguments = InstrumentationRegistry.getArguments()
        runBlocking {
            application.container.userProfileRepository.save(
                UserProfile(displayName = "Kris", heightCm = HEIGHT_CM, birthDate = BIRTH_DATE),
            )
        }
        assertEquals(
            "the save has to journal exactly one pending row",
            1,
            runBlocking { application.container.sync.syncDao.countInState("pending") },
        )

        pair(
            server,
            requireNotNull(arguments.getString("mueLiveEmail")),
            requireNotNull(arguments.getString("mueLivePassword")),
        )

        assertTrue(
            "pairing refused: ${pairingFailures().joinToString(" | ")} — ${diagnosis()}",
            pairingFailures().isEmpty(),
        )
        assertTrue(
            "the status line never reached `${SyncMessages.STATE_SYNCED}`: " +
                "${statusDescriptions()} — ${diagnosis()}",
            synced(),
        )

        // The counter the owner was looking at, at zero — on the client side of this test.
        assertEquals(
            "`1 change waiting` has to be able to reach zero — ${diagnosis()}",
            0,
            runBlocking { application.container.sync.syncDao.countInState("pending") },
        )
        assertEquals(
            "and nothing was refused to make that true",
            0,
            runBlocking { application.container.sync.syncDao.countInState("failed") },
        )
    }

    // --- pairing, as `LiveServerPairingTest` drives it ---------------------------------------

    private fun pair(server: String, email: String, password: String) {
        composeRule.onNodeWithText("Profile").performClick()
        composeRule.onNodeWithTag(SyncTestTags.SERVER_SETTINGS).performScrollTo().performClick()

        composeRule.field(SyncTestTags.ADDRESS_FIELD).performTextReplacement(server)
        composeRule.field(SyncTestTags.EMAIL_FIELD).performTextReplacement(email)
        composeRule.field(SyncTestTags.PASSWORD_FIELD).performTextReplacement(password)
        composeRule.onNodeWithTag(SyncTestTags.CONNECT_BUTTON).performScrollTo().performClick()

        // Polled rather than `waitUntil`, for the reason `LiveServerPairingTest` gives: a
        // `ComposeTimeoutException` says "condition still not satisfied" and nothing else, and
        // here every interesting failure is a silent one. The loop ends by reading the phone's
        // own `sync_state`, which is where the engine writes what actually went wrong.
        val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (synced() || pairingFailures().isNotEmpty()) break
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * One mutation, authored by another device, on the client the app already trusts.
     *
     * It reuses `SyncContainer.httpClient` on purpose: PRD 16 has the pairing verify the
     * certificate of the address that was entered, and a second client would be a second trust
     * configuration proving nothing about the one synchronisation actually uses. The bearer is
     * the phone's, because the *account* is the same account — section 13.4 is about origins,
     * which travel inside the envelope (PRD 12.2), not about sessions.
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
                    // A UUIDv7, as `mutationIdSchema` requires. Fixed rather than minted so a
                    // re-run of this test replays FR-SYNC-006 instead of applying twice.
                    mutationId = SECOND_CLIENT_MUTATION_ID,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = 1,
                    payload = payload,
                    origin = OriginDto(OriginDto.TYPE_ANDROID, "device-laptop"),
                    clientOccurredAt = Instant.now().toString(),
                ),
            ),
        )

        val response = sync.httpClient.post("${server.trimEnd('/')}/api/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        SyncJson.instance.decodeFromString(serializer<PushResponseDto>(), response.bodyAsText())
    }

    // --- what the screen is saying -----------------------------------------------------------

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

    /** The phone's own account of the exchange. `connectedAndroidTest` uninstalls afterwards. */
    private fun diagnosis(): String = runBlocking {
        val sync = application.container.sync
        val state = sync.syncDao.syncState()
        val profile = sync.healthProfileDao.get()
        "sync_state(server=${state?.serverUrl}, cursor=${state?.cursor}, " +
            "lastSuccessAt=${state?.lastSuccessAt}, lastErrorCode=${state?.lastErrorCode}, " +
            "lastErrorMessage=${state?.lastErrorMessage}), " +
            "profileRevision=${sync.syncDao.aggregateState("healthProfile", "me")?.revision}, " +
            "profileFields=(height=${profile?.heightCm}, birthDate=${profile?.birthDate}), " +
            "pending=${sync.syncDao.countInState("pending")}, " +
            "inflight=${sync.syncDao.countInState("inflight")}, " +
            "failed=${sync.syncDao.countInState("failed")}"
    }

    private companion object {
        /** The owner's own, so the row in `mue_app.health_profile` is identifiable. */
        const val HEIGHT_CM = 171
        val BIRTH_DATE: LocalDate = LocalDate.of(1998, 11, 18)

        /**
         * A UUIDv7, as `mutationIdSchema` requires, and a fixed one: a re-run therefore
         * exercises FR-SYNC-006's replay rather than applying a second time.
         */
        const val SECOND_CLIENT_MUTATION_ID = "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6099"

        const val PAIRING_TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 250L
    }
}
