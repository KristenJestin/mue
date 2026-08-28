package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.database.decodeSteps
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import fr.kristenjestin.mue.domain.model.FoodAggregates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six aggregates that never reached the server, each carried from a stored outbox row to the
 * wire and back into a Room entity.
 *
 * These are *value* tests, not shape tests, and that distinction is the point of the file.
 * `ContractDrift` already proves the DTOs hold the committed fixtures — it compares JSON trees —
 * and it is blind to everything this contract narrows: a separator outside the identifier
 * alphabet, a serving count off its step, an unknown nutrient turned into a zero. Each of those
 * is the right shape and the wrong content, which is exactly how a UUIDv4 where a v7 was required
 * refused every push a phone ever made without anything looking wrong.
 */
class SyncWireAggregatesTest {

    private val origin = OriginDto(OriginDto.TYPE_ANDROID, "device-7f3c1a04")

    private fun row(
        aggregateType: String,
        aggregateId: String,
        payload: String?,
        op: String = SyncMutationEntity.OP_UPSERT,
        baseRevision: Long? = null,
    ) = SyncMutationEntity(
        mutationId = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        op = op,
        baseRevision = baseRevision,
        payload = payload,
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = 1_774_425_124_117L,
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    private val sessionId = "3a0f7b26-9c41-4a5e-8d13-6f2b8e04c751"

    private val sessionPayload = """
        {"id":"$sessionId","movement":"strength_training","customMovementName":null,
         "environment":"indoor","startedOn":"2026-08-25","startedAtTime":"18:30",
         "durationSeconds":3600,"perceivedEffort":7,"notes":"Felt strong.","source":"manual",
         "metrics":[{"kind":"estimated_energy","value":380,"source":"manual"}],
         "equipment":[{"equipmentType":"other","customName":"  Home RACK ","position":0}],
         "exercises":[{"id":"1f6a2d70-4c8b-4e15-9f27-3b6d0a4e8c19","position":0,"notes":null,
           "definition":{"id":"8b2b1c9a-3a4f-4b1c-9d5e-7f8a0b1c2d3e","name":"Bench press",
             "trackingMode":"weight_and_reps","equipment":"barbell","isCustom":false},
           "sets":[{"id":"2c7b3e81-5d9c-4f26-8a38-4c7e1b5f9d20","position":0,"setType":"working",
             "repetitions":5,"loadGrams":82500,"durationSeconds":null,"perceivedEffort":null}]}]}
    """.trimIndent()

    /**
     * PRD 10.2, at the seam where a partial session could have been invented.
     *
     * The metric, the equipment, the exercise, its definition snapshot and its set all cross in
     * one envelope. Room holds them in five tables and the wire has one payload, so there is no
     * shape here that a partial synchronisation could produce.
     */
    @Test
    fun aSessionCrossesWithEveryChildItHas() {
        val envelope = assertIs<ActivitySessionUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION, sessionId, sessionPayload),
                origin,
            ),
        )

        assertEquals(WIRE_AGGREGATE_ACTIVITY_SESSION, envelope.aggregateType)
        assertEquals(sessionId, envelope.aggregateId)
        assertEquals(3_600, envelope.payload.durationSeconds)
        assertEquals(1, envelope.payload.metrics.size)
        assertEquals(380, envelope.payload.metrics.single().value)
        assertEquals(1, envelope.payload.equipment.size)
        assertEquals(1, envelope.payload.exercises.size)
        assertEquals(82_500, envelope.payload.exercises.single().sets.single().loadGrams)
        assertEquals("Bench press", envelope.payload.exercises.single().definition.name)
    }

    /**
     * A stated null survives; it is not dropped and it is not turned into a zero.
     *
     * `SyncJson` does not encode defaults, so a `.nullable()` field given a Kotlin default would
     * vanish from a body the server requires — the failure is invisible in the payload class and
     * loud on the wire. PRD_ACTIVITIES 9.4 is the rule being kept: *"un champ non renseigné vaut
     * `null`, jamais `0`"*.
     */
    @Test
    fun anUnrecordedSetFieldIsWrittenAsNullAndNeverAsZero() {
        val envelope = SyncWire.toEnvelope(
            row(SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION, sessionId, sessionPayload),
            origin,
        )
        val text = SyncJson.instance.encodeToString(
            MutationEnvelopeSerializer,
            assertIs<MutationEnvelopeDto>(envelope),
        )

        assertTrue(text.contains("\"durationSeconds\":null"), text)
        assertTrue(text.contains("\"perceivedEffort\":null"), text)
        assertTrue(text.contains("\"customMovementName\":null"), text)
        assertTrue(!text.contains("\"loadGrams\":0"), text)
    }

    /** The folded name the unique index compares is derived here, never carried on the wire. */
    @Test
    fun aReceivedSessionFoldsItsEquipmentNameForTheIndexThatComparesIt() {
        val payload = assertIs<ActivitySessionUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION, sessionId, sessionPayload),
                origin,
            ),
        ).payload

        val entities = SyncWire.sessionEquipmentEntities(payload) { "equipment-row-1" }

        assertEquals("home rack", entities.single().customNameFolded)
        assertEquals("  Home RACK ", entities.single().customName)
        assertEquals("equipment-row-1", entities.single().id)
    }

    /**
     * A definition arriving as its own aggregate is personal, and the payload has no field that
     * could say otherwise (PRD 10.1: the shipped catalogue is not synchronised).
     */
    @Test
    fun aReceivedDefinitionIsCustomBecauseItsAggregateTypeSaysSo() {
        val entity = SyncWire.customExerciseEntity(
            CustomExerciseDefinitionPayloadV1Dto(
                id = "d41f6c58-7b90-4e2a-8c31-5a6b7c8d9e0f",
                name = "  Bulgarian Split Squat ",
                trackingMode = "weight_and_reps",
                equipment = "dumbbells",
            ),
        )

        assertTrue(entity.isCustom)
        assertEquals("bulgarian split squat", entity.nameFolded)
        assertEquals("  Bulgarian Split Squat ", entity.name)
    }

    /**
     * A definition *inside* a session may be one of the seventeen Mue ships, and its snapshot
     * says so. The two shapes differ for that reason rather than by accident.
     */
    @Test
    fun aDefinitionSnapshotKeepsWhetherMueShippedIt() {
        val entity = SyncWire.definitionSnapshotEntity(
            ExerciseDefinitionSnapshotDto(
                id = "8b2b1c9a-3a4f-4b1c-9d5e-7f8a0b1c2d3e",
                name = "Bench press",
                trackingMode = "weight_and_reps",
                equipment = "barbell",
                isCustom = false,
            ),
        )

        assertTrue(!entity.isCustom)
        assertEquals("bench press", entity.nameFolded)
    }

    /**
     * PRD_FOOD 13.1, through the seam: an unknown nutrient has no key at all, and a measured zero
     * does. A payload that wrote `0` for the unknown one would have the server store a claim the
     * phone never made and hand it back as fact.
     */
    @Test
    fun anUnknownNutrientStaysAbsentAndAMeasuredZeroStaysZero() {
        val envelope = assertIs<FoodUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    FoodAggregates.TYPE_FOOD,
                    "0d1e2f30-4a5b-4c60-9d71-8e9f0a1b2c34",
                    """
                    {"id":"0d1e2f30-4a5b-4c60-9d71-8e9f0a1b2c34","name":"Cafe noir",
                     "source":"custom","referenceUnit":"millilitre","rawLabel":"Raw",
                     "cookedLabel":"Cooked","energyMilliKcal":0}
                    """.trimIndent(),
                ),
                origin,
            ),
        )

        assertEquals(0, envelope.payload.energyMilliKcal)
        assertNull(envelope.payload.proteinMilligrams)

        val text = SyncJson.instance.encodeToString(
            MutationEnvelopeSerializer,
            envelope as MutationEnvelopeDto,
        )
        assertTrue(text.contains("\"energyMilliKcal\":0"), text)
        assertTrue(!text.contains("proteinMilligrams"), "an unknown macro has no key at all: $text")
    }

    /** A recipe with no steps journalled no `steps` key, and it still crosses. */
    @Test
    fun aSteplessRecipeCrossesWithoutTheKeyItNeverWrote() {
        val envelope = assertIs<RecipeUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    FoodAggregates.TYPE_RECIPE,
                    "f92cd615-4a7e-4b0d-8256-3d8e1f4a9b72",
                    """
                    {"id":"f92cd615-4a7e-4b0d-8256-3d8e1f4a9b72","name":"Oeufs durs",
                     "type":"snack","baseServings":12,"isFavourite":false,
                     "ingredients":[{"id":"0a3de726-5b8f-4c1e-9367-4e9f2a5b0c83",
                       "foodId":"1b4ef837-6c90-4d2f-8478-5f0a3b6c1d94",
                       "quantityThousandths":60000,"unit":"gram","position":0}]}
                    """.trimIndent(),
                ),
                origin,
            ),
        )

        assertEquals(1, envelope.payload.ingredients.size)
        assertEquals(emptyList(), envelope.payload.steps)
        assertEquals(emptyList(), decodeSteps(SyncWire.recipeEntity(envelope.payload, 0L).steps))
    }

    /**
     * The trap, at the seam that would have met it.
     *
     * Every meal-plan row on this phone spells its identifier with a `/`, which
     * `aggregateIdSchema` has never accepted. The wire identifier is rebuilt from the payload, so
     * the row goes out correctly whether or not `MealPlanIdRepair` has reached it — which is what
     * a phone upgrading with a queue already in it actually has.
     */
    @Test
    fun aProposalJournalledWithASlashGoesOutWithTheColonTheContractAccepts() {
        val envelope = assertIs<MealPlanEntryUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                    "2026-09-01/dinner",
                    """
                    {"plannedOn":"2026-09-01","slot":"dinner",
                     "recipeId":"b5e8f271-0c3a-4d69-8e12-9f4a7b0c5d38",
                     "plannedServingsThousandths":1500}
                    """.trimIndent(),
                ),
                origin,
            ),
        )

        assertEquals("2026-09-01:dinner", envelope.aggregateId)
        assertEquals("2026-09-01", envelope.payload.plannedOn)
        assertEquals("dinner", envelope.payload.slot)
        assertTrue(AGGREGATE_ID.matches(envelope.aggregateId))
    }

    /** A tombstone for a proposal is normalised too, and by the same rule. */
    @Test
    fun aProposalTombstoneIsNormalisedOnTheWayOut() {
        val envelope = assertIs<DeleteMutationDto>(
            SyncWire.toEnvelope(
                row(
                    FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
                    "2026-09-01/dinner",
                    payload = null,
                    op = SyncMutationEntity.OP_DELETE,
                ),
                origin,
            ),
        )

        assertEquals("2026-09-01:dinner", envelope.aggregateId)
    }

    /**
     * The provenance a line carries is the *same* identifier, so it is normalised the same way —
     * and the stored payload is not rewritten to achieve it.
     *
     * `FoodLogEntryPayloadV1.fromPlan` is validated by the meal plan's own schema on the server,
     * so a `/` in it refuses the whole line. Repairing it in storage would mean decoding, editing
     * and re-encoding a record of what the user ate; normalising it here costs one parse and
     * leaves that record alone.
     */
    @Test
    fun aLineNamesItsProposalWithTheSeparatorTheContractAccepts() {
        val envelope = assertIs<FoodLogEntryUpsertMutationDto>(
            SyncWire.toEnvelope(
                row(
                    FoodAggregates.TYPE_FOOD_LOG_ENTRY,
                    "2c5fa948-7d01-4e30-9589-6a1b4c7d2e05",
                    """
                    {"id":"2c5fa948-7d01-4e30-9589-6a1b4c7d2e05","consumedOn":"2026-09-01",
                     "consumedAt":"20:15","slot":"dinner","kind":"recipe","title":"Skyr bowl",
                     "estimation":"measured","weighedCooked":false,
                     "quantityThousandths":1500,"quantityUnit":"serving",
                     "fromPlan":"2026-09-01/dinner"}
                    """.trimIndent(),
                ),
                origin,
            ),
        )

        assertEquals("2026-09-01:dinner", envelope.payload.fromPlan)

        // And it is split back into the two columns the table holds, not stored as a composite.
        val entity = SyncWire.foodLogEntryEntity(envelope.payload, at = 0L)
        assertEquals("2026-09-01", entity.plannedOn)
        assertEquals("dinner", entity.planSlot)
    }

    /** Neither of the two aggregates without a deletion can put one on the wire. */
    @Test
    fun anAggregateWithNoDeletionHasNoDeleteBranch() {
        assertNull(
            SyncWire.toEnvelope(
                row(
                    SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                    "me",
                    payload = null,
                    op = SyncMutationEntity.OP_DELETE,
                ),
                origin,
            ),
        )
        assertNull(
            SyncWire.toEnvelope(
                row(
                    SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE,
                    "d41f6c58-7b90-4e2a-8c31-5a6b7c8d9e0f",
                    payload = null,
                    op = SyncMutationEntity.OP_DELETE,
                ),
                origin,
            ),
        )
    }

    /**
     * Every type a send may select is one the client also declares it can apply, and one with a
     * local table behind it. The eight of PRD 10.1's matrix, and no ninth.
     */
    @Test
    fun theEightAggregatesOfTheMatrixAreSendableDeclaredAndApplicable() {
        assertEquals(8, SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES.size)
        assertEquals(8, SyncWire.SUPPORTED_SCHEMA_VERSIONS.size)
        assertEquals(
            SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES.sorted(),
            SyncWire.SUPPORTED_SCHEMA_VERSIONS.keys
                .mapNotNull(SyncWire::localAggregateType)
                .sorted(),
        )
    }

    private companion object {
        /**
         * `aggregateIdSchema` in `packages/contracts/src/primitives.ts`, transcribed.
         *
         * Written out rather than referenced, because it is the rule a stored identifier is judged
         * against on a machine this test cannot reach — and a copy that drifted towards what this
         * build happens to emit would assert nothing.
         */
        val AGGREGATE_ID = Regex("^[A-Za-z0-9._:-]+$")
    }
}
