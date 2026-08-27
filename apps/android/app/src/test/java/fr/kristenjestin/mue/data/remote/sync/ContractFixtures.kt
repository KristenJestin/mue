package fr.kristenjestin.mue.data.remote.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.File
import kotlin.test.assertNotNull

/**
 * The nineteen files under `src/test/resources/contract`, and the Kotlin consumer they were
 * committed for.
 *
 * `packages/contracts/src/fixtures.ts` emits them: every instance is `.parse`d by its own Zod
 * schema before it is written, so no fixture can ship that the source of truth rejects. They
 * arrive here as committed bytes, which is what makes the JVM side of the contract offline —
 * no server, no network, no emulator, and no Bun on the machine running Gradle.
 */
object ContractFixtures {

    const val MANIFEST: String = "index.json"

    private const val DIRECTORY = "contract"

    /**
     * The `openapi.json` component id each fixture is an instance of, mapped to the hand-written
     * DTO that has to be able to hold it.
     *
     * This map is the register of "which contract shapes Android claims to consume". A schema
     * the manifest names and this map does not is a fixture with no Kotlin consumer, and
     * [ContractDriftTest] fails on it rather than skipping it quietly — which is the failure
     * mode that let sixteen fixtures land unconsumed in the first place.
     */
    val CONSUMERS: Map<String, KSerializer<*>> = mapOf(
        "ActivitySessionPayloadV1" to serializer<ActivitySessionPayloadV1Dto>(),
        "AggregateMeta" to serializer<AggregateMetaDto>(),
        "CustomExerciseDefinitionPayloadV1" to
            serializer<CustomExerciseDefinitionPayloadV1Dto>(),
        "FoodLogEntryPayloadV1" to serializer<FoodLogEntryPayloadV1Dto>(),
        "FoodPayloadV1" to serializer<FoodPayloadV1Dto>(),
        "HealthProfilePayloadV1" to serializer<HealthProfilePayloadV1Dto>(),
        "MealPlanEntryPayloadV1" to serializer<MealPlanEntryPayloadV1Dto>(),
        "MeasurementPayloadV1" to serializer<MeasurementPayloadV1Dto>(),
        "RecipePayloadV1" to serializer<RecipePayloadV1Dto>(),
        "MueError" to serializer<MueErrorDto>(),
        "MutationEnvelope" to serializer<MutationEnvelopeDto>(),
        "PullRequest" to serializer<PullRequestDto>(),
        "PullResponse" to serializer<PullResponseDto>(),
        "PushRequest" to serializer<PushRequestDto>(),
        "PushResponse" to serializer<PushResponseDto>(),
    )

    fun read(name: String): String {
        val stream = ContractFixtures::class.java.classLoader
            ?.getResourceAsStream("$DIRECTORY/$name")
        assertNotNull(stream, "missing fixture $DIRECTORY/$name")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Every `.json` actually on disk, so a file nobody listed cannot hide. */
    fun files(): List<String> {
        val url = ContractFixtures::class.java.classLoader?.getResource(DIRECTORY)
        assertNotNull(url, "missing fixture directory $DIRECTORY")
        val directory = File(url.toURI())
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name }
            .sorted()
    }

    /** One row of `index.json`. The manifest exists so this list is written once, not twice. */
    data class Entry(
        val file: String,
        val schema: String,
        val kind: String,
        val description: String,
    )

    fun manifest(): List<Entry> {
        val root = SyncJson.instance.parseToJsonElement(read(MANIFEST)).jsonObject
        val fixtures = root["fixtures"] as? JsonArray
        assertNotNull(fixtures, "$MANIFEST carries no `fixtures` array")
        return fixtures.map { element ->
            val row = element.jsonObject
            Entry(
                file = row.getValue("file").jsonPrimitive.content,
                schema = row.getValue("schema").jsonPrimitive.content,
                kind = row.getValue("kind").jsonPrimitive.content,
                description = row.getValue("description").jsonPrimitive.content,
            )
        }
    }

    /** A readable rendering of one JSON node, for a drift message that names what it saw. */
    fun describe(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "the string \"${element.content}\"" else
            "the ${primitiveKind(element)} ${element.content}"

        is JsonObject -> "an object with ${element.keys.size} field(s) ${element.keys.sorted()}"
        is JsonArray -> "an array of ${element.size} element(s)"
    }

    private fun primitiveKind(primitive: JsonPrimitive): String = when (primitive.content) {
        "true", "false" -> "boolean"
        else -> "number"
    }
}
