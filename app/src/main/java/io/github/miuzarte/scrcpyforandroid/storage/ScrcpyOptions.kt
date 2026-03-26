package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.miuzarte.scrcpyforandroid.scrcpy.ClientOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.AudioSource
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.CameraFacing
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Codec
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.DisplayImePolicy
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.ListOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.LogLevel
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Orientation
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.OrientationLock
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Tick
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.VideoSource
import kotlinx.coroutines.runBlocking

class ScrcpyOptions(context: Context) : Settings(context, "ScrcpyOptions") {
    companion object {
        private val CROP = Pair(
            stringPreferencesKey("crop"),
            ""
        )
        private val RECORD_FILENAME = Pair(
            stringPreferencesKey("record_filename"),
            ""
        )
        private val VIDEO_CODEC_OPTIONS = Pair(
            stringPreferencesKey("video_codec_options"),
            ""
        )
        private val AUDIO_CODEC_OPTIONS = Pair(
            stringPreferencesKey("audio_codec_options"),
            ""
        )
        private val VIDEO_ENCODER = Pair(
            stringPreferencesKey("video_encoder"),
            ""
        )
        private val AUDIO_ENCODER = Pair(
            stringPreferencesKey("audio_encoder"),
            ""
        )
        private val CAMERA_ID = Pair(
            stringPreferencesKey("camera_id"),
            ""
        )
        private val CAMERA_SIZE = Pair(
            stringPreferencesKey("camera_size"),
            ""
        )
        private val CAMERA_AR = Pair(
            stringPreferencesKey("camera_ar"),
            ""
        )
        private val CAMERA_FPS = Pair(
            intPreferencesKey("camera_fps"),
            0
        )
        private val LOG_LEVEL = Pair(
            stringPreferencesKey("log_level"),
            "info"
        )
        private val VIDEO_CODEC = Pair(
            stringPreferencesKey("video_codec"),
            "h264"
        )
        private val AUDIO_CODEC = Pair(
            stringPreferencesKey("audio_codec"),
            "opus"
        )
        private val VIDEO_SOURCE = Pair(
            stringPreferencesKey("video_source"),
            "display"
        )
        private val AUDIO_SOURCE = Pair(
            stringPreferencesKey("audio_source"),
            "output"
        )
        private val RECORD_FORMAT = Pair(
            stringPreferencesKey("record_format"),
            "auto"
        )
        private val CAMERA_FACING = Pair(
            stringPreferencesKey("camera_facing"),
            "any"
        )
        private val MAX_SIZE = Pair(
            intPreferencesKey("max_size"),
            0
        )
        private val VIDEO_BIT_RATE = Pair(
            intPreferencesKey("video_bit_rate"),
            8000000
        )
        private val AUDIO_BIT_RATE = Pair(
            intPreferencesKey("audio_bit_rate"),
            128000
        )
        private val MAX_FPS = Pair(
            stringPreferencesKey("max_fps"),
            ""
        )
        private val ANGLE = Pair(
            stringPreferencesKey("angle"),
            ""
        )
        private val CAPTURE_ORIENTATION = Pair(
            intPreferencesKey("capture_orientation"),
            0
        )
        private val CAPTURE_ORIENTATION_LOCK = Pair(
            stringPreferencesKey("capture_orientation_lock"),
            "unlocked"
        )
        private val DISPLAY_ORIENTATION = Pair(
            intPreferencesKey("display_orientation"),
            0
        )
        private val RECORD_ORIENTATION = Pair(
            intPreferencesKey("record_orientation"),
            0
        )
        private val DISPLAY_IME_POLICY = Pair(
            stringPreferencesKey("display_ime_policy"),
            "undefined"
        )
        private val DISPLAY_ID = Pair(
            intPreferencesKey("display_id"),
            0
        )
        private val SCREEN_OFF_TIMEOUT = Pair(
            longPreferencesKey("screen_off_timeout"),
            -1
        )
        private val SHOW_TOUCHES = Pair(
            booleanPreferencesKey("show_touches"),
            false
        )
        private val FULLSCREEN = Pair(
            booleanPreferencesKey("fullscreen"),
            false
        )
        private val CONTROL = Pair(
            booleanPreferencesKey("control"),
            true
        )
        private val VIDEO_PLAYBACK = Pair(
            booleanPreferencesKey("video_playback"),
            true
        )
        private val AUDIO_PLAYBACK = Pair(
            booleanPreferencesKey("audio_playback"),
            true
        )
        private val TURN_SCREEN_OFF = Pair(
            booleanPreferencesKey("turn_screen_off"),
            false
        )
        private val STAY_AWAKE = Pair(
            booleanPreferencesKey("stay_awake"),
            false
        )
        private val DISABLE_SCREENSAVER = Pair(
            booleanPreferencesKey("disable_screensaver"),
            false
        )
        private val POWER_OFF_ON_CLOSE = Pair(
            booleanPreferencesKey("power_off_on_close"),
            false
        )
        private val CLEANUP = Pair(
            booleanPreferencesKey("cleanup"),
            true
        )
        private val POWER_ON = Pair(
            booleanPreferencesKey("power_on"),
            true
        )
        private val VIDEO = Pair(
            booleanPreferencesKey("video"),
            true
        )
        private val AUDIO = Pair(
            booleanPreferencesKey("audio"),
            true
        )
        private val REQUIRE_AUDIO = Pair(
            booleanPreferencesKey("require_audio"),
            false
        )
        private val KILL_ADB_ON_CLOSE = Pair(
            booleanPreferencesKey("kill_adb_on_close"),
            false
        )
        private val CAMERA_HIGH_SPEED = Pair(
            booleanPreferencesKey("camera_high_speed"),
            false
        )
        private val LIST = Pair(
            stringPreferencesKey("list"),
            "null"
        )
        private val AUDIO_DUP = Pair(
            booleanPreferencesKey("audio_dup"),
            false
        )
        private val NEW_DISPLAY = Pair(
            stringPreferencesKey("new_display"),
            ""
        )
        private val START_APP = Pair(
            stringPreferencesKey("start_app"),
            ""
        )
        private val VD_DESTROY_CONTENT = Pair(
            booleanPreferencesKey("vd_destroy_content"),
            true
        )
        private val VD_SYSTEM_DECORATIONS = Pair(
            booleanPreferencesKey("vd_system_decorations"),
            true
        )
    }

    val crop by setting(CROP)
    val recordFilename by setting(RECORD_FILENAME)
    val videoCodecOptions by setting(VIDEO_CODEC_OPTIONS)
    val audioCodecOptions by setting(AUDIO_CODEC_OPTIONS)
    val videoEncoder by setting(VIDEO_ENCODER)
    val audioEncoder by setting(AUDIO_ENCODER)
    val cameraId by setting(CAMERA_ID)
    val cameraSize by setting(CAMERA_SIZE)
    val cameraAr by setting(CAMERA_AR)
    val cameraFps by setting(CAMERA_FPS)
    val logLevel by setting(LOG_LEVEL)
    val videoCodec by setting(VIDEO_CODEC)
    val audioCodec by setting(AUDIO_CODEC)
    val videoSource by setting(VIDEO_SOURCE)
    val audioSource by setting(AUDIO_SOURCE)
    val recordFormat by setting(RECORD_FORMAT)
    val cameraFacing by setting(CAMERA_FACING)
    val maxSize by setting(MAX_SIZE)
    val videoBitRate by setting(VIDEO_BIT_RATE)
    val audioBitRate by setting(AUDIO_BIT_RATE)
    val maxFps by setting(MAX_FPS)
    val angle by setting(ANGLE)
    val captureOrientation by setting(CAPTURE_ORIENTATION)
    val captureOrientationLock by setting(CAPTURE_ORIENTATION_LOCK)
    val displayOrientation by setting(DISPLAY_ORIENTATION)
    val recordOrientation by setting(RECORD_ORIENTATION)
    val displayImePolicy by setting(DISPLAY_IME_POLICY)
    val displayId by setting(DISPLAY_ID)
    val screenOffTimeout by setting(SCREEN_OFF_TIMEOUT)
    val showTouches by setting(SHOW_TOUCHES)
    val fullscreen by setting(FULLSCREEN)
    val control by setting(CONTROL)
    val videoPlayback by setting(VIDEO_PLAYBACK)
    val audioPlayback by setting(AUDIO_PLAYBACK)
    val turnScreenOff by setting(TURN_SCREEN_OFF)
    val stayAwake by setting(STAY_AWAKE)
    val disableScreensaver by setting(DISABLE_SCREENSAVER)
    val powerOffOnClose by setting(POWER_OFF_ON_CLOSE)
    val cleanup by setting(CLEANUP)
    val powerOn by setting(POWER_ON)
    val video by setting(VIDEO)
    val audio by setting(AUDIO)
    val requireAudio by setting(REQUIRE_AUDIO)
    val killAdbOnClose by setting(KILL_ADB_ON_CLOSE)
    val cameraHighSpeed by setting(CAMERA_HIGH_SPEED)
    val list by setting(LIST)
    val audioDup by setting(AUDIO_DUP)
    val newDisplay by setting(NEW_DISPLAY)
    val startApp by setting(START_APP)
    val vdDestroyContent by setting(VD_DESTROY_CONTENT)
    val vdSystemDecorations by setting(VD_SYSTEM_DECORATIONS)

    override suspend fun toMap(): Map<String, Any> {
        return mapOf(
            CROP.name to crop.get(),
            RECORD_FILENAME.name to recordFilename.get(),
            VIDEO_CODEC_OPTIONS.name to videoCodecOptions.get(),
            AUDIO_CODEC_OPTIONS.name to audioCodecOptions.get(),
            VIDEO_ENCODER.name to videoEncoder.get(),
            AUDIO_ENCODER.name to audioEncoder.get(),
            CAMERA_ID.name to cameraId.get(),
            CAMERA_SIZE.name to cameraSize.get(),
            CAMERA_AR.name to cameraAr.get(),
            CAMERA_FPS.name to cameraFps.get(),
            LOG_LEVEL.name to logLevel.get(),
            VIDEO_CODEC.name to videoCodec.get(),
            AUDIO_CODEC.name to audioCodec.get(),
            VIDEO_SOURCE.name to videoSource.get(),
            AUDIO_SOURCE.name to audioSource.get(),
            RECORD_FORMAT.name to recordFormat.get(),
            CAMERA_FACING.name to cameraFacing.get(),
            MAX_SIZE.name to maxSize.get(),
            VIDEO_BIT_RATE.name to videoBitRate.get(),
            AUDIO_BIT_RATE.name to audioBitRate.get(),
            MAX_FPS.name to maxFps.get(),
            ANGLE.name to angle.get(),
            CAPTURE_ORIENTATION.name to captureOrientation.get(),
            CAPTURE_ORIENTATION_LOCK.name to captureOrientationLock.get(),
            DISPLAY_ORIENTATION.name to displayOrientation.get(),
            RECORD_ORIENTATION.name to recordOrientation.get(),
            DISPLAY_IME_POLICY.name to displayImePolicy.get(),
            DISPLAY_ID.name to displayId.get(),
            SCREEN_OFF_TIMEOUT.name to screenOffTimeout.get(),
            SHOW_TOUCHES.name to showTouches.get(),
            FULLSCREEN.name to fullscreen.get(),
            CONTROL.name to control.get(),
            VIDEO_PLAYBACK.name to videoPlayback.get(),
            AUDIO_PLAYBACK.name to audioPlayback.get(),
            TURN_SCREEN_OFF.name to turnScreenOff.get(),
            STAY_AWAKE.name to stayAwake.get(),
            DISABLE_SCREENSAVER.name to disableScreensaver.get(),
            POWER_OFF_ON_CLOSE.name to powerOffOnClose.get(),
            CLEANUP.name to cleanup.get(),
            POWER_ON.name to powerOn.get(),
            VIDEO.name to video.get(),
            AUDIO.name to audio.get(),
            REQUIRE_AUDIO.name to requireAudio.get(),
            KILL_ADB_ON_CLOSE.name to killAdbOnClose.get(),
            CAMERA_HIGH_SPEED.name to cameraHighSpeed.get(),
            LIST.name to list.get(),
            AUDIO_DUP.name to audioDup.get(),
            NEW_DISPLAY.name to newDisplay.get(),
            START_APP.name to startApp.get(),
            VD_DESTROY_CONTENT.name to vdDestroyContent.get(),
            VD_SYSTEM_DECORATIONS.name to vdSystemDecorations.get()
        )
    }

    override fun validate(): Boolean = runBlocking {
        runCatching {
            toClientOptions().validate()
            true
        }.getOrDefault(false)
    }

    // TODO: 处理空值
    suspend fun toClientOptions(): ClientOptions {
        return ClientOptions(
            crop = crop.get(),
            recordFilename = recordFilename.get(),
            videoCodecOptions = videoCodecOptions.get(),
            audioCodecOptions = audioCodecOptions.get(),
            videoEncoder = videoEncoder.get(),
            audioEncoder = audioEncoder.get(),
            cameraId = cameraId.get(),
            cameraSize = cameraSize.get(),
            cameraAr = cameraAr.get(),
            cameraFps = cameraFps.get().toUShort(),
            logLevel = LogLevel.valueOf(logLevel.get().uppercase()),
            videoCodec = Codec.valueOf(videoCodec.get().uppercase()),
            audioCodec = Codec.valueOf(audioCodec.get().uppercase()),
            videoSource = VideoSource.valueOf(videoSource.get().uppercase()),
            audioSource = AudioSource.valueOf(audioSource.get().uppercase()),
            recordFormat = ClientOptions.RecordFormat.valueOf(recordFormat.get().uppercase()),
            cameraFacing = CameraFacing.valueOf(cameraFacing.get().uppercase()),
            maxSize = maxSize.get().toUShort(),
            videoBitRate = videoBitRate.get().toUInt(),
            audioBitRate = audioBitRate.get().toUInt(),
            maxFps = maxFps.get(),
            angle = angle.get(),
            captureOrientation = Orientation.fromInt(captureOrientation.get()),
            captureOrientationLock = OrientationLock.valueOf(
                captureOrientationLock.get().uppercase()
            ),
            displayOrientation = Orientation.fromInt(displayOrientation.get()),
            recordOrientation = Orientation.fromInt(recordOrientation.get()),
            displayImePolicy = DisplayImePolicy.valueOf(displayImePolicy.get().uppercase()),
            displayId = displayId.get().toUInt(),
            screenOffTimeout = Tick(screenOffTimeout.get()),
            showTouches = showTouches.get(),
            fullscreen = fullscreen.get(),
            control = control.get(),
            videoPlayback = videoPlayback.get(),
            audioPlayback = audioPlayback.get(),
            turnScreenOff = turnScreenOff.get(),
            stayAwake = stayAwake.get(),
            disableScreensaver = disableScreensaver.get(),
            powerOffOnClose = powerOffOnClose.get(),
            cleanUp = cleanup.get(),
            powerOn = powerOn.get(),
            video = video.get(),
            audio = audio.get(),
            requireAudio = requireAudio.get(),
            killAdbOnClose = killAdbOnClose.get(),
            cameraHighSpeed = cameraHighSpeed.get(),
            list = ListOptions.valueOf(list.get().uppercase()),
            audioDup = audioDup.get(),
            newDisplay = newDisplay.get(),
            startApp = startApp.get(),
            vdDestroyContent = vdDestroyContent.get(),
            vdSystemDecorations = vdSystemDecorations.get()
        )
    }
}
