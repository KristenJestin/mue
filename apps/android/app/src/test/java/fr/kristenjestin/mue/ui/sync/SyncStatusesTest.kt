package fr.kristenjestin.mue.ui.sync

import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four states of sync PRD 9.1, and the rule that decides between them.
 *
 * The tests are written from the position of somebody who has just lost a history: the question
 * is never "did the last request work", it is "is my data anywhere but here". So the cases that
 * carry the most weight are the ones where a naive implementation would say `Synced` and be
 * wrong.
 */
class SyncStatusesTest {

    private val paired = SyncStateEntity(
        serverUrl = "https://mue.home.arpa",
        serverName = "mue.home.arpa",
        accountId = "kris@example.org",
        deviceId = "device-1",
        lastSuccessAt = 1_756_240_000_000L,
    )

    @Test
    fun noRowAtAllIsNotConnected() {
        assertEquals(SyncStatus.NOT_CONNECTED, SyncStatuses.derive(null, pending = 0, failed = 0))
    }

    /** The engine's own guard is `server_url` **or** `device_id` blank; the section repeats it. */
    @Test
    fun aRowWithNoServerOrNoDeviceIsNotConnected() {
        assertEquals(
            SyncStatus.NOT_CONNECTED,
            SyncStatuses.derive(paired.copy(serverUrl = null), pending = 0, failed = 0),
        )
        assertEquals(
            SyncStatus.NOT_CONNECTED,
            SyncStatuses.derive(paired.copy(deviceId = null), pending = 0, failed = 0),
        )
    }

    @Test
    fun anEmptyOutboxAfterASuccessfulRunIsSynced() {
        assertEquals(SyncStatus.SYNCED, SyncStatuses.derive(paired, pending = 0, failed = 0))
    }

    /**
     * The whole point of the section. A row in the outbox is a change that exists on this phone
     * and nowhere else, and saying `Synced` over it is the one lie the owner cannot afford.
     */
    @Test
    fun aSingleQueuedRowIsEnoughToStopSayingSynced() {
        assertEquals(
            SyncStatus.CHANGES_PENDING,
            SyncStatuses.derive(paired, pending = 1, failed = 0),
        )
    }

    /** FR-SYNC-007: a refused mutation is kept, and it is a problem rather than a queue. */
    @Test
    fun aRefusedMutationIsASyncIssueEvenWithAnOtherwiseCleanRun() {
        assertEquals(SyncStatus.SYNC_ISSUE, SyncStatuses.derive(paired, pending = 0, failed = 1))
    }

    /** FR-SYNC-008: the engine records the failure, and `recordSuccess` is what clears it. */
    @Test
    fun aRecordedTransportFailureIsASyncIssue() {
        val failing = paired.copy(
            lastErrorCode = "client.unreachable",
            lastErrorMessage = "The server could not be reached.",
        )

        assertEquals(SyncStatus.SYNC_ISSUE, SyncStatuses.derive(failing, pending = 0, failed = 0))
    }

    @Test
    fun aSyncIssueOutranksAQueueBecauseAQueueThatCannotDrainIsNotAQueue() {
        assertEquals(SyncStatus.SYNC_ISSUE, SyncStatuses.derive(paired, pending = 5, failed = 2))
    }

    /**
     * Paired thirty seconds ago, nothing queued, nothing failed, and nothing has ever succeeded.
     * `Synced` would claim an exchange that never happened.
     */
    @Test
    fun aPairingThatHasNeverSynchronisedDoesNotClaimToBeSynced() {
        val fresh = paired.copy(lastSuccessAt = null)

        assertEquals(SyncStatus.CHANGES_PENDING, SyncStatuses.derive(fresh, pending = 0, failed = 0))
    }

    // --- the whole state ---------------------------------------------------------------------------

    /**
     * PRD 9.1's count is "les changements locaux en attente". A refused row is one of them: it is
     * on this phone, it is not on the server, and no later send will pick it up.
     */
    @Test
    fun theCountIsEveryLocalChangeThatIsNotOnTheServer() {
        val state = SyncStatuses.from(
            state = paired,
            pending = 4,
            failed = 2,
            undeliverable = 1,
            serverName = "mue.home.arpa",
        )

        assertEquals(6, state.outstandingChanges)
        assertEquals(2, state.refusedChanges)
        assertEquals(1, state.undeliverableChanges)
    }

    @Test
    fun theStateCarriesTheServerTheAccountAndTheLastSuccess() {
        val state = SyncStatuses.from(
            state = paired,
            pending = 0,
            failed = 0,
            undeliverable = 0,
            serverName = "mue.home.arpa",
        )

        assertEquals("mue.home.arpa", state.serverName)
        assertEquals("kris@example.org", state.account)
        assertEquals(1_756_240_000_000L, state.lastSuccessAt)
        assertEquals(true, state.connected)
    }

    /** An error message left over from a run that later succeeded must not be shown as current. */
    @Test
    fun theLastErrorIsOnlyCarriedWhenTheRowStillHoldsOne() {
        val cleared = SyncStatuses.from(
            state = paired.copy(lastErrorMessage = null),
            pending = 0,
            failed = 0,
            undeliverable = 0,
            serverName = "mue.home.arpa",
        )

        assertNull(cleared.lastErrorMessage)
    }

    @Test
    fun anUnpairedPhoneReportsNoServerAndNoLastSuccess() {
        val state = SyncStatuses.from(
            state = null,
            pending = 0,
            failed = 0,
            undeliverable = 0,
            serverName = null,
        )

        assertEquals(SyncStatus.NOT_CONNECTED, state.status)
        assertEquals(false, state.connected)
        assertNull(state.serverName)
        assertNull(state.lastSuccessAt)
    }

    /**
     * PRD 9.3 keeps `account_id` across a disconnect so the guard survives, so an unpaired phone
     * may still know whose data it holds — and `Server settings` says so before the sign-in.
     */
    @Test
    fun anUnpairedPhoneStillKnowsWhoseDataItHolds() {
        val state = SyncStatuses.from(
            state = SyncStateEntity(accountId = "kris@example.org", deviceId = "device-1"),
            pending = 0,
            failed = 0,
            undeliverable = 0,
            serverName = null,
        )

        assertEquals(SyncStatus.NOT_CONNECTED, state.status)
        assertEquals("kris@example.org", state.account)
    }

    // --- the one `Sync issue` the person holding the phone can fix -------------------------------

    /**
     * An account recreated on the server, a session revoked, a bearer expired: the server answers
     * `401` with `auth.unauthenticated`, the engine records it, and `Data & sync` reads
     * `Sync issue` with the server's own sentence beneath it — `Sign in to synchronise.`
     *
     * That sentence named an action the app had no control for. It has one now, and this flag is
     * what decides whether the screen leads with it.
     */
    @Test
    fun aRefusedBearerIsToldApartFromEveryOtherSyncIssue() {
        val refused = SyncStatuses.from(
            state = paired.copy(
                lastErrorCode = "auth.unauthenticated",
                lastErrorMessage = "Sign in to synchronise.",
            ),
            pending = 1,
            failed = 0,
            undeliverable = 0,
            serverName = "mue.home.arpa",
        )

        assertEquals(SyncStatus.SYNC_ISSUE, refused.status)
        assertTrue(refused.sessionRejected)
        assertEquals("Sign in to synchronise.", refused.lastErrorMessage)
    }

    /** A server that could not be reached is not a session that was refused. */
    @Test
    fun anUnreachableServerIsNotARefusedSession() {
        val unreachable = SyncStatuses.from(
            state = paired.copy(
                lastErrorCode = "client.unreachable",
                lastErrorMessage = "The server could not be reached.",
            ),
            pending = 0,
            failed = 0,
            undeliverable = 0,
            serverName = "mue.home.arpa",
        )

        assertEquals(SyncStatus.SYNC_ISSUE, unreachable.status)
        assertFalse(unreachable.sessionRejected)
    }

    /** `recordSuccess` clears the code, so a session that works never reads as refused. */
    @Test
    fun aHealthyPairingNeverReadsAsRefused() {
        val healthy = SyncStatuses.from(
            state = paired,
            pending = 0,
            failed = 0,
            undeliverable = 0,
            serverName = "mue.home.arpa",
        )

        assertEquals(SyncStatus.SYNCED, healthy.status)
        assertFalse(healthy.sessionRejected)
    }
}
