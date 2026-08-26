package fr.kristenjestin.muespike

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every line the spike produces, on one logcat tag and mirrored on screen.
 *
 * The screen copy is capped; logcat is not, and logcat is what gets read afterwards.
 */
object SpikeLog {

    const val TAG: String = "MUE_SPIKE"

    private const val MAX_LINES = 300

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String, error: Throwable? = null) {
        write("E", message)
        if (error != null) Log.e(TAG, message, error)
    }

    fun clear() = _lines.update { emptyList() }

    private fun write(level: String, message: String) {
        val stamped = "${clock.format(Date())} $message"
        when (level) {
            "W" -> Log.w(TAG, stamped)
            "E" -> Log.e(TAG, stamped)
            else -> Log.i(TAG, stamped)
        }
        _lines.update { previous ->
            val next = previous + stamped
            if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
        }
    }
}
