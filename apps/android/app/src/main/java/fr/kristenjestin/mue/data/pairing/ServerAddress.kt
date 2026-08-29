package fr.kristenjestin.mue.data.pairing

import java.net.URI
import java.net.URISyntaxException

/**
 * A private server address, as typed, turned into the origin the rest of the app stores and calls.
 *
 * Sync PRD 9.2 makes manual entry the fallback for the QR code, which means this is what a person
 * types on a phone keyboard, at night, from memory. So the parsing is generous about the two
 * things people leave out — the scheme and a trailing slash — and refuses everything else by
 * name rather than by silently repairing it.
 *
 * [origin] is what `sync_state.server_url` holds and what [KtorPairingApi] and `KtorSyncApi`
 * concatenate their paths onto. It is normalised once, here, so no later caller has to wonder
 * whether the stored value ends in a slash.
 *
 * [name] is what `Data & sync` shows as "the paired server's name" (PRD 9.1). There is no
 * endpoint that reports a display name — the platform exposes `/health/live`, `/health/ready`,
 * the Better Auth routes and the guarded v1 routes, and none of them says what it is called — so
 * the host is the name, which is also the string the owner typed and will recognise. The port is
 * kept when it is not the **scheme's own** default, because a phone that reached the wrong one has
 * to be able to see that.
 */
data class ServerAddress(val origin: String, val name: String)

/**
 * Whether an explicitly typed `http://` is an address this build is allowed to keep.
 *
 * PRD 16 admits nothing but HTTPS, and until this existed [ServerAddresses.parse] enforced that
 * for every build there is. It still does for [Refused], which is what `release` gets, and that is
 * the load-bearing half: **a build that can be published must not be able to leave in clear**, by
 * accident or otherwise. Nothing in this file may make that conditional on anything else.
 *
 * [Permitted] is `local`, `beta` and `debug`. The owner's Mue server runs on his own machine, on
 * his own network, with no hosting and no public name — so no authority will ever issue a
 * certificate for it, and he has decided not to run one of his own. He was told what cleartext on
 * a home WiFi costs: the bearer in `sync_state` is readable by every device on that network, which
 * is write access to his health data and to his MCP tools. It is his server and his decision.
 *
 * The permission is a property of the *build* and not a setting: there is no switch on any screen,
 * so `release`'s answer is not reachable from inside the running application, and the containment
 * is a property of the binaries rather than of a preference somebody could flip.
 *
 * It arrives as a parameter rather than being read here because [ServerAddresses.parse] is a pure
 * function — no `Context`, no resource lookup — which is what lets all of it be proved on the JVM.
 * `SyncContainer`, which does hold a `Context`, reads the per-build-type `bool` resource and hands
 * the answer down. `buildConfig` is off in this module, so a generated resource is how a build type
 * answers a question in this codebase; `app_name` and `launcher_background` take the same road.
 */
enum class CleartextPolicy {

    /** `http://` is refused by name, as [PairingFailure.InsecureScheme]. This is `release`. */
    Refused,

    /** `http://` is kept as typed — and only ever when it *was* typed. `local`, `beta`, `debug`. */
    Permitted,
}

/** [ServerAddress.parse]'s two outcomes; a failure is a value, as everywhere in this package. */
sealed interface ServerAddressResult {
    data class Valid(val address: ServerAddress) : ServerAddressResult
    data class Invalid(val failure: PairingFailure) : ServerAddressResult
}

object ServerAddresses {

    private const val HTTPS = "https"
    private const val HTTP = "http"
    private const val DEFAULT_HTTPS_PORT = 443
    private const val DEFAULT_HTTP_PORT = 80

    /**
     * Parses what was typed.
     *
     * The order of the checks is the order of the excuses a user is owed: nothing typed, then a
     * scheme this build will not keep, then something that is not an address at all. `http://` is
     * checked *before* the general syntax so that typing it under [CleartextPolicy.Refused]
     * produces the message about HTTPS rather than a generic one about web addresses.
     *
     * A bare `mue.home.arpa` is read as `https://mue.home.arpa` under **both** policies. That is
     * not a silent upgrade of an insecure address — nothing insecure was written — it is the
     * omission every person makes, and the scheme filled in is the one with something to defend.
     * The mirror image is the fault worth naming: completing a bare host in clear *because the
     * build happens to allow it* would be a silent downgrade of an address on which nobody wrote a
     * scheme at all. [CleartextPolicy.Permitted] therefore widens what may be **typed**, and never
     * what is **assumed**.
     */
    fun parse(input: String, cleartext: CleartextPolicy): ServerAddressResult {
        val typed = input.trim()
        if (typed.isEmpty()) return ServerAddressResult.Invalid(PairingFailure.AddressMissing)

        val declaredScheme = typed.substringBefore("://", missingDelimiterValue = "")
        val typedInClear = declaredScheme.equals(HTTP, ignoreCase = true)
        if (typedInClear && cleartext == CleartextPolicy.Refused) {
            return ServerAddressResult.Invalid(PairingFailure.InsecureScheme(typed))
        }
        if (declaredScheme.isNotEmpty() &&
            !typedInClear &&
            !declaredScheme.equals(HTTPS, ignoreCase = true)
        ) {
            return ServerAddressResult.Invalid(PairingFailure.MalformedAddress(typed))
        }

        // Only an `http://` that was actually written survives as one; an omitted scheme is https
        // whatever this build allows. Lower-cased on the way out too, so `HTTPS://` and `https://`
        // cannot become two different rows in `sync_state` for one server.
        val scheme = if (typedInClear) HTTP else HTTPS
        val withScheme = if (declaredScheme.isEmpty()) "$scheme://$typed" else typed

        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return ServerAddressResult.Invalid(PairingFailure.MalformedAddress(typed))
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return ServerAddressResult.Invalid(PairingFailure.MalformedAddress(typed))
        }
        // Credentials in the address are refused rather than dropped: `https://kris:hunter2@…`
        // would otherwise put a password in `sync_state.server_url`, in clear, for ever — which
        // is exactly what PRD 9.2's "le mot de passe n'est jamais conservé" forbids.
        if (uri.userInfo != null) {
            return ServerAddressResult.Invalid(PairingFailure.MalformedAddress(typed))
        }
        // A query or a fragment cannot be part of an origin, and appending `/api/v1/sync/push`
        // after one would produce a path nobody meant.
        if (uri.query != null || uri.fragment != null) {
            return ServerAddressResult.Invalid(PairingFailure.MalformedAddress(typed))
        }

        val port = uri.port
        val path = uri.path.orEmpty().trimEnd('/')
        // The default port belongs to the scheme and is not always 443. Dropping 80 is what makes
        // `http://mue.home.arpa:80` and `http://mue.home.arpa` one origin and one stored row; the
        // same rule seen from the other side is why 443 is *kept* on a cleartext address, where it
        // is a port somebody chose rather than the one `https` already means.
        val defaultPort = if (scheme == HTTP) DEFAULT_HTTP_PORT else DEFAULT_HTTPS_PORT
        val authority = if (port == -1 || port == defaultPort) host else "$host:$port"

        return ServerAddressResult.Valid(
            ServerAddress(origin = "$scheme://$authority$path", name = authority),
        )
    }

    /**
     * The host of an already-stored origin, for a message about a server this phone is paired
     * with rather than one it is being pointed at.
     *
     * Parsed as [CleartextPolicy.Permitted] whatever this build is, and that is not a way round
     * the rule: the string comes out of `sync_state.server_url`, so the policy question was
     * settled when the row was written, and nothing reachable from here can create one. Refusing
     * to *read* a stored `http://` origin would not make it any less stored — it would only put a
     * whole URL where the host belongs, in `Data & sync` and in every failure message
     * [KtorPairingApi] builds out of this call.
     *
     * Falls back to the whole string: a stored value that cannot be parsed is still the best name
     * available, and an empty server name in `Data & sync` would be worse than an ugly one.
     */
    fun displayName(origin: String): String =
        when (val parsed = parse(origin, CleartextPolicy.Permitted)) {
            is ServerAddressResult.Valid -> parsed.address.name
            is ServerAddressResult.Invalid -> origin
        }
}
