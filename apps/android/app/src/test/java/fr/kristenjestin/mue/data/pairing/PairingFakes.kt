package fr.kristenjestin.mue.data.pairing

import fr.kristenjestin.mue.data.local.database.SyncStateEntity

/**
 * The three seams [ServerPairing] is built against, as fakes that remember what was done to them.
 *
 * They record rather than merely answer, because half of what PRD 9.2 and 9.3 require is about
 * what did *not* happen: no token written on a refused pairing, no `sync_state` row touched when
 * a second account signs in, no revocation skipped when a session is discarded.
 */
internal class FakePairingStore(initial: SyncStateEntity? = null) : PairingStore {

    var current: SyncStateEntity? = initial
        private set

    /** Every row ever written, in order, so a test can see a half-write that was undone. */
    val writes = mutableListOf<SyncStateEntity>()

    var failOnSave: Boolean = false

    override suspend fun state(): SyncStateEntity? = current

    override suspend fun save(state: SyncStateEntity) {
        if (failOnSave) throw IllegalStateException("disk full")
        writes += state
        current = state
    }
}

internal class FakeTokenStore(initial: String? = null) : TokenStore {

    var token: String? = initial
        private set

    var clears: Int = 0
        private set

    var failOnWrite: Boolean = false

    override suspend fun read(): String? = token

    override suspend fun write(token: String) {
        if (failOnWrite) throw IllegalStateException("keystore refused")
        this.token = token
    }

    override suspend fun clear() {
        clears++
        token = null
    }
}

/**
 * A [PairingApi] whose every step can be made to fail by name.
 *
 * Each step is a lambda rather than a flag so a test states the *one* thing it is about — a
 * certificate, a password, a bearer refused on the next request — and every other step behaves.
 */
internal class FakePairingApi(
    var onProbe: (String) -> Unit = {},
    var onSignIn: (String, String, String) -> PairingSession = { _, _, _ -> DefaultSession },
    var onAccount: (String, String) -> PairingAccount = { _, _ -> DefaultAccount },
    var revokeAnswers: Boolean = true,
) : PairingApi {

    val probed = mutableListOf<String>()
    val signedIn = mutableListOf<Triple<String, String, String>>()
    val revoked = mutableListOf<Pair<String, String>>()

    override suspend fun probe(origin: String) {
        probed += origin
        onProbe(origin)
    }

    override suspend fun signIn(
        origin: String,
        email: String,
        password: String,
    ): PairingSession {
        signedIn += Triple(origin, email, password)
        return onSignIn(origin, email, password)
    }

    override suspend fun account(origin: String, token: String): PairingAccount =
        onAccount(origin, token)

    override suspend fun revoke(origin: String, token: String): Boolean {
        revoked += origin to token
        return revokeAnswers
    }

    companion object {
        val DefaultAccount = PairingAccount(
            id = "user_1",
            email = "Kris@Example.org",
            displayName = "Kris",
        )
        val DefaultSession = PairingSession(token = "bearer-1", account = DefaultAccount)
    }
}
