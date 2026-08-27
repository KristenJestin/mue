package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import fr.kristenjestin.mue.data.remote.sync.SyncEventStream
import fr.kristenjestin.mue.data.remote.sync.SyncTransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live channel's loop, on the JVM, with no socket and no emulator.
 *
 * The properties below are the ones that cannot be seen by using the application — a channel that
 * reconnected too eagerly, that stacked synchronisations on top of each other, or that surfaced a
 * failure would all look fine on a desk with the server running, and be wrong on a train.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSyncChannelTest {

    /**
     * A stream whose every connection is scripted by the test.
     *
     * [limit] is not decoration: past it, `connect` simply never returns, which is what a healthy
     * connection to a running server does. Without it a test of the reconnection loop would be a
     * test of how long this machine takes to run out of memory.
     */
    private class ScriptedStream(private val limit: Int = 4) : SyncEventStream {
        /** One entry per connection attempt, taken in order; the last one repeats. */
        val attempts = ArrayDeque<suspend (suspend () -> Unit) -> Unit>()
        var connections = 0
            private set

        override suspend fun connect(onHint: suspend () -> Unit) {
            connections++
            if (connections > limit) awaitCancellation()
            val attempt = if (attempts.size > 1) attempts.removeFirst() else attempts.first()
            attempt(onHint)
        }
    }

    /** A connection that is opened and held, as one to a running server is. */
    private fun held(open: CompletableDeferred<suspend () -> Unit>):
        suspend (suspend () -> Unit) -> Unit = { onHint ->
        open.complete(onHint)
        awaitCancellation()
    }

    private fun unreachable(): suspend (suspend () -> Unit) -> Unit = {
        throw SyncTransportException(
            SyncErrorCodes.CLIENT_UNREACHABLE,
            "The event stream could not be held open.",
            retryable = true,
        )
    }

    // --- a hint becomes a synchronisation ------------------------------------------------------

    @Test
    fun aChangeAnnouncedByTheServerSynchronisesWithoutAnybodyPressingAnything() = runTest {
        val synchronisations = Channel<Unit>(Channel.UNLIMITED)
        val stream = ScriptedStream()
        val open = CompletableDeferred<suspend () -> Unit>()
        stream.attempts += held(open)

        val channel = LiveSyncChannel(
            paired = { true },
            stream = stream,
            sync = {
                synchronisations.send(Unit)
                SyncOutcome.NotPaired
            },
            now = { testScheduler.currentTime },
            sleep = { delay(it) },
            random = Random(1),
        )

        val job = launch { channel.run() }
        val hint = withTimeout(1_000) { open.await() }

        // The greeting: every successful connection ends in one pull, because a phone that was
        // disconnected cannot know what it missed and must not be told a position.
        hint()
        advanceUntilIdle()
        assertEquals(Unit, synchronisations.tryReceive().getOrNull(), "the greeting did not pull")

        // And the journal moving.
        hint()
        advanceUntilIdle()
        assertEquals(Unit, synchronisations.tryReceive().getOrNull(), "the change did not pull")

        job.cancel()
    }

    /**
     * The engine's own `Mutex` serialises `sync()`; what this proves is that the channel does not
     * pile a queue of runs against it. Ten changes saved on the Web in ten seconds are one pull
     * and a follow-up, not ten.
     */
    @Test
    fun aBurstOfHintsCollapsesIntoOneFollowUpRatherThanAQueueOfRuns() = runTest {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.RENDEZVOUS)
        val stream = ScriptedStream()
        val open = CompletableDeferred<suspend () -> Unit>()
        stream.attempts += held(open)

        var runs = 0
        val channel = LiveSyncChannel(
            paired = { true },
            stream = stream,
            sync = {
                runs++
                started.send(Unit)
                release.receive()
                SyncOutcome.NotPaired
            },
            now = { testScheduler.currentTime },
            sleep = { delay(it) },
            random = Random(1),
        )

        val job = launch { channel.run() }
        val hint = withTimeout(1_000) { open.await() }

        // The first hint starts a run and the run blocks, standing in for a synchronisation still
        // talking to the server.
        hint()
        withTimeout(1_000) { started.receive() }

        // Nine more arrive while it is in flight.
        repeat(9) { hint() }
        advanceUntilIdle()

        release.send(Unit)
        withTimeout(1_000) { started.receive() }
        release.send(Unit)
        advanceUntilIdle()

        assertEquals(2, runs, "the burst should collapse into the run in flight plus one follow-up")

        job.cancel()
    }

    // --- failure is not an event ---------------------------------------------------------------

    @Test
    fun anUnreachableServerIsRetriedSilentlyAndNeverSynchronises() = runTest {
        val stream = ScriptedStream()
        stream.attempts += unreachable()
        var synchronisations = 0
        val waits = mutableListOf<Long>()

        val channel = LiveSyncChannel(
            paired = { true },
            stream = stream,
            sync = { synchronisations++; SyncOutcome.NotPaired },
            now = { testScheduler.currentTime },
            sleep = { waits += it; delay(it) },
            random = Random(7),
        )

        val job = launch { channel.run() }
        advanceTimeBy(30_000)
        job.cancel()
        advanceUntilIdle()

        assertTrue(stream.connections > 1, "a refused connection must be attempted again")
        assertEquals(0, synchronisations, "an unreachable server must not synchronise")
        assertTrue(waits.isNotEmpty(), "a refused connection must be followed by a wait")
        assertTrue(waits.all { it > 0 }, "a refused connection must never be retried immediately")
    }

    /**
     * PRD 21: an unpaired phone is a working phone. It must not open a socket, and it must not
     * spin looking for one either.
     */
    @Test
    fun anUnpairedPhoneOpensNoConnectionAtAll() = runTest {
        val stream = ScriptedStream()
        stream.attempts += unreachable()
        val waits = mutableListOf<Long>()

        val channel = LiveSyncChannel(
            paired = { false },
            stream = stream,
            sync = { SyncOutcome.NotPaired },
            now = { testScheduler.currentTime },
            sleep = { waits += it; delay(it) },
            random = Random(3),
        )

        val job = launch { channel.run() }
        advanceTimeBy(10 * 60_000)
        job.cancel()
        advanceUntilIdle()

        assertEquals(0, stream.connections, "an unpaired phone must not reach for the network")
        assertTrue(waits.isNotEmpty(), "an unpaired phone must wait rather than spin")
        assertTrue(
            waits.last() >= LiveSyncChannel.MAX_BACKOFF_MILLIS * 4 / 5,
            "an unpaired phone must settle on the ceiling rather than keep looking: $waits",
        )
    }

    /**
     * A pairing that happens while the app is already open brings the channel up without a
     * restart, because `paired` is asked before every attempt rather than once.
     */
    @Test
    fun pairingDuringAForegroundSessionBringsTheChannelUp() = runTest {
        var paired = false
        val stream = ScriptedStream()
        val open = CompletableDeferred<suspend () -> Unit>()
        stream.attempts += held(open)

        val channel = LiveSyncChannel(
            paired = { paired },
            stream = stream,
            sync = { SyncOutcome.NotPaired },
            now = { testScheduler.currentTime },
            sleep = { delay(it) },
            random = Random(5),
        )

        val job = launch { channel.run() }
        advanceTimeBy(5 * 60_000)
        assertEquals(0, stream.connections, "an unpaired phone must not reach for the network")

        paired = true
        advanceTimeBy(5 * 60_000)

        assertTrue(stream.connections > 0, "pairing must be noticed without an app restart")
        assertTrue(open.isCompleted, "the channel must actually open once the phone is paired")

        job.cancel()
        advanceUntilIdle()
    }

    // --- backoff -------------------------------------------------------------------------------

    @Test
    fun theBackoffGrowsFromOneSecondToAOneMinuteCeilingAndNeverBeyond() {
        val channel = LiveSyncChannel(
            paired = { true },
            stream = ScriptedStream(),
            sync = { SyncOutcome.NotPaired },
            random = Random(0),
        )

        // Zero failures is a healthy connection that closed: reconnect at once.
        assertEquals(0L, channel.backoffMillis(0))

        // 1s, 2s, 4s, 8s, 16s, 32s, then the ceiling for ever. Each wait is asserted against its
        // own expected base rather than against the previous one: the jitter is ±20 %, so two
        // consecutive draws at the ceiling legitimately go down as well as up, and a test that
        // demanded a monotonic sequence would be testing the random seed.
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        expected.forEachIndexed { index, base ->
            val wait = channel.backoffMillis(index + 1)
            assertTrue(
                wait in (base * 4 / 5)..(base * 6 / 5),
                "wait ${index + 1} should be $base ±20 %, was $wait",
            )
        }

        // Repeated many times over, it never runs away past the ceiling.
        assertTrue(
            (1..200).all { channel.backoffMillis(it) <= LiveSyncChannel.MAX_BACKOFF_MILLIS * 6 / 5 },
            "no wait may exceed the ceiling and its jitter",
        )
    }

    /**
     * A connection that stood up and lasted resets the curve; one that was accepted and dropped at
     * once does not. Without the second half, a server answering and closing in a loop would be
     * reconnected to as fast as the network allows for the whole foreground session.
     */
    @Test
    fun aServerThatAcceptsAndDropsAtOnceDoesNotResetTheCurve() = runTest {
        val waits = mutableListOf<Long>()
        val stream = ScriptedStream()
        // Accepted, greeted, and closed immediately — over and over. The clock does not move, so
        // no connection ever counts as healthy.
        stream.attempts += { onHint -> onHint() }

        val channel = LiveSyncChannel(
            paired = { true },
            stream = stream,
            sync = { SyncOutcome.NotPaired },
            now = { 0L },
            sleep = { waits += it; delay(it) },
            random = Random(11),
        )

        val job = launch { channel.run() }
        advanceTimeBy(30_000)
        job.cancel()
        advanceUntilIdle()

        assertTrue(waits.size >= 3, "the loop should have run several times: $waits")
        assertTrue(
            waits.last() > waits.first(),
            "a server that accepts and drops must not reset the backoff: $waits",
        )
    }

    @Test
    fun aConnectionThatLastedIsReopenedAtOnce() = runTest {
        val waits = mutableListOf<Long>()
        var clock = 0L
        val stream = ScriptedStream()
        stream.attempts += { onHint ->
            onHint()
            // The connection stood up for long enough to be proof the server is there.
            clock += LiveSyncChannel.HEALTHY_CONNECTION_MILLIS
        }

        val channel = LiveSyncChannel(
            paired = { true },
            stream = stream,
            sync = { SyncOutcome.NotPaired },
            now = { clock },
            sleep = { waits += it; delay(it) },
            random = Random(11),
        )

        val job = launch { channel.run() }
        advanceTimeBy(30_000)
        job.cancel()
        advanceUntilIdle()

        assertTrue(waits.isNotEmpty(), "the loop should have run: $waits")
        assertTrue(
            waits.all { it == 0L },
            "a connection that lasted should be reopened at once, not backed off: $waits",
        )
    }

    // --- the channel outlives its own failures -------------------------------------------------

    /**
     * `SyncEngine.sync` returns its failures as values and throws only for a bug. A bug in it must
     * not take the channel down: the next hint gets a fresh attempt, and nothing else in PRD 9.4
     * is affected either way.
     */
    @Test
    fun aSynchronisationThatThrowsDoesNotKillTheChannel() = runTest {
        val stream = ScriptedStream()
        val open = CompletableDeferred<suspend () -> Unit>()
        stream.attempts += held(open)

        var attempts = 0
        val channel = LiveSyncChannel(
            paired = { true },
            stream = stream,
            sync = {
                attempts++
                if (attempts == 1) error("a bug in the engine")
                SyncOutcome.NotPaired
            },
            now = { testScheduler.currentTime },
            sleep = { delay(it) },
            random = Random(2),
        )

        val job = launch { channel.run() }
        val hint = withTimeout(1_000) { open.await() }

        hint()
        advanceUntilIdle()
        hint()
        advanceUntilIdle()

        assertEquals(2, attempts, "a thrown synchronisation must not stop the next hint")
        assertTrue(job.isActive, "the channel must survive a failing synchronisation")

        job.cancel()
    }
}
