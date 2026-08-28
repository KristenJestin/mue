package fr.kristenjestin.mue.data.remote.openfoodfacts

import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the transport alone decides: what goes on the wire, and which named outcome each answer
 * becomes.
 *
 * `MockEngine` answers in memory, so this runs on the JVM with no socket, no service and no
 * emulator — exactly as `KtorPairingApiTest` and the sync client's own test do. The bodies are
 * `OpenFoodFactsFixtures`', recorded from the live service, so a response that passes here is a
 * response a real barcode produces.
 *
 * ## The half that matters most
 *
 * `OpenFoodFactsUrlTest` already proves that the *request value* carries the number, the fields
 * list and one static header. That proof is worth nothing if the transport adds something on the
 * way out, and a transport is exactly where a locale, a cookie or a bearer gets attached without
 * anybody deciding to. So the first tests below read the request that actually left the client
 * and assert on **every header it carries**, by name — not that the User-Agent is right, but that
 * nothing else is there.
 */
class KtorProductLookupTest {

    private val nutella = OpenFoodFactsFixtures.FOUND_BARCODE

    // --- what leaves the phone (PRD_FOOD 9.2, 16.3 and 22) -----------------------------------

    @Test
    fun `the request is a GET on the url the builder produced, and nothing else`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val lookup = lookup(recorded) { respondJson(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.FOUND)) }

        lookup.byBarcode(nutella)

        val request = recorded.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals(OpenFoodFactsUrl.productRequest(nutella)?.url, request.url.toString())
    }

    /**
     * The whole of "seul le numéro est transmis", checked against the socket rather than the
     * builder.
     *
     * The assertion is on the **set of header names**, because that is the only form of it that
     * fails when something is added. `Accept` and `Accept-Charset` are Ktor's own content
     * negotiation of the response and carry nothing about the phone or its owner; every other
     * name on this list would.
     */
    @Test
    fun `the outgoing headers are the static User-Agent and Ktor's own content negotiation`() =
        runTest {
            val recorded = mutableListOf<HttpRequestData>()
            val lookup = lookup(recorded) { respondJson("{}") }

            lookup.byBarcode(nutella)

            val names = recorded.single().headers.names().map { it.lowercase() }.toSet()
            assertEquals(
                setOf("user-agent", "accept", "accept-charset"),
                names,
                "a header that is not one of these is a fact about this phone leaving it",
            )
            assertEquals(
                OpenFoodFactsUrl.USER_AGENT,
                recorded.single().headers[HttpHeaders.UserAgent],
            )
        }

    /** The four a shared client would have contributed, named so the test says what it guards. */
    @Test
    fun `no credential, no cookie, no locale and no device identifier leave with the request`() =
        runTest {
            val recorded = mutableListOf<HttpRequestData>()
            val lookup = lookup(recorded) { respondJson("{}") }

            lookup.byBarcode(nutella)

            val headers = recorded.single().headers
            assertEquals(null, headers[HttpHeaders.Authorization])
            assertEquals(null, headers[HttpHeaders.Cookie])
            assertEquals(null, headers[HttpHeaders.AcceptLanguage])
            assertEquals(null, headers["X-Request-Id"])
        }

    @Test
    fun `two barcodes produce two requests differing only by the number`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val lookup = lookup(recorded) { respondJson("{}") }

        lookup.byBarcode(nutella)
        lookup.byBarcode(OpenFoodFactsFixtures.KNOWN_ZEROS_BARCODE)

        val (first, second) = recorded
        assertEquals(first.headers.entries(), second.headers.entries())
        assertEquals(
            first.url.toString().replace(nutella, "{barcode}"),
            second.url.toString()
                .replace(OpenFoodFactsFixtures.KNOWN_ZEROS_BARCODE, "{barcode}"),
        )
    }

    /** Nothing that is not a retail barcode is ever put on a socket. */
    @Test
    fun `a code that is not a barcode never reaches the network`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val lookup = lookup(recorded) { respondJson("{}") }

        val result = lookup.byBarcode("not-a-barcode")

        assertTrue(recorded.isEmpty(), "an unaskable code must not open a connection")
        // Not a failure: nothing broke, and PRD_FOOD 17 routes an unknown code to the creation.
        assertEquals(ProductLookupResult.NotFound, result)
    }

    // --- what comes back (PRD_FOOD 9.2 and 17) -----------------------------------------------

    @Test
    fun `a recorded product card becomes a candidate carrying its provenance`() = runTest {
        val lookup = lookup { respondJson(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.FOUND)) }

        val found = assertIs<ProductLookupResult.Found>(lookup.byBarcode(nutella))

        assertEquals(FoodSource.OPEN_FOOD_FACTS, found.food.source)
        assertEquals(nutella, found.food.barcode)
    }

    /**
     * The rule no transport change may undo (PRD_FOOD 13.1 and 17).
     *
     * The recorded Nutella card states energy, protein, carbohydrate and fat from the
     * manufacturer and an **estimated** fibre. The mapper drops the estimate; this asserts the
     * drop survives the round trip through HTTP, because "unknown is not zero" is the one
     * property of this feature that would be invisible if it broke.
     */
    @Test
    fun `an estimated fibre arrives unknown rather than as a zero`() = runTest {
        val lookup = lookup { respondJson(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.FOUND)) }

        val found = assertIs<ProductLookupResult.Found>(lookup.byBarcode(nutella))

        assertEquals(null, found.food.per100.fibre)
    }

    /** The same card's known zeros stay zeros, which is what makes the test above mean anything. */
    @Test
    fun `a documented zero arrives as a zero rather than as an unknown`() = runTest {
        val lookup = lookup {
            respondJson(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.KNOWN_ZEROS))
        }

        val found = assertIs<ProductLookupResult.Found>(
            lookup.byBarcode(OpenFoodFactsFixtures.KNOWN_ZEROS_BARCODE),
        )

        assertEquals(0, found.food.per100.protein?.milligrams)
        assertEquals(0, found.food.per100.fat?.milligrams)
        assertEquals(null, found.food.per100.fibre)
    }

    /**
     * Open Food Facts answers a missing product with **404 plus a body that says so**, and the
     * body is what decides. A transport that mapped the status alone would be right here by
     * accident and wrong on the next test.
     */
    @Test
    fun `the recorded not-found body is read through its 404`() = runTest {
        val lookup = lookup {
            respondJson(
                OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.NOT_FOUND),
                HttpStatusCode.NotFound,
            )
        }

        assertEquals(
            ProductLookupResult.NotFound,
            lookup.byBarcode(OpenFoodFactsFixtures.NOT_FOUND_BARCODE),
        )
    }

    /**
     * A 404 that is *not* Open Food Facts' own answer is a route that has moved or a proxy in the
     * way. Nothing was learned about the barcode, so it must not send anybody off to type a
     * label — and it must not be blamed on an unreadable card either.
     */
    @Test
    fun `a 404 from something that is not Open Food Facts is a service error`() = runTest {
        val lookup = lookup { respondHtml(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.NOT_JSON)) }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.SERVICE_ERROR, unavailable.reason)
    }

    @Test
    fun `a rate limit is a service error and not a missing product`() = runTest {
        val lookup = lookup { respondError(HttpStatusCode.TooManyRequests) }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.SERVICE_ERROR, unavailable.reason)
    }

    @Test
    fun `a bad gateway is a service error`() = runTest {
        val lookup = lookup { respondError(HttpStatusCode.BadGateway) }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.SERVICE_ERROR, unavailable.reason)
    }

    /** The outage page Open Food Facts really serves under load: 200, and not JSON at all. */
    @Test
    fun `an html outage page answered with 200 is a malformed response`() = runTest {
        val lookup = lookup { respondHtml(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.NOT_JSON), HttpStatusCode.OK) }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.MALFORMED_RESPONSE, unavailable.reason)
    }

    @Test
    fun `a body cut off mid-json is a malformed response`() = runTest {
        val lookup = lookup {
            respondJson(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.TRUNCATED))
        }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.MALFORMED_RESPONSE, unavailable.reason)
    }

    @Test
    fun `a stated failure that is not a missing product is a service error`() = runTest {
        val lookup = lookup {
            respondJson(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.SERVICE_FAILURE))
        }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.SERVICE_ERROR, unavailable.reason)
    }

    // --- the four transport failures, told apart (PRD_FOOD 17) -------------------------------

    @Test
    fun `an unresolvable host is offline`() = runTest {
        val lookup = lookup { throw UnknownHostException(OpenFoodFactsUrl.HOST) }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.OFFLINE, unavailable.reason)
    }

    @Test
    fun `a refused connection is offline`() = runTest {
        val lookup = lookup { throw ConnectException("ECONNREFUSED") }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.OFFLINE, unavailable.reason)
    }

    @Test
    fun `a socket timeout is a timeout and never offline`() = runTest {
        val lookup = lookup { throw SocketTimeoutException("timed out") }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.TIMEOUT, unavailable.reason)
    }

    /**
     * A TLS failure against a public host is a network this phone cannot use — a captive portal,
     * a middlebox, a wrong clock. Saying "the service is broken" would send somebody to try
     * another product; `OFFLINE` is what is true. The judgement is written out in
     * `KtorProductLookup`, and it is asserted here so it cannot drift silently.
     */
    @Test
    fun `an untrusted certificate is offline rather than a service error`() = runTest {
        val lookup = lookup { throw SSLHandshakeException("no trust anchor") }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.OFFLINE, unavailable.reason)
    }

    @Test
    fun `a connection reset mid-body is offline rather than an unreadable card`() = runTest {
        val lookup = lookup { throw IOException("connection reset by peer") }

        val unavailable = assertIs<ProductLookupResult.Unavailable>(lookup.byBarcode(nutella))

        assertEquals(LookupFailure.OFFLINE, unavailable.reason)
    }

    /** Four causes, four values, and not one of them collapses into another. */
    @Test
    fun `the four failures stay four`() = runTest {
        val reasons = listOf(
            reason { throw UnknownHostException("x") },
            reason { throw SocketTimeoutException("x") },
            reason { respondError(HttpStatusCode.ServiceUnavailable) },
            reason { respondJson("{ this is not json") },
        )

        assertEquals(
            listOf(
                LookupFailure.OFFLINE,
                LookupFailure.TIMEOUT,
                LookupFailure.SERVICE_ERROR,
                LookupFailure.MALFORMED_RESPONSE,
            ),
            reasons,
        )
    }

    // --- helpers -------------------------------------------------------------------------------

    private suspend fun reason(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): LookupFailure {
        val result = lookup(handler = handler).byBarcode(nutella)
        return assertIs<ProductLookupResult.Unavailable>(result).reason
    }

    private fun lookup(
        recorded: MutableList<HttpRequestData> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorProductLookup {
        val engine = MockEngine { request ->
            recorded += request
            handler(request)
        }
        return KtorProductLookup(
            client = HttpClient(engine) { expectSuccess = false },
            timeoutMillis = 1_000,
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.respondHtml(
        body: String,
        status: HttpStatusCode = HttpStatusCode.NotFound,
    ): HttpResponseData = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "text/html"),
    )
}
