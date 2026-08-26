package fr.kristenjestin.mue.data.remote.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the HTTP layer alone decides: the path, the bearer, the body it writes and the way a
 * non-2xx becomes a [MueErrorDto].
 *
 * Ktor's `MockEngine` answers in memory, so this runs on the JVM with no socket, no server and
 * no emulator — the same rule the contract test follows, and for the same reason: a test that
 * needs infrastructure is a test that stops being run.
 */
class KtorSyncApiTest {

    private val pullRequest = PullRequestDto(
        cursor = "eyJ2IjoxLCJzZXEiOiI0MSJ9",
        limit = WIRE_PULL_DEFAULT_LIMIT,
        supportedSchemaVersions = SyncWire.SUPPORTED_SCHEMA_VERSIONS,
    )

    @Test
    fun aPullGoesToTheContractPathWithTheDeviceBearer() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded) {
            respondJson(
                """{"status":"ok","changes":[],"nextCursor":"eyJ2IjoxLCJzZXEiOiI0MiJ9",""" +
                    """"hasMore":false,"serverTime":"2026-08-25T06:12:07.000Z",""" +
                    """"lastAndroidSyncAt":null}""",
            )
        }

        val response = api.pull(pullRequest)

        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://mue.home.arpa/api/v1/sync/pull", request.url.toString())
        assertEquals("Bearer session-token", request.headers[HttpHeaders.Authorization])
        val page = assertIs<PullPageDto>(response)
        assertEquals("eyJ2IjoxLCJzZXEiOiI0MiJ9", page.nextCursor)
    }

    /** A trailing slash on the paired URL must not become a double slash in the path. */
    @Test
    fun aTrailingSlashOnThePairedUrlIsNotSentTwice() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded, baseUrl = "https://mue.home.arpa/") {
            respondJson("""{"results":[],"serverTime":"2026-08-25T06:12:06.000Z"}""")
        }

        api.push(PushRequestDto(emptyList()))

        assertEquals("https://mue.home.arpa/api/v1/sync/push", recorded.single().url.toString())
    }

    /**
     * `upgrade_required` is a 200 with a discriminated body, not an HTTP error. A client that
     * threw on it would never read the [MueErrorDto] telling the user what to do.
     */
    @Test
    fun anUpgradeDemandArrivesAsAValueAndNotAsAThrow() = runTest {
        val api = api {
            respondJson(
                """{"status":"upgrade_required","error":{"code":"sync.upgrade_required",""" +
                    """"message":"schema version 2","retryable":false},""" +
                    """"serverTime":"2026-08-25T06:12:07.000Z","lastAndroidSyncAt":null}""",
            )
        }

        val response = assertIs<PullUpgradeRequiredDto>(api.pull(pullRequest))

        assertEquals(SyncErrorCodes.SYNC_UPGRADE_REQUIRED, response.error.code)
    }

    /** FR-SYNC-007's structured error is in the body of a non-2xx, so the body is always read. */
    @Test
    fun aNonSuccessBodyIsReadForItsStructuredError() = runTest {
        val api = api {
            respondJson(
                """{"error":{"code":"sync.invalid_cursor","message":"That cursor is not one """ +
                    """this server issued.","retryable":false}}""",
                HttpStatusCode.BadRequest,
            )
        }

        val failure = runCatching { api.pull(pullRequest) }.exceptionOrNull()

        val transport = assertIs<SyncTransportException>(failure)
        assertEquals(SyncErrorCodes.SYNC_INVALID_CURSOR, transport.code)
        assertEquals("That cursor is not one this server issued.", transport.message)
        assertTrue(!transport.retryable)
    }

    /**
     * The answer a phone away from home actually gets: a reverse proxy's HTML, with no `error`
     * envelope anywhere in it. The status alone has to decide, and 502 is worth another attempt.
     */
    @Test
    fun aProxyErrorPageStillProducesARetryableFailure() = runTest {
        val api = api { respondError(HttpStatusCode.BadGateway, "<html>Bad Gateway</html>") }

        val transport = assertIs<SyncTransportException>(
            runCatching { api.pull(pullRequest) }.exceptionOrNull(),
        )

        assertEquals(SyncErrorCodes.SERVER_UNAVAILABLE, transport.code)
        assertTrue(transport.retryable)
    }

    /** A revoked session is not retryable: repeating it would only be refused again. */
    @Test
    fun aRefusedSessionIsNotRetried() = runTest {
        val api = api { respondError(HttpStatusCode.Unauthorized, "") }

        val transport = assertIs<SyncTransportException>(
            runCatching { api.pull(pullRequest) }.exceptionOrNull(),
        )

        assertEquals(SyncErrorCodes.AUTH_UNAUTHENTICATED, transport.code)
        assertTrue(!transport.retryable)
    }

    /** FR-SYNC-008: an unreachable server is retryable and carries no alarm. */
    @Test
    fun anUnreachableServerIsRetryable() = runTest {
        val api = api { throw IOException("connect timed out") }

        val transport = assertIs<SyncTransportException>(
            runCatching { api.pull(pullRequest) }.exceptionOrNull(),
        )

        assertEquals(SyncErrorCodes.CLIENT_UNREACHABLE, transport.code)
        assertTrue(transport.retryable)
    }

    /**
     * A body this build cannot read is drift reaching production, and repeating the request
     * would produce the same bytes — so it is recorded and not retried.
     */
    @Test
    fun aBodyThisBuildCannotReadIsNotRetried() = runTest {
        val api = api { respondJson("""{"status":"partial","changes":[]}""") }

        val transport = assertIs<SyncTransportException>(
            runCatching { api.pull(pullRequest) }.exceptionOrNull(),
        )

        assertEquals(SyncErrorCodes.CLIENT_MALFORMED_RESPONSE, transport.code)
        assertTrue(!transport.retryable)
    }

    /** With no server paired there is nothing to call, and nothing to retry either. */
    @Test
    fun anUnpairedPhoneFailsBeforeOpeningAConnection() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded, baseUrl = null) { respondJson("{}") }

        val transport = assertIs<SyncTransportException>(
            runCatching { api.pull(pullRequest) }.exceptionOrNull(),
        )

        assertEquals(SyncErrorCodes.CLIENT_NOT_PAIRED, transport.code)
        assertEquals(0, recorded.size)
    }

    /** No token means no request: a bearer-less call would be refused and would leak the path. */
    @Test
    fun aMissingBearerFailsBeforeOpeningAConnection() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded, token = null) { respondJson("{}") }

        val transport = assertIs<SyncTransportException>(
            runCatching { api.pull(pullRequest) }.exceptionOrNull(),
        )

        assertEquals(SyncErrorCodes.AUTH_UNAUTHENTICATED, transport.code)
        assertEquals(0, recorded.size)
    }

    /**
     * The body is written through the sealed serializer, so `op` and `status` really appear and
     * a `sequence` past 2^53 is written as text. Serialising through `Any` would silently drop
     * both.
     */
    @Test
    fun theRequestBodyCarriesTheDiscriminatorsAndTheCursorVerbatim() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded) {
            respondJson("""{"results":[],"serverTime":"2026-08-25T06:12:06.000Z"}""")
        }

        api.push(
            PushRequestDto(
                listOf(
                    DeleteMutationDto(
                        mutationId = "0198f0a1-9e8d-7c6b-b5a4-938271605f4e",
                        aggregateType = WIRE_AGGREGATE_MEASUREMENT,
                        aggregateId = "2026-08-24",
                        baseRevision = "9",
                        payloadSchemaVersion = 1,
                        origin = OriginDto(OriginDto.TYPE_ANDROID, "device-7f3c1a04"),
                        clientOccurredAt = "2026-08-25T06:12:05.004Z",
                    ),
                ),
            ),
        )

        val body = recorded.single().body.toByteArray().decodeToString()
        assertTrue(body.contains("\"op\":\"delete\""), body)
        assertTrue(body.contains("\"payload\":null"), body)
        assertTrue(body.contains("\"baseRevision\":\"9\""), body)
    }

    // --- helpers --------------------------------------------------------------------------

    private fun api(
        recorded: MutableList<HttpRequestData> = mutableListOf(),
        baseUrl: String? = "https://mue.home.arpa",
        token: String? = "session-token",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorSyncApi {
        val engine = MockEngine { request ->
            recorded += request
            handler(request)
        }
        return KtorSyncApi(
            client = KtorSyncApi.defaultClient(engine),
            baseUrl = { baseUrl },
            token = { token },
        )
    }
}

private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = ByteReadChannel(body),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
