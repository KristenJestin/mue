package fr.kristenjestin.mue.data.local.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.model.TrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/**
 * The three projections of PRD 11 and the constraints of PRD 16.3, read through the DAO.
 *
 * Nothing here mentions a tracking mode when counting: the save path already dropped every
 * invalid set, so a stored set is a valid set and `COUNT(*)` is the answer PRD 11.2 asks for.
 */
@RunWith(AndroidJUnit4::class)
class ActivityDaoTest {

    private lateinit var database: MueDatabase
    private lateinit var dao: ActivityDao

    private val benchPress = ExerciseCatalogSeed.DEFINITIONS.first { it.name == "Bench press" }
    private val plank = ExerciseCatalogSeed.DEFINITIONS.first { it.name == "Plank" }

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).addCallback(ExerciseCatalogSeed.CALLBACK).build()
        dao = database.activityDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aSessionComesBackWithEverythingItWasWrittenWith() = runTest {
        writeStrengthSession()

        val rows = dao.findDetailRows(SESSION)

        assertNotNull(rows)
        assertEquals("2026-08-20", rows?.session?.startedOn)
        assertEquals(1, rows?.metrics?.size)
        assertEquals(1, rows?.equipment?.size)
        assertEquals(1, rows?.exercises?.size)
        assertEquals("Bench press", rows?.exercises?.first()?.definition?.name)
        assertEquals(2, rows?.sets?.size)
    }

    @Test
    fun anUnknownSessionHasNoDetailAtAll() = runTest {
        assertNull(dao.findDetailRows("no-such-session"))
    }

    /** Saving an edited session must not leave the metric or the set the user removed. */
    @Test
    fun savingAgainReplacesTheChildrenRatherThanAddingToThem() = runTest {
        writeStrengthSession()

        dao.saveDetail(
            session = session(SESSION, "2026-08-20"),
            metrics = emptyList(),
            equipment = emptyList(),
            exercises = emptyList(),
            sets = emptyList(),
        )

        val rows = dao.findDetailRows(SESSION)
        assertEquals(0, rows?.metrics?.size)
        assertEquals(0, rows?.equipment?.size)
        assertEquals(0, rows?.exercises?.size)
        assertEquals(0, rows?.sets?.size)
    }

    /** An edit updates a session; it does not make it new. */
    @Test
    fun anEditKeepsTheOriginalCreationStamp() = runTest {
        dao.saveDetail(session(SESSION, "2026-08-20", createdAt = 1_000L), emptyList(), emptyList(), emptyList(), emptyList())
        dao.saveDetail(session(SESSION, "2026-08-21", createdAt = 9_000L, updatedAt = 9_000L), emptyList(), emptyList(), emptyList(), emptyList())

        val stored = dao.findSession(SESSION)
        assertEquals(1_000L, stored?.createdAt)
        assertEquals(9_000L, stored?.updatedAt)
        assertEquals("2026-08-21", stored?.startedOn)
    }

    @Test
    fun theSummaryCarriesTheDistanceAndTheEnergyItWasGiven() = runTest {
        dao.saveDetail(
            session = session(SESSION, "2026-08-20"),
            metrics = listOf(
                ActivityMetricEntity(SESSION, "distance", 4_200, "manual"),
                ActivityMetricEntity(SESSION, "estimated_energy", 280, "equipment"),
                ActivityMetricEntity(SESSION, "incline", 25, "equipment"),
            ),
            equipment = emptyList(),
            exercises = emptyList(),
            sets = emptyList(),
        )

        val summary = dao.observeSummaries(null, null, NO_LIMIT).first().single().toDomain()

        assertEquals(4_200, summary.distanceMetres)
        assertEquals(280, summary.estimatedEnergyKcal)
        assertNull("a walk reports no set count", summary.validSetCount)
    }

    @Test
    fun theSummaryCountsEveryStoredSet() = runTest {
        writeStrengthSession()

        val summary = dao.observeSummaries(null, null, NO_LIMIT).first().single().toDomain()

        assertEquals(2, summary.validSetCount)
        assertNull(summary.distanceMetres)
    }

    /** PRD 11.1 rule 2: a single titling machine names the whole session. */
    @Test
    fun aLoneTreadmillTitlesTheSession() = runTest {
        dao.saveDetail(
            session = session(SESSION, "2026-08-20", movement = "walking", environment = "indoor"),
            metrics = emptyList(),
            equipment = listOf(equipment(EQUIPMENT, "treadmill", 0)),
            exercises = emptyList(),
            sets = emptyList(),
        )

        assertEquals(
            "Treadmill walk",
            dao.observeSummaries(null, null, NO_LIMIT).first().single().toDomain().label,
        )
    }

    /** Two pieces of gear describe nothing on their own, so the environment takes the label back. */
    @Test
    fun aSecondPieceOfGearGivesTheLabelBackToTheEnvironment() = runTest {
        dao.saveDetail(
            session = session(SESSION, "2026-08-20", movement = "walking", environment = "indoor"),
            metrics = emptyList(),
            equipment = listOf(
                equipment(EQUIPMENT, "treadmill", 0),
                equipment(OTHER_EQUIPMENT, "dumbbells", 1),
            ),
            exercises = emptyList(),
            sets = emptyList(),
        )

        assertEquals(
            "Indoor walk",
            dao.observeSummaries(null, null, NO_LIMIT).first().single().toDomain().label,
        )
    }

    @Test
    fun theMostRecentDayComesFirst() = runTest {
        writeBare("a", "2026-08-18")
        writeBare("b", "2026-08-20")
        writeBare("c", "2026-08-19")

        assertEquals(listOf("b", "c", "a"), summaryIds())
    }

    /** PRD's same-day rule: a session with a time sits above one without. */
    @Test
    fun aTimedSessionComesBeforeAnUntimedOneOnTheSameDay() = runTest {
        writeBare("untimed", "2026-08-20", startedAtTime = null)
        writeBare("morning", "2026-08-20", startedAtTime = "07:15")
        writeBare("evening", "2026-08-20", startedAtTime = "18:40")

        assertEquals(listOf("evening", "morning", "untimed"), summaryIds())
    }

    /** The stable final tiebreak: two identical sessions never swap places between two reads. */
    @Test
    fun twoUntimedSessionsOfTheSameDayFallBackOnTheirWriteTime() = runTest {
        writeBare("first", "2026-08-20", createdAt = 100L)
        writeBare("second", "2026-08-20", createdAt = 200L)

        assertEquals(listOf("second", "first"), summaryIds())
        assertEquals(listOf("second", "first"), summaryIds())
    }

    @Test
    fun theLimitKeepsOnlyTheMostRecentSessions() = runTest {
        (1..8).forEach { day -> writeBare("day-$day", "2026-08-%02d".format(day)) }

        val recent = dao.observeSummaries(null, null, 5).first()

        assertEquals(5, recent.size)
        assertEquals(listOf("day-8", "day-7", "day-6", "day-5", "day-4"), recent.map { it.id })
    }

    /** What the weekly card reads: the sessions of one Monday-to-Sunday window and nothing else. */
    @Test
    fun theWindowKeepsOnlyTheSessionsInsideIt() = runTest {
        writeBare("before", "2026-08-16")
        writeBare("monday", "2026-08-17")
        writeBare("sunday", "2026-08-23")
        writeBare("after", "2026-08-24")

        val week = dao.observeSummaries("2026-08-17", "2026-08-23", NO_LIMIT).first()

        assertEquals(listOf("sunday", "monday"), week.map { it.id })
    }

    /** The weekly bars add durations up, so the window has to hand them back untouched. */
    @Test
    fun theWindowKeepsEachSessionDuration() = runTest {
        writeBare("monday", "2026-08-17", durationSeconds = 1_800)
        writeBare("tuesday", "2026-08-18", durationSeconds = 2_700)

        val week = dao.observeSummaries("2026-08-17", "2026-08-23", NO_LIMIT).first().map { it.toDomain() }

        assertEquals(4_500, week.sumOf { it.duration.seconds })
        assertEquals(LocalDate.of(2026, 8, 18), week.first().startedOn)
    }

    @Test
    fun aSummaryKeepsTheStartTimeToTheMinute() = runTest {
        writeBare("timed", "2026-08-20", startedAtTime = "07:05")

        assertEquals(
            LocalTime.of(7, 5),
            dao.observeSummaries(null, null, NO_LIMIT).first().single().toDomain().startedAtTime,
        )
    }

    @Test
    fun theSessionCountFollowsTheTable() = runTest {
        assertEquals(0, dao.observeSessionCount().first())

        writeBare("a", "2026-08-20")
        writeBare("b", "2026-08-21")

        assertEquals(2, dao.observeSessionCount().first())
    }

    /** PRD 11.4: the last set of the most recent session that used the exercise. */
    @Test
    fun theLastPerformanceIsTheLastSetOfTheMostRecentSession() = runTest {
        writeBenchPress("old", "2026-08-10", reps = listOf(5, 6))
        writeBenchPress("recent", "2026-08-18", reps = listOf(8, 10))

        val last = dao.findLastPerformance(benchPress.id.value, null)?.toDomain()

        assertEquals(LocalDate.of(2026, 8, 18), last?.performedOn)
        assertEquals(10, last?.set?.repetitions)
        assertEquals(TrackingMode.WEIGHT_AND_REPS, last?.trackingMode)
    }

    /** The session being edited never quotes itself. */
    @Test
    fun theExcludedSessionIsSkipped() = runTest {
        writeBenchPress("old", "2026-08-10", reps = listOf(5, 6))
        writeBenchPress("current", "2026-08-18", reps = listOf(8, 10))

        val last = dao.findLastPerformance(benchPress.id.value, "current")?.toDomain()

        assertEquals(LocalDate.of(2026, 8, 10), last?.performedOn)
        assertEquals(6, last?.set?.repetitions)
    }

    @Test
    fun anExerciseNeverPractisedHasNoLastPerformance() = runTest {
        writeBenchPress("old", "2026-08-10", reps = listOf(5))

        assertNull(dao.findLastPerformance(plank.id.value, null))
    }

    @Test
    fun theOnlySessionThatUsedTheExerciseCanBeExcludedAway() = runTest {
        writeBenchPress("only", "2026-08-10", reps = listOf(5))

        assertNull(dao.findLastPerformance(benchPress.id.value, "only"))
    }

    /** A load is what PRD 11.4 renders as `60 kg × 8`, so it has to survive the projection. */
    @Test
    fun theLastPerformanceCarriesItsLoad() = runTest {
        writeBenchPress("recent", "2026-08-18", reps = listOf(8), loadGrams = 62_500)

        val last = dao.findLastPerformance(benchPress.id.value, null)?.toDomain()

        assertEquals(62_500, last?.set?.load?.grams)
    }

    /** The mode travels with the set, so a hold reads as a duration and not as repetitions. */
    @Test
    fun theLastPerformanceCarriesTheModeItMustBeReadWith() = runTest {
        dao.saveDetail(
            session = session(SESSION, "2026-08-18"),
            metrics = emptyList(),
            equipment = emptyList(),
            exercises = listOf(StrengthExerciseEntity(EXERCISE, SESSION, plank.id.value, 0, null)),
            sets = listOf(StrengthSetEntity("s1", EXERCISE, 0, "working", null, null, 90, null)),
        )

        val last = dao.findLastPerformance(plank.id.value, null)?.toDomain()

        assertEquals(TrackingMode.DURATION, last?.trackingMode)
        assertEquals(90, last?.set?.duration?.seconds)
        assertNull(last?.set?.repetitions)
    }

    /**
     * PRD FR-ACTIVITY-008 in SQLite rather than in the UI. The fold column is `NOT NULL DEFAULT ''`
     * exactly so this collides: a nullable one would make every unnamed row distinct.
     */
    @Test
    fun aSessionCannotCarryTheSameEquipmentTwice() = runTest {
        writeBare(SESSION, "2026-08-20")
        dao.insertEquipment(listOf(equipment(EQUIPMENT, "treadmill", 0)))

        val error = runCatching {
            dao.insertEquipment(listOf(equipment(OTHER_EQUIPMENT, "treadmill", 1)))
        }.exceptionOrNull()

        assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
    }

    /** Two `other` machines with different names are two different pieces of gear, though. */
    @Test
    fun twoFreelyNamedPiecesOfGearAreAllowedOnOneSession() = runTest {
        writeBare(SESSION, "2026-08-20")

        dao.insertEquipment(
            listOf(
                equipment(EQUIPMENT, "other", 0, customName = "Sled", folded = "sled"),
                equipment(OTHER_EQUIPMENT, "other", 1, customName = "Ropes", folded = "ropes"),
            )
        )

        assertEquals(2, dao.findEquipment(SESSION).size)
    }

    /** PRD 8.3: a session never carries two measurements of the same kind. */
    @Test
    fun aSessionCannotCarryTwoMetricsOfTheSameKind() = runTest {
        writeBare(SESSION, "2026-08-20")
        dao.insertMetrics(listOf(ActivityMetricEntity(SESSION, "distance", 4_200, "manual")))

        val error = runCatching {
            dao.insertMetrics(listOf(ActivityMetricEntity(SESSION, "distance", 5_000, "manual")))
        }.exceptionOrNull()

        assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
    }

    /** PRD FR-ACTIVITY-011: everything attached to a session goes with it. */
    @Test
    fun deletingASessionTakesItsChildrenWithIt() = runTest {
        writeStrengthSession()

        dao.deleteSession(SESSION)

        val raw = database.openHelper.writableDatabase
        assertEquals(0, raw.countOf("activity_sessions"))
        assertEquals(0, raw.countOf("activity_metrics"))
        assertEquals(0, raw.countOf("session_equipment"))
        assertEquals(0, raw.countOf("strength_exercises"))
        assertEquals(0, raw.countOf("strength_sets"))
    }

    @Test
    fun deletingASessionKeepsTheDefinitionsItNamed() = runTest {
        writeStrengthSession()

        dao.deleteSession(SESSION)

        assertEquals(
            ExerciseCatalogSeed.DEFINITIONS.size,
            database.exerciseCatalogDao().count(),
        )
    }

    private suspend fun summaryIds(): List<String> =
        dao.observeSummaries(null, null, NO_LIMIT).first().map { it.id }

    private suspend fun writeBare(
        id: String,
        startedOn: String,
        startedAtTime: String? = null,
        createdAt: Long = 1L,
        durationSeconds: Int = 1_800,
    ) {
        dao.saveDetail(
            session = session(
                id = id,
                startedOn = startedOn,
                startedAtTime = startedAtTime,
                createdAt = createdAt,
                durationSeconds = durationSeconds,
            ),
            metrics = emptyList(),
            equipment = emptyList(),
            exercises = emptyList(),
            sets = emptyList(),
        )
    }

    private suspend fun writeStrengthSession() {
        dao.saveDetail(
            session = session(SESSION, "2026-08-20", movement = "strength_training"),
            metrics = listOf(ActivityMetricEntity(SESSION, "estimated_energy", 310, "manual")),
            equipment = listOf(equipment(EQUIPMENT, "barbell", 0)),
            exercises = listOf(
                StrengthExerciseEntity(EXERCISE, SESSION, benchPress.id.value, 0, "felt heavy"),
            ),
            sets = listOf(
                StrengthSetEntity("set-1", EXERCISE, 0, "working", 8, 60_000, null, null),
                StrengthSetEntity("set-2", EXERCISE, 1, "working", 6, 65_000, null, null),
            ),
        )
    }

    private suspend fun writeBenchPress(
        sessionId: String,
        startedOn: String,
        reps: List<Int>,
        loadGrams: Int? = 60_000,
    ) {
        val exerciseId = "$sessionId-exercise"
        dao.saveDetail(
            session = session(sessionId, startedOn, movement = "strength_training"),
            metrics = emptyList(),
            equipment = emptyList(),
            exercises = listOf(
                StrengthExerciseEntity(exerciseId, sessionId, benchPress.id.value, 0, null),
            ),
            sets = reps.mapIndexed { index, count ->
                StrengthSetEntity("$exerciseId-$index", exerciseId, index, "working", count, loadGrams, null, null)
            },
        )
    }

    private fun session(
        id: String,
        startedOn: String,
        startedAtTime: String? = null,
        movement: String = "walking",
        environment: String = "unknown",
        durationSeconds: Int = 1_800,
        createdAt: Long = 1L,
        updatedAt: Long = createdAt,
    ) = ActivitySessionEntity(
        id = id,
        movement = movement,
        customMovementName = null,
        environment = environment,
        startedOn = startedOn,
        startedAtTime = startedAtTime,
        durationSeconds = durationSeconds,
        perceivedEffort = null,
        notes = null,
        source = "manual",
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun equipment(
        id: String,
        type: String,
        position: Int,
        customName: String? = null,
        folded: String = "",
    ) = SessionEquipmentEntity(
        id = id,
        sessionId = SESSION,
        equipmentType = type,
        customName = customName,
        customNameFolded = folded,
        position = position,
    )

    private companion object {
        const val SESSION = "session-1"
        const val EQUIPMENT = "equipment-1"
        const val OTHER_EQUIPMENT = "equipment-2"
        const val EXERCISE = "exercise-1"
        const val NO_LIMIT = -1
    }
}
