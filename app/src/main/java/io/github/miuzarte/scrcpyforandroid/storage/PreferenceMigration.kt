package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PreferenceMigration"

/**
 * 从旧的 SharedPreferences 迁移到新的 DataStore
 */
class PreferenceMigration(private val appContext: Context) {
    private val appSharedPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val sharedPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("nativecore_adb_rsa", Context.MODE_PRIVATE)
    }

    /**
     * 检查是否需要迁移（SharedPreferences 是否包含数据）
     */
    suspend fun needsMigration(): Boolean = withContext(Dispatchers.IO) {
        appSharedPrefs.all.isNotEmpty() || sharedPrefs.all.isNotEmpty()
    }

    /**
     * 执行完整迁移
     */
    suspend fun migrate(
        clearSharedPrefs: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (!needsMigration()) {
            Log.i(TAG, "No data to migrate, skipping")
            return@withContext
        } else {
            val appList = appSharedPrefs.all.entries.joinToString { (k, v) -> "\"$k\" to $v" }
            val adbList = sharedPrefs.all.entries.joinToString { (k, v) -> "\"$k\" to $v" }
            Log.d(TAG, "Migrating appSharedPrefs ($appList)")
        }

        Log.i(TAG, "Starting migration from SharedPreferences to DataStore")

        // 迁移 AppSettings
        migrateAppSettings()

        // 迁移 ScrcpyOptions
        migrateScrcpyOptions()

        // 迁移 QuickDevices
        migrateQuickDevices()

        // 迁移 ADB 密钥
        migrateAdbClientData()

        // 清空 SharedPreferences
        if (clearSharedPrefs) {
            appSharedPrefs.edit { clear() }
            sharedPrefs.edit { clear() }
            Log.d(TAG, "SharedPreferences cleared")
        }

        Log.i(
            TAG, "Migration completed successfully" +
                    " and SharedPreferences ${if (clearSharedPrefs) "" else "is not "}cleared"
        )
    }

    /**
     * 迁移应用设置
     */
    private suspend fun migrateAppSettings() {
        val appSettings = Storage.appSettings

        // Theme Settings
        migrateInt(
            THEME_BASE_INDEX,
            AppSettings.THEME_BASE_INDEX.defaultValue,
            appSettings.themeBaseIndex,
        )
        migrateBoolean(
            MONET,
            AppSettings.MONET.defaultValue,
            appSettings.monet,
        )

        // Scrcpy Settings
        migrateBoolean(
            FULLSCREEN_DEBUG_INFO,
            AppSettings.FULLSCREEN_DEBUG_INFO.defaultValue,
            appSettings.fullscreenDebugInfo,
        )
        migrateBoolean(
            SHOW_FULLSCREEN_VIRTUAL_BUTTONS,
            AppSettings.SHOW_FULLSCREEN_VIRTUAL_BUTTONS.defaultValue,
            appSettings.showFullscreenVirtualButtons,
        )
        migrateInt(
            DEVICE_PREVIEW_CARD_HEIGHT_DP,
            AppSettings.DEVICE_PREVIEW_CARD_HEIGHT_DP.defaultValue,
            appSettings.devicePreviewCardHeightDp,
        )
        migrateBoolean(
            PREVIEW_VIRTUAL_BUTTON_SHOW_TEXT,
            AppSettings.PREVIEW_VIRTUAL_BUTTON_SHOW_TEXT.defaultValue,
            appSettings.previewVirtualButtonShowText,
        )
        migrateString(
            VIRTUAL_BUTTONS_LAYOUT,
            AppSettings.VIRTUAL_BUTTONS_LAYOUT.defaultValue,
            appSettings.virtualButtonsLayout,
        )

        // Scrcpy Server Settings
        migrateString(
            CUSTOM_SERVER_URI,
            AppSettings.CUSTOM_SERVER_URI.defaultValue,
            appSettings.customServerUri,
        )
        migrateString(
            SERVER_REMOTE_PATH,
            AppSettings.SERVER_REMOTE_PATH.defaultValue,
            appSettings.serverRemotePath,
        )

        // ADB Settings
        migrateString(
            ADB_KEY_NAME,
            AppSettings.ADB_KEY_NAME.defaultValue,
            appSettings.adbKeyName,
        )
        migrateBoolean(
            ADB_PAIRING_AUTO_DISCOVER_ON_DIALOG_OPEN,
            AppSettings.ADB_PAIRING_AUTO_DISCOVER_ON_DIALOG_OPEN.defaultValue,
            appSettings.adbPairingAutoDiscoverOnDialogOpen,
        )
        migrateBoolean(
            ADB_AUTO_RECONNECT_PAIRED_DEVICE,
            AppSettings.ADB_AUTO_RECONNECT_PAIRED_DEVICE.defaultValue,
            appSettings.adbAutoReconnectPairedDevice,
        )
        migrateBoolean(
            ADB_MDNS_LAN_DISCOVERY,
            AppSettings.ADB_MDNS_LAN_DISCOVERY.defaultValue,
            appSettings.adbMdnsLanDiscovery,
        )

        Log.d(TAG, "AppSettings migration completed")
    }

    /**
     * 迁移 Scrcpy 选项
     */
    private suspend fun migrateScrcpyOptions() {
        val scrcpyOptions = Storage.scrcpyOptions

        // Audio & Video Codecs
        migrateString(
            AUDIO_CODEC,
            ScrcpyOptions.AUDIO_CODEC.defaultValue,
            scrcpyOptions.audioCodec,
        )
        migrateString(
            VIDEO_CODEC,
            ScrcpyOptions.VIDEO_CODEC.defaultValue,
            scrcpyOptions.videoCodec,
        )

        // Bit Rates
        val audioBitRateKbps = sharedPrefs.getInt(
            AUDIO_BIT_RATE_KBPS,
            ScrcpyOptions.AUDIO_BIT_RATE.defaultValue / 1_000,
        )
        scrcpyOptions.audioBitRate.set(audioBitRateKbps * 1_000) // Convert to bps

        val videoBitRateMbps = sharedPrefs.getFloat(
            VIDEO_BIT_RATE_MBPS,
            (ScrcpyOptions.VIDEO_BIT_RATE.defaultValue / 1_000_000).toFloat(),
        )
        scrcpyOptions.videoBitRate.set((videoBitRateMbps * 1_000_000).toInt())

        // Control Options
        migrateBoolean(
            TURN_SCREEN_OFF,
            ScrcpyOptions.TURN_SCREEN_OFF.defaultValue,
            scrcpyOptions.turnScreenOff,
        )
        migrateBoolean(
            NO_CONTROL,
            !ScrcpyOptions.CONTROL.defaultValue,
        ) { value ->
            scrcpyOptions.control.set(!value) // Invert logic
        }
        migrateBoolean(
            NO_VIDEO,
            !ScrcpyOptions.VIDEO.defaultValue,
        ) { value ->
            scrcpyOptions.video.set(!value) // Invert logic
        }

        // Video Source
        val videoSourcePreset = sharedPrefs.getString(
            VIDEO_SOURCE_PRESET,
            ScrcpyOptions.VIDEO_SOURCE.defaultValue,
        ).orEmpty().ifBlank { ScrcpyOptions.VIDEO_SOURCE.defaultValue }
        scrcpyOptions.videoSource.set(videoSourcePreset)

        migrateString(
            DISPLAY_ID,
            ScrcpyOptions.DISPLAY_ID.defaultValue.toString(),
        ) { value ->
            value.toIntOrNull()?.let { scrcpyOptions.displayId.set(it) }
        }

        // Camera Settings
        migrateString(
            CAMERA_ID,
            ScrcpyOptions.CAMERA_ID.defaultValue,
            scrcpyOptions.cameraId,
        )
        migrateString(
            CAMERA_FACING_PRESET,
            ScrcpyOptions.CAMERA_FACING.defaultValue,
            scrcpyOptions.cameraFacing,
        )
        migrateString(
            CAMERA_SIZE_PRESET,
            ScrcpyOptions.CAMERA_SIZE.defaultValue,
        ) { value ->
            if (value == "custom") {
                val customSize = sharedPrefs.getString(
                    CAMERA_SIZE_CUSTOM,
                    ScrcpyOptions.CAMERA_SIZE_CUSTOM.defaultValue,
                ).orEmpty()
                scrcpyOptions.cameraSizeCustom.set(customSize)
                scrcpyOptions.cameraSizeUseCustom.set(true)
            } else {
                scrcpyOptions.cameraSize.set(value)
            }
        }
        migrateString(
            CAMERA_AR,
            ScrcpyOptions.CAMERA_AR.defaultValue,
            scrcpyOptions.cameraAr,
        )
        migrateString(
            CAMERA_FPS,
            ScrcpyOptions.CAMERA_FPS.defaultValue.toString(),
        ) { value ->
            value.toIntOrNull()?.let { scrcpyOptions.cameraFps.set(it) }
        }
        migrateBoolean(
            CAMERA_HIGH_SPEED,
            ScrcpyOptions.CAMERA_HIGH_SPEED.defaultValue,
            scrcpyOptions.cameraHighSpeed,
        )

        // Audio Source
        val audioSourcePreset = sharedPrefs.getString(
            AUDIO_SOURCE_PRESET,
            "auto",
        ).orEmpty().ifBlank { "auto" }

        if (audioSourcePreset == "custom") {
            val customSource = sharedPrefs.getString(
                AUDIO_SOURCE_CUSTOM,
                ScrcpyOptions.AUDIO_SOURCE.defaultValue,
            ).orEmpty()
            scrcpyOptions.audioSource.set(customSource)
        } else {
            scrcpyOptions.audioSource.set(audioSourcePreset)
        }

        migrateBoolean(
            AUDIO_DUP,
            ScrcpyOptions.AUDIO_DUP.defaultValue,
            scrcpyOptions.audioDup,
        )
        migrateBoolean(
            NO_AUDIO_PLAYBACK,
            !ScrcpyOptions.AUDIO_PLAYBACK.defaultValue,
        ) { value ->
            scrcpyOptions.audioPlayback.set(!value) // Invert logic
        }
        migrateBoolean(
            REQUIRE_AUDIO,
            ScrcpyOptions.REQUIRE_AUDIO.defaultValue,
            scrcpyOptions.requireAudio,
        )

        // Max Size & FPS
        migrateString(
            MAX_SIZE_INPUT,
            ScrcpyOptions.MAX_SIZE.defaultValue.toString(),
        ) { value ->
            value.toIntOrNull()?.let { scrcpyOptions.maxSize.set(it) }
        }
        migrateString(
            MAX_FPS_INPUT,
            ScrcpyOptions.MAX_FPS.defaultValue,
            scrcpyOptions.maxFps,
        )

        // Encoders & Codec Options
        migrateString(
            VIDEO_ENCODER,
            ScrcpyOptions.VIDEO_ENCODER.defaultValue,
            scrcpyOptions.videoEncoder,
        )
        migrateString(
            VIDEO_CODEC_OPTION,
            ScrcpyOptions.VIDEO_CODEC_OPTIONS.defaultValue,
            scrcpyOptions.videoCodecOptions,
        )
        migrateString(
            AUDIO_ENCODER,
            ScrcpyOptions.AUDIO_ENCODER.defaultValue,
            scrcpyOptions.audioEncoder,
        )
        migrateString(
            AUDIO_CODEC_OPTION,
            ScrcpyOptions.AUDIO_CODEC_OPTIONS.defaultValue,
            scrcpyOptions.audioCodecOptions,
        )

        // New Display
        val newDisplayWidth = sharedPrefs.getString(
            NEW_DISPLAY_WIDTH,
            "",
        ).orEmpty()
        val newDisplayHeight = sharedPrefs.getString(
            NEW_DISPLAY_HEIGHT,
            "",
        ).orEmpty()
        val newDisplayDpi = sharedPrefs.getString(
            NEW_DISPLAY_DPI,
            "",
        ).orEmpty()

        if (newDisplayWidth.isNotBlank() && newDisplayHeight.isNotBlank()) {
            val newDisplay = if (newDisplayDpi.isNotBlank()) {
                "${newDisplayWidth}x${newDisplayHeight}/${newDisplayDpi}"
            } else {
                "${newDisplayWidth}x${newDisplayHeight}"
            }
            scrcpyOptions.newDisplay.set(newDisplay)
        }

        // Crop
        val cropWidth = sharedPrefs.getString(
            CROP_WIDTH,
            "",
        ).orEmpty()
        val cropHeight = sharedPrefs.getString(
            CROP_HEIGHT,
            "",
        ).orEmpty()
        val cropX = sharedPrefs.getString(
            CROP_X,
            "",
        ).orEmpty()
        val cropY = sharedPrefs.getString(
            CROP_Y,
            "",
        ).orEmpty()

        if (cropWidth.isNotBlank() && cropHeight.isNotBlank()
            && cropX.isNotBlank() && cropY.isNotBlank()
        ) {
            scrcpyOptions.crop.set("${cropWidth}:${cropHeight}:${cropX}:${cropY}")
        }

        migrateBoolean(
            AUDIO_ENABLED,
            ScrcpyOptions.AUDIO.defaultValue,
            scrcpyOptions.audio,
        )

        Log.d(TAG, "ScrcpyOptions migration completed")
    }

    /**
     * 迁移快速设备列表
     */
    private suspend fun migrateQuickDevices() {
        val quickDevices = Storage.quickDevices

        // Migrate quick devices list
        val quickDevicesRaw = appSharedPrefs.getString(
            QUICK_DEVICES,
            ""
        ).orEmpty()
        if (quickDevicesRaw.isNotBlank()) {
            quickDevices.quickDevicesList.set(quickDevicesRaw)
        }

        // Migrate quick connect input
        migrateString(
            QUICK_CONNECT_INPUT,
            QuickDevices.QUICK_CONNECT_INPUT.defaultValue,
            quickDevices.quickConnectInput,
        )

        Log.d(TAG, "QuickDevices migration completed")
    }

    /**
     * 迁移 ADB 客户端数据（RSA 密钥）
     */
    private suspend fun migrateAdbClientData() {
        val adbClientData = Storage.adbClientData

        // 迁移 RSA 私钥
        val privKey = sharedPrefs.getString("priv", null)
        if (privKey != null) {
            adbClientData.rsaPrivateKey.set(privKey)
            Log.d(TAG, "ADB RSA private key migrated")
        }

        Log.d(TAG, "AdbClientData migration completed")
    }

    // Helper methods for different data types

    private suspend fun migrateString(
        key: String,
        defaultValue: String,
        settingProperty: Settings.SettingProperty<String>
    ) {
        val value = appSharedPrefs.getString(key, defaultValue)
            .orEmpty()
            .ifBlank { defaultValue }
        settingProperty.set(value)
    }

    private suspend fun migrateString(
        key: String,
        defaultValue: String,
        action: suspend (String) -> Unit
    ) {
        val value = appSharedPrefs.getString(key, defaultValue)
            .orEmpty()
            .ifBlank { defaultValue }
        action(value)
    }

    private suspend fun migrateInt(
        key: String,
        defaultValue: Int,
        settingProperty: Settings.SettingProperty<Int>
    ) {
        val value = appSharedPrefs.getInt(key, defaultValue)
        settingProperty.set(value)
    }

    private suspend fun migrateInt(
        key: String,
        defaultValue: Int,
        action: suspend (Int) -> Unit
    ) {
        val value = appSharedPrefs.getInt(key, defaultValue)
        action(value)
    }

    private suspend fun migrateBoolean(
        key: String,
        defaultValue: Boolean,
        settingProperty: Settings.SettingProperty<Boolean>
    ) {
        val value = appSharedPrefs.getBoolean(key, defaultValue)
        settingProperty.set(value)
    }

    private suspend fun migrateBoolean(
        key: String,
        defaultValue: Boolean,
        action: suspend (Boolean) -> Unit
    ) {
        val value = appSharedPrefs.getBoolean(key, defaultValue)
        action(value)
    }

    companion object {
        const val PREFS_NAME = "scrcpy_app_prefs"

        // Devices
        const val QUICK_DEVICES = "quick_devices"
        const val QUICK_CONNECT_INPUT = "quick_connect_input"

        const val PAIR_HOST = "pair_host"
        const val PAIR_PORT = "pair_port"
        const val PAIR_CODE = "pair_code"

        const val AUDIO_ENABLED = "audio_enabled"
        const val AUDIO_CODEC = "audio_codec"
        const val AUDIO_BIT_RATE_INPUT = "audio_bit_rate_input"
        const val AUDIO_BIT_RATE_KBPS = "audio_bit_rate_kbps"
        const val VIDEO_CODEC = "video_codec"
        const val VIDEO_BIT_RATE_MBPS = "video_bit_rate_mbps"
        const val VIDEO_BIT_RATE_INPUT = "video_bit_rate_input"

        const val TURN_SCREEN_OFF = "turn_screen_off"
        const val NO_CONTROL = "no_control"
        const val NO_VIDEO = "no_video"

        const val VIDEO_SOURCE_PRESET = "video_source_preset"
        const val DISPLAY_ID = "display_id"

        const val CAMERA_ID = "camera_id"
        const val CAMERA_FACING_PRESET = "camera_facing_preset"
        const val CAMERA_SIZE_PRESET = "camera_size_preset"
        const val CAMERA_SIZE_CUSTOM = "camera_size_custom"
        const val CAMERA_AR = "camera_ar"
        const val CAMERA_FPS = "camera_fps"
        const val CAMERA_HIGH_SPEED = "camera_high_speed"

        const val AUDIO_SOURCE_PRESET = "audio_source_preset"
        const val AUDIO_SOURCE_CUSTOM = "audio_source_custom"
        const val AUDIO_DUP = "audio_dup"
        const val NO_AUDIO_PLAYBACK = "no_audio_playback"
        const val REQUIRE_AUDIO = "require_audio"

        const val MAX_SIZE_INPUT = "max_size_input"
        const val MAX_FPS_INPUT = "max_fps_input"

        const val VIDEO_ENCODER = "video_encoder"
        const val VIDEO_CODEC_OPTION = "video_codec_options"
        const val AUDIO_ENCODER = "audio_encoder"
        const val AUDIO_CODEC_OPTION = "audio_codec_options"

        const val NEW_DISPLAY_WIDTH = "new_display_width"
        const val NEW_DISPLAY_HEIGHT = "new_display_height"
        const val NEW_DISPLAY_DPI = "new_display_dpi"

        const val CROP_WIDTH = "crop_width"
        const val CROP_HEIGHT = "crop_height"
        const val CROP_X = "crop_x"
        const val CROP_Y = "crop_y"

        // Settings
        const val THEME_BASE_INDEX = "theme_base_index"
        const val MONET = "monet"

        const val FULLSCREEN_DEBUG_INFO = "fullscreen_debug_info"
        const val SHOW_FULLSCREEN_VIRTUAL_BUTTONS = "show_fullscreen_virtual_buttons"
        const val DEVICE_PREVIEW_CARD_HEIGHT_DP = "device_preview_card_height_dp"
        const val PREVIEW_VIRTUAL_BUTTON_SHOW_TEXT = "preview_virtual_button_show_text"
        const val VIRTUAL_BUTTONS_LAYOUT = "virtual_buttons_layout"

        const val CUSTOM_SERVER_URI = "custom_server_uri"

        const val SERVER_REMOTE_PATH = "server_remote_path"

        const val ADB_KEY_NAME = "adb_key_name"
        const val ADB_PAIRING_AUTO_DISCOVER_ON_DIALOG_OPEN =
            "adb_pairing_auto_discover_on_dialog_open"
        const val ADB_AUTO_RECONNECT_PAIRED_DEVICE = "adb_auto_reconnect_paired_device"
        const val ADB_MDNS_LAN_DISCOVERY = "adb_mdns_lan_discovery"
    }
}
