package fr.kristenjestin.mue.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sync PRD 9.2 and 16: the device session bearer is protected by Android Keystore.
 *
 * The round trip alone would pass on a store that wrote the token in clear, so the file's own
 * contents are asserted too — that is the part a refactor can break without any other test
 * noticing.
 */
@RunWith(AndroidJUnit4::class)
class SyncTokenStoreTest {

    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SyncTokenStore

    private val token = "mue_session_9f2c41d8e7b04a6f"

    @Before
    fun createStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "test_sync_token_${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { file }
        store = SyncTokenStore(dataStore)
    }

    @After
    fun deleteStore() = runTest {
        store.clear()
        file.delete()
    }

    @Test
    fun anUntouchedStoreHoldsNoToken() = runTest {
        assertNull(store.read())
    }

    @Test
    fun theTokenSurvivesARoundTrip() = runTest {
        store.write(token)

        assertEquals(token, store.read())
    }

    /** The point of the exercise: what lands on disk must not be the token. */
    @Test
    fun whatIsStoredIsCiphertextAndAnIv() = runTest {
        store.write(token)

        val stored = dataStore.data.first().asMap().mapKeys { it.key.name }
        assertEquals(setOf("session_token_ciphertext", "session_token_iv"), stored.keys)
        stored.values.forEach { value ->
            assertFalse("the token must not be readable on disk", value.toString().contains(token))
        }
        assertNotNull(stored["session_token_iv"])
    }

    /**
     * GCM must never reuse an IV with the same key. `setRandomizedEncryptionRequired(true)` is
     * what guarantees it, and writing the same token twice is how that shows.
     */
    @Test
    fun twoWritesOfTheSameTokenProduceDifferentCiphertext() = runTest {
        store.write(token)
        val first = dataStore.data.first().asMap().mapKeys { it.key.name }

        store.write(token)
        val second = dataStore.data.first().asMap().mapKeys { it.key.name }

        assertTrue(first["session_token_iv"] != second["session_token_iv"])
        assertTrue(first["session_token_ciphertext"] != second["session_token_ciphertext"])
        assertEquals(token, store.read())
    }

    /** `Disconnect server` (PRD 9.3) leaves nothing behind for a later session to reuse. */
    @Test
    fun clearingLeavesNothingToRead() = runTest {
        store.write(token)

        store.clear()

        assertNull(store.read())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    /** Pairing again after a disconnect has to work, which needs the key to be regenerated. */
    @Test
    fun aNewTokenCanBeStoredAfterAClear() = runTest {
        store.write(token)
        store.clear()

        store.write("mue_session_second")

        assertEquals("mue_session_second", store.read())
    }
}
