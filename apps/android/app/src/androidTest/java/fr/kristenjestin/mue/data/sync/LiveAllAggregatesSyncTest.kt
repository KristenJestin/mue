package fr.kristenjestin.mue.data.sync

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.pairing.PairingResult
import fr.kristenjestin.mue.data.remote.sync.ActivitySessionPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.ActivitySessionUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.CustomExerciseDefinitionPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.CustomExerciseUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.FoodLogEntryPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.FoodLogEntryUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.FoodPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.FoodUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.MealPlanEntryPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.MealPlanEntryUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.MutationAppliedDto
import fr.kristenjestin.mue.data.remote.sync.MutationDuplicateDto
import fr.kristenjestin.mue.data.remote.sync.MutationEnvelopeDto
import fr.kristenjestin.mue.data.remote.sync.MutationRejectedDto
import fr.kristenjestin.mue.data.remote.sync.OriginDto
import fr.kristenjestin.mue.data.remote.sync.PushRequestDto
import fr.kristenjestin.mue.data.remote.sync.PushResponseDto
import fr.kristenjestin.mue.data.remote.sync.RecipeIngredientDto
import fr.kristenjestin.mue.data.remote.sync.RecipePayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.RecipeUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.SyncJson
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivityEnvironment
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivityMetric
import fr.kristenjestin.mue.domain.model.ActivityMetrics
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ActivitySource
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.MetricSource
import fr.kristenjestin.mue.domain.model.Movement
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.SetType
import fr.kristenjestin.mue.domain.model.StrengthExercise
import fr.kristenjestin.mue.domain.model.StrengthExerciseDetail
import fr.kristenjestin.mue.domain.model.StrengthExerciseId
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.StrengthSetId
import fr.kristenjestin.mue.domain.model.TrackingMode
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The six aggregates PRD 10.1 marks synchronised and that never reached a server, against one
 * that is actually running.
 *
 * `LiveHealthProfileSyncTest` proved the aggregate that was journalled and undeliverable.
 * `LiveOutboxRepairTest` proved the row whose identifier no server would read. This proves the
 * rest of the matrix, in both directions, and it is the only kind of test that could: every
 * defect it covers was invisible to `ContractDrift`, which compares *shapes*, and four of the six
 * aggregates were not even journalled — there was no row for a shape test to look at.
 *
 * ## What each half asserts
 *
 * - **A row written on this device reaches PostgreSQL.** One of each aggregate is written through
 *   the repositories the screens use, the engine drains, and the server's own revision is read
 *   back out of `sync_aggregate_state`. A revision exists only because the server issued one.
 * - **A row written on the server reaches this device.** A second client pushes one of each over
 *   HTTP under a different `origin.id`, the phone pulls, and the rows are read back out of Room
 *   through the same repositories.
 *
 * The aggregate identifiers are logged so `mue_app` can be read out of band for the same values.
 * An instrumented test has no business holding a database credential, and a phone reporting
 * `Synced` while the server stored nothing is precisely the lie this exercise exists to catch.
 *
 * ## It skips unless it is told where to go
 *
 * ```
 * adb -s emulator-5554 shell am instrument -w \
 *   -e class fr.kristenjestin.mue.data.sync.LiveAllAggregatesSyncTest \
 *   -e mueLiveServer https://192.168.1.100:3100 \
 *   -e mueLiveEmail … -e mueLivePassword … \
 *   fr.kristenjestin.mue.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveAllAggregatesSyncTest {

    private val application get() = ApplicationProvider.getApplicationContext<MueApplication>()

    private val container get() = application.container

    private val sync get() = container.sync

    /**
     * Everything the matrix promised, leaving the phone at once.
     *
     * The meal plan is journalled twice on purpose: once by the repository, which writes the
     * identifier a current build writes, and once by hand under the spelling every row already on
     * the owner's phone carries. The second is the row `MealPlanIdRepair` exists for, and the
     * engine start below is what repairs it — before anything is sent, which is the only moment it
     * can be repaired at all.
     */
    @Test
    fun oneOfEachAggregateWrittenHereReachesTheServer() {
        val server = liveServer() ?: return

        // Nothing else may push while this runs. The periodic worker shares the container's
        // engine but not this one's gate, and two engines pushing one batch is a race the
        // application never has (PRD 9.4 gives them one engine) and this test must not invent.
        WorkManager.getInstance(application).cancelUniqueWork(SyncScheduler.PERIODIC_WORK)

        val paired = runBlocking {
            sync.pairing.pair(
                server,
                requireNotNull(argument("mueLiveEmail")),
                requireNotNull(argument("mueLivePassword")),
            )
        }
        assertTrue("pairing refused: $paired", paired is PairingResult.Paired)

        // Drain whatever the pairing itself queued, so the counts below are this test's.
        runBlocking { sync.engine.sync() }

        writeOneOfEach()
        journalTheLegacyMealPlanRow()

        val pendingBefore = runBlocking { sync.syncDao.countInState(SyncMutationEntity.STATE_PENDING) }
        assertTrue("nothing was journalled at all: $pendingBefore", pendingBefore >= 7)

        /*
         * A new engine, because the repair runs at *engine start* and the container's engine was
         * constructed before this test's first line — against an outbox that was empty at the
         * time. That is correct behaviour and it is not the situation being reproduced: a legacy
         * row is inherited across a restart, and an instrumented test cannot restart its process.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val outcome = try {
            runBlocking { SyncEngine(store = sync.store, api = sync.api, scope = scope).sync() }
        } finally {
            scope.cancel()
        }

        val completed = outcome as? SyncOutcome.Completed
        assertNotNull("the synchronisation did not complete: $outcome", completed)
        requireNotNull(completed)

        assertEquals(
            "a row nothing could ever send is still in the queue",
            0,
            runBlocking { sync.syncDao.countInState(SyncMutationEntity.STATE_PENDING) },
        )
        assertEquals(
            "the server refused something: see `Data & sync`",
            0,
            runBlocking { sync.syncDao.countInState(SyncMutationEntity.STATE_FAILED) },
        )
        assertEquals("nothing was held back", 0, completed.deferred)
        assertEquals("nothing was unreadable", 0, completed.unreadable)
        assertEquals("the legacy meal plan row was not repaired", 1, completed.repaired)

        /*
         * The server issued a revision for every one of them.
         *
         * `sync_aggregate_state.revision` is written from an acknowledgement and from nothing
         * else, so a non-null value here is the server's own answer rather than this phone's
         * opinion of what it sent.
         */
        for ((type, id) in written()) {
            val state = runBlocking { sync.syncDao.aggregateState(type, id) }
            assertNotNull("$type $id was never acknowledged", state)
            assertNotNull("$type $id has no server revision", state?.revision)
            Log.i(TAG, "pushed $type $id revision=${state?.revision}")
        }

        // And the repaired proposal is addressed under the identifier the contract accepts.
        val repaired = runBlocking {
            sync.syncDao.aggregateState(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, LEGACY_PLAN_CANONICAL)
        }
        assertNotNull("the repaired proposal was never acknowledged", repaired)
        assertNotNull("the repaired proposal has no server revision", repaired?.revision)
        assertNull(
            "the legacy identifier must not survive the repair",
            runBlocking {
                sync.syncDao.aggregateState(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, LEGACY_PLAN_STORED)
            },
        )
        Log.i(TAG, "repaired $LEGACY_PLAN_STORED -> $LEGACY_PLAN_CANONICAL")
    }

    /**
     * FR-SYNC-004, for the six aggregates that could not receive one either.
     *
     * A second client writes one of each over HTTP, under its own `origin.id`, and the phone
     * applies them as ordinary data on its next pull. The activity session is the interesting one:
     * it references a definition this phone has never held, which `strength_exercises`' `RESTRICT`
     * foreign key would refuse — the session carries a snapshot precisely so the transaction that
     * advances the cursor cannot abort on it.
     */
    @Test
    fun oneOfEachAggregateWrittenOnTheServerReachesThisDevice() {
        val server = liveServer() ?: return
        WorkManager.getInstance(application).cancelUniqueWork(SyncScheduler.PERIODIC_WORK)

        val paired = runBlocking {
            sync.pairing.pair(
                server,
                requireNotNull(argument("mueLiveEmail")),
                requireNotNull(argument("mueLivePassword")),
            )
        }
        assertTrue("pairing refused: $paired", paired is PairingResult.Paired)
        runBlocking { sync.engine.sync() }

        val response = pushAsSecondClient(server)
        val refused = response.results.filterIsInstance<MutationRejectedDto>()
        assertTrue("the server refused a second client's write: $refused", refused.isEmpty())
        assertTrue(
            "nothing was applied: ${response.results}",
            response.results.all { it is MutationAppliedDto || it is MutationDuplicateDto },
        )

        runBlocking { sync.engine.sync() }

        // Read back through the repositories the screens read through, not through the DAOs: a
        // row that lands in a column no reader maps is a row that arrived and cannot be seen.
        val session = runBlocking { container.activityRepository.findDetail(ActivityId(REMOTE_SESSION_ID)) }
        assertNotNull("the session never arrived", session)
        requireNotNull(session)
        assertEquals(Movement.CYCLING, session.session.movement)
        assertEquals(2_700, session.session.duration.seconds)
        assertEquals(1, session.metrics.values.size)
        assertEquals(1, session.exercises.size)
        assertEquals(2, session.exercises.single().sets.size)
        assertEquals(
            "the definition the session referenced was materialised from its snapshot",
            "Kettlebell swing",
            session.exercises.single().definition.name,
        )

        val definition = runBlocking {
            container.exerciseCatalogRepository.findById(
                fr.kristenjestin.mue.domain.model.ExerciseDefinitionId(REMOTE_DEFINITION_ID),
            )
        }
        assertNotNull("the definition never arrived", definition)
        assertEquals("Farmer carry", definition?.name)
        assertTrue("a received definition is a personal one", definition?.isCustom == true)

        val food = runBlocking { container.food.foodCatalogueRepository.findById(FoodId(REMOTE_FOOD_ID)) }
        assertNotNull("the food never arrived", food)
        assertEquals("Skyr nature", food?.name)
        assertEquals(
            "an unknown macro must not arrive as a zero",
            null,
            food?.per100?.fibre,
        )

        val recipe = runBlocking { container.food.recipeRepository.findDetail(RecipeId(REMOTE_RECIPE_ID)) }
        assertNotNull("the recipe never arrived", recipe)
        assertEquals("Skyr bowl", recipe?.recipe?.name)
        assertEquals("a recipe never arrives without its ingredients", 2, recipe?.ingredients?.size)

        val line = runBlocking { container.food.foodLogRepository.findById(FoodLogEntryId(REMOTE_LINE_ID)) }
        assertNotNull("the journal line never arrived", line)
        assertEquals("Skyr bowl", line?.title)
        assertEquals(
            "and it still names the proposal it came from",
            MealPlanKey(REMOTE_DAY, MealSlot.DINNER),
            line?.fromPlan,
        )

        val plan = runBlocking {
            container.food.mealPlanRepository.find(MealPlanKey(REMOTE_DAY, MealSlot.DINNER))
        }
        assertNotNull("the proposal never arrived", plan)
        assertEquals(RecipeId(REMOTE_RECIPE_ID), plan?.recipeId)

        Log.i(TAG, "received session=$REMOTE_SESSION_ID definition=$REMOTE_DEFINITION_ID food=$REMOTE_FOOD_ID recipe=$REMOTE_RECIPE_ID line=$REMOTE_LINE_ID plan=$REMOTE_DAY:dinner")
    }

    // --- what this device writes -------------------------------------------------------------

    private fun written(): List<Pair<String, String>> = listOf(
        SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION to SESSION_ID,
        SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE to definitionId,
        FoodAggregates.TYPE_FOOD to FOOD_ID,
        FoodAggregates.TYPE_RECIPE to RECIPE_ID,
        FoodAggregates.TYPE_FOOD_LOG_ENTRY to LINE_ID,
        FoodAggregates.TYPE_MEAL_PLAN_ENTRY to "$PLAN_DAY:dinner",
    )

    private lateinit var definitionId: String

    private fun writeOneOfEach() = runBlocking {
        // A personal definition, created the way the strength editor creates one.
        val definition = container.exerciseCatalogRepository.findOrCreate(
            name = "Sled push $PLAN_DAY",
            trackingMode = TrackingMode.DURATION,
            equipment = EquipmentType.MACHINE,
        )
        definitionId = definition.id.value

        val exerciseId = StrengthExerciseId("6a1f7c25-9b30-4d6a-8e72-8a1c5f9d3b64")
        container.activityRepository.save(
            ActivitySessionDetail(
                session = ActivitySession(
                    id = ActivityId(SESSION_ID),
                    movement = Movement.STRENGTH_TRAINING,
                    startedOn = PLAN_DAY,
                    duration = requireNotNull(ActivityDuration.ofSessionOrNull(1, 0)),
                    environment = ActivityEnvironment.INDOOR,
                    startedAtTime = LocalTime.of(18, 30),
                    notes = "Written by LiveAllAggregatesSyncTest.",
                    source = ActivitySource.MANUAL,
                ),
                metrics = ActivityMetrics.of(
                    ActivityMetric(MetricKind.ESTIMATED_ENERGY, 380, MetricSource.MANUAL),
                ),
                equipment = listOf(SessionEquipment(EquipmentType.MACHINE, position = 0)),
                exercises = listOf(
                    StrengthExerciseDetail(
                        exercise = StrengthExercise(exerciseId, position = 0),
                        definition = definition,
                        sets = listOf(
                            StrengthSet(
                                id = StrengthSetId("5f0e6b14-8a2f-4c59-9d61-7f0b4e8c2a53"),
                                position = 0,
                                setType = SetType.WORKING,
                                duration = requireNotNull(ActivityDuration.ofSecondsOrNull(45)),
                                load = Load.ofGramsOrNull(60_000),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val food = Food(
            id = FoodId(FOOD_ID),
            name = "Skyr nature (proof)",
            source = FoodSource.CUSTOM,
            referenceUnit = ReferenceUnit.GRAM,
            per100 = Nutrients(energy = requireNotNull(fr.kristenjestin.mue.domain.model.Energy.ofPer100OrNull(63.0))),
        )
        assertTrue("the food was refused", container.food.foodCatalogueRepository.save(food))

        container.food.recipeRepository.save(
            RecipeDetail(
                recipe = Recipe(
                    id = RecipeId(RECIPE_ID),
                    name = "Skyr bowl (proof)",
                    type = RecipeType.BREAKFAST,
                    baseServings = 2,
                ),
                ingredients = listOf(
                    RecipeIngredient(
                        id = RecipeIngredientId("c6f9a382-1d4b-4e7a-9f23-0a5b8c1d6e49"),
                        foodId = FoodId(FOOD_ID),
                        quantity = requireNotNull(Quantity.ofIngredientOrNull(300.0)),
                        unit = ReferenceUnit.GRAM,
                        position = 0,
                        foodName = "Skyr nature (proof)",
                    ),
                ),
            ),
        )

        container.food.mealPlanRepository.save(
            MealPlanEntry(
                plannedOn = PLAN_DAY,
                slot = MealSlot.DINNER,
                recipeId = RecipeId(RECIPE_ID),
                plannedServings = requireNotNull(Servings.ofConsumedOrNull(1.5)),
            ),
        )

        container.food.foodLogRepository.save(
            FoodLogEntry(
                id = FoodLogEntryId(LINE_ID),
                consumedOn = PLAN_DAY,
                consumedAt = LocalTime.of(20, 15),
                slot = MealSlot.DINNER,
                kind = FoodLogKind.RECIPE,
                title = "Skyr bowl (proof)",
                amount = LoggedAmount.Portioned(requireNotNull(Servings.ofConsumedOrNull(1.5))),
                nutrients = Nutrients(
                    energy = requireNotNull(fr.kristenjestin.mue.domain.model.Energy.ofQuickAddOrNull(284.0)),
                ),
                estimation = Estimation.MEASURED,
                sourceRef = RECIPE_ID,
                fromPlan = MealPlanKey(PLAN_DAY, MealSlot.DINNER),
            ),
        )
    }

    /**
     * The row every phone already holds: a proposal journalled under the separator
     * `aggregateIdSchema` has never accepted.
     *
     * `enqueueMutation` and not the repository, for the reason `LiveOutboxRepairTest` gives about
     * `SyncOutbox`: the repository writes the *correct* identifier now, which is the fix this is
     * meant to look past. The point is the row that was already there.
     */
    private fun journalTheLegacyMealPlanRow() = runBlocking {
        sync.syncDao.enqueueMutation(
            SyncMutationEntity(
                mutationId = MutationIds.random(),
                aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                aggregateId = LEGACY_PLAN_STORED,
                op = SyncMutationEntity.OP_UPSERT,
                baseRevision = null,
                payload = """
                    {"plannedOn":"$LEGACY_PLAN_DAY","slot":"lunch","recipeId":"$RECIPE_ID",
                     "plannedServingsThousandths":1000}
                """.trimIndent(),
                payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
                createdAt = System.currentTimeMillis(),
                state = SyncMutationEntity.STATE_PENDING,
                attemptCount = 0,
                lastErrorCode = null,
                lastErrorMessage = null,
            ),
        )
        sync.syncDao.insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(FoodAggregates.TYPE_MEAL_PLAN_ENTRY, LEGACY_PLAN_STORED),
        )
    }

    // --- what a second client writes ---------------------------------------------------------

    /**
     * A second client, and genuinely one: a different `origin.id` authoring against the same
     * account.
     *
     * It reuses `SyncContainer.httpClient` on purpose — PRD 16 has the pairing verify the
     * certificate of the address that was entered, and a second trust configuration would prove
     * nothing about the one synchronisation actually uses. The bearer is the phone's, because the
     * *account* is the same account: origins travel inside the envelope (PRD 12.2), not in the
     * session.
     */
    private fun pushAsSecondClient(server: String): PushResponseDto = runBlocking {
        val token = requireNotNull(sync.tokenStore.read()) { "the phone holds no bearer" }
        val laptop = OriginDto(OriginDto.TYPE_ANDROID, "device-laptop")
        val now = Instant.now().toString()

        val mutations = listOf<MutationEnvelopeDto>(
            ActivitySessionUpsertMutationDto(
                mutationId = REMOTE_SESSION_MUTATION,
                aggregateId = REMOTE_SESSION_ID,
                baseRevision = null,
                payloadSchemaVersion = 1,
                payload = ActivitySessionPayloadV1Dto(
                    id = REMOTE_SESSION_ID,
                    movement = "cycling",
                    customMovementName = null,
                    environment = "outdoor",
                    startedOn = REMOTE_DAY.toString(),
                    startedAtTime = "07:15",
                    durationSeconds = 2_700,
                    perceivedEffort = 6,
                    notes = null,
                    source = "manual",
                    metrics = listOf(
                        fr.kristenjestin.mue.data.remote.sync.ActivityMetricDto(
                            kind = "distance",
                            value = 24_000,
                            source = "wearable",
                        ),
                    ),
                    equipment = listOf(
                        fr.kristenjestin.mue.data.remote.sync.SessionEquipmentDto(
                            equipmentType = "bicycle",
                            customName = null,
                            position = 0,
                        ),
                    ),
                    exercises = listOf(
                        fr.kristenjestin.mue.data.remote.sync.StrengthExerciseDto(
                            id = "7b2c8d36-0a41-4e7b-9f83-9b2d6a0e4c75",
                            position = 0,
                            notes = null,
                            definition = fr.kristenjestin.mue.data.remote.sync
                                .ExerciseDefinitionSnapshotDto(
                                    id = REMOTE_SNAPSHOT_DEFINITION_ID,
                                    name = "Kettlebell swing",
                                    trackingMode = "reps_only",
                                    equipment = "kettlebell",
                                    isCustom = true,
                                ),
                            sets = listOf(
                                fr.kristenjestin.mue.data.remote.sync.StrengthSetDto(
                                    id = "8c3d9e47-1b52-4f8c-8a94-0c3e7b1f5d86",
                                    position = 0,
                                    setType = "working",
                                    repetitions = 20,
                                    loadGrams = 24_000,
                                    durationSeconds = null,
                                    perceivedEffort = null,
                                ),
                                fr.kristenjestin.mue.data.remote.sync.StrengthSetDto(
                                    id = "9d4e0f58-2c63-4a9d-8ba5-1d4f8c2a6e97",
                                    position = 1,
                                    setType = "drop",
                                    repetitions = 15,
                                    loadGrams = null,
                                    durationSeconds = null,
                                    perceivedEffort = 8,
                                ),
                            ),
                        ),
                    ),
                ),
                origin = laptop,
                clientOccurredAt = now,
            ),
            CustomExerciseUpsertMutationDto(
                mutationId = REMOTE_DEFINITION_MUTATION,
                aggregateId = REMOTE_DEFINITION_ID,
                baseRevision = null,
                payloadSchemaVersion = 1,
                payload = CustomExerciseDefinitionPayloadV1Dto(
                    id = REMOTE_DEFINITION_ID,
                    name = "Farmer carry",
                    trackingMode = "duration",
                    equipment = "dumbbells",
                ),
                origin = laptop,
                clientOccurredAt = now,
            ),
            FoodUpsertMutationDto(
                mutationId = REMOTE_FOOD_MUTATION,
                aggregateId = REMOTE_FOOD_ID,
                baseRevision = null,
                payloadSchemaVersion = 1,
                payload = FoodPayloadV1Dto(
                    id = REMOTE_FOOD_ID,
                    name = "Skyr nature",
                    source = "open_food_facts",
                    referenceUnit = "gram",
                    rawLabel = "Raw",
                    cookedLabel = "Cooked",
                    energyMilliKcal = 63_000,
                    proteinMilligrams = 10_400,
                    barcode = "5701092103246",
                ),
                origin = laptop,
                clientOccurredAt = now,
            ),
            RecipeUpsertMutationDto(
                mutationId = REMOTE_RECIPE_MUTATION,
                aggregateId = REMOTE_RECIPE_ID,
                baseRevision = null,
                payloadSchemaVersion = 1,
                payload = RecipePayloadV1Dto(
                    id = REMOTE_RECIPE_ID,
                    name = "Skyr bowl",
                    type = "breakfast",
                    baseServings = 2,
                    isFavourite = true,
                    ingredients = listOf(
                        RecipeIngredientDto(
                            id = "a4e7b169-3d74-4b0e-9cb6-2e5f9d3b7fa8",
                            foodId = REMOTE_FOOD_ID,
                            quantityThousandths = 300_000,
                            unit = "gram",
                            position = 0,
                            foodName = "Skyr nature",
                        ),
                        RecipeIngredientDto(
                            id = "b5f8c27a-4e85-4c1f-8dc7-3f60ae4c80b9",
                            foodId = "c609d38b-5f96-4d20-9ed8-4071bf5d91ca",
                            quantityThousandths = 80_000,
                            unit = "gram",
                            position = 1,
                            foodName = "Myrtilles",
                        ),
                    ),
                    steps = listOf("Spoon the skyr.", "Top with the blueberries."),
                ),
                origin = laptop,
                clientOccurredAt = now,
            ),
            MealPlanEntryUpsertMutationDto(
                mutationId = REMOTE_PLAN_MUTATION,
                aggregateId = "$REMOTE_DAY:dinner",
                baseRevision = null,
                payloadSchemaVersion = 1,
                payload = MealPlanEntryPayloadV1Dto(
                    plannedOn = REMOTE_DAY.toString(),
                    slot = "dinner",
                    recipeId = REMOTE_RECIPE_ID,
                    plannedServingsThousandths = 1_500,
                ),
                origin = laptop,
                clientOccurredAt = now,
            ),
            FoodLogEntryUpsertMutationDto(
                mutationId = REMOTE_LINE_MUTATION,
                aggregateId = REMOTE_LINE_ID,
                baseRevision = null,
                payloadSchemaVersion = 1,
                payload = FoodLogEntryPayloadV1Dto(
                    id = REMOTE_LINE_ID,
                    consumedOn = REMOTE_DAY.toString(),
                    consumedAt = "20:15",
                    slot = "dinner",
                    kind = "recipe",
                    title = "Skyr bowl",
                    estimation = "measured",
                    weighedCooked = false,
                    energyMilliKcal = 284_000,
                    sourceRef = REMOTE_RECIPE_ID,
                    quantityThousandths = 1_500,
                    quantityUnit = "serving",
                    fromPlan = "$REMOTE_DAY:dinner",
                ),
                origin = laptop,
                clientOccurredAt = now,
            ),
        )

        val response = sync.httpClient.post("${server.trimEnd('/')}/api/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(PushRequestDto(mutations))
        }
        SyncJson.instance.decodeFromString(serializer<PushResponseDto>(), response.bodyAsText())
    }

    // --- arguments ---------------------------------------------------------------------------

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private fun liveServer(): String? {
        val server = argument("mueLiveServer")
        assumeTrue(
            "set -e mueLiveServer/mueLiveEmail/mueLivePassword to run this against a server",
            !server.isNullOrBlank() &&
                !argument("mueLiveEmail").isNullOrBlank() &&
                !argument("mueLivePassword").isNullOrBlank(),
        )
        return server
    }

    private companion object {
        const val TAG = "LiveAllAggregates"

        val PLAN_DAY: LocalDate = LocalDate.of(2026, 9, 1)
        val REMOTE_DAY: LocalDate = LocalDate.of(2026, 9, 3)

        const val SESSION_ID = "3a0f7b26-9c41-4a5e-8d13-6f2b8e04c751"
        const val FOOD_ID = "7c3d9e15-6a2b-4f80-9c47-1e5d8a2b3c40"
        const val RECIPE_ID = "b5e8f271-0c3a-4d69-8e12-9f4a7b0c5d38"
        const val LINE_ID = "2c5fa948-7d01-4e30-9589-6a1b4c7d2e05"

        /** `2026-09-02/lunch`, exactly as a previous build wrote it. */
        const val LEGACY_PLAN_DAY = "2026-09-02"
        const val LEGACY_PLAN_STORED = "$LEGACY_PLAN_DAY/lunch"
        const val LEGACY_PLAN_CANONICAL = "$LEGACY_PLAN_DAY:lunch"

        const val REMOTE_SESSION_ID = "1e5c9a37-8b02-4d61-9f74-5a2c8e0b3d19"
        const val REMOTE_DEFINITION_ID = "2f6d0b48-9c13-4e72-8a85-6b3d9f1c4e20"
        const val REMOTE_SNAPSHOT_DEFINITION_ID = "3a7e1c59-0d24-4f83-9b96-7c4e0a2d5f31"
        const val REMOTE_FOOD_ID = "4b8f2d60-1e35-4a94-8ca7-8d5f1b3e6042"
        const val REMOTE_RECIPE_ID = "5c903e71-2f46-4ba5-9db8-9e602c4f7153"
        const val REMOTE_LINE_ID = "6da14f82-3057-4cb6-8ec9-0f713d508264"

        // Fixed UUIDv7s, so a re-run replays FR-SYNC-006 rather than applying twice.
        const val REMOTE_SESSION_MUTATION = "0198f0b0-0001-7000-8000-000000000001"
        const val REMOTE_DEFINITION_MUTATION = "0198f0b0-0002-7000-8000-000000000002"
        const val REMOTE_FOOD_MUTATION = "0198f0b0-0003-7000-8000-000000000003"
        const val REMOTE_RECIPE_MUTATION = "0198f0b0-0004-7000-8000-000000000004"
        const val REMOTE_PLAN_MUTATION = "0198f0b0-0005-7000-8000-000000000005"
        const val REMOTE_LINE_MUTATION = "0198f0b0-0006-7000-8000-000000000006"
    }
}
