package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.testing.measurementOf
import java.time.LocalDate
import java.util.Locale
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule

/**
 * PRD FR-CSV-003 and the V1 acceptance criteria require the export to be identical on
 * a phone configured in French. These tests run the real builder with the JVM default
 * locale set to `fr-FR`.
 */
class CsvExportLocaleTest {

    @get:Rule
    val localeRule = LocaleRule(Locale.FRANCE)

    private val measurements = listOf(
        measurementOf("2026-08-12", 74.8),
        measurementOf("2026-08-18", 74.9),
        measurementOf("2026-08-23", 74.5),
    )

    @Test
    fun `the rule really switches the default locale`() {
        assertEquals(Locale.FRANCE, Locale.getDefault())
        // Proof that this locale would break a formatter-based implementation.
        assertNotEquals("74.5", String.format("%.1f", 74.5))
    }

    @Test
    fun `the French default locale leaves the content untouched`() {
        assertEquals(EXPECTED_CONTENT, CsvExport.buildContent(measurements))
    }

    @Test
    fun `the French default locale leaves the bytes untouched`() {
        assertContentEquals(
            EXPECTED_CONTENT.toByteArray(Charsets.UTF_8),
            CsvExport.buildBytes(measurements),
        )
    }

    @Test
    fun `the decimal separator stays a dot`() {
        assertEquals("74.5", CsvExport.formatWeight(measurements.last().weight))
    }

    @Test
    fun `dates stay ISO rather than following the French convention`() {
        val firstRow = CsvExport.buildContent(measurements).lines()[1]
        assertEquals("2026-08-12", firstRow.substringBefore(','))
    }

    @Test
    fun `the file name stays ISO too`() {
        assertEquals("mue-weight-2026-08-23.csv", CsvExport.fileName(LocalDate.of(2026, 8, 23)))
    }

    private companion object {
        const val EXPECTED_CONTENT =
            "date,weight_kg\n2026-08-12,74.8\n2026-08-18,74.9\n2026-08-23,74.5\n"
    }
}

/**
 * A locale whose numbering system is not Latin: `String.format` would emit Devanagari
 * digits here, so the same expectations catch a much wider class of regressions.
 */
class CsvExportNonLatinLocaleTest {

    @get:Rule
    val localeRule = LocaleRule(Locale.forLanguageTag("hi-IN-u-nu-deva"))

    @Test
    fun `digits stay ASCII whatever the numbering system is`() {
        val content = CsvExport.buildContent(listOf(measurementOf("2026-08-23", 74.5)))
        assertEquals("date,weight_kg\n2026-08-23,74.5\n", content)
        assertContentEquals(content.toByteArray(Charsets.UTF_8), content.toByteArray(Charsets.US_ASCII))
    }
}
