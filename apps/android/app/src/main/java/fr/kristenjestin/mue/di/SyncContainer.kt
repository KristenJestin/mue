package fr.kristenjestin.mue.di

import android.content.Context
import fr.kristenjestin.mue.data.local.database.HealthProfileDao
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.local.datastore.syncTokenDataStore
import fr.kristenjestin.mue.data.local.datastore.userProfileDataStore
import fr.kristenjestin.mue.data.sync.HealthProfileSeeding
import fr.kristenjestin.mue.data.sync.SyncOutbox
import fr.kristenjestin.mue.data.sync.SyncTokenStore

/**
 * Everything server synchronisation needs, registered in one place.
 *
 * [AppContainer] gains a single property for the whole module, exactly as the Activity Timer
 * did, so the engine, the workers and the `Data & sync` screen can be built against this
 * surface without the shipped container having to move again.
 *
 * Lazy for the same reason as everything in [AppContainer]: a cold start that never
 * synchronises must not pay for a database handle or a Keystore lookup. [outbox] is the one
 * exception — it owns nothing but a UUID generator and a clock, so there is nothing to defer,
 * and the measurement repository needs it on the very first save.
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
        HealthProfileSeeding(database, applicationContext.userProfileDataStore)
    }
}
