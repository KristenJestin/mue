package fr.kristenjestin.mue.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * The height and the birth date typed before version 5 have to arrive in Room, and exactly once.
 *
 * This is the step that could not be a Room `Migration`: a `SupportSQLiteDatabase` cannot read a
 * Preferences file. It is guarded twice — a preference outside the transaction and the
 * `sync_state` flag inside it — so the interesting cases are the second run, the phone that has
 * nothing to copy, and the cold start that must not open the database at all.
 */
@RunWith(AndroidJUnit4::class)
class HealthProfileSeedingTest {

    private lateinit var file: File
    private lateinit var profileStore: DataStore<Preferences>
    private lateinit var database: MueDatabase
    private lateinit var seeding: HealthProfileSeeding

    @Before
    fun createStores() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "test_seed_profile_${System.nanoTime()}.preferences_pb")
        profileStore = PreferenceDataStoreFactory.create { file }
        database = Room.inMemoryDatabaseBuilder(context, MueDatabase::class.java).build()
        seeding = HealthProfileSeeding({ database }, profileStore)
    }

    @After
    fun closeStores() {
        database.close()
        file.delete()
    }

    @Test
    fun theProfileTypedBeforeVersionFiveArrivesInRoom() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")

        seeding.seedOnce()

        val stored = database.healthProfileDao().get()
        assertEquals(HealthProfileEntity.ROW_ID, stored?.id)
        assertEquals(178, stored?.heightCm)
        assertEquals("1990-05-04", stored?.birthDate)
        assertEquals(true, database.syncDao().syncState()?.profileSeeded)
    }

    /** Half a profile is still a profile: either field alone has to survive the move. */
    @Test
    fun aHeightWithoutABirthDateIsSeededOnItsOwn() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = null)

        seeding.seedOnce()

        assertEquals(178, database.healthProfileDao().get()?.heightCm)
        assertNull(database.healthProfileDao().get()?.birthDate)
    }

    /**
     * The second run is what the flag is for. If it seeded again it would put the pre-upgrade
     * height back over one the user has since corrected — a silent, un-undoable regression.
     */
    @Test
    fun aSecondRunDoesNotUndoALaterEdit() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")
        seeding.seedOnce()

        database.healthProfileDao().upsert(HealthProfileEntity(heightCm = 181, birthDate = null))
        seeding.seedOnce()

        assertEquals(181, database.healthProfileDao().get()?.heightCm)
        assertNull(database.healthProfileDao().get()?.birthDate)
    }

    /** A phone with nothing to copy still records that the copy happened, so it never repeats. */
    @Test
    fun anEmptyPreferencesFileSeedsNoRowAndStillSetsTheFlag() = runTest {
        seeding.seedOnce()

        assertNull(database.healthProfileDao().get())
        assertEquals(true, database.syncDao().syncState()?.profileSeeded)
    }

    /**
     * The old keys are left in place on purpose. Clearing them is a second write to a store that
     * cannot join this transaction, and a crash between the two would destroy the only copy of a
     * height the user typed before the upgrade.
     */
    @Test
    fun theLegacyKeysAreLeftWhereTheyWere() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")

        seeding.seedOnce()

        val preferences = profileStore.data.first()
        assertEquals(178, preferences[DataStoreUserProfileRepository.KEY_HEIGHT_CM])
        assertEquals("1990-05-04", preferences[DataStoreUserProfileRepository.KEY_BIRTH_DATE])
    }

    /** What the user sees afterwards: one profile, unchanged, assembled from two stores. */
    @Test
    fun theSeededProfileReadsBackThroughTheRepository() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")
        profileStore.edit { it[DataStoreUserProfileRepository.KEY_DISPLAY_NAME] = "Kristen" }

        seeding.seedOnce()

        val repository =
            DataStoreUserProfileRepository(profileStore, database.healthProfileDao())
        assertEquals(
            UserProfile("Kristen", 178, LocalDate.of(1990, 5, 4)),
            repository.profile.first(),
        )
    }

    /** The seeding creates the `sync_state` row it needs; it must not create a second one. */
    @Test
    fun theSyncStateRowStaysSingular() = runTest {
        seeding.seedOnce()
        seeding.seedOnce()

        val rows = database.openHelper.writableDatabase
            .query("SELECT id FROM sync_state")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) } }

        assertEquals(listOf(0), rows)
        assertTrue(database.syncDao().syncState()?.profileSeeded == true)
    }

    /**
     * Gap 4, and the only assertion that can show it.
     *
     * `MueApplication.onCreate` calls `seedOnce` at every cold start. Reading the guard from
     * `sync_state` meant **opening Room to learn that there was nothing to do** — a disk open, a
     * schema check and a migration check on the startup path of every launch, for a task that
     * runs once ever, in a container whose whole contract is that it is lazy.
     *
     * A Room handle is not open until something queries it, so a fresh handle that is still
     * closed afterwards is proof that nothing did.
     */
    @Test
    fun aStartAfterTheFirstNeverOpensTheDatabase() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")
        seeding.seedOnce()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val untouched = Room.inMemoryDatabaseBuilder(context, MueDatabase::class.java).build()
        try {
            HealthProfileSeeding({ untouched }, profileStore).seedOnce()

            assertFalse(
                "the guard must be read from DataStore, not from Room",
                untouched.isOpen,
            )
        } finally {
            untouched.close()
        }
    }

    /**
     * The upgrade path: a phone that seeded under the previous build has the Room flag and not
     * the preference. It must not copy anything a second time, and it must take the fast path
     * from then on.
     */
    @Test
    fun aPhoneThatSeededBeforeThePreferenceExistedIsNotSeededAgain() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")
        database.syncDao().insertSyncStateIfAbsent(
            fr.kristenjestin.mue.data.local.database.SyncStateEntity()
        )
        database.syncDao().markProfileSeeded()
        database.healthProfileDao().upsert(HealthProfileEntity(heightCm = 181, birthDate = null))

        seeding.seedOnce()

        assertEquals(181, database.healthProfileDao().get()?.heightCm)
        assertEquals(true, profileStore.data.first()[HealthProfileSeeding.KEY_SEEDED])
    }

    /** The preference is written after the transaction, so a seeded phone reads it as done. */
    @Test
    fun aSuccessfulSeedRecordsThePreferenceAsWellAsTheRoomFlag() = runTest {
        writeLegacyProfile(heightCm = 178, birthDate = "1990-05-04")

        seeding.seedOnce()

        assertEquals(true, profileStore.data.first()[HealthProfileSeeding.KEY_SEEDED])
        assertEquals(true, database.syncDao().syncState()?.profileSeeded)
    }

    private suspend fun writeLegacyProfile(heightCm: Int?, birthDate: String?) {
        profileStore.edit { preferences ->
            heightCm?.let { preferences[DataStoreUserProfileRepository.KEY_HEIGHT_CM] = it }
            birthDate?.let { preferences[DataStoreUserProfileRepository.KEY_BIRTH_DATE] = it }
        }
    }
}
