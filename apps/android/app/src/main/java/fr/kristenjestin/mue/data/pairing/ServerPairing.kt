package fr.kristenjestin.mue.data.pairing

import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Sync PRD 9.2 and 9.3, as two calls: pair this phone with a server, and let it go again.
 *
 * Everything the sync layer needed to run already existed and was tested — the engine, the
 * outbox, the worker, the Keystore token store. The one thing missing was the row that says
 * which server, which is why `SyncEngine.sync()` has been returning `NotPaired` at every app
 * start since it shipped. This class writes that row, and nothing else in the sync stack is
 * touched to make it work.
 *
 * ## Nothing is stored until everything has worked
 *
 * The order below is the order of what can be undone. The address is parsed, the host is
 * identified, the credentials are exchanged and the bearer is *tried once* — all before a byte
 * reaches Keystore or Room. A pairing that half-succeeded would leave a `server_url` with no
 * token, and every later sync would fail with `auth.unauthenticated` for a reason no screen could
 * explain.
 *
 * ## The password
 *
 * It is a parameter of [pair] and it is never written anywhere. What is kept is the device
 * bearer, in [TokenStore] — `SyncTokenStore` in production, AES-GCM under an `AndroidKeyStore`
 * key. That is PRD 9.2's "le mot de passe n'est jamais conservé sur le téléphone", enforced by
 * there being no field for it.
 */
class ServerPairing(
    private val store: PairingStore,
    private val tokenStore: TokenStore,
    private val api: PairingApi,
    /**
     * PRD 9.2's "une association réussie déclenche la synchronisation initiale", as a call rather
     * than as an engine.
     *
     * It is the same `SyncEngine` instance the worker uses — the container passes
     * `{ engine.sync() }` — so the initial run shares the engine's gate instead of racing the
     * periodic one. Passing the call and not the object is what lets the whole of PRD 9.2 and 9.3
     * be proved by JVM tests, which is the same decision `SyncStore` made for the engine itself.
     */
    private val firstSync: suspend () -> SyncOutcome,
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * PRD 9.2, whole: discover, authenticate, keep, and synchronise.
     *
     * @return [PairingResult.Paired] with the outcome of the first synchronisation, so the screen
     * can say what actually happened rather than "done" — a server that is too new for this build
     * answers `upgrade_required` on the very first pull, and that is a fact about the pairing the
     * user has to be told at the moment they made it.
     */
    suspend fun pair(address: String, email: String, password: String): PairingResult {
        val parsed = when (val result = ServerAddresses.parse(address)) {
            is ServerAddressResult.Invalid -> return PairingResult.Failed(result.failure)
            is ServerAddressResult.Valid -> result.address
        }
        if (email.isBlank() || password.isEmpty()) {
            return PairingResult.Failed(PairingFailure.CredentialsMissing)
        }

        return try {
            pairChecked(parsed, email.trim(), password)
        } catch (pairing: PairingException) {
            PairingResult.Failed(pairing.failure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Room or Keystore. Anything the network could raise has already been named by
            // `KtorPairingApi`, so reaching here means the phone, not the server.
            PairingResult.Failed(PairingFailure.NotStored(failure.message))
        }
    }

    private suspend fun pairChecked(
        address: ServerAddress,
        email: String,
        password: String,
    ): PairingResult {
        // Unauthenticated, and first: a phone must identify what it is talking to before it
        // offers a password to it.
        api.probe(address.origin)

        val session = api.signIn(address.origin, email, password)
        // The bearer is proved on the wire before it is stored. PRD 24 still lists the exact
        // behaviour of the Better Auth bearer in Ktor as an open question; a token that is never
        // tried is a pairing that looks fine and synchronises nothing.
        val account = api.account(address.origin, session.token)

        val previous = store.state()
        val guard = accountGuard(previous?.accountId, account)
        if (guard != null) {
            // The session this app just caused to exist is not one it is going to keep, so it
            // does not get to outlive the attempt (PRD 15.3).
            api.revoke(address.origin, session.token)
            return PairingResult.Failed(guard)
        }

        tokenStore.write(session.token)
        try {
            store.save(
                SyncStateEntity(
                    serverUrl = address.origin,
                    serverName = address.name,
                    accountId = account.identity,
                    // The device identity is the phone, not the pairing (PRD 12.1's `origin.id`),
                    // so a re-pairing keeps the one the server already knows and only a phone
                    // that has never been paired mints one.
                    deviceId = previous?.deviceId?.takeUnless(String::isBlank) ?: newDeviceId(),
                    // Deliberately null on every pairing, including a re-pairing of the same
                    // server. A cursor is opaque and server-owned (PRD 12.3); one kept across a
                    // disconnect can outlive the journal it indexes — a reinstalled server (PRD
                    // 18) would answer `sync.invalid_cursor` and leave the phone stuck with no
                    // way back. Reading the journal from the start costs bandwidth and nothing
                    // else: applying a page repeats no effect (FR-SYNC-006).
                    cursor = null,
                    lastSuccessAt = null,
                    lastErrorCode = null,
                    lastErrorMessage = null,
                    // Preserved: it records that the pre-version-5 height and birth date have
                    // already been copied into Room. Clearing it would re-run that copy from a
                    // DataStore the user has since edited through Room.
                    profileSeeded = previous?.profileSeeded ?: false,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A token with no server row is a secret kept for nothing. Give it back.
            tokenStore.clear()
            api.revoke(address.origin, session.token)
            throw failure
        }

        // PRD 9.2: "Une association réussie déclenche la synchronisation initiale." Inline rather
        // than through WorkManager, because the person is looking at the screen and is owed the
        // answer; the engine's own gate serialises it against the periodic worker.
        val outcome = firstSync()
        return PairingResult.Paired(
            serverName = address.name,
            account = account.label,
            firstSync = outcome,
        )
    }

    /**
     * PRD 9.3's trap, and the reason `sync_state.account_id` survives a disconnect.
     *
     * The column holds the **email address**, lowercased, and not the Better Auth user id. Two
     * cases decide it, and they pull in opposite directions:
     *
     * - PRD 9.3 forbids a *different account*'s data ever being merged into this Room store. Two
     *   accounts on one server are two email addresses, so the email catches it.
     * - PRD 18 requires a *reinstalled server* to be re-paired and synchronised without erasing
     *   anything. A reinstall mints a new user id for the same person, so a guard on the id would
     *   refuse the one case the PRD names as supported.
     *
     * The email is the only identifier that is stable across the second and distinct across the
     * first. A null stored value means this phone's data has never belonged to anyone, which is
     * the state of every phone before its first pairing: it is adopted by whoever signs in, and
     * the local history goes up with it (PRD 21).
     */
    private fun accountGuard(stored: String?, offered: PairingAccount): PairingFailure? {
        val known = stored?.trim()?.lowercase()?.takeUnless(String::isEmpty) ?: return null
        val incoming = offered.identity
        // An account the server names with no email cannot be compared, and refusing every such
        // pairing would be worse than allowing it: the guard exists to catch a second person, and
        // a server that reports no email cannot tell us there is one.
        if (incoming.isEmpty()) return null
        if (incoming == known) return null
        return PairingFailure.DifferentAccount(storedAccount = known, offeredAccount = incoming)
    }

    /**
     * PRD 9.3: confirm, revoke where possible, delete the local token, and **delete no data**.
     *
     * What stays is as deliberate as what goes:
     *
     * - Every business row. The PRD says so, and it is why `Disconnect server` is not dangerous.
     * - The **outbox**. Its rows are changes that exist on this phone and nowhere else; deleting
     *   them to tidy up would be the loss FR-SYNC-001 forbids, and keeping them means re-pairing
     *   the same account sends them at once.
     * - `account_id`, which is what makes the guard in [accountGuard] outlive the pairing. Losing
     *   it here would let the next pairing be somebody else's and re-open the trap.
     * - `device_id`, which identifies the phone rather than the session.
     *
     * The periodic worker is deliberately not cancelled: with no `server_url`, `SyncEngine.sync`
     * returns `NotPaired` and `SyncWorker` reports success, so an unpaired phone accumulates no
     * failed work and needs no scheduling change (PRD 21).
     */
    suspend fun disconnect(): DisconnectResult {
        val state = store.state()
        val origin = state?.serverUrl?.takeUnless(String::isBlank)
            ?: return DisconnectResult.NotPaired
        val name = state.serverName?.takeUnless(String::isBlank)
            ?: ServerAddresses.displayName(origin)

        val token = tokenStore.read()
        val revoked = if (token == null) {
            // A server row with no readable token is already a session this phone cannot use —
            // a Keystore key invalidated by a lock-screen change does exactly this. There is
            // nothing to revoke and nothing to wait for.
            true
        } else {
            api.revoke(origin, token)
        }

        tokenStore.clear()
        store.save(
            state.copy(
                serverUrl = null,
                serverName = null,
                cursor = null,
                lastSuccessAt = null,
                lastErrorCode = null,
                lastErrorMessage = null,
            ),
        )

        return if (revoked) DisconnectResult.Revoked(name) else DisconnectResult.LocalOnly(name)
    }
}

/** What [ServerPairing.pair] did. Both branches are values; neither is a throw. */
sealed interface PairingResult {

    /**
     * The phone is paired. [firstSync] is the initial synchronisation's own outcome and is not
     * hidden: a `Failed` or an `UpgradeRequired` here means the pairing worked and the
     * synchronisation did not, which are two different pieces of news and the screen says both.
     */
    data class Paired(
        val serverName: String,
        val account: String,
        val firstSync: SyncOutcome,
    ) : PairingResult

    /** Nothing was stored, nothing was changed, and [failure] says which step stopped. */
    data class Failed(val failure: PairingFailure) : PairingResult
}

/** What [ServerPairing.disconnect] did. In every branch, the local token is gone. */
sealed interface DisconnectResult {

    /** There was nothing to disconnect. */
    data object NotPaired : DisconnectResult

    /** The server confirmed: that device session no longer exists anywhere. */
    data class Revoked(val serverName: String) : DisconnectResult

    /**
     * The token is gone from this phone and the server could not be told.
     *
     * PRD 9.3 plans for this: the remote revocation stays possible from the future Web interface
     * or another authorised session, and the message says so rather than pretending.
     */
    data class LocalOnly(val serverName: String) : DisconnectResult
}
