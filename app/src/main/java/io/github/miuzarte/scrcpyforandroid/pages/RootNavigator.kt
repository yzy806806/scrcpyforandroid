package io.github.miuzarte.scrcpyforandroid.pages

import android.content.pm.ActivityInfo
import androidx.compose.runtime.staticCompositionLocalOf

class RootNavigator(
    val push: (RootScreen) -> Unit,
    val pop: () -> Unit,
)

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator> {
    error("No RootNavigator provided")
}

class FullscreenNavigationState(
    val setOrientation: (Int) -> Unit,
)

val LocalFullscreenNavigationState = staticCompositionLocalOf<FullscreenNavigationState> {
    error("No FullscreenNavigationState provided")
}
