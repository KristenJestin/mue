package fr.kristenjestin.mue.data.sync

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.pairing.PairingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The owner's row, on his server, drained.
 *
 * `OutboxRepairTest` proves the rule and `OutboxRepairRoomTest` proves the statement. Neither of
 * them can prove the thing that actually matters, because both of them are arguments about what
 * a server *would* do. This one asks one.
 *
 * That distinction is not pedantry here: the defect being repaired was invisible to every test
 * in this repository for exactly that reason. `ContractDrift` compares the *shape* of a fixture
 * against the DTOs, and a UUIDv4 and a UUIDv7 are the same shape. A value format is only ever
 * tested by something that refuses the value.
 *
 * ## The state it reproduces
 *
 * One `sync_mutations` row, journalled by a build that predates `MutationIds`:
 *
 * ```
 * mutation_id     4317e938-539e-4c48-abd5-27311fb39b74      ← the version nibble is 4
 * aggregate_type  healthProfile   aggregate_id  "me"   op  upsert
 * payload         {"heightCm":171,"birthDate":"1998-11-18"}
 * state           pending         attempt_count 0
 * ```
 *
 * written **into the app's own database**, not a fresh in-memory one, and pushed at a Mue
 * Platform that is genuinely running. The height and the birth date are the owner's own, which
 * is what makes `mue_app.health_profile` readable out of band as evidence rather than as a shape.
 *
 * ## It skips unless it is told where to go
 *
 * No address is hard-coded and there is no default, as in every other live test here, so
 * `connectedDebugAndroidTest` on a machine with no server stays green:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=fr.kristenjestin.mue.data.sync.LiveOutboxRepairTest \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLiveServer=https://192.168.1.100:3000 \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLiveEmail=you@example.org \
 *   -Pandroid.testInstrumentationRunnerArguments.mueLivePassword=…
 * ```
 *
 * ## It pairs through `ServerPairing` and not through the screen
 *
 * `LiveHealthProfileSyncTest` drives `Server settings` with Compose, which is the right test for
 * the screen. This one is about the storage layer, and a screen between the row and the assertion
 * would be a second subject. `ServerPairing.pair` is what that button calls.
 *
 * ## Why it builds an engine of its own
 *
 * Because a legacy row is inherited **across a restart** — written by one build, first read by the
 * next one's engine start — and an instrumented test cannot restart the process it runs in.
 * `SyncContainer.engine` is one object for the life of the process and its recovery is latched;
 * the periodic worker has already constructed it before the first line of this test runs, against
 * an outbox that was empty at the time. That is correct behaviour and it is not the situation
 * being reproduced. So the row is journalled first and a new `SyncEngine` is then built over the
 * container's own store and API, exactly as `SyncContainer` builds it. Constructing it is engine
 * start, and `sync()` afterwards does nothing special — which is the point.
 */
@RunWith(AndroidJUnit4::class)
class LiveOutboxRepairTest {

    private val application get() = ApplicationProvider.getApplicationContext<MueApplication>()

    private val sync get() = application.container.sync

    /**
     * Pair, and watch a row nothing could send leave the phone.
     *
     * Everything asserted below was false before the repair existed: the identifier on the wire
     * was the v4, the push came back `sync.invalid_payload` for the whole request, the row went
     * straight back to `pending` with `attempt_count` still at zero, and `1 change waiting to be
     * sent` never moved.
     */
    @Test
    fun theRowNoRunCouldDrainReachesTheServerAndTheCounterFalls() {
        val server = liveServer() ?: return

        // Nothing else may push while this runs. The periodic worker shares the container's
        // engine but not this one's gate, and two engines pushing the same batch is a race the
        // application never has (PRD 9.4 gives them one engine) and this test must not invent.
        WorkManager.getInstance(application).cancelUniqueWork(SyncScheduler.PERIODIC_WORK)

        // 1. His phone as `Server settings` shows it: connected to 192.168.1.100:3000 as
        //    kris@mue.home.arpa. Pairing comes first because that is the order of events — the
        //    complaint is not that pairing failed, it is that a *paired* phone cannot drain.
        val paired = runBlocking {
            sync.pairing.pair(
                server,
                requireNotNull(argument("mueLiveEmail")),
                requireNotNull(argument("mueLivePassword")),
            )
        }
        assertTrue("pairing refused: $paired — ${diagnosis()}", paired is PairingResult.Paired)

        // 2. The row an older build left behind, written straight into `sync_mutations`.
        journalHisRow()
        assertEquals(
            "the reproduction has to hold exactly one waiting change — ${diagnosis()}",
            1,
            runBlocking { sync.syncDao.countInState(SyncMutationEntity.STATE_PENDING) },
        )
        assertNotNull(
            "and it has to be the row he actually has",
            runBlocking { sync.syncDao.mutation(LEGACY_MUTATION_ID) },
        )

        /*
         * 3. And now the application starts.
         *
         * That is the step the reproduction turns on, and it has to be a *new* engine. A legacy
         * row is inherited **across a restart**: it is written by one build and first read by
         * the next one's engine start. `SyncContainer.engine` is one object for the life of the
         * process and its recovery is latched, so the container's own engine — already
         * constructed by the periodic worker before this test's first line ran — recovered an
         * outbox that was empty at the time, which is correct and is not the situation being
         * reproduced.
         *
         * This is that engine, built from the container's own store and API exactly as
         * `SyncContainer` builds it. Constructing it is what runs `requeueInflight` and the
         * repair; `sync()` then does nothing special, which is the point.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val outcome = try {
            runBlocking { SyncEngine(store = sync.store, api = sync.api, scope = scope).sync() }
        } finally {
            scope.cancel()
        }

        val completed = outcome as? SyncOutcome.Completed
        assertNotNull("the synchronisation did not complete: $outcome — ${diagnosis()}", completed)
        requireNotNull(completed)

        // 4. The row was re-minted, at engine start, before anything was sent.
        assertEquals(
            "exactly the one legacy row was repaired — ${diagnosis()}",
            1L,
            completed.repaired.toLong(),
        )

        // 5. It reached the server and was applied. `applied`, not `duplicate`: the server has
        //    never seen this mutation, because it never could — which is the whole safety
        //    argument for re-minting it, asserted rather than assumed.
        assertEquals(
            "the server did not apply it — ${diagnosis()}",
            1L,
            completed.applied.toLong(),
        )
        assertEquals(
            "it must not be a replay — ${diagnosis()}",
            0L,
            completed.duplicates.toLong(),
        )
        assertEquals(
            "nothing was refused — ${diagnosis()}",
            0L,
            completed.rejected.toLong(),
        )

        // 6. The outbox is empty and the counter he was looking at is at zero.
        assertEquals(
            "`1 change waiting to be sent` has to be able to reach zero — ${diagnosis()}",
            0,
            runBlocking { sync.syncDao.countInState(SyncMutationEntity.STATE_PENDING) },
        )
        assertEquals(
            "and nothing was marked `failed` to make that true — ${diagnosis()}",
            0,
            runBlocking { sync.syncDao.countInState(SyncMutationEntity.STATE_FAILED) },
        )
        assertNull(
            "the row must not still answer to the name the server refuses",
            runBlocking { sync.syncDao.mutation(LEGACY_MUTATION_ID) },
        )

        // 7. The identifier the server accepted, recorded by the acknowledgement — and it is the
        //    one this run minted, not the one the row was written with.
        val state = runBlocking {
            sync.syncDao.aggregateState(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, "me")
        }
        val accepted = requireNotNull(state?.lastMutationId) { "no acknowledgement — ${diagnosis()}" }
        assertTrue("the accepted identifier is not a UUIDv7: $accepted", MutationIds.isMutationId(accepted))
        assertNotNull("the server issued no revision — ${diagnosis()}", state.revision)

        // Printed so `mue_app.mutation_log` can be read out of band for the same identifier. The
        // application is uninstalled when `connectedAndroidTest` finishes, so this is the only
        // moment the value exists anywhere a human can see it.
        Log.i(TAG, "repaired $LEGACY_MUTATION_ID -> $accepted revision=${state.revision}")
    }

    /**
     * His row, written straight into `sync_mutations`, exactly as the older build left it.
     *
     * `enqueueMutation` and not `SyncOutbox`: the outbox mints a *correct* identifier now, which
     * is the fix this test exists to look past. The point of the reproduction is the row that
     * was already there.
     */
    private fun journalHisRow() = runBlocking {
        sync.syncDao.enqueueMutation(
            SyncMutationEntity(
                mutationId = LEGACY_MUTATION_ID,
                aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                aggregateId = "me",
                op = SyncMutationEntity.OP_UPSERT,
                // Null, as it was: the server had never acknowledged this aggregate to this
                // phone, because nothing this phone sent was ever read.
                baseRevision = null,
                payload = """{"heightCm":$HEIGHT_CM,"birthDate":"$BIRTH_DATE"}""",
                payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
                createdAt = 1_770_000_000_000L,
                state = SyncMutationEntity.STATE_PENDING,
                attemptCount = 0,
                lastErrorCode = null,
                lastErrorMessage = null,
            ),
        )
        // The local row too, so the phone and the payload agree about what the profile is.
        sync.healthProfileDao.upsert(
            HealthProfileEntity(
                heightCm = HEIGHT_CM,
                birthDate = BIRTH_DATE.toString(),
            ),
        )
    }

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    /** The address to test against, or null when this run was not given one. */
    private fun liveServer(): String? {
        val server = argument("mueLiveServer")
        assumeTrue(
            "set -e mueLiveServer/mueLiveEmail/mueLivePassword to run this against a server",
            !server.isNullOrBlank() &&
                !argument("mueLiveEmail").isNullOrBlank() &&
                !argument("mueLivePassword").isNullOrBlank(),
        )
        return server
    }

    /** The phone's own account of the exchange, which is where the original defect was found. */
    private fun diagnosis(): String = runBlocking {
        val state = sync.syncDao.syncState()
        val profile = sync.healthProfileDao.get()
        "sync_state(server=${state?.serverUrl}, cursor=${state?.cursor}, " +
            "lastSuccessAt=${state?.lastSuccessAt}, lastErrorCode=${state?.lastErrorCode}, " +
            "lastErrorMessage=${state?.lastErrorMessage}), " +
            "profileState=${sync.syncDao.aggregateState("healthProfile", "me")}, " +
            "profileFields=(height=${profile?.heightCm}, birthDate=${profile?.birthDate}), " +
            "pending=${sync.syncDao.countInState("pending")}, " +
            "inflight=${sync.syncDao.countInState("inflight")}, " +
            "failed=${sync.syncDao.countInState("failed")}"
    }

    private companion object {
        const val TAG = "LiveOutboxRepair"

        /** The identifier on the owner's phone. A `UUID.randomUUID()`, version nibble `4`. */
        const val LEGACY_MUTATION_ID = "4317e938-539e-4c48-abd5-27311fb39b74"

        /** His own, so the row in `mue_app.health_profile` is identifiable with certainty. */
        const val HEIGHT_CM = 171
        val BIRTH_DATE: LocalDate = LocalDate.of(1998, 11, 18)
    }
}
