package io.github.miuzarte.scrcpyforandroid.pages

import androidx.compose.runtime.staticCompositionLocalOf

class ServerPicker(
    val pick: () -> Unit,
)

val LocalServerPicker = staticCompositionLocalOf<ServerPicker> {
    error("No ServerPicker provided")
}
