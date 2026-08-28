package fr.kristenjestin.mue.data.remote.sync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * `GET /api/v1/sync/events` as Server-Sent Events, on the connection that already works.
 *
 * ## Why SSE and not a socket
 *
 * The server is never publicly exposed (PRD 6 and 22.5), so nothing can push to this phone from
 * outside the house: no FCM, no push provider, no account with anybody. Whatever is used has to
 * be a connection the phone opens itself, to the private HTTPS endpoint of PRD 16 that pairing
 * already proved. That leaves a long-lived HTTP response or a WebSocket, and SSE wins on four
 * counts that all matter here:
 *
 * 1. **It is the same request as every other one.** Same [HttpClient], same connection pool, same
 *    platform trust store, and the same `Authorization: Bearer` of PRD 9.2 read by the same guard
 *    the push and the pull go through. A WebSocket upgrade cannot carry that header from every
 *    client and would have needed a ticket endpoint, which is a second authentication path.
 * 2. **It survives a proxy.** PRD 20.5 puts the platform behind a reverse proxy in a deployment.
 *    A chunked `text/event-stream` is an ordinary response every proxy already forwards; an
 *    `Upgrade: websocket` is a thing each one has to be configured for.
 * 3. **It is one-directional, which is exactly the traffic.** The phone has nothing to say here.
 *    Everything it sends goes through `POST /sync/push`, which is batched, idempotent and proven.
 * 4. **No new port and no new dependency.** No `ktor-client-websockets`, no second listener.
 *
 * ## Timeouts
 *
 * The shared client's `requestTimeoutMillis` is sixty seconds, which is right for a pull and
 * fatal for a stream, so it is overridden per request. What is *not* removed is the socket
 * timeout: it is the only thing that tells a phone whose WiFi vanished apart from one that is
 * merely idle. It is set well above the server's heartbeat, so a few lost comments are needed
 * before the connection is declared dead, and a genuinely dead one is noticed within the minute.
 */
class KtorSyncEventStream(
    private val client: HttpClient,
    /** The paired server's origin. Never guessed, never defaulted — as in [KtorSyncApi]. */
    private val baseUrl: suspend () -> String?,
    /** Read per connection, so `Disconnect server` takes effect on the next one (PRD 9.3). */
    private val token: suspend () -> String?,
) : SyncEventStream {

    override suspend fun connect(onHint: suspend () -> Unit) {
        val origin = baseUrl()?.trimEnd('/')
            ?: throw SyncTransportException(
                SyncErrorCodes.CLIENT_NOT_PAIRED,
                "No server is paired with this phone.",
                retryable = false,
            )
        val bearer = token()
            ?: throw SyncTransportException(
                SyncErrorCodes.AUTH_UNAUTHENTICATED,
                "This phone holds no session for $origin.",
                retryable = false,
            )

        try {
            client.prepareGet("$origin$EVENTS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                header(HttpHeaders.Accept, EVENT_STREAM_MEDIA_TYPE)
                // Belt to the server's braces: nothing on this response may be cached, and a
                // cached event stream is one that replays yesterday's hints on reconnection.
                header(HttpHeaders.CacheControl, "no-store")
                timeout {
                    // The point of the whole endpoint. A request timeout here would close a
                    // healthy channel on a fixed clock and make it a slow poll with extra steps.
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                }
            }.execute { response ->
                if (!response.status.isSuccess()) throw refusal(response.status)
                val channel = response.bodyAsChannel()
                read({ channel.readUTF8Line() }, onHint)
            }
        } catch (cancellation: CancellationException) {
            // The foreground went away, or the caller is shutting the channel down. Not a
            // failure, and specifically not one to back off from.
            throw cancellation
        } catch (transport: SyncTransportException) {
            throw transport
        } catch (failure: IOException) {
            throw SyncTransportException(
                SyncErrorCodes.CLIENT_UNREACHABLE,
                "The event stream to $origin could not be held open.",
                retryable = true,
                cause = failure,
            )
        } catch (failure: Exception) {
            throw SyncTransportException(
                SyncErrorCodes.CLIENT_UNREACHABLE,
                "The event stream to $origin ended unexpectedly.",
                retryable = true,
                cause = failure,
            )
        }
    }

    /**
     * A non-2xx on this endpoint, judged by status alone.
     *
     * The body is deliberately not read. A refusal here has no `MueError` worth showing — nothing
     * is on screen waiting for this channel — and reading a body from a route that was supposed
     * to stream is how a proxy's HTML error page becomes a parse failure instead of a reconnect.
     */
    private fun refusal(status: HttpStatusCode): SyncTransportException {
        val retryable = status.value >= 500 || status == HttpStatusCode.TooManyRequests
        val code = when {
            status == HttpStatusCode.Unauthorized -> SyncErrorCodes.AUTH_UNAUTHENTICATED
            status == HttpStatusCode.Forbidden -> SyncErrorCodes.AUTH_FORBIDDEN
            // An older server with no live channel. Retryable on purpose: the periodic worker
            // and every other trigger still work, and the day the server is upgraded the phone
            // picks the channel up without being reinstalled.
            status == HttpStatusCode.NotFound -> SyncErrorCodes.HTTP_NOT_FOUND
            retryable -> SyncErrorCodes.SERVER_UNAVAILABLE
            else -> SyncErrorCodes.SERVER_INTERNAL
        }
        return SyncTransportException(
            code,
            "The event stream was refused with ${status.value}.",
            // A 401 is not retryable here and must not be: the credential is wrong until
            // something outside this class changes it, and a channel that reconnected on it
            // would knock on a closed door for the whole foreground session.
            retryable = retryable || status == HttpStatusCode.NotFound,
        )
    }

    companion object {
        const val EVENTS_PATH: String = "/api/v1/sync/events"

        /** The one media type this endpoint answers. A literal, so nothing negotiates it away. */
        const val EVENT_STREAM_MEDIA_TYPE: String = "text/event-stream"

        /** As in [KtorSyncApi]: a server that does not answer the handshake is not at home. */
        const val CONNECT_TIMEOUT_MILLIS: Long = 15_000

        /**
         * Longer than the server's heartbeat by enough to tolerate two lost ones, short enough
         * that a phone which left the network notices within a minute rather than holding a dead
         * socket for the length of the foreground session.
         */
        const val SOCKET_TIMEOUT_MILLIS: Long = 70_000

        /**
         * The SSE frame parser, over a source of lines.
         *
         * It is a function of a line reader rather than of a channel so a JVM test can prove the
         * parsing — comments ignored, unknown fields ignored, an event dispatched on the blank
         * line and never before it — without a socket.
         *
         * `data:` is read and dropped. That is the invariant this file exists to keep: a hint is
         * the word *pull*, and there is no branch here in which its content could become one.
         */
        suspend fun read(nextLine: suspend () -> String?, onHint: suspend () -> Unit) {
            var event: String? = null
            // Whether anything at all has been accumulated since the last blank line. Without it
            // a stray blank line — the one a comment is followed by, the one a server writes to
            // flush — would dispatch an empty event and pull for nothing.
            var pending = false

            while (true) {
                val line = nextLine() ?: return
                when {
                    // The dispatch point of the SSE grammar. Everything accumulated since the
                    // last blank line is one event, and an event with no name is a `message`.
                    line.isEmpty() -> {
                        if (pending && (event == null || event in HINT_EVENTS)) onHint()
                        event = null
                        pending = false
                    }

                    // A comment, and not part of any event. The server's heartbeat is one: it
                    // proves the connection is alive and means nothing changed, so it must never
                    // cause a pull.
                    line.startsWith(':') -> Unit

                    line.startsWith(FIELD_EVENT) -> {
                        event = line.removePrefix(FIELD_EVENT).trim()
                        pending = true
                    }

                    // `data`, `id`, `retry`, and any field a later server adds. Counted as part
                    // of the frame and otherwise discarded — read but never looked at, which is
                    // the whole of PRD 12.3 as it applies to this file.
                    else -> pending = true
                }
            }
        }

        private const val FIELD_EVENT = "event:"

        /**
         * `hello` is the greeting; every successful connection ends in one pull because a phone
         * that was disconnected cannot know what it missed. `change` is the journal moving.
         *
         * Anything else a later server sends is ignored rather than treated as a hint: an
         * unrecognised event that caused a pull would make an old build loop against a new
         * server.
         */
        private val HINT_EVENTS = setOf("hello", "change")
    }
}
