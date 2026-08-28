package fr.kristenjestin.mue.data.pairing

import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import java.security.cert.CertificateException

/**
 * The three requests sync PRD 9.2's manual pairing is made of, and nothing else.
 *
 * 1. **Is there a Mue server there.** `GET /health/live`, which `apps/platform/src/edge.ts`
 *    registers before every business route precisely so that it answers whatever else is broken.
 *    It is unauthenticated by design, so this runs before a password is ever sent — a phone must
 *    not offer credentials to a host it has not identified.
 * 2. **Sign in.** `POST /api/auth/sign-in/email`, the Better Auth endpoint `packages/auth`
 *    enables with `emailAndPassword`. The device bearer comes back in `set-auth-token`, put there
 *    by the `bearer()` plugin, and that token — not the password — is what this phone keeps.
 * 3. **Prove the bearer.** `GET /api/auth/get-session` with `Authorization: Bearer`. PRD 24 lists
 *    "comportement exact du Bearer Better Auth dans le client Ktor" as an open spike, which is
 *    exactly the reason not to assume: a token stored without being tried once would turn into a
 *    paired server that fails at every later sync for a reason the screen could not name.
 *
 * A fourth, [revoke], serves PRD 9.3's `Disconnect server` and the retreat from
 * [PairingFailure.DifferentAccount] — a session this app minted and decided not to keep must not
 * be left alive on the server.
 *
 * Every failure is a [PairingException] carrying a named [PairingFailure]. Nothing here returns
 * a nullable, and nothing collapses two causes into one.
 */
interface PairingApi {

    /** @throws PairingException when nothing there is a Mue server. */
    suspend fun probe(origin: String)

    /** @throws PairingException on a refused or unanswerable sign-in. */
    suspend fun signIn(origin: String, email: String, password: String): PairingSession

    /** @throws PairingException when the bearer just issued is not accepted. */
    suspend fun account(origin: String, token: String): PairingAccount

    /**
     * Ends the session on the server.
     *
     * @return true when the server confirmed. False — never an exception — when it could not be
     * reached, because PRD 9.3 requires the local token to be deleted either way and leaves the
     * remote revocation to the Web interface or another authorised session.
     */
    suspend fun revoke(origin: String, token: String): Boolean
}

/** The bearer the server issued, with the account it belongs to as the server reported it. */
data class PairingSession(val token: String, val account: PairingAccount)

/**
 * Who this session is.
 *
 * [email] is the identity `Data & sync` compares against `sync_state.account_id`, and the reason
 * it is the email rather than [id] is written out in [ServerPairing]. [displayName] is only ever
 * shown; nothing is decided by it.
 */
data class PairingAccount(val id: String, val email: String, val displayName: String?) {

    /** What a message about this account says. The email, unless the server sent none. */
    val label: String get() = email.ifBlank { displayName ?: id }

    /** The comparison key: case-insensitive, because an email address is. */
    val identity: String get() = email.trim().lowercase()
}

/** A named pairing failure, thrown so a four-step flow reads as four steps. */
class PairingException(val failure: PairingFailure) : Exception(failure.message)

/**
 * [PairingApi] over the app's one Ktor client.
 *
 * It shares `SyncContainer.httpClient` rather than building a second: a second client is a second
 * connection pool and a second trust configuration, and the certificate this flow verifies has to
 * be the same one every later synchronisation will be checked against. OkHttp honours the
 * platform trust store, so a private certificate authority is a device-level decision and is
 * never worked around here — which is what makes [PairingFailure.UntrustedCertificate] mean
 * something.
 */
class KtorPairingApi(private val client: HttpClient) : PairingApi {

    override suspend fun probe(origin: String) {
        val host = ServerAddresses.displayName(origin)
        val response = request(host) { client.get("$origin$HEALTH_PATH") }
        val body = response.bodyAsText()

        if (!response.status.isSuccess()) {
            // A Mue server that is up but not ready answers 503 on `/health/ready`; `/health/live`
            // never fails for a business reason, so a 5xx here is a proxy in front of a server
            // that is starting. Kept apart from "not Mue" because waiting fixes one and nothing
            // fixes the other.
            if (response.status.value >= 500) {
                throw PairingException(PairingFailure.ServerUnavailable(host, response.status.value))
            }
            throw PairingException(
                PairingFailure.NotAMueServer(host, "It answered ${response.status.value}."),
            )
        }

        val report = try {
            PairingJson.instance.decodeFromString(LivenessDto.serializer(), body)
        } catch (_: SerializationException) {
            throw PairingException(
                PairingFailure.NotAMueServer(host, "Its reply was not one Mue understands."),
            )
        }
        if (report.status != "ok") {
            throw PairingException(
                PairingFailure.NotAMueServer(host, "It reported \"${report.status}\"."),
            )
        }
    }

    override suspend fun signIn(origin: String, email: String, password: String): PairingSession {
        val host = ServerAddresses.displayName(origin)
        val response = request(host) {
            client.post("$origin$SIGN_IN_PATH") {
                contentType(ContentType.Application.Json)
                setBody(
                    PairingJson.instance.encodeToString(
                        SignInRequestDto.serializer(),
                        SignInRequestDto(email = email, password = password),
                    ),
                )
            }
        }
        val body = response.bodyAsText()

        if (!response.status.isSuccess()) throw PairingException(signInFailure(host, response, body))

        val decoded = try {
            PairingJson.instance.decodeFromString(SignInResponseDto.serializer(), body)
        } catch (_: SerializationException) {
            throw PairingException(
                PairingFailure.NotAMueServer(host, "Its sign-in reply was not one Mue can read."),
            )
        }

        // `set-auth-token` is the bearer plugin's own header and is what the documentation says to
        // present. The body's `token` is read only as a fallback, for a server whose proxy strips
        // unknown response headers — a real failure mode on a home reverse proxy, and one that
        // would otherwise look like "the server issued no session".
        val token = response.headers[SET_AUTH_TOKEN]?.takeUnless(String::isBlank)
            ?: decoded.token?.takeUnless(String::isBlank)
            ?: throw PairingException(PairingFailure.NoSessionToken(host))

        return PairingSession(
            token = token,
            account = PairingAccount(
                id = decoded.user?.id.orEmpty(),
                email = decoded.user?.email.orEmpty(),
                displayName = decoded.user?.name,
            ),
        )
    }

    override suspend fun account(origin: String, token: String): PairingAccount {
        val host = ServerAddresses.displayName(origin)
        val response = request(host) {
            client.get("$origin$SESSION_PATH") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        val body = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw PairingException(PairingFailure.SessionRejected(host))
        }

        val decoded = try {
            PairingJson.instance.decodeFromString(SessionResponseDto.serializer().nullable, body)
        } catch (_: SerializationException) {
            throw PairingException(PairingFailure.SessionRejected(host))
        }
        // Better Auth answers 200 with a JSON `null` for an unknown credential, which is the
        // shape a rejected bearer actually takes. Treating that as success is how a phone ends up
        // storing a token nothing accepts.
        val user = decoded?.user ?: throw PairingException(PairingFailure.SessionRejected(host))

        return PairingAccount(id = user.id, email = user.email.orEmpty(), displayName = user.name)
    }

    override suspend fun revoke(origin: String, token: String): Boolean = try {
        val response = client.post("$origin$SIGN_OUT_PATH") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        // 401 counts as revoked: the session the caller wanted gone is already gone.
        response.status.isSuccess() || response.status == HttpStatusCode.Unauthorized
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // PRD 9.3: an unreachable server never blocks a disconnect. The caller deletes the local
        // token regardless and tells the user where the remote session can still be ended.
        false
    }

    /**
     * Runs one request and turns everything that can stop it into a named failure.
     *
     * The cause chain is walked rather than the top exception matched: Ktor wraps an OkHttp
     * failure, OkHttp wraps a JSSE one, and the difference between "Android will not trust this
     * certificate" and "nothing answered" is three levels down. Collapsing them is exactly the
     * failure this package exists to prevent.
     */
    private suspend fun request(host: String, block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (pairing: PairingException) {
            throw pairing
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw PairingException(classify(host, failure))
        }

    private companion object {
        const val HEALTH_PATH = "/health/live"
        const val SIGN_IN_PATH = "/api/auth/sign-in/email"
        const val SESSION_PATH = "/api/auth/get-session"
        const val SIGN_OUT_PATH = "/api/auth/sign-out"
        const val SET_AUTH_TOKEN = "set-auth-token"
    }
}

/**
 * A non-2xx from Better Auth, named.
 *
 * 401 and 403 are one message on purpose — the server does not say which of the address and the
 * password was wrong, and neither does this (PRD 15.3). 404 is *not* "wrong password": it means
 * the endpoint is absent, which is a server too old or not Mue at all.
 */
private fun signInFailure(host: String, response: HttpResponse, body: String): PairingFailure {
    val status = response.status
    val detail = runCatching {
        PairingJson.instance.decodeFromString(AuthErrorDto.serializer(), body)
    }.getOrNull()

    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
        return PairingFailure.CredentialsRejected
    }
    if (status == HttpStatusCode.TooManyRequests) {
        return PairingFailure.TooManyAttempts(
            response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: detail?.retryAfter,
        )
    }
    if (status == HttpStatusCode.NotFound) return PairingFailure.SignInUnsupported(host)
    if (status.value >= 500) return PairingFailure.ServerUnavailable(host, status.value)

    // Better Auth answers 400 with `INVALID_EMAIL_OR_PASSWORD` for a bad pair, so a 400 that says
    // so is the credentials failure and not a policy one.
    val code = detail?.code.orEmpty()
    if (code.contains("PASSWORD", ignoreCase = true) ||
        code.contains("CREDENTIAL", ignoreCase = true)
    ) {
        return PairingFailure.CredentialsRejected
    }

    val explanation = detail?.message.orEmpty()
    return if (explanation.isNotBlank()) {
        PairingFailure.SignInRefused(detail?.code, explanation)
    } else {
        PairingFailure.SignInRefused(
            code = null,
            detail = "it answered ${status.value} without saying why.",
        )
    }
}

/**
 * The transport failure, from the bottom of the cause chain up.
 *
 * [SSLPeerUnverifiedException] is listed before the general [SSLException] because it is the one
 * a hostname mismatch produces, and a hostname mismatch is what a pairing typed into the wrong
 * address looks like — PRD 16's "l'association initiale vérifie que l'URL et le certificat
 * correspondent" is enforced by OkHttp, and this is where that enforcement becomes a sentence.
 */
private fun classify(host: String, failure: Throwable): PairingFailure {
    var cause: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    while (cause != null && seen.add(cause)) {
        when (cause) {
            is UnknownHostException -> return PairingFailure.HostNotFound(host)
            is SSLPeerUnverifiedException,
            is SSLHandshakeException,
            is CertificateException,
            is SSLException,
            -> return PairingFailure.UntrustedCertificate(host, cause.message)

            is SocketTimeoutException,
            is ConnectException,
            is NoRouteToHostException,
            -> return PairingFailure.Unreachable(host)

            else -> Unit
        }
        cause = cause.cause
    }
    // Everything left is a socket that did not work out: an IO error, a timeout Ktor raised
    // itself, a connection reset. All of them are "it did not answer" to the person waiting.
    return if (failure is IOException || failure.cause is IOException) {
        PairingFailure.Unreachable(host)
    } else {
        PairingFailure.NotAMueServer(host, failure.message)
    }
}

// --- wire shapes ------------------------------------------------------------------------------

/** `apps/platform/src/edge.ts`: `{ "status": "ok" }`, and the whole of what liveness reports. */
@Serializable
internal data class LivenessDto(val status: String)

@Serializable
internal data class SignInRequestDto(val email: String, val password: String)

/**
 * Better Auth's sign-in reply. Every field is optional because none of them is what this flow
 * depends on: the bearer is a header, and the account is confirmed by a second request.
 */
@Serializable
internal data class SignInResponseDto(
    val token: String? = null,
    val user: AuthUserDto? = null,
)

@Serializable
internal data class SessionResponseDto(val user: AuthUserDto? = null)

@Serializable
internal data class AuthUserDto(
    val id: String,
    val email: String? = null,
    val name: String? = null,
)

@Serializable
internal data class AuthErrorDto(
    val code: String? = null,
    val message: String = "",
    val retryAfter: Long? = null,
)

/**
 * Pairing's own [Json], separate from `SyncJson` because that one documents itself as belonging
 * to the sync contract and its drift test alone — and because these bodies are Better Auth's,
 * which version on their own schedule and carry many more fields than are read here.
 */
internal object PairingJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }
}
