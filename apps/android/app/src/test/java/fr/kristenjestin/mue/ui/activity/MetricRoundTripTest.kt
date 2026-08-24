package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.logic.ActivityValidation
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.testing.LocaleRule
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one property the `2.95 km` bug broke: opening a stored session and saving it again must
 * write back the number that was already there.
 *
 * Two halves have to agree for that to hold. The field renders a stored value through
 * [LogActivityFormat.metricInput], and a save reads it back through
 * [ActivityValidation.validateMetric] — so the round trip is `canonical → text → canonical`
 * and the test walks it for every kind rather than for the one that was reported.
 *
 * Both directions are locale-sensitive in opposite ways (PRD 12): rendering follows the
 * phone's language, parsing accepts `.` and `,` whatever that language is. Every case
 * therefore runs in English and in French.
 */
class MetricRoundTripTest {

    @Rule
    @JvmField
    val locale = LocaleRule(Locale.UK)

    private val languages = listOf(Locale.UK, Locale.FRANCE)

    /**
     * The value the owner reported. `2950 m` used to render `3` and save back as `3000 m`,
     * losing fifty metres on a screen nobody was told about.
     */
    @Test
    fun `the reported walk keeps its fifty metres through a re-edit`() {
        languages.forEach { language ->
            val rendered = LogActivityFormat.metricInput(MetricKind.DISTANCE, 2_950, language)
            assertEquals(if (language == Locale.UK) "2.95" else "2,95", rendered)
            assertEquals(2_950, parse(MetricKind.DISTANCE, rendered))
        }
    }

    /**
     * Every value a field can hold survives, not only the reported one.
     *
     * A kind's step is the finest quantity its decimal count can express — ten metres for a
     * distance, one hundredth of a km/h for a speed, one tenth of a percent for an incline.
     * Walking the whole range in steps proves the render and the parse are inverses of each
     * other rather than merely agreeing on one number.
     */
    @Test
    fun `every editable measurement survives a render then a save`() {
        MetricKind.entries
            .filter { it.editableInV1 && it != MetricKind.AVERAGE_PACE }
            .forEach { kind ->
                val step = kind.step()
                languages.forEach { language ->
                    (0..200).map { it * step }.forEach { stored ->
                        val rendered = LogActivityFormat.metricInput(kind, stored, language)
                        assertEquals(
                            stored,
                            parse(kind, rendered),
                            "${kind.id} drifted: $stored rendered as \"$rendered\" in $language",
                        )
                    }
                }
            }
    }

    /** A pace is two boxes rather than a decimal, and makes the same promise (PRD 8.3). */
    @Test
    fun `a pace survives a render then a save, second by second`() {
        languages.forEach { language ->
            (1..ActivityValidation.MAX_PACE_SECONDS step 7).forEach { stored ->
                val rendered = LogActivityFormat.metricInput(MetricKind.AVERAGE_PACE, stored, language)
                assertEquals(stored, parse(MetricKind.AVERAGE_PACE, rendered))
            }
        }
    }

    /**
     * Repeating the trip cannot keep moving the value. A measurement arriving from somewhere
     * finer than the form — a Health Connect import, PRD 16.6 — is rounded once to what the
     * field can show and then stands still, rather than creeping on every visit.
     */
    @Test
    fun `a value finer than its field settles after one round trip`() {
        MetricKind.entries.filter { it.editableInV1 && it != MetricKind.AVERAGE_PACE }.forEach { kind ->
            listOf(1, 7, 12_345, 99_999).forEach { stored ->
                val once = parse(kind, LogActivityFormat.metricInput(kind, stored, Locale.UK))
                val twice = parse(kind, LogActivityFormat.metricInput(kind, once!!, Locale.UK))
                assertEquals(once, twice, "${kind.id} kept moving after the first round trip")
            }
        }
    }

    /**
     * The other half of the promise: the field itself refuses digits it could not render back,
     * so nothing typed here can ever reach the state the test above has to tolerate.
     */
    @Test
    fun `a field refuses a decimal finer than the value it renders`() {
        assertEquals("2.95", LogActivityViewModel.decimal("2.955", MetricKind.DISTANCE.displayDecimals))
        assertEquals("5.6", LogActivityViewModel.decimal("5.6", MetricKind.REPORTED_SPEED.displayDecimals))
        assertEquals("2,5", LogActivityViewModel.decimal("2,55", MetricKind.INCLINE.displayDecimals))
        assertEquals("280", LogActivityViewModel.decimal("280.7", MetricKind.ESTIMATED_ENERGY.displayDecimals))
        // PRD 16.4: a half-typed number stays exactly as typed, separator and all.
        assertEquals("7,", LogActivityViewModel.decimal("7,", MetricKind.DISTANCE.displayDecimals))
    }

    /** A round value drops its trailing zeros, as a round load already does (`60`, not `60.00`). */
    @Test
    fun `a round measurement reads without a fraction it does not have`() {
        assertEquals("3", LogActivityFormat.metricInput(MetricKind.DISTANCE, 3_000, Locale.UK))
        assertEquals("4.2", LogActivityFormat.metricInput(MetricKind.DISTANCE, 4_200, Locale.UK))
        assertEquals("2.5", LogActivityFormat.metricInput(MetricKind.INCLINE, 25, Locale.UK))
        assertEquals("280", LogActivityFormat.metricInput(MetricKind.ESTIMATED_ENERGY, 280, Locale.UK))
    }

    /**
     * A rendered value never carries a grouping separator: `parseDecimal` reads a comma as the
     * decimal mark in every language, so a grouped `12,345` would come back as twelve.
     */
    @Test
    fun `a large measurement is rendered without grouping`() {
        languages.forEach { language ->
            val rendered = LogActivityFormat.metricInput(MetricKind.ESTIMATED_ENERGY, 12_345, language)
            assertTrue(rendered.none { it == ',' || it == '.' || it == ' ' }, rendered)
            assertEquals(12_345, parse(MetricKind.ESTIMATED_ENERGY, rendered))
        }
    }

    private fun parse(kind: MetricKind, rendered: String): Int? =
        ActivityValidation.validateMetric(kind, rendered).valueOrNull

    /** The finest stored value this kind's decimal count can express. */
    private fun MetricKind.step(): Int {
        val renderable = generateSequence(1) { it * 10 }.take(displayDecimals + 1).last()
        return (canonicalPerDisplayUnit / renderable).coerceAtLeast(1)
    }
}
