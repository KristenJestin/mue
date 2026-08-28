package fr.kristenjestin.mue.data.pairing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What a person may type into sync PRD 9.2's fallback, and what each of those becomes.
 *
 * The cases that matter are the refusals. A parser that quietly repaired `http://` would claim
 * PRD 16's encryption without providing it, and one that accepted `https://kris:hunter2@host`
 * would write a password into `sync_state.server_url` in clear — the very thing 9.2 says is never
 * kept on the phone.
 */
class ServerAddressesTest {

    @Test
    fun aBareHostBecomesAnHttpsOrigin() {
        val address = valid("mue.home.arpa")

        assertEquals("https://mue.home.arpa", address.origin)
        assertEquals("mue.home.arpa", address.name)
    }

    @Test
    fun aTypedHttpsAddressIsKeptAsItIs() {
        assertEquals("https://mue.home.arpa", valid("https://mue.home.arpa").origin)
    }

    @Test
    fun surroundingSpaceIsIgnored() {
        assertEquals("https://mue.home.arpa", valid("  https://mue.home.arpa  ").origin)
    }

    @Test
    fun aTrailingSlashIsRemovedSoNoPathIsEverDoubled() {
        assertEquals("https://mue.home.arpa", valid("https://mue.home.arpa/").origin)
    }

    @Test
    fun aNonDefaultPortIsKeptInBothTheOriginAndTheName() {
        val address = valid("https://mue.home.arpa:8443")

        assertEquals("https://mue.home.arpa:8443", address.origin)
        assertEquals("mue.home.arpa:8443", address.name)
    }

    /** 443 is what `https` already means; repeating it would show the owner a name he did not type. */
    @Test
    fun theDefaultPortIsNotRepeatedInTheName() {
        val address = valid("https://mue.home.arpa:443")

        assertEquals("https://mue.home.arpa", address.origin)
        assertEquals("mue.home.arpa", address.name)
    }

    @Test
    fun aBasePathIsKeptBecauseAServerMayLiveUnderOne() {
        val address = valid("https://home.arpa/mue/")

        assertEquals("https://home.arpa/mue", address.origin)
        assertEquals("home.arpa", address.name)
    }

    @Test
    fun anEmptyAddressIsNamedAsMissingAndNotAsMalformed() {
        assertEquals(PairingFailure.AddressMissing, invalid("   "))
    }

    /**
     * PRD 16 encrypts the Android-server traffic without exception, and a private network is not
     * a substitute. Upgrading the scheme silently would claim a guarantee nobody asked for.
     */
    @Test
    fun anHttpAddressIsRefusedByNameRatherThanUpgraded() {
        val failure = assertIs<PairingFailure.InsecureScheme>(invalid("http://mue.home.arpa"))

        assertEquals("http://mue.home.arpa", failure.input)
    }

    @Test
    fun anotherSchemeIsMalformedRatherThanInsecure() {
        assertIs<PairingFailure.MalformedAddress>(invalid("ftp://mue.home.arpa"))
    }

    @Test
    fun credentialsInTheAddressAreRefusedRatherThanDropped() {
        assertIs<PairingFailure.MalformedAddress>(invalid("https://kris:hunter2@mue.home.arpa"))
    }

    @Test
    fun aQueryOrAFragmentCannotBePartOfAnOrigin() {
        assertIs<PairingFailure.MalformedAddress>(invalid("https://mue.home.arpa?token=1"))
        assertIs<PairingFailure.MalformedAddress>(invalid("https://mue.home.arpa#here"))
    }

    @Test
    fun somethingWithNoHostAtAllIsMalformed() {
        assertIs<PairingFailure.MalformedAddress>(invalid("https://"))
        assertIs<PairingFailure.MalformedAddress>(invalid("not a host at all"))
    }

    @Test
    fun theDisplayNameOfAStoredOriginIsItsAuthority() {
        assertEquals("mue.home.arpa", ServerAddresses.displayName("https://mue.home.arpa/mue"))
    }

    /** A stored value nothing can parse is still the best name there is; an empty one is not. */
    @Test
    fun anUnparseableStoredOriginFallsBackToItself() {
        assertEquals("¯\\_(ツ)_/¯", ServerAddresses.displayName("¯\\_(ツ)_/¯"))
    }

    private fun valid(input: String): ServerAddress =
        assertIs<ServerAddressResult.Valid>(ServerAddresses.parse(input)).address

    private fun invalid(input: String): PairingFailure =
        assertIs<ServerAddressResult.Invalid>(ServerAddresses.parse(input)).failure
}
