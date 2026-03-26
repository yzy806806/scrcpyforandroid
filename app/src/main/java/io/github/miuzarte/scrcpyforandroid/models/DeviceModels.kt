package io.github.miuzarte.scrcpyforandroid.models

import io.github.miuzarte.scrcpyforandroid.constants.AppDefaults

data class DeviceShortcut(
    val name: String = "",
    val host: String,
    val port: Int = AppDefaults.ADB_PORT,
    val online: Boolean = false,
) {
    val id: String get() = "$host:$port"

    fun marshalToString(
        separator: String = "|",
    ): String = listOf(
        name.trim(), host.trim(), port.toString()
    ).joinToString(
        separator = separator
    )

    companion object {
        fun unmarshalFrom(
            s: String,
            delimiter: String = "|",
        ): DeviceShortcut? {
            val parts = s.split(delimiter, limit = 3)
            return when (parts.size) {
                3 -> {
                    val name = parts[0].trim()
                    val host = parts[1].trim()
                    val port = parts[2].trim().toIntOrNull() ?: AppDefaults.ADB_PORT
                    if (host.isNotBlank()) DeviceShortcut(
                        name = name,
                        host = host,
                        port = port,
                    )
                    else null
                }

                else -> null
            }
        }
    }
}

data class ConnectionTarget(
    val host: String,
    val port: Int = AppDefaults.ADB_PORT,
) {
    fun marshalToString(): String = "$host:$port"

    companion object {
        fun unmarshalFrom(s: String): ConnectionTarget? {
            val parts = s.split(":", limit = 2)
            return when (parts.size) {
                2 -> ConnectionTarget(
                    host = parts[0].trim(),
                    port = parts[1].trim().toIntOrNull() ?: AppDefaults.ADB_PORT,
                )

                1 -> ConnectionTarget(
                    host = parts[0].trim(),
                    port = AppDefaults.ADB_PORT,
                )

                0 -> ConnectionTarget(
                    host = s.trim(),
                    port = AppDefaults.ADB_PORT,
                )

                else -> null
            }
        }
    }
}
