package fr.kristenjestin.mue.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.R
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.pairing.DisconnectResult
import fr.kristenjestin.mue.data.pairing.PairingResult
import fr.kristenjestin.mue.data.pairing.ServerAddresses
import fr.kristenjestin.mue.data.pairing.ServerPairing
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.sync.SyncEngine
import fr.kristenjestin.mue.data.sync.SyncOutcome
import fr.kristenjestin.mue.data.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state holder behind both halves of sync PRD 9: the `Data & sync` section inside `Profile`
 * (9.1) and the `Server settings` screen it opens (9.2 and 9.3).
 *
 * One view model for the two on purpose. They read the same row, the same outbox and the same
 * counters, and a pairing done on the second has to be visible on the first before the animation
 * has finished. Both take it from the activity's store, so it is literally the same object.
 *
 * ## Where the numbers come from
 *
 * Nothing here caches a status. `sync_state` is a Room `Flow`, the outbox counts are Room `Flow`s
 * of their own, and the four states of PRD 9.1 are recomputed by [SyncStatuses.derive] on every
 * emission. That is what makes the section change from `Synced` to `Changes pending` the moment
 * a weight is saved on another tab, with nothing to invalidate and nothing to remember to call.
 */
class SyncViewModel(
    private val syncDao: SyncDao,
    private val engine: SyncEngine,
    private val pairing: ServerPairing,
    /**
     * PRD 9.4's continuation. `SyncEngine` reads at most 50 pages per run so a long initial
     * history cannot hold a wakelock, and a run that stopped on that bound asks for another
     * rather than looping — which is WorkManager's job, not this screen's.
     */
    private val requestFollowUpSync: () -> Unit,
    /**
     * The address the pairing form starts with on a phone that has never been paired.
     *
     * Empty in every build but `beta`, where `build.gradle.kts` fills the `default_server_address`
     * resource from `local.properties` — the note there says why the value is not in the
     * repository. A parameter and not a resource read, because this class is proved on the JVM:
     * the one object on the path that may hold a `Context` is the [Factory] below, which is the
     * same arrangement `cleartext_server_permitted` uses to reach `ServerAddresses.parse` through
     * `SyncContainer`.
     *
     * Defaulted to the empty string, and that is not a placeholder — [seedForm] reads blank as
     * *no default at all*, so every caller and every test that does not name it keeps exactly the
     * behaviour it had.
     */
    private val defaultServerAddress: String = "",
    /**
     * The account the pairing form starts with on a phone that has never been paired.
     *
     * The address's twin, one key later: `mue.beta.email` in `local.properties` reaches the
     * `default_account_email` resource the same way `mue.beta.server` reaches
     * `default_server_address`, is empty in every build but a `beta` whose owner configured it,
     * and is taken as a parameter for the same reason — this class is proved on the JVM and holds
     * no [android.content.Context].
     *
     * **There is no third parameter for the password and there is not to be one.** The email is
     * an account name; the password is what that account is worth. `build.gradle.kts` makes the
     * same refusal at the other end of this wire and explains what an APK is — the short version
     * is that a `resValue` is a string in a file anyone holding the build can read back, and that
     * PRD_SERVER_SYNC_MCP 9.2 has the password typed at every pairing precisely so that no copy
     * of it outlives the screen. [onLeaveSettings] clearing it would mean nothing if a build-time
     * constant put it straight back.
     */
    private val defaultAccountEmail: String = "",
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    private val formState = MutableStateFlow(PairingFormState())

    /** The `Server settings` form. Transient by design; see [PairingFormState]. */
    val form: StateFlow<PairingFormState> = formState.asStateFlow()

    /**
     * The outbox, as two numbers that are read together.
     *
     * The second is a suspend query rather than a second Room `Flow` because it is a strict
     * subset of the first: a row can only be undeliverable while it is `pending`, so the moment
     * the pending count can have changed is the only moment the undeliverable count can have,
     * and one observer is enough to know when to ask. The query is skipped entirely at zero.
     */
    private val outbox: Flow<OutboxCounts> = syncDao.observePendingCount().map { pending ->
        OutboxCounts(
            pending = pending,
            undeliverable = if (pending == 0) {
                0
            } else {
                syncDao.countPendingOfOtherTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES)
            },
        )
    }

    /** Everything `Data & sync` draws (PRD 9.1). */
    val state: StateFlow<DataSyncUiState> = combine(
        syncDao.observeSyncState(),
        outbox,
        syncDao.observeFailedCount(),
        transient,
    ) { stored, counts, failed, running ->
        SyncStatuses.from(
            state = stored,
            pending = counts.pending,
            failed = failed,
            undeliverable = counts.undeliverable,
            // A pairing writes both columns, so the fallback only ever fires for a row written by
            // an older build; showing the host is still better than showing nothing.
            serverName = stored?.serverName?.takeUnless(String::isBlank)
                ?: stored?.serverUrl?.takeUnless(String::isBlank)?.let(ServerAddresses::displayName),
            syncing = running.syncing,
            note = running.note,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = DataSyncUiState(),
    )

    /**
     * PRD 9.4's `Sync now`, run inline rather than enqueued.
     *
     * The user is watching, so they get the engine's own answer instead of a job that may run in
     * a quarter of an hour. It is safe to run here: the engine holds a mutex against the periodic
     * worker, and its push returns every row to `pending` under `NonCancellable` — so a screen
     * closed mid-request leaves the outbox exactly as it found it.
     */
    fun syncNow() {
        if (transient.value.syncing) return
        transient.update { it.copy(syncing = true, note = null) }
        viewModelScope.launch {
            val outcome = try {
                engine.sync()
            } catch (cancellation: CancellationException) {
                transient.update { it.copy(syncing = false) }
                throw cancellation
            }
            transient.update { it.copy(syncing = false, note = SyncMessages.describe(outcome)) }
            if (outcome is SyncOutcome.Completed && outcome.moreAvailable) requestFollowUpSync()
        }
    }

    fun onAddressChange(value: String) {
        formState.update { it.copy(address = value, failure = null) }
    }

    fun onEmailChange(value: String) {
        formState.update { it.copy(email = value, failure = null) }
    }

    fun onPasswordChange(value: String) {
        formState.update { it.copy(password = value, failure = null) }
    }

    /**
     * PRD 9.2's manual pairing, end to end.
     *
     * The password is passed down and never held: on the way out of this function, whatever
     * happened, [PairingFormState.password] is empty again. A failure keeps the address and the
     * email — they were probably right, and making someone retype a private hostname because a
     * certificate was refused is the opposite of the named-failure design.
     */
    fun connect() {
        val current = formState.value
        attempt { pairing.pair(current.address, current.email, current.password) }
    }

    /**
     * PRD 9.3's "se reconnecter au même compte reprend la synchronisation", from a phone that is
     * still paired.
     *
     * Two situations reach it and they are the same operation. A bearer the server has stopped
     * accepting — a recreated account, a revoked session — leaves a pairing whose address and
     * account are both still right, and until this existed the only control on the screen was
     * `Disconnect server`, which threw all of it away to rebuild the same row by hand. And a
     * server that changed address is the same account somewhere else, which had no path at all.
     *
     * Note what is *not* passed: the email. It is read from `sync_state.account_id` inside
     * [fr.kristenjestin.mue.data.pairing.ServerPairing.reauthenticate], so this view model cannot
     * change which account this phone belongs to even if a future screen grew a field for it.
     */
    fun signInAgain() {
        val current = formState.value
        attempt { pairing.reauthenticate(current.address, current.password) }
    }

    /**
     * One attempt at obtaining a session, whichever button asked for it.
     *
     * `Connect` and `Sign in` differ in exactly one way — where the email comes from — and every
     * other rule is the same one: the form freezes, the password is gone on the way out whatever
     * happened, a failure keeps the address, and the initial synchronisation's own outcome is
     * reported rather than swallowed. Written once so the second button cannot quietly acquire
     * a different answer to "is the password kept".
     */
    private fun attempt(action: suspend () -> PairingResult) {
        if (formState.value.busy) return
        formState.update { it.copy(connecting = true, failure = null, success = null) }
        transient.update { it.copy(syncing = true, note = null) }

        viewModelScope.launch {
            val result = try {
                action()
            } catch (cancellation: CancellationException) {
                formState.update { it.copy(connecting = false, password = "") }
                transient.update { it.copy(syncing = false) }
                throw cancellation
            }

            when (result) {
                is PairingResult.Failed -> {
                    formState.update {
                        it.copy(connecting = false, password = "", failure = result.failure.message)
                    }
                    transient.update { it.copy(syncing = false) }
                }

                is PairingResult.Paired -> {
                    // A fresh form: email and password gone, and the address put back from the
                    // row that was just written — normalised, so what the paired card shows is
                    // the origin every later request will use rather than what was typed.
                    formState.value = PairingFormState(
                        success = "Connected to ${result.serverName} as ${result.account}.",
                    )
                    seedForm()
                    transient.update {
                        it.copy(syncing = false, note = SyncMessages.describe(result.firstSync))
                    }
                    val first = result.firstSync
                    if (first is SyncOutcome.Completed && first.moreAvailable) requestFollowUpSync()
                }
            }
        }
    }

    /** PRD 9.3: `Disconnect server` asks first. */
    fun requestDisconnect() {
        formState.update { it.copy(disconnectConfirmationVisible = true) }
    }

    fun cancelDisconnect() {
        formState.update { it.copy(disconnectConfirmationVisible = false) }
    }

    fun confirmDisconnect() {
        if (formState.value.disconnecting) return
        formState.update {
            it.copy(disconnectConfirmationVisible = false, disconnecting = true, success = null)
        }
        viewModelScope.launch {
            val result = try {
                pairing.disconnect()
            } catch (cancellation: CancellationException) {
                formState.update { it.copy(disconnecting = false) }
                throw cancellation
            } catch (failure: Exception) {
                formState.update {
                    it.copy(
                        disconnecting = false,
                        failure = "Could not disconnect: ${failure.message ?: "unknown reason"}.",
                    )
                }
                return@launch
            }
            formState.value = PairingFormState()
            transient.update { it.copy(note = SyncMessages.describe(result)) }
        }
    }

    /** Clears the one-line result once it has been read. */
    fun dismissNote() {
        transient.update { it.copy(note = null) }
    }

    /**
     * Fills the address in from `sync_state` when this phone is paired.
     *
     * Read straight from the DAO rather than from [state], which is a `WhileSubscribed` flow and
     * may still be holding its initial empty value at the moment the screen is composed. A
     * paired phone that showed an empty address box would make `Sign in` unusable until the
     * server was retyped from memory, correctly, which is the retyping this whole change removes.
     */
    fun onEnterSettings() {
        viewModelScope.launch { seedForm() }
    }

    /**
     * Two sources for one form, and the order between them is the rule rather than a preference.
     *
     * `sync_state` is what this phone actually belongs to, so it wins outright: **a paired phone
     * sees neither default**, whatever the build put there. That is the property worth stating,
     * because the failure it forbids is silent — an address swapped under a working pairing would
     * send the next `Sign in` to a machine that is not the one holding this phone's history, and
     * an account name offered over a pairing made under a different one would be an invitation to
     * `Connect` somewhere that history is not.
     *
     * The address is filled from the stored row and the email deliberately is not, though
     * `sync_state.account_id` holds it. Seeding it would change `release`, `local` and `debug`
     * too — they would start showing the paired account in a box that is empty today — and what
     * bounds this whole mechanism to `beta` is that the resources are empty everywhere else. A
     * field nobody reads is the right price: `Sign in` takes the account from
     * `ServerPairing.reauthenticate`, never from this form, so a paired phone has no use for it.
     *
     * Unpaired, each default reaches only a field that is still blank, and each is decided on its
     * own — configuring one key and not the other is an ordinary state, not a half-built form. So
     * a value the owner has begun typing is not replaced, and neither is one left from an earlier
     * visit to the screen: [onLeaveSettings] clears the password and deliberately not the other
     * two, so re-entering runs this again over fields that already hold something.
     *
     * Every condition is read inside the `update` and after the suspending DAO call rather than
     * before it, because [onEnterSettings] runs in `viewModelScope`: the keyboard is live while
     * the read is in flight.
     */
    private suspend fun seedForm() {
        val stored = syncDao.syncState()?.serverUrl?.takeUnless(String::isBlank)
        if (stored != null) {
            formState.update { it.copy(address = stored) }
            return
        }
        // Blank is "this build proposes nothing", which is every build but `beta` and `beta`
        // itself on a machine whose `local.properties` says nothing. The two guards keep the
        // no-default path byte for byte what it was before the parameters existed, including the
        // case where one key is set and the other is not.
        if (defaultServerAddress.isNotBlank()) {
            formState.update { form ->
                if (form.address.isBlank()) form.copy(address = defaultServerAddress) else form
            }
        }
        if (defaultAccountEmail.isNotBlank()) {
            formState.update { form ->
                if (form.email.isBlank()) form.copy(email = defaultAccountEmail) else form
            }
        }
    }

    /** The password never outlives the screen that collected it. */
    fun onLeaveSettings() {
        formState.update { it.copy(password = "", failure = null, success = null) }
    }

    private data class OutboxCounts(val pending: Int, val undeliverable: Int)

    /** What is happening now, which is meaningless after a process death and so is not saved. */
    private data class TransientState(
        val syncing: Boolean = false,
        val note: SyncNote? = null,
    )

    companion object {
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                val sync = app.container.sync
                SyncViewModel(
                    syncDao = sync.syncDao,
                    engine = sync.engine,
                    pairing = sync.pairing,
                    requestFollowUpSync = { SyncScheduler.syncNow(app) },
                    // The only two resources read on this path, and they are read here rather
                    // than in the view model for the reason the parameters' own notes give: a
                    // `Context` in `SyncViewModel` would put the seeding rule out of reach of a
                    // JVM test. Both are empty for `release`, `local` and `debug`, which is what
                    // makes these two lines change nothing for them.
                    defaultServerAddress = app.getString(R.string.default_server_address),
                    defaultAccountEmail = app.getString(R.string.default_account_email),
                )
            }
        }
    }
}
