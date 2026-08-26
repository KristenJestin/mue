package fr.kristenjestin.mue.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device session bearer of sync PRD 9.2, encrypted by a key that never leaves the phone.
 *
 * The key is generated in `AndroidKeyStore` and used through it: on a device with a secure
 * element the raw bytes are never in the app's address space at all, and on one without,
 * extracting them still needs the platform's key blob. Only the ciphertext and its IV are
 * stored, in a DataStore of their own so no unrelated preference read touches them.
 *
 * `setUserAuthenticationRequired(false)` is deliberate and not a relaxation: a periodic
 * WorkManager sync runs with the screen locked, and a key gated on user presence would make
 * every background synchronisation fail exactly when it is meant to work.
 *
 * `androidx.security:security-crypto` is not used: `EncryptedSharedPreferences` is deprecated
 * and its Tink dependency brings a second key hierarchy for what is one string.
 *
 * Backup is off (`allowBackup="false"`) and the extraction rules are empty, which is what keeps
 * this ciphertext off Drive. It matters more now than before a server existed: the key is bound
 * to the device, so a restored ciphertext would be undecryptable — and a token that leaves the
 * phone at all is a session that could be replayed.
 */
class SyncTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * The token, or null when none is stored — or when the key is gone. A key invalidated by a
     * factory reset or a lock-screen change leaves ciphertext nobody can read; treating that as
     * "no token" makes the app ask the user to pair again, which is the only recovery there is.
     */
    suspend fun read(): String? = withContext(ioDispatcher) {
        val preferences = dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .first()
        val ciphertext = preferences[KEY_CIPHERTEXT] ?: return@withContext null
        val iv = preferences[KEY_IV] ?: return@withContext null

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey() ?: return@withContext null,
                GCMParameterSpec(TAG_LENGTH_BITS, decode(iv)),
            )
            String(cipher.doFinal(decode(ciphertext)), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            clear()
            null
        }
    }

    suspend fun write(token: String) {
        withContext(ioDispatcher) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, orCreateKey())
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            dataStore.edit { preferences ->
                preferences[KEY_CIPHERTEXT] = encode(ciphertext)
                preferences[KEY_IV] = encode(iv)
            }
        }
    }

    /** `Disconnect server` (PRD 9.3). The key goes too, so the stored bytes become unreadable. */
    suspend fun clear() {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences.remove(KEY_CIPHERTEXT)
                preferences.remove(KEY_IV)
            }
            runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        }
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun orCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                // GCM must never reuse an IV with the same key; letting the platform draw it
                // is what guarantees that, and forbidding a caller-supplied one is what stops
                // a future refactor from breaking it silently.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "mue.sync.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128

        val KEY_CIPHERTEXT = stringPreferencesKey("session_token_ciphertext")
        val KEY_IV = stringPreferencesKey("session_token_iv")
    }
}
