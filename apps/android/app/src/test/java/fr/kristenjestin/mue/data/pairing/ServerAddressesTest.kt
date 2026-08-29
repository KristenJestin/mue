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
 *
 * Cleartext is now a question the build type answers ([CleartextPolicy]), so the cases split in
 * two. Everything written before that is unchanged and reads through a helper that defaults to
 * [CleartextPolicy.Refused] — the `release` configuration — so those assertions still mean exactly
 * what they meant: this is what a build that can be published does. The permissive cases name
 * their policy in full at the call site, so no test can be about a configuration by accident.
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
     *
     * Still true of the configuration this reads through, which is `release`'s.
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

    // --- what a build that ships refuses -------------------------------------------------------

    /**
     * **The test that matters.** `release` is the only build a person other than the owner could
     * ever install, and it must not be able to talk in clear — not by a typo, not by a paste, not
     * by an omission in a build file somebody edits next year.
     *
     * Every form of the scheme is here on purpose. A parser that special-cased lower case, or that
     * only looked at the first four characters, or that made the refusal conditional on the port,
     * would pass one of these and fail another. Making cleartext unconditional — deleting the
     * parameter, defaulting it to [CleartextPolicy.Permitted], or answering `Permitted` before
     * reading it — turns every line below red, which is the only reason they are written out
     * rather than looped.
     */
    @Test
    fun aBuildThatRefusesCleartextRefusesEveryFormOfIt() {
        val refused = listOf(
            "http://mue.home.arpa",
            "http://mue.home.arpa:80",
            "http://mue.home.arpa:3000",
            "http://192.168.1.100:3000",
            "HTTP://mue.home.arpa",
            "  http://mue.home.arpa/mue/  ",
        )

        for (input in refused) {
            assertIs<PairingFailure.InsecureScheme>(
                invalid(input, CleartextPolicy.Refused),
                "$input was not refused by a build that forbids cleartext",
            )
        }
    }

    /** The refusal is the *only* thing the policy decides; a nonsense scheme stays nonsense. */
    @Test
    fun aPermissiveBuildStillRefusesASchemeThatIsNeitherOfTheTwo() {
        assertIs<PairingFailure.MalformedAddress>(
            invalid("ftp://mue.home.arpa", CleartextPolicy.Permitted),
        )
    }

    // --- what the owner's own builds accept ----------------------------------------------------

    @Test
    fun aPermissiveBuildKeepsATypedHttpAddressAsItWasTyped() {
        val address = valid("http://192.168.1.100:3000", CleartextPolicy.Permitted)

        assertEquals("http://192.168.1.100:3000", address.origin)
        assertEquals("192.168.1.100:3000", address.name)
    }

    /** Lower-cased like `https` already was, so one server cannot become two `sync_state` rows. */
    @Test
    fun anUppercaseHttpSchemeIsNormalisedRatherThanCarried() {
        assertEquals(
            "http://mue.home.arpa:3000",
            valid("HTTP://mue.home.arpa:3000", CleartextPolicy.Permitted).origin,
        )
    }

    /**
     * 80 is what `http` already means, exactly as 443 is what `https` means. The old parser knew
     * only the second, so a phone typing `http://mue.home.arpa:80` would have paired to a
     * different origin than the same phone typing `http://mue.home.arpa` — two rows, one server,
     * and a `Data & sync` name the owner never wrote.
     */
    @Test
    fun theDefaultPortOfHttpIsEightyAndIsNotRepeated() {
        val explicit = valid("http://mue.home.arpa:80", CleartextPolicy.Permitted)
        val implicit = valid("http://mue.home.arpa", CleartextPolicy.Permitted)

        assertEquals("http://mue.home.arpa", explicit.origin)
        assertEquals("mue.home.arpa", explicit.name)
        assertEquals(implicit, explicit)
    }

    /** The other half of the same rule: 443 is a port somebody chose when the scheme is `http`. */
    @Test
    fun fourFourThreeIsKeptOnACleartextAddressBecauseItIsNotItsDefault() {
        val address = valid("http://mue.home.arpa:443", CleartextPolicy.Permitted)

        assertEquals("http://mue.home.arpa:443", address.origin)
        assertEquals("mue.home.arpa:443", address.name)
    }

    /** And symmetrically, 80 is not `https`'s default and so survives on an encrypted address. */
    @Test
    fun eightyIsKeptOnAnHttpsAddressBecauseItIsNotItsDefault() {
        assertEquals(
            "https://mue.home.arpa:80",
            valid("https://mue.home.arpa:80", CleartextPolicy.Permitted).origin,
        )
    }

    /**
     * The rule that must not tip over. Permitting `http://` widens what may be **typed**; it does
     * not make cleartext the default for what was **omitted**. Somebody who writes `mue.home.arpa`
     * has asked for nothing in particular and gets the scheme with something to defend, on every
     * build there is.
     */
    @Test
    fun aBareHostIsHttpsUnderEitherPolicy() {
        for (policy in CleartextPolicy.entries) {
            assertEquals("https://mue.home.arpa", valid("mue.home.arpa", policy).origin, "$policy")
            assertEquals("https://192.168.1.100:3000", valid("192.168.1.100:3000", policy).origin)
        }
    }

    /**
     * A stored `http://` origin is named by its authority whatever this build allows: the row
     * exists, the policy question was answered when it was written, and showing a whole URL where
     * `Data & sync` promises a server name helps nobody.
     */
    @Test
    fun theDisplayNameOfAStoredCleartextOriginIsStillItsAuthority() {
        assertEquals("192.168.1.100:3000", ServerAddresses.displayName("http://192.168.1.100:3000"))
        assertEquals("mue.home.arpa", ServerAddresses.displayName("http://mue.home.arpa/mue"))
    }

    /**
     * `Refused` is the release configuration, so it is what the cases above default to: every test
     * written before cleartext was permitted anywhere keeps asserting exactly what it always did.
     */
    private fun valid(
        input: String,
        cleartext: CleartextPolicy = CleartextPolicy.Refused,
    ): ServerAddress =
        assertIs<ServerAddressResult.Valid>(ServerAddresses.parse(input, cleartext)).address

    private fun invalid(
        input: String,
        cleartext: CleartextPolicy = CleartextPolicy.Refused,
    ): PairingFailure =
        assertIs<ServerAddressResult.Invalid>(ServerAddresses.parse(input, cleartext)).failure
}
