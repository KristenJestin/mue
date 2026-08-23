package fr.kristenjestin.mue.data.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CsvExportWriterTest {

    private lateinit var cacheDir: File
    private lateinit var writer: CsvExportWriter

    private val exportDate: LocalDate = LocalDate.of(2026, 8, 23)

    @Before
    fun createWriter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cacheDir = File(context.cacheDir, "export-writer-test-${System.nanoTime()}")
        writer = CsvExportWriter(cacheDir)
    }

    @After
    fun deleteCache() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun writesTheFileWhereTheFileProviderExpectsIt() = runTest {
        val file = writer.write(listOf(measurement("2026-08-23", 7_450)), exportDate)

        assertEquals("mue-weight-2026-08-23.csv", file.name)
        assertEquals("exports", file.parentFile?.name)
        assertTrue(file.exists())
    }

    @Test
    fun theBytesOnDiskMatchTheGeneratedContent() = runTest {
        val file = writer.write(
            listOf(measurement("2026-08-12", 7_480), measurement("2026-08-23", 7_405)),
            exportDate,
        )

        assertEquals(
            "date,weight_kg\n2026-08-12,74.80\n2026-08-23,74.05\n",
            file.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun anEmptyHistoryStillProducesAHeaderOnlyFile() = runTest {
        val file = writer.write(emptyList(), exportDate)

        assertEquals("date,weight_kg\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun noPartialFileIsLeftBehind() = runTest {
        val file = writer.write(listOf(measurement("2026-08-23", 7_450)), exportDate)

        val leftovers = file.parentFile?.listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf("mue-weight-2026-08-23.csv"), leftovers)
    }

    @Test
    fun exportingTwiceOverwritesTheEarlierFile() = runTest {
        writer.write(listOf(measurement("2026-08-23", 7_450)), exportDate)
        val second = writer.write(listOf(measurement("2026-08-23", 8_020)), exportDate)

        assertEquals("date,weight_kg\n2026-08-23,80.20\n", second.readText(Charsets.UTF_8))
        assertEquals(1, second.parentFile?.listFiles()?.size)
    }

    @Test
    fun aNewerExportDateReplacesTheOlderFile() = runTest {
        writer.write(listOf(measurement("2026-08-23", 7_450)), exportDate)
        val second = writer.write(listOf(measurement("2026-08-24", 7_500)), exportDate.plusDays(1))

        assertEquals(listOf("mue-weight-2026-08-24.csv"), second.parentFile?.listFiles()?.map { it.name })
    }

    private fun measurement(isoDate: String, weightCg: Int) =
        Measurement(LocalDate.parse(isoDate), Weight.ofHundredthsClamped(weightCg))
}
