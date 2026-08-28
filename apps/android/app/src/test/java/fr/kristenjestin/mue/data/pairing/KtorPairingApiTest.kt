package fr.kristenjestin.mue.data.pairing

import fr.kristenjestin.mue.data.remote.sync.KtorSyncApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
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
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the pairing's HTTP layer alone decides: the three paths, the header the bearer arrives in,
 * and — the part this whole package exists for — which named failure each way of going wrong
 * becomes.
 *
 * `MockEngine` answers in memory, so this runs on the JVM with no socket, no server and no
 * emulator, exactly as the sync client's own test does.
 */
class KtorPairingApiTest {

    // --- probing ---------------------------------------------------------------------------------

    @Test
    fun theProbeAsksTheUnauthenticatedLivenessEndpoint() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded) { respondJson("""{"status":"ok"}""") }

        api.probe("https://mue.home.arpa")

        val request = recorded.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://mue.home.arpa/health/live", request.url.toString())
        // No credential is offered to a host that has not yet been identified.
        assertEquals(null, request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aRouterPageInsteadOfMueIsNamedAsNotAMueServer() = runTest {
        val api = api { respondJson("<html><body>Livebox</body></html>") }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        assertIs<PairingFailure.NotAMueServer>(failure.failure)
    }

    @Test
    fun aFiveHundredIsUnavailableAndNotMistakenForSomethingElse() = runTest {
        val api = api { respondJson("""{"error":"boom"}""", HttpStatusCode.BadGateway) }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        assertEquals(502, assertIs<PairingFailure.ServerUnavailable>(failure.failure).status)
    }

    @Test
    fun aFourOhFourAtLivenessIsNotAMueServer() = runTest {
        val api = api { respondJson("""{"error":{"code":"http.not_found"}}""", HttpStatusCode.NotFound) }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        assertIs<PairingFailure.NotAMueServer>(failure.failure)
    }

    // --- the four transport failures, told apart ----------------------------------------------------

    @Test
    fun anUnresolvableNameIsHostNotFound() = runTest {
        val api = api { throw UnknownHostException("mue.home.arpa") }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        assertEquals("mue.home.arpa", assertIs<PairingFailure.HostNotFound>(failure.failure).host)
    }

    @Test
    fun aRefusedConnectionIsUnreachableAndNotACertificateProblem() = runTest {
        val api = api { throw ConnectException("Connection refused") }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        assertIs<PairingFailure.Unreachable>(failure.failure)
    }

    /**
     * PRD 16 has the pairing verify that the address and the certificate agree. OkHttp enforces
     * it; this is where the enforcement becomes a sentence the owner can act on, and it must not
     * be flattened into "the server did not answer" — it answered, and Mue refused it.
     */
    @Test
    fun aCertificateAndroidWillNotTrustIsItsOwnFailure() = runTest {
        val api = api { throw SSLPeerUnverifiedException("Hostname mue.home.arpa not verified") }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        val untrusted = assertIs<PairingFailure.UntrustedCertificate>(failure.failure)
        assertEquals("mue.home.arpa", untrusted.host)
    }

    /** Ktor wraps OkHttp, OkHttp wraps JSSE; the cause chain is walked so the depth cannot hide it. */
    @Test
    fun aCertificateFailureIsFoundThroughAWrappingException() = runTest {
        val api = api {
            throw IllegalStateException("request failed", SSLPeerUnverifiedException("bad cert"))
        }

        val failure = assertFailsWith<PairingException> { api.probe("https://mue.home.arpa") }

        assertIs<PairingFailure.UntrustedCertificate>(failure.failure)
    }

    // --- signing in ---------------------------------------------------------------------------------

    @Test
    fun signingInPostsTheCredentialsAndReadsTheBearerFromItsHeader() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded) {
            respond(
                content = ByteReadChannel(
                    """{"redirect":false,"token":"raw","user":{"id":"user_1",""" +
                        """"email":"kris@example.org","name":"Kris"}}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "set-auth-token" to listOf("bearer-from-header"),
                ),
            )
        }

        val session = api.signIn("https://mue.home.arpa", "kris@example.org", "correct horse")

        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://mue.home.arpa/api/auth/sign-in/email", request.url.toString())
        assertTrue(String(request.body.toByteArray()).contains("\"correct horse\""))
        // The bearer plugin's own header wins over the body, which is the token to present.
        assertEquals("bearer-from-header", session.token)
        assertEquals("kris@example.org", session.account.email)
    }

    /** A home reverse proxy that strips unknown response headers must not look like a broken server. */
    @Test
    fun theBodyTokenIsUsedWhenTheHeaderWasStrippedOnTheWay() = runTest {
        val api = api {
            respondJson("""{"token":"bearer-from-body","user":{"id":"user_1"}}""")
        }

        val session = api.signIn("https://mue.home.arpa", "kris@example.org", "pw")

        assertEquals("bearer-from-body", session.token)
    }

    @Test
    fun asignInWithNoTokenAtAllIsNamedRatherThanTreatedAsSuccess() = runTest {
        val api = api { respondJson("""{"redirect":true,"user":{"id":"user_1"}}""") }

        val failure = assertFailsWith<PairingException> {
            api.signIn("https://mue.home.arpa", "kris@example.org", "pw")
        }

        assertIs<PairingFailure.NoSessionToken>(failure.failure)
    }

    @Test
    fun aRefusedPasswordIsCredentialsRejected() = runTest {
        val api = api {
            respondJson(
                """{"code":"INVALID_EMAIL_OR_PASSWORD","message":"Invalid email or password"}""",
                HttpStatusCode.Unauthorized,
            )
        }

        val failure = assertFailsWith<PairingException> {
            api.signIn("https://mue.home.arpa", "kris@example.org", "wrong")
        }

        assertEquals(PairingFailure.CredentialsRejected, failure.failure)
    }

    /** Better Auth answers 400 for a bad pair as well as 401; both are the same news. */
    @Test
    fun aFourHundredNamingThePasswordIsAlsoCredentialsRejected() = runTest {
        val api = api {
            respondJson(
                """{"code":"INVALID_EMAIL_OR_PASSWORD","message":"Invalid email or password"}""",
                HttpStatusCode.BadRequest,
            )
        }

        val failure = assertFailsWith<PairingException> {
            api.signIn("https://mue.home.arpa", "kris@example.org", "wrong")
        }

        assertEquals(PairingFailure.CredentialsRejected, failure.failure)
    }

    @Test
    fun rateLimitingCarriesTheServersOwnRetryAfter() = runTest {
        val api = api {
            respond(
                content = ByteReadChannel("""{"message":"too many"}"""),
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.RetryAfter to listOf("42"),
                ),
            )
        }

        val failure = assertFailsWith<PairingException> {
            api.signIn("https://mue.home.arpa", "kris@example.org", "pw")
        }

        assertEquals(42L, assertIs<PairingFailure.TooManyAttempts>(failure.failure).retryAfterSeconds)
    }

    /** A missing endpoint is a server too old, not a wrong password. */
    @Test
    fun anAbsentSignInEndpointIsNotAWrongPassword() = runTest {
        val api = api { respondJson("""{"error":{"code":"http.not_found"}}""", HttpStatusCode.NotFound) }

        val failure = assertFailsWith<PairingException> {
            api.signIn("https://mue.home.arpa", "kris@example.org", "pw")
        }

        assertIs<PairingFailure.SignInUnsupported>(failure.failure)
    }

    @Test
    fun anotherRefusalCarriesTheServersOwnExplanation() = runTest {
        val api = api {
            respondJson(
                """{"code":"EMAIL_NOT_VERIFIED","message":"Verify your email first"}""",
                HttpStatusCode.BadRequest,
            )
        }

        val failure = assertFailsWith<PairingException> {
            api.signIn("https://mue.home.arpa", "kris@example.org", "pw")
        }

        val refused = assertIs<PairingFailure.SignInRefused>(failure.failure)
        assertEquals("EMAIL_NOT_VERIFIED", refused.code)
        assertTrue(refused.message.contains("Verify your email first"))
    }

    // --- proving the bearer ---------------------------------------------------------------------------

    @Test
    fun theSessionCheckPresentsTheBearerAndReadsTheAccount() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded) {
            respondJson(
                """{"session":{"id":"s1"},"user":{"id":"user_1",""" +
                    """"email":"Kris@Example.org","name":"Kris"}}""",
            )
        }

        val account = api.account("https://mue.home.arpa", "bearer-1")

        val request = recorded.single()
        assertEquals("https://mue.home.arpa/api/auth/get-session", request.url.toString())
        assertEquals("Bearer bearer-1", request.headers[HttpHeaders.Authorization])
        assertEquals("user_1", account.id)
        // The comparison key is lowercased; the label the user sees is not.
        assertEquals("kris@example.org", account.identity)
        assertEquals("Kris@Example.org", account.label)
    }

    /**
     * Better Auth answers 200 with a JSON `null` for a credential it does not know. Treating that
     * as success is exactly how a phone ends up storing a token nothing accepts.
     */
    @Test
    fun aNullSessionBodyIsARejectionAndNotAnEmptyAccount() = runTest {
        val api = api { respondJson("null") }

        val failure = assertFailsWith<PairingException> {
            api.account("https://mue.home.arpa", "bearer-1")
        }

        assertIs<PairingFailure.SessionRejected>(failure.failure)
    }

    @Test
    fun aFourOhOneOnTheSessionCheckIsARejection() = runTest {
        val api = api { respondJson("""{"code":"UNAUTHORIZED"}""", HttpStatusCode.Unauthorized) }

        val failure = assertFailsWith<PairingException> {
            api.account("https://mue.home.arpa", "bearer-1")
        }

        assertIs<PairingFailure.SessionRejected>(failure.failure)
    }

    // --- revoking ---------------------------------------------------------------------------------------

    @Test
    fun revokingSignsTheDeviceSessionOut() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val api = api(recorded) { respondJson("""{"success":true}""") }

        assertTrue(api.revoke("https://mue.home.arpa", "bearer-1"))

        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://mue.home.arpa/api/auth/sign-out", request.url.toString())
        assertEquals("Bearer bearer-1", request.headers[HttpHeaders.Authorization])
    }

    /** A session already gone is a session revoked; there is nothing left for the caller to do. */
    @Test
    fun anAlreadyUnknownSessionCountsAsRevoked() = runTest {
        val api = api { respondJson("""{"code":"UNAUTHORIZED"}""", HttpStatusCode.Unauthorized) }

        assertTrue(api.revoke("https://mue.home.arpa", "bearer-1"))
    }

    /** PRD 9.3: an unreachable server never blocks a disconnect, so this reports rather than throws. */
    @Test
    fun anUnreachableServerMakesRevocationReportFalseRatherThanThrow() = runTest {
        val api = api { throw ConnectException("Connection refused") }

        assertFalse(api.revoke("https://mue.home.arpa", "bearer-1"))
    }

    private fun api(
        recorded: MutableList<HttpRequestData> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorPairingApi {
        val engine = MockEngine { request ->
            recorded += request
            handler(request)
        }
        // The very client the app pairs and then synchronises with, so the two are proved on the
        // same configuration rather than on two that happen to agree today.
        return KtorPairingApi(KtorSyncApi.defaultClient(engine))
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
