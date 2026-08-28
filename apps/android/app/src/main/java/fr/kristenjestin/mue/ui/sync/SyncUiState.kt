package fr.kristenjestin.mue.ui.sync

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes

/**
 * The four words sync PRD 9.1 allows `Data & sync` to say, and no fifth.
 *
 * They are not four ways of feeling about the same fact; each is a different answer to "is what
 * I typed on this phone also somewhere else", which is the only question the section is asked.
 */
enum class SyncStatus {

    /** No server is paired. PRD 21 makes this a supported way to use Mue, not a fault. */
    NOT_CONNECTED,

    /** Everything this phone has written is on the server, and the last exchange succeeded. */
    SYNCED,

    /** Paired and working, with local changes that have not reached the server yet. */
    CHANGES_PENDING,

    /** The last exchange failed, or the server refused a change and it is still here. */
    SYNC_ISSUE,
}

/**
 * Everything the `Data & sync` section draws, and the state the `Server settings` screen shows
 * above the pairing form.
 *
 * Every count is read from the outbox rather than inferred from the last outcome. That is the
 * whole point of the section: an owner who has lost a history to an uninstall is not asking
 * whether the last request succeeded, he is asking how many rows are still only on this phone,
 * and those are two different numbers whenever a synchronisation has partly failed
 * (FR-SYNC-007).
 */
@Immutable
data class DataSyncUiState(
    val status: SyncStatus = SyncStatus.NOT_CONNECTED,
    /** PRD 9.1's "nom du serveur associé"; null when there is none. */
    val serverName: String? = null,
    /** The account this phone's data belongs to, kept across a disconnect (PRD 9.3). */
    val account: String? = null,
    /** PRD 9.1's "date et heure de la dernière synchronisation réussie". */
    val lastSuccessAt: Long? = null,
    /**
     * PRD 9.1's "nombre de changements locaux en attente" — every local change that is not on
     * the server, whether it is queued, refused or undeliverable. Shown only when non-zero, as
     * the PRD says.
     */
    val outstandingChanges: Int = 0,
    /** Of [outstandingChanges], the ones the server refused and this phone kept (FR-SYNC-007). */
    val refusedChanges: Int = 0,
    /**
     * Of [outstandingChanges], the ones no build of Mue can send yet — the health profile, which
     * is journalled at every save and has no branch in `packages/contracts`. Named separately
     * because a number that never goes down looks like a fault and is not one.
     */
    val undeliverableChanges: Int = 0,
    /** The message the engine last recorded, verbatim (FR-SYNC-008). */
    val lastErrorMessage: String? = null,
    /**
     * The last exchange failed because the server would not accept this phone's bearer.
     *
     * It is told apart from every other [SyncStatus.SYNC_ISSUE] because it is the only one the
     * person holding the phone can *do* something about from this screen, and because the server
     * answers it with the sentence `Sign in to synchronise.` — an instruction that, until the
     * `Server settings` screen grew a sign-in of its own, named an action with no control behind
     * it anywhere in the app.
     *
     * A recreated account, a session revoked from the server, a bearer expired: three causes,
     * one remedy, and it is not `Disconnect server`.
     */
    val sessionRejected: Boolean = false,
    /** A synchronisation is running right now, started from this screen. */
    val syncing: Boolean = false,
    /** What the last `Sync now` reported. Transient; never a stored state. */
    val syncNote: SyncNote? = null,
) {
    val connected: Boolean get() = status != SyncStatus.NOT_CONNECTED
}

/** A one-line result of an action the user pressed, and whether it went well. */
@Immutable
data class SyncNote(val message: String, val isProblem: Boolean)

/**
 * The `Server settings` form.
 *
 * **None of it is in `SavedStateHandle`, and the password is the reason.** Saved state is written
 * to a `Bundle` and survives process death on disk; PRD 9.2 says the password is never kept on
 * the phone, and the cheapest way to keep a promise like that is to have nowhere for it to go.
 * The address and the email travel with it rather than being split off, so there is one rule
 * about this form instead of two — a rotation costs a re-typed address, which is a fair price for
 * a password that cannot be recovered from a crash dump.
 *
 * [password] is cleared the instant a pairing succeeds or the screen is left.
 *
 * The same three fields serve both halves of the screen, because they are the same three facts.
 * Unpaired, all of them are typed. Paired, [address] arrives already filled in from
 * `sync_state.server_url` and [email] is not asked at all: signing in again happens as the
 * account this phone's data already belongs to, and PRD 9.3's refusal to merge two accounts is
 * kept by there being no field to type a second one into.
 */
@Immutable
data class PairingFormState(
    val address: String = "",
    val email: String = "",
    val password: String = "",
    /** A pairing attempt is in flight; the form is frozen and the button says so. */
    val connecting: Boolean = false,
    /** The named reason the last attempt stopped. One of `PairingFailure`'s messages, verbatim. */
    val failure: String? = null,
    /** What the successful pairing connected to. */
    val success: String? = null,
    val disconnectConfirmationVisible: Boolean = false,
    val disconnecting: Boolean = false,
) {
    val busy: Boolean get() = connecting || disconnecting
}

/**
 * How the four states of PRD 9.1 are decided, in one pure function so the rule is a unit test
 * and not a screenshot.
 *
 * The order of the branches is the order of what would be a lie:
 *
 * 1. **Not connected** repeats the engine's own guard exactly — `SyncEngine.sync()` returns
 *    `NotPaired` when `server_url` or `device_id` is blank, and the section must never claim a
 *    state the engine would not act on.
 * 2. **Sync issue** comes before the counts, because a refused mutation (`state = 'failed'`) and
 *    a recorded transport failure (`last_error_code`) are both changes that are *not going to
 *    arrive on their own*. `recordSuccess` clears the error columns in the same transaction that
 *    advances the cursor, so a stale code cannot survive a good run.
 * 3. **Changes pending** is any outstanding row on a healthy pairing.
 * 4. **Synced** is left, and it is claimed only when a synchronisation has actually succeeded at
 *    least once. A phone paired thirty seconds ago with an empty outbox and no `last_success_at`
 *    has proved nothing, and saying `Synced` there would be the exact lie the section exists to
 *    avoid; it reads `Changes pending` until the first exchange lands.
 */
object SyncStatuses {

    fun derive(state: SyncStateEntity?, pending: Int, failed: Int): SyncStatus {
        // An absent row is a phone that has never had a server, which reads the same as one whose
        // pairing was given up: both are `NotPaired` to the engine, so both are the same word here.
        if (state == null) return SyncStatus.NOT_CONNECTED
        if (state.serverUrl.isNullOrBlank() || state.deviceId.isNullOrBlank()) {
            return SyncStatus.NOT_CONNECTED
        }
        if (failed > 0 || !state.lastErrorCode.isNullOrBlank()) return SyncStatus.SYNC_ISSUE
        if (pending > 0) return SyncStatus.CHANGES_PENDING
        return if (state.lastSuccessAt != null) SyncStatus.SYNCED else SyncStatus.CHANGES_PENDING
    }

    /**
     * The whole of what PRD 9.1 shows, from the row and the three outbox counts.
     *
     * It lives here rather than inside [SyncViewModel] so that the one number the owner now cares
     * about most — how much of his history is still only on this phone — is decided by a function
     * a JVM test can call, and not by a `combine` block only an emulator can reach.
     *
     * [failed] is added to [pending] rather than shown instead of it. A refused mutation is still
     * a local change that is not on the server (FR-SYNC-007 keeps it, marks it and never sends it
     * again), so leaving it out of "changes waiting" would let a phone holding three rejected
     * measurements report nothing waiting at all.
     */
    fun from(
        state: SyncStateEntity?,
        pending: Int,
        failed: Int,
        undeliverable: Int,
        serverName: String?,
        syncing: Boolean = false,
        note: SyncNote? = null,
    ): DataSyncUiState = DataSyncUiState(
        status = derive(state, pending, failed),
        serverName = serverName,
        account = state?.accountId?.takeUnless(String::isBlank),
        lastSuccessAt = state?.lastSuccessAt,
        outstandingChanges = pending + failed,
        refusedChanges = failed,
        undeliverableChanges = undeliverable,
        lastErrorMessage = state?.lastErrorMessage?.takeUnless(String::isBlank),
        // Read from the code and never from the message: `last_error_message` is whatever the
        // server wrote, in the server's words, and a screen that decided what to offer by
        // matching an English sentence would stop offering it the day that sentence changed.
        sessionRejected = state?.lastErrorCode == SyncErrorCodes.AUTH_UNAUTHENTICATED,
        syncing = syncing,
        syncNote = note,
    )
}
