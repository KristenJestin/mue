package fr.kristenjestin.mue.data.remote.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The cheap drift detector the platform contract asks for.
 *
 * It answers one question about one committed fixture: **can the hand-written Kotlin DTO hold
 * everything the Zod schema put in it, and give it all back?** It does that by parsing the
 * fixture into the DTO, re-serialising the DTO and comparing the two as JSON trees. That single
 * round trip catches every way the two sides can drift apart:
 *
 * | The server's schema… | shows up as |
 * |---|---|
 * | adds a field | a node present in the fixture and absent from the re-encoded tree |
 * | removes a required field | a decode failure naming the field Kotlin still requires |
 * | renames a field | both of the above at once |
 * | changes a type — a counter from string to number | a node whose value differs by kind |
 * | adds a union branch | a decode failure naming the discriminator value |
 * | tightens a nullable field | a node the Kotlin DTO emits and the fixture no longer carries |
 *
 * ## Why it reports instead of throwing
 *
 * A `SerializationException` propagating out of a test gives a stack that ends inside generated
 * serializer code and a message about a field index. Whoever meets it is a person who has just
 * pulled a server change and needs to be told *which fixture*, *which schema*, *which field*
 * and *what to do*. So every failure mode is caught here and turned into one line of prose, and
 * the test fails once with all of them rather than at the first.
 *
 * ## Why it does not simply compare bytes
 *
 * The fixtures are canonical JSON — keys sorted, two-space indent. Kotlin emits declaration
 * order and no indentation. Comparing text would fail on formatting forever and teach everyone
 * to ignore it. Comparing trees fails only on meaning.
 */
object ContractDrift {

    /**
     * Every way [text] and [serializer] disagree, as sentences. Empty means they agree.
     *
     * @param file the fixture's name, so a failure names the file to look at.
     * @param schema the `openapi.json` component id, so it names the Zod schema to look at.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun check(file: String, schema: String, serializer: KSerializer<*>, text: String): List<String> {
        val json = SyncJson.instance

        val expected = try {
            json.parseToJsonElement(text)
        } catch (failure: SerializationException) {
            // [headline] here too: kotlinx appends the whole offending document to a parse
            // failure, and a fixture that is really a proxy's error page would put an HTML page
            // in the middle of the test report.
            return listOf(
                "$file is not JSON: ${headline(failure)} " +
                    "Re-emit the fixtures with `bun packages/contracts/src/fixtures.ts`.",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val anySerializer = serializer as KSerializer<Any>

        val decoded = try {
            json.decodeFromJsonElement(anySerializer, expected)
        } catch (failure: MissingFieldException) {
            return listOf(
                "$file ($schema): the Kotlin DTO requires " +
                    "${failure.missingFields.joinToString(", ")}, which the fixture does not " +
                    "carry. packages/contracts made ${failure.missingFields.size} field(s) " +
                    "optional or removed them; make the same change in SyncDto.kt.",
            )
        } catch (failure: SerializationException) {
            return listOf(
                "$file ($schema) does not fit the Kotlin DTO: ${headline(failure)} " +
                    "The Zod schema and SyncDto.kt describe different shapes; " +
                    "reconcile SyncDto.kt with packages/contracts/src.",
            )
        }

        val actual = try {
            json.encodeToJsonElement(anySerializer, decoded)
        } catch (failure: SerializationException) {
            return listOf(
                "$file ($schema) parsed but could not be written back: ${headline(failure)}",
            )
        }

        val differences = mutableListOf<String>()
        diff("$", expected, actual, differences)
        return differences.map { "$file ($schema): $it" }
    }

    /**
     * The first line of a serializer's complaint, and only the first.
     *
     * kotlinx follows it with three lines of generic advice — "Use `isLenient = true`", "register
     * the subclass in a `SerializersModule`" — which are wrong here in the most damaging way:
     * they suggest loosening the parser to make the symptom go away, when the symptom *is* the
     * finding. The first line always names the field or the discriminator, which is the part
     * worth reading.
     */
    private fun headline(failure: SerializationException): String =
        failure.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()

    private fun diff(
        path: String,
        expected: JsonElement,
        actual: JsonElement,
        out: MutableList<String>,
    ) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                for (key in expected.keys + actual.keys) {
                    val left = expected[key]
                    val right = actual[key]
                    when {
                        left == null -> out += "$path.$key is written by the Kotlin DTO " +
                            "(${ContractFixtures.describe(right!!)}) and is not in the " +
                            "fixture. SyncDto.kt declares a field the contract does not."

                        right == null -> out += "$path.$key is in the fixture " +
                            "(${ContractFixtures.describe(left)}) and the Kotlin DTO drops it. " +
                            "packages/contracts added a field SyncDto.kt does not declare."

                        else -> diff("$path.$key", left, right, out)
                    }
                }
            }

            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    out += "$path holds ${expected.size} element(s) in the fixture and " +
                        "${actual.size} after the round trip."
                    return
                }
                expected.forEachIndexed { index, element ->
                    diff("$path[$index]", element, actual[index], out)
                }
            }

            expected is JsonPrimitive && actual is JsonPrimitive -> {
                // `isString` is the whole point: a counter that became a JSON number reads the
                // same in a message that prints `content` alone, and it is exactly the drift
                // the contract's decimal-string rule exists to prevent.
                if (expected.content != actual.content ||
                    expected.isString != actual.isString ||
                    (expected is JsonNull) != (actual is JsonNull)
                ) {
                    out += "$path is ${ContractFixtures.describe(expected)} in the fixture and " +
                        "${ContractFixtures.describe(actual)} after the round trip."
                }
            }

            else -> out += "$path is ${ContractFixtures.describe(expected)} in the fixture and " +
                "${ContractFixtures.describe(actual)} after the round trip."
        }
    }
}
