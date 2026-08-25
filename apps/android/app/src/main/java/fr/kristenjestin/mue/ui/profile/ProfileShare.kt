package fr.kristenjestin.mue.ui.profile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

private const val CSV_MIME_TYPE = "text/csv"
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

/**
 * Hands the finished CSV to the Android share sheet (PRD FR-CSV-001).
 *
 * The file lives in the app cache and is only readable through the `FileProvider` declared
 * in the manifest, for the lifetime of the grant carried by the intent.
 *
 * Returns false rather than throwing: a phone with no application able to receive a file is
 * a failed export like any other, and PRD 15.4 wants it reported, not crashed on.
 */
internal fun Context.shareCsvFile(file: File, chooserTitle: String): Boolean = try {
    val uri = FileProvider.getUriForFile(this, packageName + FILE_PROVIDER_SUFFIX, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = CSV_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
    true
} catch (error: Exception) {
    false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
