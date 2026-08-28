package fr.kristenjestin.mue.data.pairing

import java.net.URI
import java.net.URISyntaxException

/**
 * A private HTTPS address, as typed, turned into the origin the rest of the app stores and calls.
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
 * kept when it is not 443, because a phone that reached the wrong one has to be able to see that.
 */
data class ServerAddress(val origin: String, val name: String)

/** [ServerAddress.parse]'s two outcomes; a failure is a value, as everywhere in this package. */
sealed interface ServerAddressResult {
    data class Valid(val address: ServerAddress) : ServerAddressResult
    data class Invalid(val failure: PairingFailure) : ServerAddressResult
}

object ServerAddresses {

    private const val HTTPS = "https"
    private const val HTTP = "http"
    private const val DEFAULT_HTTPS_PORT = 443

    /**
     * Parses what was typed.
     *
     * The order of the checks is the order of the excuses a user is owed: nothing typed, then a
     * scheme that is not encrypted, then something that is not an address at all. `http://` is
     * checked *before* the general syntax so that typing it produces the message about HTTPS and
     * not a generic one about web addresses.
     *
     * A bare `mue.home.arpa` is read as `https://mue.home.arpa`. That is not a silent upgrade of
     * an insecure address — nothing insecure was written — it is the omission every person makes,
     * and the only scheme this app will ever use is the one being filled in.
     */
    fun parse(input: String): ServerAddressResult {
        val typed = input.trim()
        if (typed.isEmpty()) return ServerAddressResult.Invalid(PairingFailure.AddressMissing)

        val declaredScheme = typed.substringBefore("://", missingDelimiterValue = "")
        if (declaredScheme.equals(HTTP, ignoreCase = true)) {
            return ServerAddressResult.Invalid(PairingFailure.InsecureScheme(typed))
        }
        if (declaredScheme.isNotEmpty() && !declaredScheme.equals(HTTPS, ignoreCase = true)) {
            return ServerAddressResult.Invalid(PairingFailure.MalformedAddress(typed))
        }

        val withScheme = if (declaredScheme.isEmpty()) "$HTTPS://$typed" else typed

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
        val authority = if (port == -1 || port == DEFAULT_HTTPS_PORT) host else "$host:$port"

        return ServerAddressResult.Valid(
            ServerAddress(origin = "$HTTPS://$authority$path", name = authority),
        )
    }

    /**
     * The host of an already-stored origin, for a message about a server this phone is paired
     * with rather than one it is being pointed at.
     *
     * Falls back to the whole string: a stored value that cannot be parsed is still the best name
     * available, and an empty server name in `Data & sync` would be worse than an ugly one.
     */
    fun displayName(origin: String): String =
        when (val parsed = parse(origin)) {
            is ServerAddressResult.Valid -> parsed.address.name
            is ServerAddressResult.Invalid -> origin
        }
}
