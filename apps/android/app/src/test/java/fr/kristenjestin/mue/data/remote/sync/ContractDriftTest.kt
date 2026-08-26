package fr.kristenjestin.mue.data.remote.sync

import kotlinx.serialization.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        // Sixteen files: fifteen instances, each parsed through its Kotlin DTO below, and the
        // manifest, parsed by `ContractFixtures.manifest`. Every file the contracts package
        // emits has a reader here — which is exactly what they did not have when they landed.
        assertEquals(
            16,
            ContractFixtures.files().size,
            "the contracts package emits sixteen files and every one of them is read here",
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
        assertEquals(15, entries.size, "the manifest lost or gained a fixture")

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
        assertEquals("eyJ2IjoxLCJzZXEiOiI5MDA3MTk5MjU0NzQwOTk0In0", page.nextCursor)
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

    /** The two change branches, so the sealed hierarchy is exercised on both sides of `op`. */
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
            mapOf(WIRE_AGGREGATE_MEASUREMENT to listOf(WIRE_MEASUREMENT_PAYLOAD_VERSION)),
            SyncWire.SUPPORTED_SCHEMA_VERSIONS,
        )
    }

    private fun decodePullResponse(file: String): PullResponseDto =
        SyncJson.instance.decodeFromString(
            serializer<PullResponseDto>(),
            ContractFixtures.read(file),
        )
}
