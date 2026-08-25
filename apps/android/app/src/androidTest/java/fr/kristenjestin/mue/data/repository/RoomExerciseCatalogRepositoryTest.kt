package fr.kristenjestin.mue.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.ExerciseCatalogSeed
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.repository.ExerciseCatalogRepository
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

/**
 * PRD 9.2 seen from the domain: seventeen provided exercises, no way to delete one, and a name
 * already in the catalogue reused rather than duplicated.
 */
@RunWith(AndroidJUnit4::class)
class RoomExerciseCatalogRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: ExerciseCatalogRepository

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).addCallback(ExerciseCatalogSeed.CALLBACK).build()
        repository = RoomExerciseCatalogRepository(database.exerciseCatalogDao())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun theCatalogueOpensOnTheProvidedExercises() = runTest {
        val catalogue = repository.observeCatalogue().first()

        assertEquals(17, catalogue.size)
        assertTrue(catalogue.none { it.isCustom })
    }

    @Test
    fun aProvidedExerciseArrivesFullyMapped() = runTest {
        val squat = repository.findByName("Barbell squat")

        assertEquals("Barbell squat", squat?.name)
        assertEquals(TrackingMode.WEIGHT_AND_REPS, squat?.trackingMode)
        assertEquals(EquipmentType.BARBELL, squat?.equipment)
        assertFalse(squat?.isCustom ?: true)
    }

    @Test
    fun aNameIsMatchedWhateverItsCaseAndPadding() = runTest {
        assertEquals("Deadlift", repository.findByName("  DEADLIFT ")?.name)
    }

    @Test
    fun anAbsentNameSimplyReturnsNothing() = runTest {
        assertNull(repository.findByName("Zercher squat"))
    }

    @Test
    fun aDefinitionIsFoundBackByItsOwnId() = runTest {
        val squat = repository.findByName("Barbell squat")

        assertEquals(squat, repository.findById(squat!!.id))
    }

    @Test
    fun creatingAnExistingNameReusesTheDefinition() = runTest {
        val reused = repository.findOrCreate("  bench PRESS ", TrackingMode.REPS_ONLY)

        assertEquals("Bench press", reused.name)
        assertEquals(TrackingMode.WEIGHT_AND_REPS, reused.trackingMode)
        assertFalse(reused.isCustom)
        assertEquals(17, repository.observeCatalogue().first().size)
    }

    @Test
    fun creatingANewNameAddsACustomDefinition() = runTest {
        val created = repository.findOrCreate("Zercher squat", TrackingMode.WEIGHT_AND_REPS, EquipmentType.BARBELL)

        assertEquals("Zercher squat", created.name)
        assertTrue(created.isCustom)
        assertEquals(EquipmentType.BARBELL, created.equipment)
        assertEquals(18, repository.observeCatalogue().first().size)
    }

    @Test
    fun creatingTheSameNewNameTwiceStillAddsOneDefinition() = runTest {
        val first = repository.findOrCreate("Zercher squat", TrackingMode.WEIGHT_AND_REPS)
        val second = repository.findOrCreate("zercher SQUAT", TrackingMode.DURATION)

        assertEquals(first.id, second.id)
        assertEquals(TrackingMode.WEIGHT_AND_REPS, second.trackingMode)
        assertEquals(18, repository.observeCatalogue().first().size)
    }

    /** PRD 9.2 stores the name as typed, minus the padding the fold ignores. */
    @Test
    fun aNewNameIsStoredWithoutItsSurroundingSpaces() = runTest {
        val created = repository.findOrCreate("  Zercher squat  ", TrackingMode.WEIGHT_AND_REPS)

        assertEquals("Zercher squat", created.name)
    }
}
