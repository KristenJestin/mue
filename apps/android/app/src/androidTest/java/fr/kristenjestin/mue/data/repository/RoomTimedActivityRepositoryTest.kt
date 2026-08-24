package fr.kristenjestin.mue.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.ActivitySessionEntity
import fr.kristenjestin.mue.data.local.database.ExerciseCatalogSeed
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.TimedActivityDraftEntity
import fr.kristenjestin.mue.data.local.database.countOf
import fr.kristenjestin.mue.domain.logic.TimerElapsed
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityMetric
import fr.kristenjestin.mue.domain.model.ActivityMetrics
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySource
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.ExerciseDefinitionId
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.MetricSource
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StartTimerOutcome
import fr.kristenjestin.mue.domain.model.StartTimerRequest
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TimedActivityDraft
import fr.kristenjestin.mue.domain.model.TimedDraftId
import fr.kristenjestin.mue.domain.model.TimedDraftStatus
import fr.kristenjestin.mue.domain.model.TimerInstant
import fr.kristenjestin.mue.domain.model.TrackingMode
import fr.kristenjestin.mue.domain.repository.TimedActivityRepository
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
import java.time.ZoneId

/**
 * The timer's storage, proved from the outside (timer PRD 8 and 12).
 *
 * The clock is a value here, exactly as PRD 9 intends: every instant is written by hand, so a
 * pause across a reboot is a pair of numbers rather than an event this suite would have to
 * reproduce.
 */
@RunWith(AndroidJUnit4::class)
class RoomTimedActivityRepositoryTest {

    private lateinit var database: MueDatabase
    private lateinit var repository: TimedActivityRepository

    /** The audit clock of the stored row, separate from the timer's own instants. */
    private var stamp = 1_000L
    private var nextRowId = 0

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    @Before
    fun createRepository() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).addCallback(ExerciseCatalogSeed.CALLBACK).build()
        repository = RoomTimedActivityRepository(
            database = database,
            timerDao = database.timerDao(),
            activityDao = database.activityDao(),
            now = { stamp },
            newRowId = { "row-${nextRowId++}" },
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // ---------------------------------------------------------------- start

    @Test
    fun aStartWritesTheDraftBeforeAnythingIsDrawn() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        assertTrue(outcome is StartTimerOutcome.Started)
        assertEquals(TimedDraftStatus.RUNNING, outcome.draft.status)
        assertEquals(1, rowsIn("timed_activity_drafts"))
        assertEquals(outcome.draft, repository.observeLiveDraft().first())
    }

    /** FR-TIMER-005: the calendar reading is frozen at `Start timer`, zone and all. */
    @Test
    fun theStartFreezesTheCalendarDateAndTheLocalTimeToTheSecond() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        val stored = repository.findDraft(outcome.draft.id)
        assertEquals(LocalDate.of(2026, 8, 20), stored?.startedOn)
        assertEquals(LocalTime.of(18, 32, 47), stored?.startedAtLocalTime)
        assertEquals(START, stored?.startedAtMillis)
    }

    /** FR-TIMER-002: the second attempt opens the first timer and creates nothing. */
    @Test
    fun aSecondStartOpensTheExistingTimerAndWritesNoRow() = runTest {
        val first = repository.start(walkOnATreadmill(), at(START), zone)

        val second = repository.start(
            StartTimerRequest(Movement.CYCLING),
            at(START + 60_000),
            zone,
        )

        assertTrue(second is StartTimerOutcome.AlreadyLive)
        assertEquals(first.draft.id, second.draft.id)
        assertEquals(Movement.WALKING, second.draft.movement)
        assertEquals(1, rowsIn("timed_activity_drafts"))
    }

    /** A paused timer is live too, so it refuses a second start just as a running one does. */
    @Test
    fun aPausedTimerStillCountsAsTheOneLiveTimer() = runTest {
        val first = repository.start(walkOnATreadmill(), at(START), zone)
        repository.pause(first.draft.id, at(START + 65_000))

        val second = repository.start(StartTimerRequest(Movement.CYCLING), at(START + 70_000), zone)

        assertTrue(second is StartTimerOutcome.AlreadyLive)
        assertEquals(TimedDraftStatus.PAUSED, second.draft.status)
        assertEquals(1, rowsIn("timed_activity_drafts"))
    }

    /** FR-TIMER-008: a draft waiting to be reviewed is not the live timer and blocks nothing. */
    @Test
    fun aDraftPendingReviewDoesNotBlockANewTimer() = runTest {
        val first = repository.start(walkOnATreadmill(), at(START), zone)
        repository.finish(first.draft.id, at(START + 65_000))

        val second = repository.start(StartTimerRequest(Movement.CYCLING), at(START + 70_000), zone)

        assertTrue(second is StartTimerOutcome.Started)
        assertEquals(2, rowsIn("timed_activity_drafts"))
        assertEquals(second.draft.id, repository.observeLiveDraft().first()?.id)
    }

    // ------------------------------------------------------------ equipment

    @Test
    fun theEquipmentOfTheStartComesBackInOrder() = runTest {
        val outcome = repository.start(
            StartTimerRequest(
                movement = Movement.WALKING,
                equipment = listOf(
                    SessionEquipment(EquipmentType.TREADMILL),
                    SessionEquipment(EquipmentType.OTHER, customName = "Ski erg"),
                ),
            ),
            at(START),
            zone,
        )

        val stored = repository.findDraft(outcome.draft.id)?.equipment.orEmpty()
        assertEquals(
            listOf(EquipmentType.TREADMILL, EquipmentType.OTHER),
            stored.map { it.equipmentType },
        )
        assertEquals("Ski erg", stored[1].customName)
        assertEquals(listOf(0, 1), stored.map { it.position })
    }

    /** FR-ACTIVITY-008 folded onto the draft: `Ski erg` and `  SKI ERG ` are one piece of gear. */
    @Test
    fun theFoldedNameKeepsOneDraftFromCarryingTheSameGearTwice() = runTest {
        val outcome = repository.start(
            StartTimerRequest(
                movement = Movement.WALKING,
                equipment = listOf(
                    SessionEquipment(EquipmentType.OTHER, customName = "Ski erg"),
                    SessionEquipment(EquipmentType.OTHER, customName = "  SKI ERG "),
                    SessionEquipment(EquipmentType.TREADMILL),
                    SessionEquipment(EquipmentType.TREADMILL),
                ),
            ),
            at(START),
            zone,
        )

        val stored = repository.findDraft(outcome.draft.id)?.equipment.orEmpty()
        assertEquals(2, stored.size)
        assertEquals("the survivors are renumbered", listOf(0, 1), stored.map { it.position })
        assertEquals("what was returned is what was written", stored, outcome.draft.equipment)
    }

    /** FR-TIMER-009: the equipment goes with the draft through SQLite's own cascade. */
    @Test
    fun discardingADraftTakesItsEquipmentWithIt() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        assertEquals(1, rowsIn("timed_draft_equipment"))

        repository.discard(outcome.draft.id)

        assertEquals(0, rowsIn("timed_activity_drafts"))
        assertEquals(0, rowsIn("timed_draft_equipment"))
        assertNull(repository.observeLiveDraft().first())
    }

    // -------------------------------------------------------- the transitions

    /** PRD 8.3: the open segment is added to the accumulated total and then cleared. */
    @Test
    fun aPauseClosesTheOpenSegmentIntoTheAccumulatedTotal() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        val paused = repository.pause(outcome.draft.id, at(START + 65_000))

        assertEquals(TimedDraftStatus.PAUSED, paused?.status)
        assertEquals(65, paused?.accumulatedActive?.seconds)
        assertNull(paused?.currentSegmentStartedAtMillis)
        assertNull(paused?.currentSegmentStartedElapsedRealtimeMillis)
        assertEquals(paused, repository.findDraft(outcome.draft.id))
    }

    /** FR-TIMER-004: a resume opens a new segment and leaves the original start alone. */
    @Test
    fun aResumeOpensANewSegmentWithoutMovingTheStart() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        repository.pause(outcome.draft.id, at(START + 65_000))

        val resumed = repository.resume(outcome.draft.id, at(START + 600_000))

        assertEquals(TimedDraftStatus.RUNNING, resumed?.status)
        assertEquals(START, resumed?.startedAtMillis)
        assertEquals(65, resumed?.accumulatedActive?.seconds)
        assertEquals(START + 600_000, resumed?.currentSegmentStartedAtMillis)
    }

    /** A pause never counts, so two segments and a long wait are worth their two segments. */
    @Test
    fun thePausedIntervalNeverReachesTheTotal() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        repository.pause(outcome.draft.id, at(START + 65_000))
        repository.resume(outcome.draft.id, at(START + 3_600_000))

        val finished = repository.finish(outcome.draft.id, at(START + 3_630_000))

        assertEquals(65 + 30, finished?.accumulatedActive?.seconds)
        assertEquals(START + 3_630_000, finished?.finishedAtMillis)
    }

    @Test
    fun aFinishMovesTheDraftToReview() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        val finished = repository.finish(outcome.draft.id, at(START + 65_000))

        assertEquals(TimedDraftStatus.PENDING_REVIEW, finished?.status)
        assertNull(repository.observeLiveDraft().first())
        assertEquals(listOf(finished), repository.observeDraftsToReview().first())
    }

    // -------------------------------------------------------- idempotence

    /** PRD 12, a button pressed twice: the second press changes nothing at all. */
    @Test
    fun pausingTwiceIsANoOp() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val first = repository.pause(outcome.draft.id, at(START + 65_000))
        val touchedAt = updatedAtOf(outcome.draft.id)

        stamp += 1_000
        val second = repository.pause(outcome.draft.id, at(START + 600_000))

        assertEquals(first, second)
        assertEquals(65, second?.accumulatedActive?.seconds)
        assertEquals("nothing was written", touchedAt, updatedAtOf(outcome.draft.id))
    }

    @Test
    fun resumingARunningTimerIsANoOp() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val touchedAt = updatedAtOf(outcome.draft.id)

        stamp += 1_000
        val resumed = repository.resume(outcome.draft.id, at(START + 600_000))

        assertEquals(outcome.draft, resumed)
        assertEquals(START, resumed?.currentSegmentStartedAtMillis)
        assertEquals("nothing was written", touchedAt, updatedAtOf(outcome.draft.id))
    }

    /** Finishing is definitive: the form is where a duration is corrected afterwards. */
    @Test
    fun finishingTwiceIsANoOp() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val first = repository.finish(outcome.draft.id, at(START + 65_000))
        val touchedAt = updatedAtOf(outcome.draft.id)

        stamp += 1_000
        val second = repository.finish(outcome.draft.id, at(START + 600_000))

        assertEquals(first, second)
        assertEquals(65, second?.accumulatedActive?.seconds)
        assertEquals(START + 65_000, second?.finishedAtMillis)
        assertEquals("nothing was written", touchedAt, updatedAtOf(outcome.draft.id))
    }

    @Test
    fun discardingTwiceIsANoOp() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        repository.discard(outcome.draft.id)
        repository.discard(outcome.draft.id)

        assertEquals(0, rowsIn("timed_activity_drafts"))
    }

    /** A draft that is not there is not an error either, for any of the four. */
    @Test
    fun aTransitionOnAMissingDraftAnswersNull() = runTest {
        val ghost = TimedDraftId("no-such-draft")

        assertNull(repository.pause(ghost, at(START)))
        assertNull(repository.resume(ghost, at(START)))
        assertNull(repository.finish(ghost, at(START)))
        assertNull(repository.findDraft(ghost))
        repository.discard(ghost)
    }

    /** A resume must not reopen what `Finish` closed for good. */
    @Test
    fun aDraftInReviewNeverReopens() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        repository.finish(outcome.draft.id, at(START + 65_000))

        val resumed = repository.resume(outcome.draft.id, at(START + 600_000))

        assertEquals(TimedDraftStatus.PENDING_REVIEW, resumed?.status)
        assertNull(repository.observeLiveDraft().first())
    }

    // ------------------------------------------------------- the review list

    /**
     * FR-TIMER-008 asks for the most recent first, and the finish instant is the only honest key:
     * a draft that ran across midnight started on the wrong side of the ordering.
     */
    @Test
    fun theReviewListIsOrderedOnTheFinishAndNotOnTheStart() = runTest {
        database.timerDao().upsertDraft(reviewedDraft("late-start", startedAt = 200, finishedAt = 400))
        database.timerDao().upsertDraft(reviewedDraft("across-midnight", startedAt = 100, finishedAt = 500))

        val ids = repository.observeDraftsToReview().first().map { it.id.value }

        assertEquals(listOf("across-midnight", "late-start"), ids)
    }

    @Test
    fun theReviewListHoldsNoLiveTimer() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        assertEquals(emptyList<TimedActivityDraft>(), repository.observeDraftsToReview().first())

        repository.pause(outcome.draft.id, at(START + 65_000))

        assertEquals(emptyList<TimedActivityDraft>(), repository.observeDraftsToReview().first())
    }

    // ---------------------------------------------------- the review form blob

    /** PRD 8.2: the repository stores the string and never reads it. */
    @Test
    fun theReviewFormStateRoundTripsCharacterForCharacter() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val blob = """{"notes":"Ran \"hard\" — 5 km, 3 kg vest","effort":7,"sets":[]}"""

        repository.saveReviewFormState(outcome.draft.id, blob, schemaVersion = 3)

        val stored = repository.findDraft(outcome.draft.id)
        assertEquals(blob, stored?.reviewFormState)
        assertEquals(3, stored?.reviewFormSchemaVersion)
    }

    /** Clearing the form is a `null`, distinct from an empty blob written at version zero. */
    @Test
    fun theReviewFormStateRoundTripsANull() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        repository.saveReviewFormState(outcome.draft.id, "something", schemaVersion = 3)

        repository.saveReviewFormState(outcome.draft.id, null, schemaVersion = 0)

        val stored = repository.findDraft(outcome.draft.id)
        assertNull(stored?.reviewFormState)
        assertEquals(0, stored?.reviewFormSchemaVersion)
    }

    /** A fresh draft has no form state at all, and zero is not a version anything wrote. */
    @Test
    fun aFreshDraftCarriesNoFormState() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        assertNull(outcome.draft.reviewFormState)
        assertEquals(0, repository.findDraft(outcome.draft.id)?.reviewFormSchemaVersion)
    }

    /** Writing the form must not disturb the measured columns beside it. */
    @Test
    fun savingTheFormLeavesTheMeasuredDurationAlone() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val finished = repository.finish(outcome.draft.id, at(START + 65_000))

        repository.saveReviewFormState(outcome.draft.id, "{}", schemaVersion = 1)

        val stored = repository.findDraft(outcome.draft.id)
        assertEquals(finished?.accumulatedActive, stored?.accumulatedActive)
        assertEquals(finished?.finishedAtMillis, stored?.finishedAtMillis)
        assertEquals(finished?.equipment, stored?.equipment)
    }

    // ------------------------------------------------------- the hand-off

    /** FR-TIMER-007: one session created and the draft gone, in one transaction. */
    @Test
    fun theHandOffCreatesTheSessionAndRemovesTheDraft() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val finished = repository.finish(outcome.draft.id, at(START + 2_538_000))!!

        repository.commitToSession(finished.id, sessionFrom(finished))

        assertEquals(1, rowsIn("activity_sessions"))
        assertEquals(0, rowsIn("timed_activity_drafts"))
        assertEquals(0, rowsIn("timed_draft_equipment"))

        val session = database.activityDao().findSession(SESSION_ID)
        assertNotNull(session)
        assertEquals(ActivitySource.TIMER.id, session?.source)
        assertEquals(2_538, session?.durationSeconds)
        assertEquals("18:32", session?.startedAtTime)
    }

    /** PRD 12: a failed save leaves the draft and its form state exactly where they were. */
    @Test
    fun aFailedHandOffLeavesBothSidesUntouched() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val finished = repository.finish(outcome.draft.id, at(START + 2_538_000))!!
        repository.saveReviewFormState(finished.id, "{\"effort\":7}", schemaVersion = 2)

        val error = runCatching {
            repository.commitToSession(finished.id, sessionFrom(finished, exercises = ghostExercise()))
        }.exceptionOrNull()

        assertTrue("expected a constraint failure, got $error", error is SQLiteConstraintException)
        assertEquals("no half-written session", 0, rowsIn("activity_sessions"))
        assertEquals("no orphaned equipment", 0, rowsIn("session_equipment"))
        assertEquals(1, rowsIn("timed_activity_drafts"))

        val stored = repository.findDraft(finished.id)
        assertEquals("{\"effort\":7}", stored?.reviewFormState)
        assertEquals(2, stored?.reviewFormSchemaVersion)
        assertEquals(finished.accumulatedActive, stored?.accumulatedActive)
    }

    /** The gear the timer carried is copied into the session unchanged. */
    @Test
    fun theHandOffCarriesTheEquipmentIntoTheSession() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        val finished = repository.finish(outcome.draft.id, at(START + 2_538_000))!!

        repository.commitToSession(finished.id, sessionFrom(finished))

        val equipment = database.activityDao().findEquipment(SESSION_ID)
        assertEquals(listOf("treadmill"), equipment.map { it.equipmentType })
    }

    // -------------------------------------------------------- `Start again`

    /** PRD 6.1: the axes of the most recent session whose source is `timer`. */
    @Test
    fun theLastTimedStartIsTheMostRecentTimedSession() = runTest {
        commitATimedSession("older", LocalDate.of(2026, 8, 18), Movement.CYCLING)
        commitATimedSession("newer", LocalDate.of(2026, 8, 20), Movement.WALKING)

        val request = repository.observeLastTimedStart().first()

        assertEquals(Movement.WALKING, request?.movement)
        assertEquals(ActivityEnvironment.INDOOR, request?.environment)
        assertEquals(listOf(EquipmentType.TREADMILL), request?.equipment?.map { it.equipmentType })
    }

    /** A hand-entered session is not a timed one, however recent it is. */
    @Test
    fun aManualSessionIsNeverTheLastTimedStart() = runTest {
        commitATimedSession("timed", LocalDate.of(2026, 8, 18), Movement.CYCLING)
        database.activityDao().saveDetail(
            session = manualSession("manual", LocalDate.of(2026, 8, 22)),
            metrics = emptyList(),
            equipment = emptyList(),
            exercises = emptyList(),
            sets = emptyList(),
        )

        assertEquals(Movement.CYCLING, repository.observeLastTimedStart().first()?.movement)
    }

    @Test
    fun theLastTimedStartIsNullUntilOneHasBeenSaved() = runTest {
        assertNull(repository.observeLastTimedStart().first())
    }

    // ---------------------------------------------------------- the domain

    /** The stored draft is worth what `TimerElapsed` says it is, and not a second more. */
    @Test
    fun aStoredPausedDraftReadsBackAsItsMeasuredTotal() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)
        repository.pause(outcome.draft.id, at(START + 65_000))

        val stored = repository.findDraft(outcome.draft.id)!!
        val elapsed = TimerElapsed.of(stored, at(START + 3_600_000))

        assertTrue(elapsed is TimerElapsed.Sound)
        assertEquals(65, elapsed.duration.seconds)
    }

    /**
     * FR-TIMER-003 through the database: a reboot moves the stored boot reference out of
     * tolerance, and the persisted civil instants answer instead of the monotonic ones.
     */
    @Test
    fun aStoredRunningDraftSurvivesAReboot() = runTest {
        val outcome = repository.start(walkOnATreadmill(), at(START), zone)

        val stored = repository.findDraft(outcome.draft.id)!!
        val afterReboot = TimerInstant(wallMillis = START + 120_000, elapsedRealtimeMillis = 4_000)
        val elapsed = TimerElapsed.of(stored, afterReboot)

        assertTrue(elapsed is TimerElapsed.Sound)
        assertEquals(120, elapsed.duration.seconds)
    }

    // --------------------------------------------------------------- helpers

    private fun at(wallMillis: Long): TimerInstant = TimerInstant(
        wallMillis = wallMillis,
        elapsedRealtimeMillis = wallMillis - BOOT_REFERENCE,
    )

    private fun walkOnATreadmill() = StartTimerRequest(
        movement = Movement.WALKING,
        environment = ActivityEnvironment.INDOOR,
        equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
    )

    private fun reviewedDraft(id: String, startedAt: Long, finishedAt: Long) =
        TimedActivityDraftEntity(
            id = id,
            status = TimedDraftStatus.PENDING_REVIEW.id,
            movement = Movement.WALKING.id,
            customMovementName = null,
            environment = ActivityEnvironment.UNKNOWN.id,
            startedAtMillis = startedAt,
            startedOn = "2026-08-20",
            startedAtLocalTime = "23:50:00",
            accumulatedActiveSeconds = 60,
            currentSegmentStartedAtMillis = null,
            currentSegmentStartedElapsedRealtimeMillis = null,
            bootReferenceMillis = BOOT_REFERENCE,
            finishedAtMillis = finishedAt,
            reviewFormState = null,
            reviewFormSchemaVersion = 0,
            createdAt = startedAt,
            updatedAt = finishedAt,
        )

    /** What the review form hands back: the draft's own axes plus whatever was typed. */
    private fun sessionFrom(
        draft: TimedActivityDraft,
        id: String = SESSION_ID,
        exercises: List<StrengthExerciseDetail> = emptyList(),
    ) = ActivitySessionDetail(
        session = ActivitySession(
            id = ActivityId(id),
            movement = draft.movement,
            startedOn = draft.startedOn,
            duration = draft.accumulatedActive,
            customMovementName = draft.customMovementName,
            environment = draft.environment,
            startedAtTime = draft.startedAtLocalTime.withSecond(0).withNano(0),
            source = ActivitySource.TIMER,
        ),
        metrics = ActivityMetrics.of(
            ActivityMetric(MetricKind.DISTANCE, 5_000, MetricSource.EQUIPMENT),
        ),
        equipment = draft.equipment,
        exercises = exercises,
    )

    /** An exercise naming a definition the catalogue does not hold: the FK refuses it. */
    private fun ghostExercise() = listOf(
        StrengthExerciseDetail(
            exercise = StrengthExercise(StrengthExerciseId("ghost-exercise"), 0),
            definition = ExerciseDefinition(
                id = ExerciseDefinitionId("no-such-definition"),
                name = "Ghost press",
                trackingMode = TrackingMode.WEIGHT_AND_REPS,
            ),
            sets = listOf(StrengthSet(StrengthSetId("ghost-set"), position = 0, repetitions = 8)),
        )
    )

    private fun manualSession(id: String, startedOn: LocalDate) =
        ActivitySessionEntity(
            id = id,
            movement = Movement.RUNNING.id,
            customMovementName = null,
            environment = ActivityEnvironment.OUTDOOR.id,
            startedOn = startedOn.toString(),
            startedAtTime = "07:15",
            durationSeconds = 1_800,
            perceivedEffort = null,
            notes = null,
            source = ActivitySource.MANUAL.id,
            createdAt = 1L,
            updatedAt = 1L,
        )

    /** A whole `Start` → `Finish` → `Save activity` round, so `Start again` has something to copy. */
    private suspend fun commitATimedSession(id: String, startedOn: LocalDate, movement: Movement) {
        val outcome = repository.start(
            StartTimerRequest(
                movement = movement,
                environment = ActivityEnvironment.INDOOR,
                equipment = listOf(SessionEquipment(EquipmentType.TREADMILL)),
            ),
            at(START),
            zone,
        )
        val finished = repository.finish(outcome.draft.id, at(START + 65_000))!!
        repository.commitToSession(
            finished.id,
            sessionFrom(finished, id = id).let { it.copy(session = it.session.copy(startedOn = startedOn)) },
        )
        stamp += 1_000
    }

    private fun rowsIn(table: String): Int = database.openHelper.writableDatabase.countOf(table)

    private fun updatedAtOf(id: TimedDraftId): Long =
        database.openHelper.writableDatabase
            .query("SELECT updated_at FROM timed_activity_drafts WHERE id = '${id.value}'")
            .use { cursor -> if (cursor.moveToNext()) cursor.getLong(0) else -1L }

    private companion object {
        /** `2026-08-20T18:32:47` in Paris, the second FR-TIMER-005 keeps and the form truncates. */
        const val START = 1_787_243_567_000L

        /** An arbitrary boot, so `wall - elapsedRealtime` is stable across every fixture. */
        const val BOOT_REFERENCE = 1_787_000_000_000L

        const val SESSION_ID = "88888888-8888-4888-8888-888888888888"
    }
}
