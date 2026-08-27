package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.remote.sync.SyncEventStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Holds the live channel of PRD 9.4 open for as long as somebody is looking at the screen, and
 * synchronises whenever the server says the journal moved.
 *
 * ## Two conditions, and no third
 *
 * A connection is attempted when a server is paired and it is reachable. There is no notion of an
 * authorised network here, no SSID, no captive-portal probe and no "am I at home": those are all
 * ways of guessing an answer the connection itself gives for free. If it opens, the phone is
 * current within a second or two. If it does not, it costs one refused socket and waits — which
 * is exactly what PRD 6 concedes when it excludes real time "hors du réseau autorisé", and
 * exactly what the periodic worker is for.
 *
 * ## Foreground only
 *
 * [run] returns only when it is cancelled, and it is cancelled by the lifecycle: `MueApp` runs it
 * inside `repeatOnLifecycle(STARTED)`. A backgrounded app therefore holds no socket, no wakelock
 * and no timer, and a closed app costs nothing at all — the deferred worker of PRD 19 remains the
 * only thing that runs when nobody is looking. That is also why this is not a foreground service:
 * PRD 19 forbids an ordinary synchronisation from demanding one.
 *
 * ## Failure is not an event
 *
 * Nothing below records a failure, shows a message, or writes to `sync_state`. An unreachable
 * server is the normal state away from home (FR-SYNC-008) and PRD 9.1 keeps alarms off the main
 * screens; the reconnection loop is a private matter between this class and the network. The
 * `Data & sync` section still shows what the *synchronisations* did, because those go through
 * [SyncEngine], which records exactly as much as it did before this class existed.
 *
 * ## It reuses the engine's gate rather than adding one
 *
 * [SyncEngine.sync] is already serialised by a `Mutex`, and `Sync now`, the foreground trigger and
 * the periodic worker all share the one engine instance. A hint calls the same method, so a burst
 * of events cannot produce overlapping runs, and a run started by the worker is not raced by one
 * started here.
 *
 * A second `Mutex` would have been the obvious mistake. What is added instead is a **conflating**
 * queue: hints arriving while a synchronisation is in flight collapse into exactly one follow-up,
 * so ten changes saved on the Web in ten seconds cost one pull and not ten, and a hint is never
 * silently dropped either — the last one always survives to trigger the run that sees them all.
 */
class LiveSyncChannel(
    /**
     * PRD 21: an unpaired phone is a working phone. Asked before every attempt rather than once,
     * so pairing during a foreground session brings the channel up without a restart, and
     * `Disconnect server` takes it down.
     */
    private val paired: suspend () -> Boolean,
    private val stream: SyncEventStream,
    /** [SyncEngine.sync], and nothing else. Injected so the loop is provable on the JVM. */
    private val sync: suspend () -> SyncOutcome,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) {

    /**
     * Runs the channel until cancelled.
     *
     * Never throws for a network reason. It propagates [CancellationException] and nothing else,
     * because the only thing that ends it is the foreground ending.
     */
    suspend fun run(): Unit = coroutineScope {
        val hints = Channel<Unit>(Channel.CONFLATED)

        val pulls = launch {
            for (hint in hints) {
                try {
                    sync()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // `SyncEngine.sync` returns its failures as values and throws only for a bug.
                    // A bug in it must not take the channel down with it: the next hint gets a
                    // fresh attempt, and the periodic worker is untouched either way.
                }
            }
        }

        try {
            var failures = 0
            while (currentCoroutineContext().isActive) {
                val connectedAt = now()
                var greeted = false

                if (paired()) {
                    try {
                        stream.connect {
                            greeted = true
                            hints.trySend(Unit)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // Unreachable, refused, or cut mid-stream. All the same thing from here:
                        // nothing to say, and something to wait for.
                    }
                }

                // A connection that stood up and lasted is proof the server is there, so the next
                // failure starts the curve again from the bottom. The duration matters as much as
                // the greeting: a server that accepts and immediately closes would otherwise reset
                // the backoff on every attempt and be reconnected to in a tight loop.
                val healthy = greeted && now() - connectedAt >= HEALTHY_CONNECTION_MILLIS
                failures = if (healthy) 0 else failures + 1

                sleep(backoffMillis(failures))
            }
        } finally {
            hints.close()
            pulls.cancel()
        }
    }

    /**
     * How long to wait before the next attempt, after [failures] consecutive ones.
     *
     * Exponential from one second to a one-minute ceiling — PRD 9.4's "backoff, jamais une boucle
     * agressive". The ceiling is what a phone that is simply not at home settles on: one refused
     * connection a minute for as long as the screen is on, which is cheaper than the screen.
     *
     * The jitter is not decoration. Without it every foreground return after an outage reconnects
     * on the same schedule, and the phone, the tablet and the retry of whatever else is waiting
     * all arrive at the server in the same millisecond.
     */
    internal fun backoffMillis(failures: Int): Long {
        if (failures <= 0) return 0
        val exponent = (failures - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        val base = (INITIAL_BACKOFF_MILLIS shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
        val spread = (base * JITTER_FRACTION).toLong().coerceAtLeast(1L)
        return base - spread + random.nextLong(2 * spread + 1)
    }

    companion object {
        /** The first wait, and the one a healthy connection that closed cleanly falls back to. */
        const val INITIAL_BACKOFF_MILLIS: Long = 1_000

        /** One attempt a minute is the floor of the curve, not a promise about when it opens. */
        const val MAX_BACKOFF_MILLIS: Long = 60_000

        /**
         * How long a connection has to last before it counts as proof the server is there.
         *
         * Ten seconds: longer than the greeting and the first heartbeat, shorter than any session
         * a person would call "it worked".
         */
        const val HEALTHY_CONNECTION_MILLIS: Long = 10_000

        /** 1s, 2s, 4s, 8s, 16s, 32s, then the ceiling. */
        private const val MAX_BACKOFF_EXPONENT: Int = 6

        /** ±20 %, which is enough to break lockstep and too little to feel like a delay. */
        private const val JITTER_FRACTION: Double = 0.2
    }
}
