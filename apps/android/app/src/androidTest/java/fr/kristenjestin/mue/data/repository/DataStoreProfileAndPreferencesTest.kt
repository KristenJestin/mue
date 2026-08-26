package fr.kristenjestin.mue.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DataStoreProfileAndPreferencesTest {

    private lateinit var profileFile: File
    private lateinit var preferencesFile: File
    private lateinit var profileStore: DataStore<Preferences>
    private lateinit var preferencesStore: DataStore<Preferences>
    private lateinit var profileRepository: DataStoreUserProfileRepository
    private lateinit var preferencesRepository: DataStoreUserPreferencesRepository

    /**
     * The profile now spans two stores: the display name stays in DataStore, the height and the
     * birth date are Room rows (sync PRD 19). The assertions below are unchanged on purpose —
     * the repository's contract did not move, only where it keeps things.
     */
    private lateinit var database: MueDatabase

    @Before
    fun createStores() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        profileFile = File(context.cacheDir, "test_profile_${System.nanoTime()}.preferences_pb")
        preferencesFile = File(context.cacheDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        profileStore = PreferenceDataStoreFactory.create { profileFile }
        preferencesStore = PreferenceDataStoreFactory.create { preferencesFile }
        database = Room.inMemoryDatabaseBuilder(context, MueDatabase::class.java).build()
        profileRepository =
            DataStoreUserProfileRepository(profileStore, database.healthProfileDao())
        preferencesRepository = DataStoreUserPreferencesRepository(preferencesStore)
    }

    @After
    fun deleteStores() {
        database.close()
        profileFile.delete()
        preferencesFile.delete()
    }

    @Test
    fun anUntouchedProfileReadsAsEmpty() = runTest {
        assertEquals(UserProfile.EMPTY, profileRepository.profile.first())
    }

    @Test
    fun persistsEveryProfileField() = runTest {
        val profile = UserProfile("Kristen", 178, LocalDate.of(1990, 5, 4))

        profileRepository.save(profile)

        assertEquals(profile, profileRepository.profile.first())
    }

    @Test
    fun theBirthDateSurvivesAsAPureLocalDate() = runTest {
        val birthDate = LocalDate.of(1990, 1, 1)

        profileRepository.save(UserProfile(birthDate = birthDate))

        assertEquals(birthDate, profileRepository.profile.first().birthDate)
    }

    @Test
    fun clearingAFieldRemovesItRatherThanLeavingItStale() = runTest {
        profileRepository.save(UserProfile("Kristen", 178, LocalDate.of(1990, 5, 4)))

        profileRepository.save(UserProfile.EMPTY)

        val stored = profileRepository.profile.first()
        assertNull(stored.displayName)
        assertNull(stored.heightCm)
        assertNull(stored.birthDate)
    }

    @Test
    fun aBlankDisplayNameIsStoredAsNoName() = runTest {
        profileRepository.save(UserProfile(displayName = "   "))

        assertNull(profileRepository.profile.first().displayName)
    }

    @Test
    fun anOverlongDisplayNameIsCappedOnTheWayIn() = runTest {
        profileRepository.save(UserProfile(displayName = "x".repeat(80)))

        assertEquals(
            UserProfile.MAX_DISPLAY_NAME_LENGTH,
            profileRepository.profile.first().displayName?.length,
        )
    }

    @Test
    fun hapticsAreEnabledUntilTheUserSaysOtherwise() = runTest {
        assertTrue(preferencesRepository.preferences.first().hapticsEnabled)
    }

    @Test
    fun hapticsCanBeTurnedOffAndBackOn() = runTest {
        preferencesRepository.setHapticsEnabled(false)
        assertEquals(UserPreferences(false), preferencesRepository.preferences.first())

        preferencesRepository.setHapticsEnabled(true)
        assertEquals(UserPreferences(true), preferencesRepository.preferences.first())
    }

    /** PRD_FOOD 13.2 and FR-FOOD-010: the figures are shown until someone asks otherwise. */
    @Test
    fun energyIsShownUntilTheUserSaysOtherwise() = runTest {
        assertTrue(preferencesRepository.preferences.first().showEnergy)
    }

    @Test
    fun energyCanBeHiddenAndShownAgain() = runTest {
        preferencesRepository.setShowEnergy(false)
        assertEquals(
            UserPreferences(hapticsEnabled = true, showEnergy = false),
            preferencesRepository.preferences.first(),
        )

        preferencesRepository.setShowEnergy(true)
        assertEquals(
            UserPreferences(hapticsEnabled = true, showEnergy = true),
            preferencesRepository.preferences.first(),
        )
    }

    /**
     * The two preferences share one file and must not share one key: writing either has to leave
     * the other exactly where it was.
     */
    @Test
    fun theTwoPreferencesAreStoredUnderKeysOfTheirOwn() = runTest {
        preferencesRepository.setHapticsEnabled(false)
        preferencesRepository.setShowEnergy(false)

        assertEquals(
            UserPreferences(hapticsEnabled = false, showEnergy = false),
            preferencesRepository.preferences.first(),
        )

        preferencesRepository.setHapticsEnabled(true)

        val stored = preferencesRepository.preferences.first()
        assertTrue(stored.hapticsEnabled)
        assertEquals(false, stored.showEnergy)
    }

    /**
     * PRD_SCALE FR-SCALE-025: no permission is asked for at launch, so a fresh install has to
     * read "never asked". The default is what `ScalePermissions` turns into the system prompt
     * rather than a pointless trip to Android settings.
     */
    @Test
    fun theScalePermissionHasNotBeenAskedForOnAFreshInstall() = runTest {
        assertEquals(false, preferencesRepository.preferences.first().scalePermissionRequested)
    }

    @Test
    fun theScalePermissionRequestIsRememberedAcrossReads() = runTest {
        preferencesRepository.setScalePermissionRequested(true)

        assertEquals(true, preferencesRepository.preferences.first().scalePermissionRequested)
    }

    /**
     * The flag joined a file two visible preferences already share, so the same rule applies to
     * it: writing it may not disturb either of them, and neither may disturb it.
     */
    @Test
    fun theScalePermissionFlagKeepsAKeyOfItsOwn() = runTest {
        preferencesRepository.setHapticsEnabled(false)
        preferencesRepository.setShowEnergy(false)

        preferencesRepository.setScalePermissionRequested(true)

        assertEquals(
            UserPreferences(
                hapticsEnabled = false,
                showEnergy = false,
                scalePermissionRequested = true,
            ),
            preferencesRepository.preferences.first(),
        )

        preferencesRepository.setShowEnergy(true)

        val stored = preferencesRepository.preferences.first()
        assertTrue(stored.scalePermissionRequested)
        assertTrue(stored.showEnergy)
        assertEquals(false, stored.hapticsEnabled)
    }

    @Test
    fun theProfileAndThePreferencesDoNotShareAFile() = runTest {
        profileRepository.save(UserProfile("Kristen", 178, null))
        preferencesRepository.setHapticsEnabled(false)

        assertEquals("Kristen", profileRepository.profile.first().displayName)
        assertEquals(false, preferencesRepository.preferences.first().hapticsEnabled)
    }
}
