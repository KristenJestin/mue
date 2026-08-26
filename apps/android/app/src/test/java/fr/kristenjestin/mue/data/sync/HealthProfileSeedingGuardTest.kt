package fr.kristenjestin.mue.data.sync

import androidx.datastore.preferences.core.edit
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.datastore.FakePreferencesDataStore
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gap 4, as far as the JVM can take it: **the cold-start guard is a preference, not a Room row.**
 *
 * `MueApplication.onCreate` calls `seedOnce` at every cold start for the rest of the app's life,
 * for a task that runs exactly once ever. While the guard lived in `sync_state.profile_seeded`,
 * answering "is there anything to do" meant **opening the database to find out there was not** —
 * a disk open, a schema check and a migration check on the startup path of every launch, in a
 * container whose entire contract is that a launch which never touches Room does not pay for it.
 *
 * `HealthProfileSeeding` takes its database as a provider precisely so this is observable off a
 * device: the fast path must not so much as ask for one. A provider that throws turns "it did not
 * open the file" — which needs an emulator to see — into "it did not ask", which is stronger and
 * needs nothing.
 *
 * The instrumented `HealthProfileSeedingTest.aStartAfterTheFirstNeverOpensTheDatabase` asserts
 * the file itself stays closed, and the rest of the seeding's behaviour lives there too.
 */
class HealthProfileSeedingGuardTest {

    private val preferences = FakePreferencesDataStore()

    private var databaseRequests = 0

    /** Asking for the database at all is the failure, so asking for it throws. */
    private val refusedDatabase: () -> MueDatabase = {
        databaseRequests++
        throw AssertionError("the fast path must not ask for a database handle")
    }

    private fun seeding() =
        HealthProfileSeeding(refusedDatabase, preferences, Dispatchers.Unconfined)

    @Test
    fun aStartAfterTheFirstNeverAsksForTheDatabase() = runTest {
        preferences.edit { it[HealthProfileSeeding.KEY_SEEDED] = true }
        val writesBefore = preferences.writes

        seeding().seedOnce()

        assertEquals(0, databaseRequests)
        assertEquals(
            writesBefore,
            preferences.writes,
            "the fast path writes nothing either: it reads one preference and returns",
        )
    }

    /**
     * The other half, without which the test above would pass on a `seedOnce` that did nothing at
     * all. A phone that has not seeded yet *does* reach for the database, on that one launch.
     */
    @Test
    fun aFirstStartDoesAskForTheDatabase() = runTest {
        val failure = runCatching { seeding().seedOnce() }.exceptionOrNull()

        assertTrue(failure is AssertionError, "expected the refused provider to be called: $failure")
        assertEquals(1, databaseRequests)
    }

    /**
     * And the legacy keys are read from the same file the guard lives in, so the fast path costs
     * the read it was going to make anyway rather than opening a store of its own.
     */
    @Test
    fun theGuardSharesTheFileTheLegacyKeysAreIn() = runTest {
        preferences.edit {
            it[DataStoreUserProfileRepository.KEY_HEIGHT_CM] = 178
            it[HealthProfileSeeding.KEY_SEEDED] = true
        }

        seeding().seedOnce()

        assertEquals(0, databaseRequests)
        assertEquals(
            178,
            preferences.current()[DataStoreUserProfileRepository.KEY_HEIGHT_CM],
            "and the pre-upgrade height is left where it was",
        )
    }
}
