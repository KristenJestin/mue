package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.local.database.HealthProfileDao
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.local.datastore.syncTokenDataStore
import fr.kristenjestin.mue.data.local.datastore.userProfileDataStore
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
import fr.kristenjestin.mue.data.sync.RoomSyncStore
import fr.kristenjestin.mue.data.sync.SyncEngine
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.data.sync.SyncStore
import fr.kristenjestin.mue.data.sync.SyncTokenStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything server synchronisation needs, registered in one place.
 *
 * [AppContainer] gains a single property for the whole module, exactly as the Activity Timer
 * did, so the engine, the workers and the `Data & sync` screen can be built against this
 * surface without the shipped container having to move again.
 *
 * Lazy for the same reason as everything in [AppContainer]: a cold start that never
 * synchronises must not pay for a database handle, a Keystore lookup or an HTTP client.
 * [outbox] is the one exception — it owns nothing but a UUID generator and a clock, so there is
 * nothing to defer, and the measurement repository needs it on the very first save.
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

    val store: SyncStore by lazy { RoomSyncStore(database) }

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
            // The engine is resolved when the pairing succeeds, not when the container is built,
            // so opening `Server settings` still does not run the `inflight` recovery.
            firstSync = { engine.sync() },
        )
    }
}
