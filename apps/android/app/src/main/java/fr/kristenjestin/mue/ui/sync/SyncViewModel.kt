package fr.kristenjestin.mue.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
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
        if (current.busy) return
        formState.update { it.copy(connecting = true, failure = null, success = null) }
        transient.update { it.copy(syncing = true, note = null) }

        viewModelScope.launch {
            val result = try {
                pairing.pair(current.address, current.email, current.password)
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
                    // A fresh form: address, email and password all gone, because the screen is
                    // about to show the paired server instead of the form.
                    formState.value = PairingFormState(
                        success = "Connected to ${result.serverName} as ${result.account}.",
                    )
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
                )
            }
        }
    }
}
