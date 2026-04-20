package io.github.miuzarte.scrcpyforandroid.pages

import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun submitImeText(
    scrcpy: Scrcpy,
    text: String,
    onFailure: suspend (error: Throwable, useClipboardPaste: Boolean) -> Unit,
) {
    if (text.isBlank()) {
        return
    }
    val useClipboardPaste = text.any { it.code > 0x7F }
    runCatching {
        withContext(Dispatchers.IO) {
            if (useClipboardPaste) {
                scrcpy.setClipboard(text, paste = true)
            } else {
                scrcpy.injectText(text)
            }
        }
    }.onFailure { error ->
        onFailure(error, useClipboardPaste)
    }
}
