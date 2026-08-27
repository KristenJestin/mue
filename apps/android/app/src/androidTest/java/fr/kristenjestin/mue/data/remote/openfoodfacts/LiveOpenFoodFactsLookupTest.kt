package fr.kristenjestin.mue.data.remote.openfoodfacts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scan's one network call, made against the **real** Open Food Facts, with the request that
 * left the phone captured byte by byte.
 *
 * Every other test of this package answers from a recorded fixture or a `MockEngine`, which is
 * the right shape for a rule and proves nothing about a socket. This one is the opposite, and it
 * exists for one sentence: PRD_FOOD 9.2 and 22 promise that a scan sends the barcode and nothing
 * else, and that promise is about what a real OkHttp puts on a real TLS connection — not about
 * what a builder returns.
 *
 * The capture is a **network** interceptor rather than an application one, deliberately. An
 * application interceptor sees the request before OkHttp's own `BridgeInterceptor` adds `Host`,
 * `Accept-Encoding` and `Connection`, so it would show a cleaner request than the one that
 * actually leaves — which is precisely the reassurance nobody should accept. [ALLOWED_HEADERS]
 * is therefore the complete list including those three, and the test fails on anything else.
 *
 * ## It skips unless it is told to run
 *
 * `LiveServerPairingTest`'s arrangement, for its reason: a suite must not go red because a train
 * went into a tunnel, and a third party's availability is not this app's correctness. Run it
 * deliberately:
 *
 * ```
 * adb -s <emulator> shell am instrument -w \
 *   -e class fr.kristenjestin.mue.data.remote.openfoodfacts.LiveOpenFoodFactsLookupTest \
 *   -e mueLiveOpenFoodFacts true \
 *   fr.kristenjestin.mue.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveOpenFoodFactsLookupTest {

    /** Records every request as it goes onto the connection, headers included. */
    private class WireLog : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            Log.i(TAG, "> ${request.method} ${request.url.encodedPath}?${request.url.encodedQuery}")
            request.headers.forEach { (name, value) -> Log.i(TAG, "> $name: $value") }
            return chain.proceed(request)
        }
    }

    private fun lookupWithWireLog(): Pair<KtorProductLookup, WireLog> {
        val wire = WireLog()
        val engine = OkHttp.create { addNetworkInterceptor(wire) }
        return KtorProductLookup(KtorProductLookup.defaultClient(engine)) to wire
    }

    @Test
    fun aRealBarcodeIsLookedUpAndOnlyTheNumberLeavesThePhone() {
        assumeLive()
        val (lookup, wire) = lookupWithWireLog()

        val result = runBlocking { lookup.byBarcode(NUTELLA) }

        // --- what came back ------------------------------------------------------------------
        val found = result as? ProductLookupResult.Found
        assertNotNull("expected a product card, got $result", found)
        val food = found!!.food
        assertTrue("name was '${food.name}'", food.name.contains("Nutella", ignoreCase = true))
        assertEquals(FoodSource.OPEN_FOOD_FACTS, food.source)
        assertEquals(NUTELLA, food.barcode)
        assertNotNull("the live card states an energy", food.per100.energy)

        /*
         * PRD_FOOD 17, against the live service rather than a fixture.
         *
         * Open Food Facts *has* a fibre figure for this card and marks it `estimate` — computed
         * from the ingredient list, not read off a label. It has to arrive unknown, because the
         * whole of this module's discipline is that a `—` is never quietly a `0`. If Open Food
         * Facts ever starts reporting a manufacturer's fibre for Nutella this assertion will
         * fail, and it should: that would be a change worth noticing rather than absorbing.
         */
        assertNull("an estimated value must never arrive as a number", food.per100.fibre)

        // --- what went out --------------------------------------------------------------------
        val request = wire.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://world.openfoodfacts.org/api/v3.6/product/$NUTELLA" +
                "?fields=code,product_name,brands,nutrition,serving_size,serving_quantity," +
                "image_front_url,rev",
            request.url.toString(),
        )
        assertEquals(OpenFoodFactsUrl.USER_AGENT, request.header("User-Agent"))

        val names = request.headers.names().map { it.lowercase() }.toSet()
        assertTrue(
            "a header outside the allowed set left this phone: ${names - ALLOWED_HEADERS}",
            names.all { it in ALLOWED_HEADERS },
        )
        // Named individually as well, because these are the four a shared client would add.
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Accept-Language"))
        assertTrue("no body is sent at all", request.body == null)
    }

    /**
     * PRD_FOOD 17's "produit absent d'Open Food Facts", live.
     *
     * The service answers `404` with `{"status":"failure","result":{"id":"product_not_found"}}`,
     * and reading the *body* rather than the status is what keeps this apart from a service
     * failure. The code is a well-formed EAN-13 that no product carries.
     */
    @Test
    fun aBarcodeTheServiceDoesNotKnowComesBackAsNotFoundRatherThanAsAFailure() {
        assumeLive()
        val (lookup, _) = lookupWithWireLog()

        val result = runBlocking { lookup.byBarcode(UNKNOWN_BUT_VALID) }

        assertEquals(ProductLookupResult.NotFound, result)
    }

    private fun assumeLive() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "set -e mueLiveOpenFoodFacts true to run the live lookup",
            arguments.getString(ARGUMENT).toBoolean(),
        )
    }

    private companion object {
        const val TAG = "MueOffWire"
        const val ARGUMENT = "mueLiveOpenFoodFacts"

        /** The recorded fixture's own barcode, so the live answer and the offline one line up. */
        const val NUTELLA = "3017620422003"

        /**
         * A real, well-formed EAN-13 that Open Food Facts has no card for.
         *
         * It is `product-not-found.json`'s own barcode, recorded from this service on 26 August
         * 2026, so the live answer and the offline fixture describe the same product — which is
         * what makes the two halves of this package check each other.
         *
         * Picking one is harder than it looks, and the first attempt got it wrong: `9999999999994`
         * seemed obviously unallocated and is in fact a real card somebody uploaded, and
         * `1234567890128` is another. Open Food Facts is collaborative and its junk entries are
         * genuine data; a barcode is "unknown" only because it was checked, never because it
         * looks made up.
         */
        const val UNKNOWN_BUT_VALID = "3596710352975"

        /**
         * Every header this request is allowed to carry, and why each one is harmless.
         *
         * - `user-agent` — the static string PRD_FOOD 9.2 asks for, identical on every phone;
         * - `accept`, `accept-charset` — Ktor negotiating the *response*, not describing the
         *   device;
         * - `host`, `accept-encoding`, `connection` — HTTP/1.1 framing that OkHttp adds to every
         *   request ever made and that carries nothing about anybody.
         *
         * Anything else — a locale, a cookie, a bearer, a request id, a device model — would be
         * a fact about this phone leaving it, and is what this set exists to fail on.
         */
        val ALLOWED_HEADERS = setOf(
            "user-agent",
            "accept",
            "accept-charset",
            "host",
            "accept-encoding",
            "connection",
        )
    }
}
