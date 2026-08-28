package fr.kristenjestin.mue.data.remote.openfoodfacts

import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.domain.repository.ProductLookup
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException as JavaSocketTimeoutException
import java.net.UnknownHostException

/**
 * [ProductLookup] over HTTP: the one network call the Food module makes (PRD_FOOD 9.2).
 *
 * ## What is on the wire, and why this class adds nothing to it
 *
 * PRD_FOOD 9.2 and 22 promise that a scan sends the number and nothing else — "seul le numéro
 * est transmis" — and that is a claim about the **whole request**, headers included.
 * [OpenFoodFactsUrl] already answers it as a value: a URL and a one-entry header map, asserted by
 * equality in `OpenFoodFactsUrlTest` with no socket in sight. The only way for a transport to
 * break a promise proved that way is to *add* something, so this class is written to have nothing
 * to add: it copies [OpenFoodFactsRequest.headers] verbatim, sets no default request, installs no
 * plugin that contributes a header, and never touches the locale, the account, the clock or the
 * device.
 *
 * ## Its own client, which is the point rather than an oversight
 *
 * `SyncContainer` keeps one [HttpClient] for the whole process and argues, correctly, that a
 * second means a second connection pool and a second trust configuration. That argument is about
 * two calls to **the same server**. This one goes to a third party, and the reasoning inverts:
 *
 * - the sync client exists to carry a bearer to the paired server. It reads that bearer per call
 *   today, but a plugin installed on it tomorrow — `Auth`, `HttpCookies`, `UserAgent`,
 *   `DefaultRequest` — would attach something to *every* request made through it, and one of
 *   those requests would be this one. A shared client makes "only the number is transmitted" a
 *   property of a file this module does not own;
 * - a scan is a person waiting at a shelf, and a synchronisation is deferrable work nobody is
 *   watching. `KtorSyncApi.defaultClient` allows sixty seconds for a request because nothing is
 *   on screen; a scanner that waits sixty seconds is a scanner that looks broken.
 *
 * So [defaultClient] builds the smallest client that can make this call, and the FoodContainer
 * holds it lazily: a phone that never scans never opens a socket, never creates a pool and never
 * loads OkHttp.
 *
 * ## Every refusal is named
 *
 * PRD_FOOD 17 gives three of these outcomes three different screens, and [LookupFailure]'s four
 * values three different sentences. Nothing below collapses two of them: "you are in a tunnel",
 * "Open Food Facts is slow today", "Open Food Facts is broken today" and "Open Food Facts said
 * something Mue cannot read" lead to four different things a person would do next, and a single
 * "something went wrong" would leave them re-scanning a product that will never work or typing
 * out a label that was two seconds from arriving.
 */
class KtorProductLookup(
    private val client: HttpClient,
    /**
     * How long a person at a shelf is asked to wait, in total.
     *
     * A parameter so a test can make it small, never so a caller can make it generous: the number
     * that matters is the one below, and it is short on purpose.
     */
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : ProductLookup {

    /**
     * One barcode, looked up. Never throws — every outcome is a value, as [ProductLookup] says.
     *
     * [CancellationException] is the single exception that still leaves: a sheet closed mid-scan
     * has to cancel the coroutine rather than resolve it into a failure the closed screen would
     * then try to draw.
     */
    override suspend fun byBarcode(barcode: String): ProductLookupResult {
        // The guard that lets the URL be built by concatenation, and it lives in the builder.
        // Reaching this branch means a caller skipped the field validation every screen applies,
        // and the honest answer is the one PRD_FOOD 17 already has a screen for: a number Open
        // Food Facts could not possibly have a card for is a number that leads to the manual
        // creation. It is deliberately **not** a `LookupFailure` — nothing failed, and no message
        // about the network would be true.
        val request = OpenFoodFactsUrl.productRequest(barcode) ?: return ProductLookupResult.NotFound

        val response = try {
            client.get(request.url) {
                // Verbatim, and by iteration rather than by naming the one header there is: the
                // day `OpenFoodFactsUrl` adds a second, this forwards it without being edited,
                // and the test that counts what leaves still counts the truth.
                request.headers.forEach { (name, value) -> header(name, value) }
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return ProductLookupResult.Unavailable(classify(failure))
        }

        val body = try {
            response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // The status arrived and the body did not: a connection cut mid-stream. That is a
            // transport failure and not a malformed card, so it is classified like one.
            return ProductLookupResult.Unavailable(classify(failure))
        }

        val status = response.status

        // Open Food Facts answers a missing product with **404 and a body that says so** —
        // `{"status":"failure","result":{"id":"product_not_found"}}`, recorded verbatim in
        // `product-not-found.json`. So a 404 is read rather than assumed, and the mapper decides.
        if (status == HttpStatusCode.NotFound) {
            val read = OpenFoodFactsMapper.read(barcode, body)
            // A 404 whose body is *not* Open Food Facts' own answer is an endpoint that has moved
            // or a proxy answering in its place — nothing was learned about the barcode, and
            // calling that "unreadable card" would point the blame at a card that was never sent.
            return if (read == ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE)) {
                ProductLookupResult.Unavailable(LookupFailure.SERVICE_ERROR)
            } else {
                read
            }
        }

        // Everything else that is not a success is the service saying it cannot answer: a 429
        // rate limit, a 502 from the load balancer, a 503 during a deploy. None of them says
        // anything about the barcode, which is what keeps them out of `NotFound`.
        if (!status.isSuccess()) return ProductLookupResult.Unavailable(LookupFailure.SERVICE_ERROR)

        // A success is the mapper's business alone, and it is a pure function of the text — which
        // is why the whole of PRD_FOOD 13.1's "unknown is never zero" is proved on the JVM from
        // recorded fixtures, and why no decision this class makes can undo it.
        return OpenFoodFactsMapper.read(barcode, body)
    }

    companion object {

        /**
         * Twelve seconds, and the number is a product decision rather than a default.
         *
         * PRD_FOOD 17 puts a scan on the critical path of adding a line and keeps "les trois
         * autres chemins" available while it runs. Somebody standing in a kitchen with a jar in
         * one hand will decide the app is broken long before a network stack gives up, and the
         * cost of being wrong is one tap on `Try again` — against a minute of a spinner.
         */
        const val REQUEST_TIMEOUT_MILLIS: Long = 12_000

        const val CONNECT_TIMEOUT_MILLIS: Long = 8_000

        const val SOCKET_TIMEOUT_MILLIS: Long = 10_000

        /**
         * The failure, from the bottom of the cause chain up.
         *
         * Ktor wraps OkHttp and OkHttp wraps the JDK, so the difference between a timeout and an
         * unresolvable host is two or three levels down; matching on the top exception is how the
         * four values of [LookupFailure] would quietly become one. `KtorPairingApi` walks the same
         * chain for the same reason.
         *
         * A **TLS failure counts as offline**, and that is a judgement rather than an oversight.
         * Open Food Facts is a public host with a public certificate: a handshake that fails
         * against it is a captive portal, a corporate middlebox or a phone whose clock is wrong —
         * a network this app cannot use. Telling somebody the service is broken would send them
         * to try another product; telling them there is no usable connection is what is true, and
         * PRD_FOOD 17 leaves the other three paths open either way.
         */
        @JvmStatic
        internal fun classify(failure: Throwable): LookupFailure {
            var cause: Throwable? = failure
            val seen = mutableSetOf<Throwable>()
            while (cause != null && seen.add(cause)) {
                when (cause) {
                    is HttpRequestTimeoutException,
                    is ConnectTimeoutException,
                    is KtorSocketTimeoutException,
                    is JavaSocketTimeoutException,
                    -> return LookupFailure.TIMEOUT

                    is UnknownHostException -> return LookupFailure.OFFLINE

                    else -> Unit
                }
                cause = cause.cause
            }
            // Everything left that is an IO failure is a socket that did not work out — refused,
            // reset, no route, no trusted certificate. All of them are "there is no usable
            // connection" to the person waiting.
            return if (failure is IOException || failure.cause is IOException) {
                LookupFailure.OFFLINE
            } else {
                // Not a network failure at all: the service answered something the client could
                // not process. `MALFORMED_RESPONSE` is the one of the four that says exactly that.
                LookupFailure.MALFORMED_RESPONSE
            }
        }

        /**
         * The smallest client that can make this one call.
         *
         * Read it as the list of what is **not** here. No `ContentNegotiation`: the body is taken
         * as text and handed to [OpenFoodFactsMapper], which is a pure function of that text and
         * is tested from recorded bytes. No `DefaultRequest`, no `UserAgent` plugin, no
         * `HttpCookies`, no `Auth` — those are the four ways a client adds something to a request
         * without the call site saying so, and this call site is the whole of PRD_FOOD 22's
         * guarantee. No logging: a log line carrying a URL carries a barcode.
         *
         * `expectSuccess = false` because the 404 that means "no such product" is an answer this
         * module reads, not an exception it catches.
         */
        fun defaultClient(engine: HttpClientEngine = OkHttp.create()): HttpClient =
            HttpClient(engine) {
                expectSuccess = false
                // Installed with no defaults of its own: every bound is set per request, so the
                // numbers a reader has to check are all in one place above.
                install(HttpTimeout)
            }
    }
}
