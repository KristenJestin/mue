package fr.kristenjestin.mue.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.ExerciseCatalogSeed
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.domain.logic.WeeklyActivitySummary
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityMetric
import fr.kristenjestin.mue.domain.model.ActivityMetrics
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.MetricSource
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.PerceivedEffort
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * PRD 16.1: a whole detailed session is written in one transaction, or not at all.
 *
 * This is also where the module's central invariant is proved from the outside: `StrengthRules`
 * runs on the write path, so an invalid set never reaches the database and no query downstream
 * has to know what makes a set valid.
 */
@RunWith(AndroidJUnit4::class)
class RoomActivityRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: ActivityRepository

    private var clock = 1_000L

    private val benchPress = ExerciseCatalogSeed.DEFINITIONS.first { it.name == "Bench press" }
    private val plank = ExerciseCatalogSeed.DEFINITIONS.first { it.name == "Plank" }

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).addCallback(ExerciseCatalogSeed.CALLBACK).build()
        repository = RoomActivityRepository(database.activityDao(), now = { clock })
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aWholeDetailedSessionSurvivesTheRoundTrip() = runTest {
        repository.save(detailedSession())

        val stored = repository.findDetail(ActivityId(SESSION))

        assertNotNull(stored)
        assertEquals(Movement.STRENGTH_TRAINING, stored?.session?.movement)
        assertEquals(LocalDate.of(2026, 8, 20), stored?.session?.startedOn)
        assertEquals(LocalTime.of(18, 30), stored?.session?.startedAtTime)
        assertEquals(3_600, stored?.session?.duration?.seconds)
        assertEquals(7, stored?.session?.perceivedEffort?.value)
        assertEquals("Felt strong", stored?.session?.notes)
    }

    @Test
    fun theMetricsComeBackWithTheirValuesAndTheirProvenance() = runTest {
        repository.save(detailedSession())

        val metrics = repository.findDetail(ActivityId(SESSION))?.metrics

        assertEquals(310, metrics?.valueOf(MetricKind.ESTIMATED_ENERGY))
        assertEquals(MetricSource.EQUIPMENT, metrics?.get(MetricKind.ESTIMATED_ENERGY)?.source)
        assertNull(metrics?.valueOf(MetricKind.DISTANCE))
    }

    @Test
    fun theEquipmentComesBackInTheOrderItWasAdded() = runTest {
        repository.save(
            detailedSession(
                equipment = listOf(
                    SessionEquipment(EquipmentType.BARBELL, position = 0),
                    SessionEquipment(EquipmentType.OTHER, customName = "Sled", position = 1),
                )
            )
        )

        val equipment = repository.findDetail(ActivityId(SESSION))?.equipment.orEmpty()

        assertEquals(listOf(EquipmentType.BARBELL, EquipmentType.OTHER), equipment.map { it.equipmentType })
        assertEquals("Sled", equipment[1].customName)
    }

    @Test
    fun theExercisesAndTheirSetsComeBackInOrder() = runTest {
        repository.save(detailedSession())

        val exercises = repository.findDetail(ActivityId(SESSION))?.exercises.orEmpty()

        assertEquals(2, exercises.size)
        assertEquals("Bench press", exercises[0].definition.name)
        assertEquals("Plank", exercises[1].definition.name)
        assertEquals(listOf(8, 6), exercises[0].sets.map { it.repetitions })
        assertEquals(listOf(0, 1), exercises[0].sets.map { it.position })
        assertEquals(60_000, exercises[0].sets[0].load?.grams)
        assertEquals(90, exercises[1].sets[0].duration?.seconds)
    }

    /** PRD 9.4: a set that does not carry the primary measure of its mode is never written. */
    @Test
    fun anInvalidSetNeverReachesTheDatabase() = runTest {
        repository.save(
            detailedSession(
                exercises = listOf(
                    exercise(
                        EXERCISE, benchPress, TrackingMode.WEIGHT_AND_REPS,
                        listOf(
                            set("a", 0, repetitions = 8),
                            set("b", 1, repetitions = null, load = Load.ofGramsOrNull(60_000)),
                            set("c", 2, repetitions = 10),
                        ),
                    )
                )
            )
        )

        val sets = repository.findDetail(ActivityId(SESSION))?.exercises?.single()?.sets.orEmpty()

        assertEquals(listOf(8, 10), sets.map { it.repetitions })
        assertEquals("the survivors are renumbered", listOf(0, 1), sets.map { it.position })
    }

    /** PRD FR-ACTIVITY-009: an exercise left with no valid set is dropped silently. */
    @Test
    fun anExerciseWithNoValidSetIsNotWrittenAtAll() = runTest {
        repository.save(
            detailedSession(
                exercises = listOf(
                    exercise(EXERCISE, benchPress, TrackingMode.WEIGHT_AND_REPS, listOf(set("a", 0, repetitions = 8))),
                    exercise("empty", plank, TrackingMode.DURATION, listOf(set("b", 0))),
                )
            )
        )

        val exercises = repository.findDetail(ActivityId(SESSION))?.exercises.orEmpty()

        assertEquals(1, exercises.size)
        assertEquals("Bench press", exercises.single().definition.name)
    }

    /** A load typed under `weight_and_reps` must not survive a switch to `reps_only`. */
    @Test
    fun aFieldTheModeDoesNotUseIsDroppedOnTheWayIn() = runTest {
        repository.save(
            detailedSession(
                exercises = listOf(
                    exercise(
                        EXERCISE, benchPress, TrackingMode.REPS_ONLY,
                        listOf(set("a", 0, repetitions = 12, load = Load.ofGramsOrNull(60_000))),
                    )
                )
            )
        )

        assertNull(
            repository.findDetail(ActivityId(SESSION))?.exercises?.single()?.sets?.single()?.load,
        )
    }

    /** PRD FR-ACTIVITY-008: the same piece of gear cannot be attached twice. */
    @Test
    fun theSameEquipmentAddedTwiceIsStoredOnce() = runTest {
        repository.save(
            detailedSession(
                equipment = listOf(
                    SessionEquipment(EquipmentType.TREADMILL, position = 0),
                    SessionEquipment(EquipmentType.TREADMILL, position = 1),
                )
            )
        )

        assertEquals(1, repository.findDetail(ActivityId(SESSION))?.equipment?.size)
    }

    /** Case folds, so `Sled` and ` sled ` are the same custom piece of gear. */
    @Test
    fun aCustomEquipmentNameIsDeduplicatedByItsFold() = runTest {
        repository.save(
            detailedSession(
                equipment = listOf(
                    SessionEquipment(EquipmentType.OTHER, customName = "Sled", position = 0),
                    SessionEquipment(EquipmentType.OTHER, customName = " sled ", position = 1),
                    SessionEquipment(EquipmentType.OTHER, customName = "Ropes", position = 2),
                )
            )
        )

        val equipment = repository.findDetail(ActivityId(SESSION))?.equipment.orEmpty()

        assertEquals(2, equipment.size)
        assertEquals(listOf(0, 1), equipment.map { it.position })
    }

    /**
     * The atomicity of PRD 16.1, seen from the outside: an exercise naming a definition that is
     * not in the catalogue fails on the foreign key, after the session row has already been
     * written. Nothing may be left behind.
     */
    @Test
    fun aFailureHalfwayThroughLeavesNothingBehind() = runTest {
        val ghost = ExerciseDefinition(
            id = ExerciseDefinitionId.random(),
            name = "Ghost press",
            trackingMode = TrackingMode.WEIGHT_AND_REPS,
            isCustom = true,
        )

        val error = runCatching {
            repository.save(
                detailedSession(
                    exercises = listOf(
                        exercise(EXERCISE, ghost, TrackingMode.WEIGHT_AND_REPS, listOf(set("a", 0, repetitions = 8))),
                    )
                )
            )
        }.exceptionOrNull()

        assertNotNull("the write should have failed", error)
        assertNull(repository.findDetail(ActivityId(SESSION)))
        assertEquals(0, repository.observeSessionCount().first())
    }

    /** And a failed edit leaves the version already stored exactly as it was. */
    @Test
    fun aFailedEditLeavesTheStoredSessionUntouched() = runTest {
        repository.save(detailedSession())

        val ghost = ExerciseDefinition(
            id = ExerciseDefinitionId.random(),
            name = "Ghost press",
            trackingMode = TrackingMode.WEIGHT_AND_REPS,
            isCustom = true,
        )
        runCatching {
            repository.save(
                detailedSession(
                    notes = "rewritten",
                    exercises = listOf(
                        exercise(EXERCISE, ghost, TrackingMode.WEIGHT_AND_REPS, listOf(set("a", 0, repetitions = 8))),
                    )
                )
            )
        }

        val stored = repository.findDetail(ActivityId(SESSION))
        assertEquals("Felt strong", stored?.session?.notes)
        assertEquals(2, stored?.exercises?.size)
        assertEquals(310, stored?.metrics?.valueOf(MetricKind.ESTIMATED_ENERGY))
    }

    @Test
    fun savingAnEditedSessionReplacesItRatherThanAddingASecond() = runTest {
        repository.save(detailedSession())
        repository.save(detailedSession(notes = "second thoughts", exercises = emptyList()))

        assertEquals(1, repository.observeSessionCount().first())
        val stored = repository.findDetail(ActivityId(SESSION))
        assertEquals("second thoughts", stored?.session?.notes)
        assertEquals(0, stored?.exercises?.size)
    }

    @Test
    fun deletingASessionRemovesEverythingAttachedToIt() = runTest {
        repository.save(detailedSession())

        repository.delete(ActivityId(SESSION))

        assertNull(repository.findDetail(ActivityId(SESSION)))
        assertEquals(0, repository.observeSessionCount().first())
    }

    @Test
    fun theDashboardSeesOnlyItsFiveMostRecentSessions() = runTest {
        (1..7).forEach { day -> repository.save(walk("day-$day", LocalDate.of(2026, 8, day))) }

        val recent = repository.observeRecentSummaries(5).first()

        assertEquals(5, recent.size)
        assertEquals(LocalDate.of(2026, 8, 7), recent.first().startedOn)
        assertEquals(7, repository.observeAllSummaries().first().size)
    }

    @Test
    fun aSummaryCarriesTheLabelTheCardShows() = runTest {
        repository.save(
            walk("treadmill", LocalDate.of(2026, 8, 20)).let { detail ->
                detail.copy(
                    session = detail.session.copy(environment = ActivityEnvironment.INDOOR),
                    equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
                    metrics = ActivityMetrics.of(
                        ActivityMetric(MetricKind.DISTANCE, 4_200, MetricSource.EQUIPMENT),
                    ),
                )
            }
        )

        val summary = repository.observeAllSummaries().first().single()

        assertEquals("Treadmill walk", summary.label)
        assertEquals(4_200, summary.distanceMetres)
        assertNull(summary.validSetCount)
    }

    @Test
    fun aStrengthSummaryCountsItsSets() = runTest {
        repository.save(detailedSession())

        assertEquals(3, repository.observeAllSummaries().first().single().validSetCount)
    }

    /** What the weekly card of PRD FR-ACTIVITY-001 reads, computed off the windowed projection. */
    @Test
    fun theWeeklyWindowFeedsTheAggregate() = runTest {
        repository.save(walk("before", LocalDate.of(2026, 8, 16)))
        repository.save(walk("monday", LocalDate.of(2026, 8, 17)))
        repository.save(walk("wednesday", LocalDate.of(2026, 8, 19)))
        repository.save(walk("after", LocalDate.of(2026, 8, 24)))

        val week = WeeklyActivitySummary.weekOf(LocalDate.of(2026, 8, 19))
        val summaries = repository.observeSummariesIn(week).first()
        val aggregate = WeeklyActivitySummary.of(summaries, LocalDate.of(2026, 8, 19))

        assertEquals(2, aggregate.sessionCount)
        assertEquals(3_600, aggregate.totalDuration.seconds)
        assertEquals(1_800, aggregate.durationOn(DayOfWeek.MONDAY).seconds)
        assertEquals(0, aggregate.durationOn(DayOfWeek.SUNDAY).seconds)
    }

    @Test
    fun theLastPerformanceComesFromTheMostRecentOtherSession() = runTest {
        repository.save(benchSession("old", LocalDate.of(2026, 8, 10), listOf(5, 6)))
        repository.save(benchSession("recent", LocalDate.of(2026, 8, 18), listOf(8, 10)))

        val last = repository.findLastPerformance(benchPress.id)

        assertEquals(LocalDate.of(2026, 8, 18), last?.performedOn)
        assertEquals(10, last?.set?.repetitions)
        assertEquals(TrackingMode.WEIGHT_AND_REPS, last?.trackingMode)
    }

    @Test
    fun aSessionNeverQuotesItself() = runTest {
        repository.save(benchSession("old", LocalDate.of(2026, 8, 10), listOf(5, 6)))
        repository.save(benchSession("current", LocalDate.of(2026, 8, 18), listOf(8, 10)))

        val last = repository.findLastPerformance(benchPress.id, ActivityId("current"))

        assertEquals(6, last?.set?.repetitions)
    }

    @Test
    fun anExerciseNeverPractisedShowsNothing() = runTest {
        repository.save(benchSession("old", LocalDate.of(2026, 8, 10), listOf(5)))

        assertNull(repository.findLastPerformance(plank.id))
    }

    /** PRD 8.2: the audit stamps belong to the row, and an edit does not make a session new. */
    @Test
    fun theCreationStampSurvivesAnEditWhileTheUpdateStampMoves() = runTest {
        clock = 1_000L
        repository.save(walk(SESSION, LocalDate.of(2026, 8, 20)))
        clock = 9_000L
        repository.save(walk(SESSION, LocalDate.of(2026, 8, 21)))

        val stored = database.activityDao().findSession(SESSION)
        assertEquals(1_000L, stored?.createdAt)
        assertEquals(9_000L, stored?.updatedAt)
    }

    /** Two sessions on one day is a normal day, not a conflict to merge (PRD 8.2). */
    @Test
    fun twoSessionsMayShareADay() = runTest {
        repository.save(walk("morning", LocalDate.of(2026, 8, 20), startedAtTime = LocalTime.of(7, 15)))
        repository.save(walk("evening", LocalDate.of(2026, 8, 20), startedAtTime = LocalTime.of(18, 40)))

        val summaries = repository.observeAllSummaries().first()

        assertEquals(2, summaries.size)
        assertEquals(listOf("evening", "morning"), summaries.map { it.id.value })
    }

    @Test
    fun anUntimedSessionKeepsItsAbsentTime() = runTest {
        repository.save(walk(SESSION, LocalDate.of(2026, 8, 20), startedAtTime = null))

        assertNull(repository.findDetail(ActivityId(SESSION))?.session?.startedAtTime)
        assertNull(repository.observeAllSummaries().first().single().startedAtTime)
    }

    private fun detailedSession(
        notes: String? = "Felt strong",
        equipment: List<SessionEquipment> = listOf(SessionEquipment(EquipmentType.BARBELL)),
        exercises: List<StrengthExerciseDetail> = listOf(
            exercise(
                EXERCISE, benchPress, TrackingMode.WEIGHT_AND_REPS,
                listOf(
                    set("bench-0", 0, repetitions = 8, load = Load.ofGramsOrNull(60_000)),
                    set("bench-1", 1, repetitions = 6, load = Load.ofGramsOrNull(65_000)),
                ),
            ),
            exercise(
                "plank-exercise", plank, TrackingMode.DURATION,
                listOf(set("plank-0", 0, duration = ActivityDuration.ofSecondsOrNull(90))),
            ),
        ),
    ) = ActivitySessionDetail(
        session = ActivitySession(
            id = ActivityId(SESSION),
            movement = Movement.STRENGTH_TRAINING,
            startedOn = LocalDate.of(2026, 8, 20),
            duration = ActivityDuration.ofSecondsOrNull(3_600)!!,
            environment = ActivityEnvironment.INDOOR,
            startedAtTime = LocalTime.of(18, 30),
            perceivedEffort = PerceivedEffort.ofOrNull(7),
            notes = notes,
        ),
        metrics = ActivityMetrics.of(
            ActivityMetric(MetricKind.ESTIMATED_ENERGY, 310, MetricSource.EQUIPMENT),
        ),
        equipment = equipment,
        exercises = exercises,
    )

    private fun walk(
        id: String,
        startedOn: LocalDate,
        startedAtTime: LocalTime? = null,
    ) = ActivitySessionDetail(
        session = ActivitySession(
            id = ActivityId(id),
            movement = Movement.WALKING,
            startedOn = startedOn,
            duration = ActivityDuration.ofSecondsOrNull(1_800)!!,
            startedAtTime = startedAtTime,
        ),
    )

    private fun benchSession(id: String, startedOn: LocalDate, reps: List<Int>) = ActivitySessionDetail(
        session = ActivitySession(
            id = ActivityId(id),
            movement = Movement.STRENGTH_TRAINING,
            startedOn = startedOn,
            duration = ActivityDuration.ofSecondsOrNull(1_800)!!,
        ),
        exercises = listOf(
            exercise(
                "$id-exercise", benchPress, TrackingMode.WEIGHT_AND_REPS,
                reps.mapIndexed { index, count ->
                    set("$id-$index", index, repetitions = count, load = Load.ofGramsOrNull(60_000))
                },
            ),
        ),
    )

    private fun exercise(
        id: String,
        definition: ExerciseDefinition,
        mode: TrackingMode,
        sets: List<StrengthSet>,
        position: Int = 0,
    ) = StrengthExerciseDetail(
        exercise = StrengthExercise(StrengthExerciseId(id), position),
        definition = definition.copy(trackingMode = mode),
        sets = sets,
    )

    private fun set(
        id: String,
        position: Int,
        repetitions: Int? = null,
        load: Load? = null,
        duration: ActivityDuration? = null,
    ) = StrengthSet(
        id = StrengthSetId(id),
        position = position,
        repetitions = repetitions,
        load = load,
        duration = duration,
    )

    private companion object {
        const val SESSION = "session-1"
        const val EXERCISE = "exercise-1"
    }
}
