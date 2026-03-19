package io.github.miuzarte.scrcpyforandroid.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.miuzarte.scrcpyforandroid.constants.UiAndroidKeycodes
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class VirtualButtonAction(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val keycode: Int?,
) {
    MORE("more", "更多", Icons.Default.MoreVert, null),
    HOME("home", "主页", Icons.Default.Home, UiAndroidKeycodes.HOME),
    BACK("back", "返回", Icons.AutoMirrored.Filled.ArrowBack, UiAndroidKeycodes.BACK),
    APP_SWITCH("app_switch", "多任务", Icons.Default.Apps, UiAndroidKeycodes.APP_SWITCH),
    MENU("menu", "菜单", Icons.Default.Menu, UiAndroidKeycodes.MENU),
    NOTIFICATION("notification", "通知栏", Icons.Default.Notifications, UiAndroidKeycodes.NOTIFICATION),
    VOLUME_UP("volume_up", "音量+", Icons.AutoMirrored.Filled.VolumeUp, UiAndroidKeycodes.VOLUME_UP),
    VOLUME_DOWN("volume_down", "音量-", Icons.AutoMirrored.Filled.VolumeDown, UiAndroidKeycodes.VOLUME_DOWN),
    VOLUME_MUTE("volume_mute", "静音", Icons.AutoMirrored.Filled.VolumeOff, UiAndroidKeycodes.VOLUME_MUTE),
    POWER("power", "锁屏", Icons.Default.PowerSettingsNew, UiAndroidKeycodes.POWER),
    SCREENSHOT("screenshot", "截图", Icons.Default.PhotoCamera, UiAndroidKeycodes.SYSRQ),
}

object VirtualButtonActions {
    val all = VirtualButtonAction.entries
    val defaultOutsideIds = listOf(
        VirtualButtonAction.MORE.id,
        VirtualButtonAction.HOME.id,
        VirtualButtonAction.BACK.id,
    )
    val defaultMoreIds = listOf(
        VirtualButtonAction.APP_SWITCH.id,
        VirtualButtonAction.MENU.id,
        VirtualButtonAction.NOTIFICATION.id,
        VirtualButtonAction.VOLUME_UP.id,
        VirtualButtonAction.VOLUME_DOWN.id,
        VirtualButtonAction.VOLUME_MUTE.id,
        VirtualButtonAction.POWER.id,
        VirtualButtonAction.SCREENSHOT.id,
    )

    private val byId = all.associateBy { it.id }

    fun parseStoredIds(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { item ->
            val id = item.trim()
            id.ifBlank { null }
        }
    }

    fun encodeStoredIds(ids: List<String>): String = ids.joinToString(",")

    fun resolveLayout(
        outsideIds: List<String>,
        moreIds: List<String>,
    ): Pair<List<VirtualButtonAction>, List<VirtualButtonAction>> {
        val outside = mutableListOf<VirtualButtonAction>()
        val overflow = mutableListOf<VirtualButtonAction>()
        val used = mutableSetOf<String>()

        outsideIds.forEach { id ->
            val action = byId[id] ?: return@forEach
            if (used.add(action.id)) outside.add(action)
        }

        if (used.add(VirtualButtonAction.MORE.id)) {
            outside.add(VirtualButtonAction.MORE)
        }

        moreIds.forEach { id ->
            if (id == VirtualButtonAction.MORE.id) return@forEach
            val action = byId[id] ?: return@forEach
            if (used.add(action.id)) overflow.add(action)
        }

        all.forEach { action ->
            if (action == VirtualButtonAction.MORE) return@forEach
            if (used.add(action.id)) overflow.add(action)
        }

        return outside to overflow
    }
}

class VirtualButtonBar(
    private val outsideActions: List<VirtualButtonAction>,
    private val moreActions: List<VirtualButtonAction>,
) {
    @Composable
    fun Preview(
        enabled: Boolean,
        onPressHaptic: () -> Unit,
        onConfirmHaptic: () -> Unit,
        onAction: (VirtualButtonAction) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val activeContainerColor = MiuixTheme.colorScheme.primary
        val disabledContainerColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
        val activeContentColor = MiuixTheme.colorScheme.onPrimary
        val disabledContentColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.45f)

        var showMorePopup by remember { mutableStateOf(false) }
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
        ) {
            outsideActions.forEach { action ->
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = {
                            onPressHaptic()
                            if (action == VirtualButtonAction.MORE) {
                                showMorePopup = true
                            } else {
                                onAction(action)
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            color = activeContainerColor,
                            disabledColor = disabledContainerColor,
                        ),
                    ) {
                        val contentColor = if (enabled) activeContentColor else disabledContentColor
                        Icon(
                            action.icon,
                            contentDescription = action.title,
                            modifier = Modifier.size(18.dp),
                            tint = contentColor,
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(UiSpacing.Small))
                        Text(action.title, color = contentColor)
                    }
                    if (action == VirtualButtonAction.MORE) {
                        MorePopup(
                            show = showMorePopup,
                            moreActions = moreActions,
                            onDismiss = { showMorePopup = false },
                            onConfirmHaptic = onConfirmHaptic,
                            onAction = {
                                onAction(it)
                                showMorePopup = false
                            },
                            renderInRootScaffold = false,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun Fullscreen(
        onPressHaptic: () -> Unit,
        onConfirmHaptic: () -> Unit,
        onAction: (VirtualButtonAction) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var showMorePopup by remember { mutableStateOf(false) }

        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            outsideActions.forEach { action ->
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = {
                            onPressHaptic()
                            if (action == VirtualButtonAction.MORE) {
                                showMorePopup = true
                            } else {
                                onAction(action)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 0.dp,
                        minHeight = 16.dp,
                        insideMargin = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Black.copy(alpha = 0.1f),
                        ),
                    ) {
                        Icon(action.icon, contentDescription = action.title, tint = Color.White)
                    }

                    if (action == VirtualButtonAction.MORE) {
                        MorePopup(
                            show = showMorePopup,
                            moreActions = moreActions,
                            onDismiss = { showMorePopup = false },
                            onConfirmHaptic = onConfirmHaptic,
                            onAction = {
                                onAction(it)
                                showMorePopup = false
                            },
                            renderInRootScaffold = true,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MorePopup(
        show: Boolean,
        moreActions: List<VirtualButtonAction>,
        onDismiss: () -> Unit,
        onConfirmHaptic: () -> Unit,
        onAction: (VirtualButtonAction) -> Unit,
        renderInRootScaffold: Boolean,
    ) {
        SuperListPopup(
            show = show,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = onDismiss,
            renderInRootScaffold = renderInRootScaffold,
            enableWindowDim = false,
        ) {
            ListPopupColumn {
                moreActions.forEachIndexed { index, action ->
                    DropdownImpl(
                        text = action.title,
                        optionSize = moreActions.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            onConfirmHaptic()
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}
