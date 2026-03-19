package io.github.miuzarte.scrcpyforandroid.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Immutable
data class AppHaptics(
    val press: () -> Unit,
    val confirm: () -> Unit,
)

@Composable
fun rememberAppHaptics(): AppHaptics {
    val hapticFeedback = LocalHapticFeedback.current
    val pressAction = rememberUpdatedState {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
    }
    val confirmAction = rememberUpdatedState {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    return remember {
        AppHaptics(
            press = { pressAction.value.invoke() },
            confirm = { confirmAction.value.invoke() },
        )
    }
}
