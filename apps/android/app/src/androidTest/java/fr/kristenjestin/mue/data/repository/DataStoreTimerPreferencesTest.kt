package fr.kristenjestin.mue.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.model.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FR-TIMER-012: a persisted boolean is the only correct implementation.
 *
 * `shouldShowRequestPermissionRationale` answers `false` both before the first request and after
 * a permanent denial, so it cannot tell "never asked" from "refused for good" — which is the one
 * thing this flag exists to remember.
 */
@RunWith(AndroidJUnit4::class)
class DataStoreTimerPreferencesTest {

    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: DataStoreTimerPreferencesRepository
    private lateinit var shippedPreferences: DataStoreUserPreferencesRepository

    @Before
    fun createStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "test_timer_prefs_${System.nanoTime()}.preferences_pb")
        store = PreferenceDataStoreFactory.create { file }
        repository = DataStoreTimerPreferencesRepository(store)
        shippedPreferences = DataStoreUserPreferencesRepository(store)
    }

    @After
    fun deleteStore() {
        file.delete()
    }

    /** Nothing has been asked yet, which is what an untouched store has to say. */
    @Test
    fun anUntouchedStoreHasAskedForNothing() = runTest {
        assertFalse(repository.notificationPermissionRequested.first())
    }

    @Test
    fun theFlagSurvivesTheWrite() = runTest {
        repository.setNotificationPermissionRequested(true)

        assertTrue(repository.notificationPermissionRequested.first())
    }

    /** A refusal is remembered, and forgetting it again is an ordinary write. */
    @Test
    fun theFlagCanBeClearedAgain() = runTest {
        repository.setNotificationPermissionRequested(true)
        repository.setNotificationPermissionRequested(false)

        assertFalse(repository.notificationPermissionRequested.first())
    }

    /**
     * The timer's key sits in the shipped preferences file and must not disturb what is already
     * there — the two repositories share a store precisely so no second file is created.
     */
    @Test
    fun theTimerFlagLeavesTheShippedPreferencesAlone() = runTest {
        shippedPreferences.setHapticsEnabled(false)

        repository.setNotificationPermissionRequested(true)

        assertEquals(
            UserPreferences(hapticsEnabled = false),
            shippedPreferences.preferences.first(),
        )
        assertTrue(repository.notificationPermissionRequested.first())
    }
}
