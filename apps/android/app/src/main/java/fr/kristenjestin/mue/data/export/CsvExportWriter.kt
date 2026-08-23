package fr.kristenjestin.mue.data.export

import fr.kristenjestin.mue.domain.logic.CsvExport
import fr.kristenjestin.mue.domain.model.Measurement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Materialises the CSV in the app cache so `FileProvider` can hand it to the share
 * sheet (PRD 20.5).
 *
 * The bytes land in a temporary file first and are renamed into place only once
 * complete, so a failed export can never leave a half-written file that looks like a
 * successful one (PRD 15.4).
 */
class CsvExportWriter(
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun write(measurements: List<Measurement>, exportDate: LocalDate): File =
        withContext(ioDispatcher) {
            val directory = File(cacheDir, EXPORT_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Could not create the export directory")
            }

            // Yesterday's export is of no use once a new one is requested, and the
            // cache is not a place to accumulate personal data.
            directory.listFiles()?.forEach { it.delete() }

            val target = File(directory, CsvExport.fileName(exportDate))
            val partial = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
            try {
                partial.writeBytes(CsvExport.buildBytes(measurements))
                target.delete()
                if (!partial.renameTo(target)) {
                    throw IOException("Could not finalise ${target.name}")
                }
                target
            } catch (error: Throwable) {
                partial.delete()
                throw error
            }
        }

    private companion object {
        /** Must match the `cache-path` declared in `res/xml/file_paths.xml`. */
        const val EXPORT_DIRECTORY = "exports"
        const val TEMP_PREFIX = "mue-export"
        const val TEMP_SUFFIX = ".csv.part"
    }
}
