package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.R
import fr.kristenjestin.mue.data.local.database.HealthProfileDao
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.local.datastore.syncTokenDataStore
import fr.kristenjestin.mue.data.local.datastore.userProfileDataStore
import fr.kristenjestin.mue.data.pairing.CleartextPolicy
import fr.kristenjestin.mue.data.pairing.KeystoreTokenStore
import fr.kristenjestin.mue.data.pairing.KtorPairingApi
import fr.kristenjestin.mue.data.pairing.PairingApi
import fr.kristenjestin.mue.data.pairing.RoomPairingStore
import fr.kristenjestin.mue.data.pairing.ServerPairing
import fr.kristenjestin.mue.data.remote.sync.KtorSyncApi
import fr.kristenjestin.mue.data.remote.sync.KtorSyncEventStream
import fr.kristenjestin.mue.data.remote.sync.SyncApi
import fr.kristenjestin.mue.data.remote.sync.SyncEventStream
import fr.kristenjestin.mue.data.sync.HealthProfileSeeding
import fr.kristenjestin.mue.data.sync.LiveSyncChannel
import fr.kristenjestin.mue.data.sync.PushOnWrite
import fr.kristenjestin.mue.data.sync.RoomSyncStore
import fr.kristenjestin.mue.data.sync.SyncEngine
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.data.sync.SyncScheduler
import fr.kristenjestin.mue.data.sync.SyncStore
import fr.kristenjestin.mue.data.sync.SyncTokenStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Everything server synchronisation needs, registered in one place.
 *
 * [AppContainer] gains a single property for the whole module, exactly as the Activity Timer
 * did, so the engine, the workers and the `Data & sync` screen can be built against this
 * surface without the shipped container having to move again.
 *
 * Lazy for the same reason as everything in [AppContainer]: a cold start that never
 * synchronises must not pay for a database handle, a Keystore lookup or an HTTP client.
 * [outbox] is the one exception — it owns a UUID generator, a clock and a flow with nothing in
 * it, so there is nothing to defer, and the measurement repository needs it on the very first
 * save. The collector started beside it in `init` is the second half of that: it has to be
 * listening *before* the first row can be minted, and building this container is the only event
 * that is guaranteed to happen first.
 *
 * [engine] is laziest of all, and deliberately so: constructing it is what runs the `inflight`
 * recovery, so "engine start" happens when a synchronisation is actually about to be attempted
 * and not on the launch path of an app the user opened to type a weight.
 */
class SyncContainer(
    private val applicationContext: Context,
    private val database: MueDatabase,
) {
    /** Mints the outbox row a local write journals in its own transaction (FR-SYNC-001). */
    val outbox: SyncOutbox = SyncOutbox()

    /**
     * Where PRD 9.4's missing trigger is wired: **a local write schedules a send.**
     *
     * ## Why here
     *
     * `SyncOutbox` is the one place that knows a row was minted, and it must stay free of
     * Android to keep the JVM tests that assert on the exact rows it builds. This file is the
     * nearest place that already holds an application context and already owns process-wide
     * lifetimes. So the outbox announces on a flow, and the two lines below turn that
     * announcement into `SyncScheduler.syncNow` — the same constrained one-shot the application
     * start and the foreground trigger enqueue, so the network and battery constraints of PRD 19
     * are inherited rather than restated. An unpaired or offline phone therefore schedules
     * nothing that spins: WorkManager holds the request until a network exists, and the worker
     * answers `NotPaired` with a success.
     *
     * ## Why it starts in `init` rather than being started by `MueApplication`
     *
     * Because that is what makes the ordering impossible to get wrong. A mutation can only be
     * minted through [outbox], [outbox] can only be reached through this container, and this
     * container cannot exist without having run this block — so there is no window in which a
     * save is journalled with nobody listening. Starting it from `MueApplication.onCreate`
     * instead would read better and be one refactor away from a first save that goes nowhere.
     *
     * It costs one coroutine that spends its entire life suspended on a flow with no value in
     * it, and no work at all in a process that never writes.
     */
    private val pushOnWrite = PushOnWrite(
        minted = outbox.minted,
        schedule = { SyncScheduler.syncNow(applicationContext) },
    )

    /**
     * The collector's home. Separate from [engineScope]: this one must survive a cancelled
     * synchronisation, and a shared job would let one failure take the other down.
     *
     * `Dispatchers.Default` and not `Main`: it waits out the quiet window and then hands
     * WorkManager a request, and neither has any business on the frame loop.
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        writeScope.launch { pushOnWrite.run() }
    }

    /** The outbox, the remote identity of each aggregate, and the single `sync_state` row. */
    val syncDao: SyncDao by lazy { database.syncDao() }

    /** The two synchronised profile fields, now that they are Room rows (sync PRD 19). */
    val healthProfileDao: HealthProfileDao by lazy { database.healthProfileDao() }

    /** The device session bearer, in Android Keystore rather than in any table (PRD 9.2). */
    val tokenStore: SyncTokenStore by lazy { SyncTokenStore(applicationContext.syncTokenDataStore) }

    /**
     * The one-shot copy of the pre-version-5 height and birth date into Room. It has to run
     * before the first synchronisation, or the phone would offer the server an empty profile
     * and overwrite the one the user actually typed.
     */
    val healthProfileSeeding: HealthProfileSeeding by lazy {
        // The database is passed as a provider, not as a value: the fast path of `seedOnce` must
        // not so much as ask for one, and a provider is what lets a JVM test see that it did not.
        HealthProfileSeeding({ database }, applicationContext.userProfileDataStore)
    }

    /** One client for the whole process: a second would mean a second connection pool. */
    val httpClient: HttpClient by lazy { KtorSyncApi.defaultClient() }

    /**
     * The bearer and the server URL are read *per call* rather than captured, so
     * `Disconnect server` (PRD 9.3) takes effect on the next request instead of on the next
     * process — a revoked session that kept synchronising because an object held a copy of its
     * token would be exactly the leak PRD 16 forbids.
     */
    val api: SyncApi by lazy {
        KtorSyncApi(
            client = httpClient,
            baseUrl = { syncDao.syncState()?.serverUrl },
            token = { tokenStore.read() },
        )
    }

    val store: SyncStore by lazy { RoomSyncStore(database, outbox) }

    /**
     * Outlives every screen and every worker invocation: the `inflight` recovery started in the
     * engine's constructor must finish even if whatever asked for the engine goes away.
     */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One engine per process, so `Sync now` and the periodic worker share its gate. */
    val engine: SyncEngine by lazy { SyncEngine(store = store, api = api, scope = engineScope) }

    /**
     * The live channel's transport, on the **same** [httpClient] as [api] and for the same reason
     * the pairing shares it: one trust configuration, proved once.
     *
     * The URL and the bearer are read per connection rather than captured, so `Disconnect server`
     * closes the channel at the next reconnection instead of at the next process.
     */
    val eventStream: SyncEventStream by lazy {
        KtorSyncEventStream(
            client = httpClient,
            baseUrl = { syncDao.syncState()?.serverUrl },
            token = { tokenStore.read() },
        )
    }

    /**
     * PRD 9.4's live trigger, held open by `MueApp` for the width of the foreground and by
     * nothing else.
     *
     * It takes the same [engine] every other trigger takes. That is the whole reason it is built
     * here rather than by whatever screen starts it: a second engine would mean a second gate, and
     * two gates are no gate at all.
     */
    val liveSync: LiveSyncChannel by lazy {
        LiveSyncChannel(
            paired = { !syncDao.syncState()?.serverUrl.isNullOrBlank() },
            stream = eventStream,
            sync = { engine.sync() },
        )
    }

    /**
     * The Better Auth half of PRD 9.2, on the **same** [httpClient] as [api].
     *
     * Sharing it is not thrift. PRD 16 has the pairing verify the certificate of the address that
     * was entered, and the certificate that matters is the one every later synchronisation will
     * be checked against — a second client is a second trust configuration, and a pairing proved
     * against one of them would say nothing about the other.
     */
    val pairingApi: PairingApi by lazy { KtorPairingApi(httpClient) }

    /**
     * `Connect` and `Disconnect server`, whole (PRD 9.2 and 9.3).
     *
     * It takes [engine] because a successful pairing has to trigger the initial synchronisation,
     * and taking the same instance is what makes that run share the gate with the worker instead
     * of racing it.
     */
    val pairing: ServerPairing by lazy {
        ServerPairing(
            store = RoomPairingStore(syncDao),
            tokenStore = KeystoreTokenStore(tokenStore),
            api = pairingApi,
            // Where the build type finally gets to answer (`CleartextPolicy`). `buildConfig` is
            // off in this module, so a build type states such a thing as a generated resource —
            // the road `app_name` and `launcher_background` already take — and this is the one
            // object on the path from that resource to `ServerAddresses.parse` that holds a
            // `Context`. The parser stays pure and JVM-testable because the reading happens here.
            //
            // `defaultConfig` declares it false and `release` does not override it, so a build
            // that says nothing refuses cleartext; only `local`, `beta` and `debug` opt in.
            cleartext = if (applicationContext.resources.getBoolean(R.bool.cleartext_server_permitted)) {
                CleartextPolicy.Permitted
            } else {
                CleartextPolicy.Refused
            },
            // The engine is resolved when the pairing succeeds, not when the container is built,
            // so opening `Server settings` still does not run the `inflight` recovery.
            firstSync = { engine.sync() },
        )
    }
}
