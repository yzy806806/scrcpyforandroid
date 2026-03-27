package io.github.miuzarte.scrcpyforandroid.services

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.miuzarte.scrcpyforandroid.constants.Defaults
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global singleton for event logging.
 * 
 * Manages event logs with timestamp formatting and automatic log rotation.
 * Logs are stored in a thread-safe SnapshotStateList for Compose integration.
 */
object EventLogger {
    private const val LOG_TAG = "EventLogger"

    private val _eventLog: SnapshotStateList<String> = mutableStateListOf()

    /**
     * Read-only access to the event log list.
     */
    val eventLog: List<String> get() = _eventLog

    /**
     * Log an event with timestamp and optional error.
     * 
     * @param message The log message
     * @param level Log level (Log.INFO, Log.ERROR, Log.WARN, Log.DEBUG)
     * @param error Optional throwable for error logging
     */
    fun logEvent(message: String, level: Int = Log.INFO, error: Throwable? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _eventLog.add(0, "[$timestamp] $message")

        // Rotate logs if exceeds max size
        if (_eventLog.size > Defaults.EVENT_LOG_LINES) {
            _eventLog.removeRange(Defaults.EVENT_LOG_LINES, _eventLog.size)
        }

        // Log to Android logcat
        when (level) {
            Log.ERROR -> if (error != null) Log.e(LOG_TAG, message, error)
            else Log.e(LOG_TAG, message)

            Log.WARN -> if (error != null) Log.w(LOG_TAG, message, error)
            else Log.w(LOG_TAG, message)

            Log.DEBUG -> if (error != null) Log.d(LOG_TAG, message, error)
            else Log.d(LOG_TAG, message)

            else -> if (error != null) Log.i(LOG_TAG, message, error)
            else Log.i(LOG_TAG, message)
        }
    }

    /**
     * Clear all event logs.
     */
    fun clearLogs() {
        _eventLog.clear()
    }

    /**
     * Check if there are any logs.
     */
    fun hasLogs(): Boolean = _eventLog.isNotEmpty()
}
