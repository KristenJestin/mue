package fr.kristenjestin.mue.data.remote.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The test of the test.
 *
 * A drift detector that cannot be seen failing is indistinguishable from one that always passes,
 * and a green contract suite is the most expensive kind of lie in this repository: it is the one
 * thing standing between a server schema change and a phone that silently stops synchronising.
 *
 * So every failure mode of [ContractDrift] is provoked here, on real fixture bytes, and the
 * message it produces is asserted. The mutations below are exactly the four ways the server's
 * Zod schemas can move away from `SyncDto.kt`:
 *
 * 1. the server **adds** a field;
 * 2. the server **removes** a field Kotlin requires;
 * 3. the server **changes a type** — the case the decimal-string counters exist to prevent;
 * 4. the server **adds a union branch** Kotlin has never heard of.
 */
class ContractDriftDetectorTest {

    private val schema = "MeasurementPayloadV1"
    private val serializer = ContractFixtures.CONSUMERS.getValue(schema)

    /** The committed bytes, unmutated, are silent. Everything below is measured against this. */
    @Test
    fun anUntouchedFixtureReportsNothing() {
        val drift = ContractDrift.check(
            file = "measurement-v1-valid.json",
            schema = schema,
            serializer = serializer,
            text = ContractFixtures.read("measurement-v1-valid.json"),
        )

        assertEquals(emptyList(), drift)
    }

    /**
     * The additive case, and the commonest one: the server grows a field and the phone quietly
     * throws it away. `ignoreUnknownKeys` is what keeps the app working; this is what stops it
     * from being invisible.
     */
    @Test
    fun aFieldTheServerAddedIsReportedAsDroppedByKotlin() {
        val drift = ContractDrift.check(
            file = "measurement-v1-valid.json",
            schema = schema,
            serializer = serializer,
            text = """{"date":"2026-08-25","weightCg":7845,"bodyFatPercent":18}""",
        )

        assertEquals(1, drift.size, drift.toString())
        assertTrue(drift.single().contains("\$.bodyFatPercent"), drift.single())
        assertTrue(drift.single().contains("the Kotlin DTO drops it"), drift.single())
        assertTrue(
            drift.single().contains("packages/contracts added a field"),
            drift.single(),
        )
    }

    /**
     * The subtractive case. It fails on decode rather than on the tree, so the message has to
     * name the field itself — a `MissingFieldException` reaching JUnit would name a field index
     * inside generated code and nothing else.
     */
    @Test
    fun aFieldTheServerRemovedIsReportedByName() {
        val drift = ContractDrift.check(
            file = "measurement-v1-valid.json",
            schema = schema,
            serializer = serializer,
            text = """{"date":"2026-08-25"}""",
        )

        assertEquals(1, drift.size, drift.toString())
        assertTrue(drift.single().contains("requires weightCg"), drift.single())
        assertTrue(drift.single().contains("SyncDto.kt"), drift.single())
    }

    /**
     * The type case. A counter that stopped being a canonical decimal string is the drift the
     * contract's own `counterStringSchema` was written to prevent, and past 2^53 it is a
     * corruption no user would ever report as one.
     */
    @Test
    fun aCounterThatBecameAJsonNumberIsReportedAsATypeChange() {
        val text = ContractFixtures.read("pull-response-ok.json")
            .replace("\"9007199254740993\"", "9007199254740993")

        val drift = ContractDrift.check(
            file = "pull-response-ok.json",
            schema = "PullResponse",
            serializer = ContractFixtures.CONSUMERS.getValue("PullResponse"),
            text = text,
        )

        assertEquals(1, drift.size, drift.toString())
        assertTrue(drift.single().contains("does not fit the Kotlin DTO"), drift.single())
        assertTrue(drift.single().contains("'sequence'"), "it must name the field: ${drift.single()}")
        // kotlinx follows its first line with "Use 'isLenient = true'". That advice would make
        // the symptom disappear and the corruption stay, so it is deliberately not repeated.
        assertTrue(!drift.single().contains("isLenient"), drift.single())
        assertEquals(1, drift.single().lines().size, "one readable line, not a stack: ${drift.single()}")
    }

    /**
     * The union case. `op` and `status` are real discriminators, so a branch the contract adds
     * arrives as an unknown discriminator value rather than as a half-parsed object.
     */
    @Test
    fun aUnionBranchTheServerAddedIsReportedAsAnUnfittableShape() {
        val text = ContractFixtures.read("pull-response-ok.json")
            .replace("\"status\": \"ok\"", "\"status\": \"partial\"")

        val drift = ContractDrift.check(
            file = "pull-response-ok.json",
            schema = "PullResponse",
            serializer = ContractFixtures.CONSUMERS.getValue("PullResponse"),
            text = text,
        )

        assertEquals(1, drift.size, drift.toString())
        assertTrue(drift.single().contains("'partial'"), drift.single())
        assertTrue(
            drift.single().contains("reconcile SyncDto.kt with packages/contracts/src"),
            drift.single(),
        )
        // Not "register it in a SerializersModule", which is what kotlinx suggests and which
        // would be answering the wrong question.
        assertTrue(!drift.single().contains("SerializersModule"), drift.single())
        assertEquals(1, drift.single().lines().size, "one readable line, not a stack: ${drift.single()}")
    }

    /**
     * The opposite drift: Kotlin declares a field the contract has dropped. It is the shape a
     * half-finished revert leaves behind, and it must not read as "everything is fine".
     */
    @Test
    fun aFieldOnlyKotlinStillWritesIsReportedTheOtherWayRound() {
        val drift = ContractDrift.check(
            file = "error-minimal.json",
            schema = "MueError",
            serializer = ContractFixtures.CONSUMERS.getValue("MueError"),
            // `retryable` has no default in the DTO, so a fixture that carries it and a Kotlin
            // side that emits it agree; `field` is optional and absent on both sides. What is
            // asserted here is the direction of the message when only Kotlin writes a node.
            text = """{"code":"server.unavailable","message":"m","retryable":true}""",
        )

        assertEquals(emptyList(), drift, "the minimal error must round-trip exactly")

        val withExtra = ContractDrift.check(
            file = "error-minimal.json",
            schema = "MueError",
            serializer = ContractFixtures.CONSUMERS.getValue("MueError"),
            text = """{"code":"server.unavailable","message":"m","retryable":true,"hint":"x"}""",
        )
        assertTrue(withExtra.single().contains("SyncDto.kt does not declare"), withExtra.single())
    }

    /** Malformed bytes must not read as a schema disagreement; they are a broken emitter. */
    @Test
    fun textThatIsNotJsonIsReportedAsSuch() {
        val drift = ContractDrift.check(
            file = "measurement-v1-valid.json",
            schema = schema,
            serializer = serializer,
            text = "<html>Page temporarily unavailable</html>",
        )

        assertTrue(drift.single().contains("is not JSON"), drift.single())
        assertTrue(drift.single().contains("fixtures.ts"), drift.single())
        // kotlinx appends the whole offending document to a parse failure. A proxy's error page
        // in the middle of a test report is how a finding gets scrolled past, so it is trimmed
        // to the same one line every other failure mode produces.
        assertTrue(!drift.single().contains("<html>"), drift.single())
        assertEquals(1, drift.single().lines().size, "one readable line, not a page: ${drift.single()}")
    }
}
