package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.logic.errorMessage
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The contract test of PRD 20.4: the hand-written Kotlin DTOs against the fixtures
 * `packages/contracts` emits from its Zod schemas.
 *
 * It runs on the JVM, offline. Nothing here starts a server, opens a socket, boots an emulator
 * or needs Bun installed — which is what lets it run on every `testDebugUnitTest`, which is in
 * turn the only reason a drift detector is worth having. A contract test that only runs in CI on
 * a good day is a contract test nobody sees fail.
 *
 * The three assertions below are deliberately separate:
 *
 * - **every fixture on disk is in the manifest**, so a file cannot land unnoticed;
 * - **every manifest entry has a Kotlin consumer**, so a schema cannot land unconsumed — which
 *   is precisely the state these fixtures were found in;
 * - **every fixture round-trips**, which is the drift detection itself.
 */
class ContractDriftTest {

    @Test
    fun theManifestListsEveryFixtureOnDisk() {
        val listed = ContractFixtures.manifest().map { it.file }.sorted()
        val onDisk = ContractFixtures.files().filterNot { it == ContractFixtures.MANIFEST }

        // Thirty-three files: thirty-two instances, each parsed through its Kotlin DTO below, and
        // the manifest, parsed by `ContractFixtures.manifest`. Every file the contracts package
        // emits has a reader here — which is exactly what they did not have when they landed.
        //
        // It was nineteen until six aggregates joined the contract at once. The number is written
        // out rather than derived from the directory listing on purpose: deriving it would make
        // this assertion true of any directory, including one a failed emit left half written.
        assertEquals(
            33,
            ContractFixtures.files().size,
            "the contracts package emits thirty-three files and every one of them is read here",
        )

        assertEquals(
            onDisk,
            listed,
            "src/test/resources/contract and its index.json disagree. Re-emit both with " +
                "`bun packages/contracts/src/fixtures.ts`.",
        )
    }

    @Test
    fun everyFixtureSchemaHasAKotlinConsumer() {
        val orphans = ContractFixtures.manifest()
            .map { it.schema }
            .distinct()
            .filterNot { it in ContractFixtures.CONSUMERS }

        assertTrue(
            orphans.isEmpty(),
            "the contracts package ships fixtures for $orphans and no Kotlin DTO consumes " +
                "them. Declare the DTO in SyncDto.kt and register it in " +
                "ContractFixtures.CONSUMERS, or Android is not testing that half of the " +
                "contract at all.",
        )
    }

    @Test
    fun everyFixtureRoundTripsThroughItsKotlinDto() {
        val entries = ContractFixtures.manifest()
        assertEquals(32, entries.size, "the manifest lost or gained a fixture")

        val drift = entries.flatMap { entry ->
            ContractDrift.check(
                file = entry.file,
                schema = entry.schema,
                serializer = ContractFixtures.CONSUMERS.getValue(entry.schema),
                text = ContractFixtures.read(entry.file),
            )
        }

        assertTrue(
            drift.isEmpty(),
            "the Kotlin sync DTOs have drifted from packages/contracts:\n" +
                drift.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The `upgrade_required` branch has no `nextCursor` **as a Kotlin type**, not as a null.
     *
     * A flat DTO with a nullable cursor would round-trip both fixtures and still be wrong: the
     * engine could read a cursor off a response that protocol-wise carries none and advance past
     * changes it cannot apply, which is exactly what PRD 12.4 forbids. This asserts the property
     * the sealed hierarchy buys, so nobody flattens it back later for convenience.
     */
    @Test
    fun theUpgradeRequiredBranchCannotCarryACursor() {
        val page = decodePullResponse("pull-response-ok.json")
        val upgrade = decodePullResponse("pull-response-upgrade-required.json")

        assertIs<PullPageDto>(page)
        assertIs<PullUpgradeRequiredDto>(upgrade)

        // The only member of the hierarchy that has a cursor at all.
        assertEquals("eyJ2IjoxLCJzZXEiOiI5MDA3MTk5MjU0NzQwOTk3In0", page.nextCursor)
        assertEquals(false, page.hasMore)
        assertEquals(SyncErrorCodes.SYNC_UPGRADE_REQUIRED, upgrade.error.code)
        assertEquals(null, upgrade.lastAndroidSyncAt)
    }

    /**
     * A sequence past 2^53 survives as text. Read as a JSON number it would come back as
     * 9007199254740992 — off by one, silently, three months after anyone looked at it.
     */
    @Test
    fun aSequencePastTheDoublePrecisionLimitSurvivesExactly() {
        val page = assertIs<PullPageDto>(decodePullResponse("pull-response-ok.json"))

        assertEquals("9007199254740993", page.changes[0].sequence)
        assertEquals("9007199254740994", page.changes[1].sequence)
        assertEquals(9_007_199_254_740_993L, SyncWire.counterOrNull(page.changes[0].sequence))
    }

    /** Every change branch, so the sealed hierarchy is exercised on both keys it turns on. */
    @Test
    fun aPageCarriesItsUpsertAndItsTombstone() {
        val page = assertIs<PullPageDto>(decodePullResponse("pull-response-ok.json"))

        val upsert = assertIs<MeasurementUpsertChangeDto>(page.changes[0])
        assertEquals("2026-08-25", upsert.aggregateId)
        assertEquals(7_845, upsert.payload.weightCg)
        assertEquals(null, upsert.meta.deletedAt)

        val delete = assertIs<DeleteChangeDto>(page.changes[1])
        assertEquals("2026-08-24", delete.aggregateId)
        assertEquals("2026-08-25T06:12:05.310Z", delete.meta.deletedAt)

        // The second upsert branch: same `op`, different `aggregateType`. kotlinx cannot
        // discriminate that with an annotation, so reaching this line at all is what says
        // [SyncChangeSerializer] reads both keys rather than the first one it finds.
        val profile = assertIs<HealthProfileUpsertChangeDto>(page.changes[2])
        assertEquals("me", profile.aggregateId)
        assertEquals(171, profile.payload.heightCm)
        assertEquals("1998-11-18", profile.payload.birthDate)
    }

    /**
     * The health profile's payload, as a **value** and not as a shape.
     *
     * This is the lesson of `MutationIds`, applied where it was learned. `SyncOutbox` minted a
     * UUIDv4 where `mutationIdSchema` says `z.uuidv7()`, every push came back
     * `sync.invalid_payload`, and [ContractDrift] could not see it: a v4 and a v7 round-trip
     * identically because they are the same shape. So the constraints `health-profile.ts` puts
     * on *content* are checked here against the value the fixture actually carries — the
     * owner's own 171 cm and 1998-11-18 — rather than against `Int?` and `String?`.
     */
    @Test
    fun theHealthProfileFixtureCarriesValuesTheContractWouldAccept() {
        val profile = SyncJson.instance.decodeFromString(
            serializer<HealthProfilePayloadV1Dto>(),
            ContractFixtures.read("health-profile-v1-valid.json"),
        )

        assertEquals(Sex.MALE.wireValue, profile.sex, "the valid instance states a sex")

        val heightCm = assertNotNull(profile.heightCm, "the valid instance states a height")
        assertTrue(
            heightCm in UserProfile.HEIGHT_RANGE_CM,
            "$heightCm is outside the range Android enforces and the contract copied",
        )

        // `z.iso.date()` validates the calendar, not just the punctuation: it is what makes
        // 1998-11-31 unrepresentable. `LocalDate.parse` is the same rule on this side.
        val birthDate = assertNotNull(profile.birthDate, "the valid instance states a birth date")
        assertEquals(LocalDate.of(1998, 11, 18), LocalDate.parse(birthDate))
        assertEquals(
            null,
            MueValidation.validateBirthDate(LocalDate.parse(birthDate), LocalDate.now())
                .errorMessage,
            "the instance the contract ships must also satisfy the rule this app applies",
        )

        // The two rules are not the same rule, and that is deliberate. Android's is relative to
        // the phone's clock; the contract's is a fixed 1900-2099, because a payload is a journal
        // snapshot that has to stay parseable for as long as the journal exists. So the *client*
        // is the one that refuses a birth date in the future, and it still does.
        assertEquals(
            MueValidation.BIRTH_DATE_ERROR,
            MueValidation.validateBirthDate(LocalDate.parse(birthDate), LocalDate.of(1998, 11, 17))
                .errorMessage,
        )
    }

    /** The cleared profile: both keys present and null, which is not the same as absent. */
    @Test
    fun aClearedProfileStatesItsNullsRatherThanOmittingThem() {
        val text = ContractFixtures.read("health-profile-v1-edge.json")
        val cleared = SyncJson.instance.decodeFromString(
            serializer<HealthProfilePayloadV1Dto>(),
            text,
        )

        assertEquals(null, cleared.heightCm)
        assertEquals(null, cleared.birthDate)
        assertEquals(null, cleared.sex)

        val written = SyncJson.instance.encodeToString(
            serializer<HealthProfilePayloadV1Dto>(),
            cleared,
        )
        assertTrue(written.contains("\"heightCm\":null"), "a cleared height is stated: $written")
        assertTrue(written.contains("\"birthDate\":null"), "a cleared date is stated: $written")

        // And the addition of PRD_SCALE 22, which is the other shape: `sex` is `.optional()`, so
        // its unstated form is the missing key. A `"sex":null` here would be refused by the
        // server, and this is what stops the DTO from ever writing one.
        assertTrue(!written.contains("sex"), "an unstated sex is absent, not null: $written")

        // An omitted key is not a third state: it does not parse.
        val failure = runCatching {
            SyncJson.instance.decodeFromString(
                serializer<HealthProfilePayloadV1Dto>(),
                """{"heightCm":171}""",
            )
        }.exceptionOrNull()
        assertIs<SerializationException>(failure)
    }

    /**
     * The two measurement instances, read as the pair they are: one carrying every field
     * PRD_SCALE 22 added, one carrying none of them.
     *
     * That pairing is the whole of the version decision, checked from the Kotlin side. The bare
     * instance is byte for byte what a build from before the scale module emits, and it must still
     * decode — a DTO that made any of the three fields mandatory fails here. The full instance must
     * come back whole and be written back whole — a DTO that dropped one fails in
     * [everyFixtureRoundTripsThroughItsKotlinDto], which is the same failure the sex suffered
     * silently until now.
     *
     * The four estimates are asserted as **values**, for the reason
     * [theHealthProfileFixtureCarriesValuesTheContractWouldAccept] gives: they are what
     * PRD_SCALE 13.2's published equations give for this weight, height, age, sex and impedance,
     * so they are simultaneously a shape check and the test vector the Kotlin calculator owes the
     * same answer to.
     */
    @Test
    fun theMeasurementFixturesPinBothHalvesOfTheDoubleOptionality() {
        val full = SyncJson.instance.decodeFromString(
            serializer<MeasurementPayloadV1Dto>(),
            ContractFixtures.read("measurement-v1-valid.json"),
        )

        assertEquals(7_845, full.weightCg)
        assertEquals(MeasurementSource.SCALE.wireValue, full.sourceType)
        assertEquals(520, full.impedanceOhm)

        val composition = assertNotNull(full.bodyComposition, "the valid instance carries one")
        assertEquals("mue-foot-to-foot-v1", composition.formulaId)
        assertEquals(1, composition.formulaVersion)
        // BR-SCALE-015, as the contract's own refinement enforces it on the way in.
        assertEquals(full.weightCg, composition.inputWeightCg)
        assertEquals(Sex.MALE.wireValue, composition.inputSex)
        assertEquals(
            listOf(5_567, 290, 519, 1_723),
            listOf(
                composition.fatFreeMassCg,
                composition.bodyFatDeciPercent,
                composition.bodyWaterDeciPercent,
                composition.restingEnergyKcal,
            ),
        )
        assertTrue(
            composition.fatFreeMassCg <= full.weightCg,
            "PRD_SCALE 13.2: 0 < FFM <= weight",
        )

        val bare = SyncJson.instance.decodeFromString(
            serializer<MeasurementPayloadV1Dto>(),
            ContractFixtures.read("measurement-v1-edge.json"),
        )

        assertEquals(3_000, bare.weightCg)
        assertEquals(null, bare.sourceType)
        assertEquals(null, bare.impedanceOhm)
        assertEquals(null, bare.bodyComposition)

        // The three additions are `.optional()` and not `.nullable()`: unset means the key is
        // gone. A `"impedanceOhm":null` would be refused by the server.
        val written = SyncJson.instance.encodeToString(serializer<MeasurementPayloadV1Dto>(), bare)
        assertEquals("""{"date":"2024-02-29","weightCg":3000}""", written)
    }

    /**
     * The mutation the phone had been holding, decoded through the two-level union.
     *
     * `op` alone cannot choose this branch — the measurement upsert answers to the same value —
     * so this passing is what says the Kotlin serializer reads `aggregateType` too.
     */
    @Test
    fun theHealthProfileUpsertIsSelectedByBothDiscriminators() {
        val envelope = SyncJson.instance.decodeFromString(
            serializer<MutationEnvelopeDto>(),
            ContractFixtures.read("mutation-upsert-health-profile-v1.json"),
        )

        val upsert = assertIs<HealthProfileUpsertMutationDto>(envelope)
        assertEquals(WIRE_HEALTH_PROFILE_AGGREGATE_ID, upsert.aggregateId)
        assertEquals(WIRE_OP_UPSERT, upsert.op)
        assertEquals(null, upsert.baseRevision)
        assertEquals(171, upsert.payload.heightCm)

        val measurement = SyncJson.instance.decodeFromString(
            serializer<MutationEnvelopeDto>(),
            ContractFixtures.read("mutation-upsert-measurement-v1.json"),
        )
        assertIs<MeasurementUpsertMutationDto>(measurement)
    }

    /** A discriminator this build does not know is a readable failure, never a wrong branch. */
    @Test
    fun anUnknownAggregateTypeOnAnUpsertIsRefusedRatherThanGuessed() {
        for (
            body in listOf(
                """{"op":"upsert","aggregateType":"recipe","aggregateId":"r-1"}""",
                """{"op":"patch","aggregateType":"measurement","aggregateId":"2026-08-25"}""",
                """{"aggregateType":"measurement","aggregateId":"2026-08-25"}""",
                """{"op":3,"aggregateType":"measurement"}""",
            )
        ) {
            val failure = runCatching {
                SyncJson.instance.decodeFromString(serializer<MutationEnvelopeDto>(), body)
            }.exceptionOrNull()

            assertIs<SerializationException>(failure, "unreadable body must not throw past sync: $body")
        }
    }

    /** FR-SYNC-006 and FR-SYNC-007 in one body: applied, duplicate and rejected side by side. */
    @Test
    fun aPushResponseCarriesAllThreeOutcomes() {
        val response = SyncJson.instance.decodeFromString(
            serializer<PushResponseDto>(),
            ContractFixtures.read("push-response.json"),
        )

        assertIs<MutationAppliedDto>(response.results[0])
        assertIs<MutationDuplicateDto>(response.results[1])
        val rejected = assertIs<MutationRejectedDto>(response.results[2])
        assertEquals(SyncErrorCodes.SYNC_REVISION_CONFLICT, rejected.error.code)
        assertEquals("7", rejected.error.currentRevision)
    }

    /** A delete carries `payload: null` on the wire; the key is required, not optional. */
    @Test
    fun aDeleteMutationWritesItsNullPayloadBack() {
        val text = SyncJson.instance.encodeToString(
            serializer<MutationEnvelopeDto>(),
            DeleteMutationDto(
                mutationId = "0198f0a1-9e8d-7c6b-b5a4-938271605f4e",
                aggregateType = WIRE_AGGREGATE_MEASUREMENT,
                aggregateId = "2026-08-24",
                baseRevision = "9",
                payloadSchemaVersion = 1,
                origin = OriginDto(OriginDto.TYPE_ANDROID, "device-7f3c1a04"),
                clientOccurredAt = "2026-08-25T06:12:05.004Z",
            ),
        )

        assertTrue(text.contains("\"payload\":null"), "a delete must carry the key: $text")
        assertTrue(text.contains("\"op\":\"delete\""), "the discriminator is `op`: $text")
    }

    /** `limit` is `.optional()` and not `.nullable()`: unset means the key is gone. */
    @Test
    fun anUnsetPullLimitIsOmittedRatherThanNull() {
        val text = SyncJson.instance.encodeToString(
            serializer<PullRequestDto>(),
            PullRequestDto(
                cursor = null,
                supportedSchemaVersions = SyncWire.SUPPORTED_SCHEMA_VERSIONS,
            ),
        )

        assertTrue(!text.contains("limit"), "an unset limit must be absent, not null: $text")
        assertTrue(text.contains("\"cursor\":null"), "an initial cursor is null, not absent: $text")
    }

    /** The versions the client declares are the versions the outbox actually stamps. */
    @Test
    fun theDeclaredSchemaVersionsAreTheOnesTheOutboxWrites() {
        assertEquals(
            mapOf(
                WIRE_AGGREGATE_ACTIVITY_SESSION to listOf(WIRE_ACTIVITY_SESSION_PAYLOAD_VERSION),
                WIRE_AGGREGATE_CUSTOM_EXERCISE to listOf(WIRE_CUSTOM_EXERCISE_PAYLOAD_VERSION),
                WIRE_AGGREGATE_FOOD to listOf(WIRE_FOOD_PAYLOAD_VERSION),
                WIRE_AGGREGATE_FOOD_LOG_ENTRY to listOf(WIRE_FOOD_LOG_ENTRY_PAYLOAD_VERSION),
                WIRE_AGGREGATE_HEALTH_PROFILE to listOf(WIRE_HEALTH_PROFILE_PAYLOAD_VERSION),
                WIRE_AGGREGATE_MEAL_PLAN_ENTRY to listOf(WIRE_MEAL_PLAN_ENTRY_PAYLOAD_VERSION),
                WIRE_AGGREGATE_MEASUREMENT to listOf(WIRE_MEASUREMENT_PAYLOAD_VERSION),
                WIRE_AGGREGATE_RECIPE to listOf(WIRE_RECIPE_PAYLOAD_VERSION),
            ),
            SyncWire.SUPPORTED_SCHEMA_VERSIONS,
        )
    }

    /**
     * Every aggregate type the client will *send* is one it also declares it can *apply*, and
     * has a local table for.
     *
     * The health profile spent this whole feature's life failing the first half: it was
     * journalled at every save and `SENDABLE_LOCAL_AGGREGATE_TYPES` could not carry it, so
     * `Data & sync` showed a pending count that could never reach zero. This is the assertion
     * that would have said so.
     */
    @Test
    fun everySendableTypeIsOneThisBuildCanAlsoDeclareAndApply() {
        for (localType in SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES) {
            val wireType = SyncWire.SUPPORTED_SCHEMA_VERSIONS.keys
                .firstOrNull { SyncWire.localAggregateType(it) == localType }
            assertNotNull(wireType, "$localType may be sent and is declared by no wire type")
        }
        for (wireType in SyncWire.SUPPORTED_SCHEMA_VERSIONS.keys) {
            assertNotNull(
                SyncWire.localAggregateType(wireType),
                "$wireType is declared applicable and has no local store",
            )
        }
    }

    private fun decodePullResponse(file: String): PullResponseDto =
        SyncJson.instance.decodeFromString(
            serializer<PullResponseDto>(),
            ContractFixtures.read(file),
        )
}
