package fr.kristenjestin.mue.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.TrackingMode
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
import java.util.Locale

/**
 * PRD 9.2: the catalogue is installed with the database, and a name already in it is reused
 * rather than duplicated.
 *
 * The fresh-install path is what this file exercises — `Callback.onCreate` on a database Room has
 * just created — while `ActivityMigrationTest` covers the upgrade half of the same seed.
 */
@RunWith(AndroidJUnit4::class)
class ExerciseCatalogDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var dao: ExerciseCatalogDao
    private lateinit var activityDao: ActivityDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).addCallback(ExerciseCatalogSeed.CALLBACK).build()
        dao = database.exerciseCatalogDao()
        activityDao = database.activityDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aFreshInstallArrivesWithTheSeventeenProvidedExercises() = runTest {
        assertEquals(17, dao.count())
        assertEquals(ExerciseCatalogSeed.DEFINITIONS.size, dao.count())
    }

    @Test
    fun theProvidedExercisesAreNotMarkedCustom() = runTest {
        dao.observeCatalogue().first().forEach { row ->
            assertFalse("${row.definition.name} came in as custom", row.definition.isCustom)
        }
    }

    @Test
    fun aProvidedExerciseKeepsItsModeAndItsEquipment() = runTest {
        val plank = dao.findByFoldedName("plank")?.toDomain()

        assertEquals("Plank", plank?.name)
        assertEquals(TrackingMode.DURATION, plank?.trackingMode)
        assertEquals(EquipmentType.BODYWEIGHT, plank?.equipment)
    }

    @Test
    fun aNameNotInTheCatalogueIsSimplyAbsent() = runTest {
        assertNull(dao.findByFoldedName("zercher squat"))
    }

    @Test
    fun aDefinitionIsFoundByItsWrittenId() = runTest {
        val seeded = ExerciseCatalogSeed.DEFINITIONS.first { it.name == "Deadlift" }

        assertEquals("Deadlift", dao.findById(seeded.id.value)?.name)
    }

    /** PRD 9.2: case does not create a twin. */
    @Test
    fun anExistingNameIsReusedWhateverItsCase() = runTest {
        val reused = dao.findOrCreate(candidate("BENCH PRESS"))

        assertEquals("Bench press", reused.name)
        assertEquals(17, dao.count())
    }

    /** PRD 9.2: neither do surrounding spaces. */
    @Test
    fun anExistingNameIsReusedWhateverItsPadding() = runTest {
        val reused = dao.findOrCreate(candidate("   bench press "))

        assertEquals("Bench press", reused.name)
        assertEquals(17, dao.count())
    }

    /** A reused definition keeps its own mode: the candidate's is thrown away with it. */
    @Test
    fun aReusedDefinitionKeepsTheModeItAlreadyHad() = runTest {
        val reused = dao.findOrCreate(candidate("bench press", TrackingMode.REPS_ONLY))

        assertEquals(TrackingMode.WEIGHT_AND_REPS.id, reused.trackingMode)
        assertFalse(reused.isCustom)
    }

    /** An accented name folds by case like any other; nothing here strips a diacritic. */
    @Test
    fun anAccentedNameFoldsByCaseAlone() = runTest {
        val created = dao.findOrCreate(candidate("Développé couché"))
        val reused = dao.findOrCreate(candidate("DÉVELOPPÉ COUCHÉ"))

        assertEquals(created.id, reused.id)
        assertEquals(18, dao.count())
        assertNotNull(dao.findByFoldedName("développé couché"))
    }

    /**
     * The reason every fold goes through `Locale.ROOT`: under a Turkish default locale
     * `"INCLINE".lowercase()` yields `"ıncline"`, and the same exercise would then be two.
     */
    @Test
    fun aNameFoldsTheSameWayOnATurkishPhone() = runTest {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val created = dao.findOrCreate(candidate("Incline press"))
            val reused = dao.findOrCreate(candidate("INCLINE PRESS"))

            assertEquals(created.id, reused.id)
            assertEquals(18, dao.count())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun aTrulyNewNameBecomesACustomDefinition() = runTest {
        val created = dao.findOrCreate(candidate("Zercher squat", TrackingMode.WEIGHT_AND_REPS))

        assertEquals("Zercher squat", created.name)
        assertTrue(created.isCustom)
        assertEquals(18, dao.count())
    }

    @Test
    fun aSecondRowWithTheSameFoldedNameIsIgnored() = runTest {
        dao.insert(candidate("Bench press"))

        assertEquals(17, dao.count())
    }

    /** PRD FR-ACTIVITY-009: `Recent & common` puts what was practised most recently on top. */
    @Test
    fun theCatalogueListsRecentlyUsedExercisesFirst() = runTest {
        practise("plank-session", "2026-08-10", "Plank")
        practise("bench-session", "2026-08-18", "Bench press")

        val names = dao.observeCatalogue().first().map { it.definition.name }

        assertEquals("Bench press", names[0])
        assertEquals("Plank", names[1])
    }

    @Test
    fun theRestOfTheCatalogueFollowsByName() = runTest {
        practise("plank-session", "2026-08-10", "Plank")

        val names = dao.observeCatalogue().first().map { it.definition.name }

        assertEquals("Plank", names.first())
        assertEquals(names.drop(1).sorted(), names.drop(1))
    }

    @Test
    fun anUntouchedCatalogueIsOrderedByNameAlone() = runTest {
        val names = dao.observeCatalogue().first().map { it.definition.name }

        assertEquals(names.sorted(), names)
        assertEquals("Barbell row", names.first())
    }

    private suspend fun practise(sessionId: String, startedOn: String, exerciseName: String) {
        val definition = ExerciseCatalogSeed.DEFINITIONS.first { it.name == exerciseName }
        val exerciseId = "$sessionId-exercise"
        activityDao.saveDetail(
            session = ActivitySessionEntity(
                id = sessionId,
                movement = "strength_training",
                customMovementName = null,
                environment = "indoor",
                startedOn = startedOn,
                startedAtTime = null,
                durationSeconds = 1_800,
                perceivedEffort = null,
                notes = null,
                source = "manual",
                createdAt = 1L,
                updatedAt = 1L,
            ),
            metrics = emptyList(),
            equipment = emptyList(),
            exercises = listOf(
                StrengthExerciseEntity(exerciseId, sessionId, definition.id.value, 0, null),
            ),
            sets = listOf(
                StrengthSetEntity("$exerciseId-0", exerciseId, 0, "working", 8, null, 60, null),
            ),
        )
    }

    private fun candidate(
        name: String,
        trackingMode: TrackingMode = TrackingMode.WEIGHT_AND_REPS,
    ): ExerciseDefinitionEntity = ExerciseDefinition(
        id = ExerciseDefinitionId.random(),
        name = name.trim(),
        trackingMode = trackingMode,
        equipment = null,
        isCustom = true,
    ).toEntity()
}
