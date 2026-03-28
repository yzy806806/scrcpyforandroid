package io.github.miuzarte.scrcpyforandroid.services

import android.content.Context
import androidx.core.content.edit
import io.github.miuzarte.scrcpyforandroid.constants.AppPreferenceKeys
import io.github.miuzarte.scrcpyforandroid.constants.Defaults
import io.github.miuzarte.scrcpyforandroid.models.DeviceShortcut

internal fun loadQuickDevices(context: Context): List<DeviceShortcut> {
    val raw = context.getSharedPreferences(AppPreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(AppPreferenceKeys.QUICK_DEVICES, "")
        .orEmpty()

    if (raw.isBlank()) return emptyList()

    val result = mutableListOf<DeviceShortcut>()
    raw.lineSequence().forEach { line ->
        val parts = line.split("|", limit = 3)
        when (parts.size) {
            3 -> {
                val name = parts[0].trim()
                val host = parts[1].trim()
                val port = parts[2].trim().toIntOrNull() ?: Defaults.ADB_PORT
                if (host.isNotBlank()) {
                    result.add(
                        DeviceShortcut(
                            name = name,
                            host = host,
                            port = port,
                            online = false,
                        ),
                    )
                }
            }

            2 -> {
                // Backward compatibility with old format: name|host:port
                val name = parts[0].trim()
                val host = parts[1].substringBefore(":").trim()
                val port = parts[1].substringAfter(":", Defaults.ADB_PORT.toString()).trim()
                    .toIntOrNull() ?: Defaults.ADB_PORT
                if (host.isNotBlank()) {
                    result.add(
                        DeviceShortcut(
                            name = name,
                            host = host,
                            port = port,
                            online = false,
                        ),
                    )
                }
            }
        }
    }
    return result
}

internal fun saveQuickDevices(context: Context, quickDevices: List<DeviceShortcut>) {
    val raw = quickDevices.joinToString("\n") { "${it.name}|${it.host}|${it.port}" }
    context.getSharedPreferences(AppPreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        .edit {
            putString(AppPreferenceKeys.QUICK_DEVICES, raw)
        }
}
