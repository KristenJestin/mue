package fr.kristenjestin.mue.data.remote.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * The HTTP half of synchronisation: `POST /api/v1/sync/push` and `POST /api/v1/sync/pull` on the
 * server the user paired with, authenticated by the device bearer of PRD 9.2.
 *
 * ## `expectSuccess = false`
 *
 * Ktor's default client throws on a non-2xx *before the body is read*, and PRD 20.4's whole
 * error design is a body: every non-2xx on `/api/v1` is the single `{ "error": … }` envelope
 * carrying the actionable [MueErrorDto] that FR-SYNC-007 requires the client to show. Letting
 * Ktor throw first would replace that with "HTTP 409", which no user and no `Data & sync` screen
 * can act on. So the status is inspected here and the body is always read.
 *
 * ## Nothing is retried at this level
 *
 * No `HttpRequestRetry`. Retrying belongs to WorkManager's backoff, where it is bounded, battery
 * aware and observable — PRD 9.4's "les échecs utilisent un backoff et ne déclenchent jamais une
 * boucle agressive". A retry loop inside the client would run inside a worker that already has
 * one, and multiply the two.
 *
 * The retry that does exist is FR-SYNC-006's, and it is not this layer's: a mutation carries the
 * same `mutationId` on every attempt, so the server replays its stored result instead of
 * applying the change again.
 */
class KtorSyncApi(
    private val client: HttpClient,
    /** The paired server's origin, e.g. `https://mue.home.arpa`. Never guessed, never defaulted. */
    private val baseUrl: suspend () -> String?,
    /** The device session bearer, read from Android Keystore at every call (PRD 9.2). */
    private val token: suspend () -> String?,
) : SyncApi {

    override suspend fun push(request: PushRequestDto): PushResponseDto =
        call(PUSH_PATH, PushRequestDto.serializer(), request, PushResponseDto.serializer())

    override suspend fun pull(request: PullRequestDto): PullResponseDto =
        call(PULL_PATH, PullRequestDto.serializer(), request, PullResponseDto.serializer())

    private suspend fun <Q, R> call(
        path: String,
        requestSerializer: kotlinx.serialization.KSerializer<Q>,
        request: Q,
        responseSerializer: kotlinx.serialization.KSerializer<R>,
    ): R {
        val origin = baseUrl()?.trimEnd('/')
            ?: throw SyncTransportException(
                SyncErrorCodes.CLIENT_NOT_PAIRED,
                "No server is paired with this phone.",
                retryable = false,
            )
        val bearer = token()
            ?: throw SyncTransportException(
                SyncErrorCodes.AUTH_UNAUTHENTICATED,
                "This phone holds no session for $origin. Pair it again.",
                retryable = false,
            )

        val response: HttpResponse = try {
            client.post("$origin$path") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                contentType(ContentType.Application.Json)
                // Serialised here rather than handed to ContentNegotiation as `Any`, so the
                // sealed hierarchies are written through their own serializer and their `op` and
                // `status` discriminators actually appear.
                setBody(SyncJson.instance.encodeToString(requestSerializer, request))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            // FR-SYNC-008: an unreachable server away from home is a normal state, not a fault.
            throw SyncTransportException(
                SyncErrorCodes.CLIENT_UNREACHABLE,
                "The server could not be reached.",
                retryable = true,
                cause = failure,
            )
        } catch (failure: Exception) {
            throw SyncTransportException(
                SyncErrorCodes.CLIENT_UNREACHABLE,
                "The request to $origin$path did not complete.",
                retryable = true,
                cause = failure,
            )
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw errorFor(response.status, text)

        return try {
            SyncJson.instance.decodeFromString(responseSerializer, text)
        } catch (failure: SerializationException) {
            // Not retryable: the same request would produce the same unparseable body. This is
            // the shape a contract drift takes in production, and the contract test is what is
            // supposed to have caught it first.
            throw SyncTransportException(
                SyncErrorCodes.CLIENT_MALFORMED_RESPONSE,
                "The server answered $path with a body this build cannot read.",
                retryable = false,
                cause = failure,
            )
        }
    }

    /**
     * A non-2xx body is the `{ error }` envelope. When it is not — a proxy's HTML error page, an
     * empty 502 — the status alone decides, because a phone away from home meets far more
     * reverse proxies than it meets Mue servers.
     */
    private fun errorFor(status: HttpStatusCode, text: String): SyncTransportException {
        val parsed = runCatching {
            SyncJson.instance.decodeFromString(ErrorResponseDto.serializer(), text).error
        }.getOrNull()

        if (parsed != null) {
            return SyncTransportException(parsed.code, parsed.message, parsed.retryable)
        }

        val retryable = status.value >= 500 || status == HttpStatusCode.TooManyRequests
        val code = when {
            status == HttpStatusCode.Unauthorized -> SyncErrorCodes.AUTH_UNAUTHENTICATED
            status == HttpStatusCode.Forbidden -> SyncErrorCodes.AUTH_FORBIDDEN
            status == HttpStatusCode.NotFound -> SyncErrorCodes.HTTP_NOT_FOUND
            retryable -> SyncErrorCodes.SERVER_UNAVAILABLE
            else -> SyncErrorCodes.SERVER_INTERNAL
        }
        return SyncTransportException(
            code,
            "The server answered ${status.value} with no readable error.",
            retryable,
        )
    }

    companion object {
        const val PUSH_PATH: String = "/api/v1/sync/push"
        const val PULL_PATH: String = "/api/v1/sync/pull"

        /**
         * The client the app uses. OkHttp because it is already the engine every Android build
         * ships, and because it honours the platform trust store — a self-hosted server behind a
         * private certificate authority is configured once, for the device, and not worked
         * around here.
         *
         * `ContentNegotiation` is registered even though every body is serialised by hand: it is
         * what makes an accidental `body<T>()` elsewhere use [SyncJson] rather than a default
         * `Json` that would parse `9007199254740993` as a double.
         */
        fun defaultClient(engine: HttpClientEngine = OkHttp.create()): HttpClient =
            HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(SyncJson.instance) }
                install(HttpTimeout) {
                    // A sync is deferrable work; nothing on screen is waiting for it. Long
                    // enough for an initial history over a slow link, short enough that a
                    // black-holed connection frees the worker instead of holding a wakelock.
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
            }
    }
}
