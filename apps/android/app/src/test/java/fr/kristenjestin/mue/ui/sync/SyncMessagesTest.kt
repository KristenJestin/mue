package fr.kristenjestin.mue.ui.sync

import fr.kristenjestin.mue.data.pairing.DisconnectResult
import fr.kristenjestin.mue.data.remote.sync.MueErrorDto
import fr.kristenjestin.mue.data.sync.SyncOutcome
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sentences, and the one rule they all obey: a run that had a problem in it says so.
 *
 * `SyncOutcome.Completed` is where that rule is easiest to break. It is returned by a run that
 * pushed nothing, by a run that pushed everything, and by a run that had two of the user's
 * measurements refused by the server — and only the third of those is a `Sync issue`. A screen
 * that rendered "Sync complete" over all three would hide a rejection behind a tick, which is
 * what FR-SYNC-007 spends a paragraph forbidding.
 */
class SyncMessagesTest {

    private fun completed(
        applied: Int = 0,
        duplicates: Int = 0,
        rejected: Int = 0,
        unreadable: Int = 0,
        deferred: Int = 0,
        changes: Int = 0,
        moreAvailable: Boolean = false,
    ) = SyncOutcome.Completed(
        recovered = 0,
        applied = applied,
        duplicates = duplicates,
        rejected = rejected,
        deferred = deferred,
        unreadable = unreadable,
        pages = 1,
        changes = changes,
        moreAvailable = moreAvailable,
    )

    @Test
    fun aRunWithNothingToDoSaysSoAndIsNotAProblem() {
        val note = SyncMessages.describe(completed())

        assertEquals("Everything is already up to date.", note.message)
        assertFalse(note.isProblem)
    }

    /**
     * The contradiction the owner reported: `Everything is already up to date.` printed beside a
     * `1 change waiting to be sent` counter.
     *
     * Both sentences were true of different things — the exchange moved nothing, and the outbox
     * holds a row — but read together they say the app cannot count. A run that sends nothing
     * *because there is nothing to send* is up to date; a run that sends nothing while a row sits
     * untried is not, and the difference is `deferred`.
     */
    @Test
    fun aRunHoldingSomethingBackDoesNotClaimToBeUpToDate() {
        val note = SyncMessages.describe(completed(deferred = 1))

        assertFalse(note.message.contains("up to date"), "up to date is a claim about the queue")
        assertEquals(
            "Nothing to exchange. 1 change is waiting for a server that understands it, " +
                "and will go out on its own once one does.",
            note.message,
        )
        assertFalse(note.isProblem, "a deferred row is not a fault")
    }

    /** The plural, and the verb that has to follow it. */
    @Test
    fun severalHeldBackChangesAreCountedAndAgree() {
        val note = SyncMessages.describe(completed(deferred = 3))

        assertEquals(
            "Nothing to exchange. 3 changes are waiting for a server that understands it, " +
                "and will go out on its own once one does.",
            note.message,
        )
    }

    /** A run that actually moved something says so, whatever is still held back. */
    @Test
    fun aRunThatMovedDataIsNotSilencedByADeferredRow() {
        val note = SyncMessages.describe(completed(applied = 2, deferred = 1))

        assertEquals("Synchronised: 2 changes sent, 0 changes received.", note.message)
    }

    @Test
    fun aRunThatMovedDataCountsBothDirections() {
        val note = SyncMessages.describe(completed(applied = 3, changes = 1))

        assertEquals("Synchronised: 3 changes sent, 1 change received.", note.message)
        assertFalse(note.isProblem)
    }

    /** FR-SYNC-006: a duplicate is the protocol working. It was sent, so it counts as sent. */
    @Test
    fun aReplayedMutationCountsAsSentRatherThanAsAFault() {
        val note = SyncMessages.describe(completed(applied = 1, duplicates = 1))

        assertEquals("Synchronised: 2 changes sent, 0 changes received.", note.message)
        assertFalse(note.isProblem)
    }

    @Test
    fun aRefusedMutationIsNeverHiddenBehindASuccessfulRun() {
        val note = SyncMessages.describe(completed(applied = 4, rejected = 2))

        assertTrue(note.isProblem)
        assertTrue(note.message.contains("2 refused by the server"))
        assertTrue(note.message.contains("Nothing was deleted."))
    }

    @Test
    fun aPayloadThisPhoneCannotReadIsNamedSeparatelyFromARefusal() {
        val note = SyncMessages.describe(completed(rejected = 1, unreadable = 2))

        assertTrue(note.message.contains("1 refused by the server"))
        assertTrue(note.message.contains("2 unreadable on this phone"))
    }

    /** A run stopped on its page bound has not finished, and must not read as though it had. */
    @Test
    fun aRunThatStoppedOnItsPageBoundSaysMoreIsComing() {
        val note = SyncMessages.describe(completed(changes = 500, moreAvailable = true))

        assertTrue(note.message.contains("More is still coming."))
        assertFalse(note.isProblem)
    }

    /** PRD 12.4 and 18: the cursor did not move and no local data was touched. Say both. */
    @Test
    fun anUpgradeDemandNamesTheServersOwnReasonAndPromisesNothingWasChanged() {
        val note = SyncMessages.describe(
            SyncOutcome.UpgradeRequired(
                MueErrorDto(
                    code = "sync.upgrade_required",
                    message = "Update Mue to read measurement v2.",
                    retryable = false,
                ),
            ),
        )

        assertTrue(note.isProblem)
        assertTrue(note.message.contains("nothing was changed on this phone"))
        assertTrue(note.message.contains("Update Mue to read measurement v2."))
    }

    /**
     * FR-SYNC-008: an unreachable server away from home is a normal state. It is still a problem
     * worth showing, but the sentence says Mue will handle it rather than asking for anything.
     */
    @Test
    fun aRetryableFailureSaysMueWillTryAgainByItself() {
        val note = SyncMessages.describe(
            SyncOutcome.Failed("client.unreachable", "The server could not be reached.", true),
        )

        assertTrue(note.isProblem)
        assertTrue(note.message.contains("Mue will try again by itself."))
    }

    /** A failure nothing will fix on its own carries its code, because somebody has to act. */
    @Test
    fun anUnretryableFailureCarriesItsCode() {
        val note = SyncMessages.describe(
            SyncOutcome.Failed("auth.unauthenticated", "Pair this phone again.", false),
        )

        assertTrue(note.message.contains("auth.unauthenticated"))
    }

    @Test
    fun anUnpairedRunIsNotAProblem() {
        val note = SyncMessages.describe(SyncOutcome.NotPaired)

        assertFalse(note.isProblem)
    }

    // --- the counts ---------------------------------------------------------------------------------

    /** PRD 9.1 shows the count only when it is not zero. */
    @Test
    fun aZeroCountIsNotRendered() {
        assertNull(SyncMessages.outstanding(0))
        assertNull(SyncMessages.refused(0))
        assertNull(SyncMessages.undeliverable(0))
    }

    @Test
    fun oneChangeIsNotOneChanges() {
        assertEquals("1 change waiting to be sent", SyncMessages.outstanding(1))
        assertEquals("7 changes waiting to be sent", SyncMessages.outstanding(7))
    }

    @Test
    fun refusedChangesSayTheyAreStillHere() {
        assertTrue(SyncMessages.refused(1)!!.contains("is still here"))
        assertTrue(SyncMessages.refused(3)!!.contains("are still here"))
    }

    /** A number that never falls looks like a fault. PRD 13.4's rows are named so it is not. */
    @Test
    fun undeliverableChangesSayTheyAreNotLost() {
        assertTrue(SyncMessages.undeliverable(2)!!.contains("They are not lost."))
    }

    // --- the timestamp -------------------------------------------------------------------------------

    @Test
    fun aPhoneThatHasNeverSynchronisedSaysSoRatherThanShowingAnEpoch() {
        assertEquals(SyncMessages.NEVER_SYNCED, SyncMessages.lastSync(null, Locale.UK))
    }

    @Test
    fun aLastSuccessIsRenderedInThePhonesOwnFormat() {
        val rendered = SyncMessages.lastSync(
            1_756_240_000_000L,
            Locale.UK,
            ZoneId.of("Europe/Paris"),
        )

        assertTrue(rendered.startsWith("Last synced "))
        assertTrue(rendered.contains("2025"))
    }

    // --- disconnecting --------------------------------------------------------------------------------

    /** PRD 9.3's first promise, said out loud where the user is looking. */
    @Test
    fun aCleanDisconnectSaysNothingWasDeleted() {
        val note = SyncMessages.describe(DisconnectResult.Revoked("mue.home.arpa"))

        assertFalse(note.isProblem)
        assertTrue(note.message.contains("nothing recorded on this phone was deleted"))
    }

    /**
     * PRD 9.3: with the server unreachable the remote session stays open, and the only honest
     * thing to do is say so and name where it can still be ended.
     */
    @Test
    fun aDisconnectTheServerNeverHeardAboutSaysTheSessionIsStillOpen() {
        val note = SyncMessages.describe(DisconnectResult.LocalOnly("mue.home.arpa"))

        assertTrue(note.isProblem)
        assertTrue(note.message.contains("still open on the server"))
        assertTrue(note.message.contains("Nothing recorded here was deleted."))
    }

    @Test
    fun disconnectingNothingIsNotAProblem() {
        assertFalse(SyncMessages.describe(DisconnectResult.NotPaired).isProblem)
    }

    // --- the four words ----------------------------------------------------------------------------------

    @Test
    fun eachStateHasExactlyThePrdsOwnWording() {
        assertEquals("Not connected", SyncMessages.label(SyncStatus.NOT_CONNECTED))
        assertEquals("Synced", SyncMessages.label(SyncStatus.SYNCED))
        assertEquals("Changes pending", SyncMessages.label(SyncStatus.CHANGES_PENDING))
        assertEquals("Sync issue", SyncMessages.label(SyncStatus.SYNC_ISSUE))
    }
}
