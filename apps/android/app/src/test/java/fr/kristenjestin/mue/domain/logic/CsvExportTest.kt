package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.testing.measurementOf
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvExportTest {

    @Test
    fun `an empty history exports the header alone`() {
        assertEquals("date,weight_kg\n", CsvExport.buildContent(emptyList()))
    }

    @Test
    fun `a single measurement follows the header`() {
        val content = CsvExport.buildContent(listOf(measurementOf("2026-08-23", 74.55)))
        assertEquals("date,weight_kg\n2026-08-23,74.55\n", content)
    }

    /** The sample of PRD FR-CSV-003, two decimals included. */
    @Test
    fun `the PRD sample is reproduced exactly`() {
        val content = CsvExport.buildContent(
            listOf(
                measurementOf("2026-08-12", 74.80),
                measurementOf("2026-08-18", 74.95),
                measurementOf("2026-08-23", 74.55),
            )
        )
        assertEquals(
            "date,weight_kg\n" +
                "2026-08-12,74.80\n" +
                "2026-08-18,74.95\n" +
                "2026-08-23,74.55\n",
            content,
        )
    }

    @Test
    fun `rows are sorted oldest to newest whatever the input order`() {
        val content = CsvExport.buildContent(
            listOf(
                measurementOf("2026-08-23", 74.5),
                measurementOf("2026-08-12", 74.8),
                measurementOf("2026-08-18", 74.9),
            )
        )
        val dates = content.lines().drop(1).filter { it.isNotEmpty() }.map { it.substringBefore(',') }
        assertEquals(listOf("2026-08-12", "2026-08-18", "2026-08-23"), dates)
    }

    @Test
    fun `a long history keeps one row per measurement`() {
        val start = LocalDate.of(2026, 1, 1)
        val measurements = (0 until 365).map {
            Measurement(
                start.plusDays(it.toLong()),
                Weight.ofHundredthsClamped(7_000 + (it % 100) * 5),
            )
        }
        val lines = CsvExport.buildContent(measurements).split("\n")
        assertEquals("date,weight_kg", lines.first())
        // 1 header + 365 rows + the empty string after the final line ending.
        assertEquals(367, lines.size)
        assertEquals("", lines.last())
        assertEquals("2026-01-01,70.00", lines[1])
        assertEquals("2026-12-31,73.20", lines[365])
    }

    @Test
    fun `line endings are LF only`() {
        val content = CsvExport.buildContent(listOf(measurementOf("2026-08-23", 74.5)))
        assertFalse(content.contains('\r'))
        assertTrue(content.endsWith("\n"))
    }

    @Test
    fun `the bytes carry no BOM and stay in ASCII`() {
        val bytes = CsvExport.buildBytes(listOf(measurementOf("2026-08-23", 74.5)))
        assertEquals('d'.code.toByte(), bytes.first())
        assertTrue(bytes.all { it in 0..127 })
    }

    @Test
    fun `weights always use a dot and always keep two decimals`() {
        assertEquals("30.00", CsvExport.formatWeight(Weight.ofHundredthsClamped(3_000)))
        assertEquals("74.05", CsvExport.formatWeight(Weight.ofHundredthsClamped(7_405)))
        assertEquals("74.50", CsvExport.formatWeight(Weight.ofHundredthsClamped(7_450)))
        assertEquals("100.00", CsvExport.formatWeight(Weight.ofHundredthsClamped(10_000)))
        assertEquals("250.00", CsvExport.formatWeight(Weight.ofHundredthsClamped(25_000)))
    }

    /** A fraction below ten hundredths must keep its leading zero, not read as `74.5`. */
    @Test
    fun `a fraction below a tenth keeps its leading zero`() {
        assertEquals("74.05", CsvExport.formatWeight(Weight.ofHundredthsClamped(7_405)))
        assertEquals("80.00", CsvExport.formatWeight(Weight.ofHundredthsClamped(8_000)))
    }

    @Test
    fun `the file name carries the export date, not the data dates`() {
        assertEquals("mue-weight-2026-08-23.csv", CsvExport.fileName(LocalDate.of(2026, 8, 23)))
        assertEquals("mue-weight-2026-01-05.csv", CsvExport.fileName(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `bytes and content agree`() {
        val measurements = listOf(measurementOf("2026-08-12", 74.8), measurementOf("2026-08-23", 74.5))
        assertContentEquals(
            CsvExport.buildContent(measurements).toByteArray(Charsets.UTF_8),
            CsvExport.buildBytes(measurements),
        )
    }
}
