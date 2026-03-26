package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 使用委托方式的 AppSettings 示例
 * 
 * 使用方式：
 * ```
 * // 获取值
 * val theme = appSettings.themeBaseIndex.get()
 * 
 * // 设置值
 * appSettings.themeBaseIndex.set(1)
 * 
 * // 观察变化（Flow）
 * appSettings.themeBaseIndex.observe().collect { value -> }
 * 
 * // 在 Composable 中观察（State）
 * val theme by appSettings.themeBaseIndex.observeAsState()
 * ```
 */
class AppSettingsNew(context: Context) : Settings(context, "AppSettings") {
    companion object {
        private val THEME_BASE_INDEX = Pair(
            intPreferencesKey("theme_base_index"),
            0
        )
        private val MONET = Pair(
            booleanPreferencesKey("monet"),
            false
        )
        private val FULLSCREEN_DEBUG_INFO = Pair(
            booleanPreferencesKey("fullscreen_debug_info"),
            false
        )
        private val SHOW_FULLSCREEN_VIRTUAL_BUTTONS = Pair(
            booleanPreferencesKey("show_fullscreen_virtual_buttons"),
            true
        )
        private val KEEP_SCREEN_ON_WHEN_STREAMING = Pair(
            booleanPreferencesKey("keep_screen_on_when_streaming"),
            false
        )
        private val DEVICE_PREVIEW_CARD_HEIGHT_DP = Pair(
            intPreferencesKey("device_preview_card_height_dp"),
            320
        )
        private val PREVIEW_VIRTUAL_BUTTON_SHOW_TEXT = Pair(
            booleanPreferencesKey("preview_virtual_button_show_text"),
            true
        )
        private val VIRTUAL_BUTTONS_LAYOUT = Pair(
            stringPreferencesKey("virtual_buttons_layout"),
            "more:1,app_switch:1,home:0,back:1,menu:0,notification:0,volume_up:0,volume_down:0,volume_mute:0,power:0,screenshot:0"
        )
        private val CUSTOM_SERVER_URI = Pair(
            stringPreferencesKey("custom_server_uri"),
            ""
        )
        private val SERVER_REMOTE_PATH = Pair(
            stringPreferencesKey("server_remote_path"),
            "/data/local/tmp/scrcpy-server.jar"
        )
        private val ADB_KEY_NAME = Pair(
            stringPreferencesKey("adb_key_name"),
            "scrcpy"
        )
        private val ADB_PAIRING_AUTO_DISCOVER_ON_DIALOG_OPEN = Pair(
            booleanPreferencesKey("adb_pairing_auto_discover_on_dialog_open"),
            true
        )
        private val ADB_AUTO_RECONNECT_PAIRED_DEVICE = Pair(
            booleanPreferencesKey("adb_auto_reconnect_paired_device"),
            true
        )
        private val ADB_MDNS_LAN_DISCOVERY = Pair(
            booleanPreferencesKey("adb_mdns_lan_discovery"),
            true
        )
    }

    // Theme Settings
    val themeBaseIndex by setting(THEME_BASE_INDEX)
    val monet by setting(MONET)

    // Scrcpy Settings
    val fullscreenDebugInfo by setting(FULLSCREEN_DEBUG_INFO)
    val showFullscreenVirtualButtons by setting(SHOW_FULLSCREEN_VIRTUAL_BUTTONS)
    val keepScreenOnWhenStreaming by setting(KEEP_SCREEN_ON_WHEN_STREAMING)
    val devicePreviewCardHeightDp by setting(DEVICE_PREVIEW_CARD_HEIGHT_DP)
    val previewVirtualButtonShowText by setting(PREVIEW_VIRTUAL_BUTTON_SHOW_TEXT)
    val virtualButtonsLayout by setting(VIRTUAL_BUTTONS_LAYOUT)

    // Scrcpy Server Settings
    val customServerUri by setting(CUSTOM_SERVER_URI)
    val serverRemotePath by setting(SERVER_REMOTE_PATH)

    // ADB Settings
    val adbKeyName by setting(ADB_KEY_NAME)
    val adbPairingAutoDiscoverOnDialogOpen by setting(ADB_PAIRING_AUTO_DISCOVER_ON_DIALOG_OPEN)
    val adbAutoReconnectPairedDevice by setting(ADB_AUTO_RECONNECT_PAIRED_DEVICE)
    val adbMdnsLanDiscovery by setting(ADB_MDNS_LAN_DISCOVERY)

    override suspend fun toMap(): Map<String, Any> {
        return mapOf(
            THEME_BASE_INDEX.name to themeBaseIndex.get(),
            MONET.name to monet.get(),
            FULLSCREEN_DEBUG_INFO.name to fullscreenDebugInfo.get(),
            SHOW_FULLSCREEN_VIRTUAL_BUTTONS.name to showFullscreenVirtualButtons.get(),
            KEEP_SCREEN_ON_WHEN_STREAMING.name to keepScreenOnWhenStreaming.get(),
            DEVICE_PREVIEW_CARD_HEIGHT_DP.name to devicePreviewCardHeightDp.get(),
            PREVIEW_VIRTUAL_BUTTON_SHOW_TEXT.name to previewVirtualButtonShowText.get(),
            VIRTUAL_BUTTONS_LAYOUT.name to virtualButtonsLayout.get(),
            CUSTOM_SERVER_URI.name to customServerUri.get(),
            SERVER_REMOTE_PATH.name to serverRemotePath.get(),
            ADB_KEY_NAME.name to adbKeyName.get(),
            ADB_PAIRING_AUTO_DISCOVER_ON_DIALOG_OPEN.name to adbPairingAutoDiscoverOnDialogOpen.get(),
            ADB_AUTO_RECONNECT_PAIRED_DEVICE.name to adbAutoReconnectPairedDevice.get(),
            ADB_MDNS_LAN_DISCOVERY.name to adbMdnsLanDiscovery.get()
        )
    }

    override fun validate(): Boolean = true
}
