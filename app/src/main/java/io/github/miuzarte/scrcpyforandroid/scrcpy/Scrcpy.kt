package io.github.miuzarte.scrcpyforandroid.scrcpy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade.ScrcpySessionInfo
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import io.github.miuzarte.scrcpyforandroid.nativecore.ScrcpyAudioPlayer
import io.github.miuzarte.scrcpyforandroid.nativecore.ScrcpySessionManager
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.ListOptions
import io.github.miuzarte.scrcpyforandroid.services.EventLogger.logEvent
import java.io.File
import kotlin.random.Random
import kotlin.random.nextUInt

/**
 * High-level scrcpy client API.
 * 
 * Manages scrcpy sessions including:
 * - Server jar extraction and deployment
 * - Session lifecycle (start/stop)
 * - Audio playback
 * - Screen control
 * 
 * @param context Android context
 * @param serverAsset Asset path for the default server jar
 * @param customServerUri Optional custom server URI (overrides serverAsset)
 * @param serverVersion Server version string
 * @param serverRemotePath Remote path where server jar will be pushed on device
 */
class Scrcpy(
    private val context: Context,
    private val serverAsset: String = DEFAULT_SERVER_ASSET,
    private val customServerUri: String? = null,
    private val serverVersion: String = "3.3.4",
    private val serverRemotePath: String = DEFAULT_REMOTE_PATH,
) {
    private val adbService = NativeAdbService(context)
    private val sessionManager = ScrcpySessionManager(adbService)
    private val nativeCore: NativeCoreFacade = NativeCoreFacade.get(context)

    @Volatile
    private var currentSession: ScrcpySessionInfo? = null

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var audioPlayer: ScrcpyAudioPlayer? = null

    companion object {
        private const val TAG = "Scrcpy"
        const val DEFAULT_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
        const val DEFAULT_SERVER_ASSET = "bin/scrcpy-server-v3.3.4"

        // Regex patterns for parsing server output
        private val VIDEO_ENCODER_REGEX = Regex("--video-encoder=([\\w.\\-]+)")
        private val AUDIO_ENCODER_REGEX = Regex("--audio-encoder=([\\w.\\-]+)")
        private val VIDEO_ENCODER_FALLBACK_REGEX = Regex("""--video-encoder=['"]?([^'"\s]+)""")
        private val AUDIO_ENCODER_FALLBACK_REGEX = Regex("""--audio-encoder=['"]?([^'"\s]+)""")
        private val VIDEO_ENCODER_TYPE_REGEX =
            Regex("""--video-codec=\S+\s+--video-encoder=(\S+).*?\((hw|sw)\)""")
        private val AUDIO_ENCODER_TYPE_REGEX =
            Regex("""--audio-codec=\S+\s+--audio-encoder=(\S+).*?\((hw|sw)\)""")
        private val CAMERA_SIZE_REGEX = Regex("--camera-size=([0-9]+x[0-9]+)")
        private val CAMERA_SIZE_FALLBACK_REGEX = Regex("\\b([1-9][0-9]{1,4}x[1-9][0-9]{1,4})\\b")
        private const val PREVIEW_LINES = 32

        fun generateScid(): UInt {
            // Only use 31 bits to avoid issues with signed values on the Java-side
            return (Random.nextUInt() and 0x7FFFFFFFu)
        }
    }

    suspend fun start(
        options: ClientOptions,
    ): ScrcpySessionInfo {
        if (isRunning) {
            throw IllegalStateException("Scrcpy session is already running")
        }

        Log.i(TAG, "Initializing scrcpy session")

        try {
            // Validate options
            options.validate()

            // Generate session ID
            val scid = generateScid()
            Log.d(TAG, "scid=0x${scid.toString(16)}")

            val serverJar = if (customServerUri.isNullOrBlank()) {
                extractAssetToCache(serverAsset)
            } else {
                extractUriToCache(customServerUri.toUri())
            }

            // Execute server
            val info = executeServer(
                serverJar = serverJar,
                options = options,
                scid = scid,
            )

            // Turn screen off if requested
            if (options.turnScreenOff) {
                if (!options.control) {
                    Log.w(TAG, "start(): turnScreenOff ignored because control is disabled")
                } else {
                    runCatching { sessionManager.setDisplayPower(on = false) }
                        .onFailure { e -> Log.w(TAG, "start(): set display power failed", e) }
                }
            }

            // Create session info
            val session = ScrcpySessionInfo(
                width = info.width,
                height = info.height,
                deviceName = info.deviceName,
                codec = info.codecName,
                controlEnabled = info.controlEnabled,
            )
            currentSession = session
            isRunning = true

            // Setup video consumer (notify NativeCoreFacade to setup decoders)
            if (options.video) {
                nativeCore.onScrcpySessionStarted(session, sessionManager)
            }

            // Setup audio player
            audioPlayer?.release()
            audioPlayer = null
            if (info.audioCodecId != 0 && options.audioPlayback) {
                Log.i(
                    TAG,
                    "start(): create audio player codecId=0x${
                        info.audioCodecId.toUInt().toString(16)
                    }"
                )
                val player = ScrcpyAudioPlayer(info.audioCodecId)
                audioPlayer = player
                sessionManager.attachAudioConsumer { packet ->
                    player.feedPacket(packet.data, packet.ptsUs, packet.isConfig)
                }
            } else {
                Log.i(TAG, "start(): audio playback disabled for this session")
            }

            Log.i(
                TAG, "start(): Session started successfully - device=${session.deviceName}, " +
                        "video=${if (options.video) "${session.codec} ${session.width}x${session.height}" else "off"}, " +
                        "audio=${if (options.audio) options.audioCodec.string else "off"}, " +
                        "control=${options.control}"
            )

            return session

        } catch (e: Exception) {
            Log.e(TAG, "start(): Failed to start scrcpy session", e)
            isRunning = false
            currentSession = null
            throw e
        }
    }

    suspend fun stop(): Boolean {
        if (!isRunning) {
            Log.w(TAG, "stop(): No active session to stop")
            return false
        }

        Log.i(TAG, "stop(): Stopping scrcpy session")

        return try {
            nativeCore.onScrcpySessionStopped()
            sessionManager.clearVideoConsumer()
            sessionManager.clearAudioConsumer()
            sessionManager.stop()
            audioPlayer?.release()
            audioPlayer = null
            isRunning = false
            currentSession = null
            Log.i(TAG, "stop(): Session stopped successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop(): Failed to stop session", e)
            false
        }
    }

    suspend fun close() {
        stop()
        adbService.close()
    }

    fun isStarted(): Boolean = isRunning && sessionManager.isStarted()

    fun getCurrentSession(): ScrcpySessionInfo? = currentSession

    fun getLastServerCommand(): String? = sessionManager.getLastServerCommand()

    sealed class ListResult {
        data class Encoders(
            val videoEncoders: List<String>,
            val audioEncoders: List<String>,
            val videoEncoderTypes: Map<String, String> = emptyMap(),
            val audioEncoderTypes: Map<String, String> = emptyMap(),
            val rawOutput: String = "",
        ) : ListResult()

        data class CameraSizes(
            val sizes: List<String>,
            val rawOutput: String = "",
        ) : ListResult()
    }

    /**
     * List various options from the scrcpy server.
     * 
     * @param list The type of list to retrieve (ENCODERS, CAMERA_SIZES, etc.)
     * @return ListResult containing the requested information
     */
    suspend fun listOptions(list: ListOptions): ListResult {
        val serverJar = if (customServerUri.isNullOrBlank()) {
            extractAssetToCache(serverAsset)
        } else {
            extractUriToCache(customServerUri.toUri())
        }

        // Push server jar to device
        adbService.push(serverJar.toPath(), serverRemotePath)

        val scid = generateScid()

        // Create ClientOptions for listing
        val options = ClientOptions(
            video = false,
            audio = false,
            control = false,
            cleanUp = false,
            list = list,
        )

        val serverParams = options.toServerParams(scid)

        // Build server command
        val serverCommand = serverParams.build(
            "CLASSPATH=$serverRemotePath",
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            serverVersion,
        )

        Log.i(TAG, "listOptions(): cmd=$serverCommand")

        // Execute shell command and capture output (merge stderr into stdout)
        val output = adbService.shell("$serverCommand 2>&1")

        // Parse output based on list option
        return when (list) {
            ListOptions.NULL -> {
                throw IllegalArgumentException("Nothing to do with ListOptions.NULL")
            }

            ListOptions.ENCODERS -> {
                val parsed = parseEncoderLists(output)
                val preview = output.lineSequence().take(PREVIEW_LINES).joinToString("\n")
                Log.i(
                    TAG,
                    "listOptions(ENCODERS): parsed video=${parsed.videoEncoders.size} audio=${parsed.audioEncoders.size}, outputPreview=\n$preview",
                )
                ListResult.Encoders(
                    videoEncoders = parsed.videoEncoders,
                    audioEncoders = parsed.audioEncoders,
                    videoEncoderTypes = parsed.videoEncoderTypes,
                    audioEncoderTypes = parsed.audioEncoderTypes,
                    rawOutput = output,
                )
            }

            ListOptions.DISPLAYS -> {
                throw Exception("TODO")
            }

            ListOptions.CAMERAS -> {
                throw Exception("TODO")
            }

            ListOptions.CAMERA_SIZES -> {
                val parsed = parseCameraSizeLists(output)
                val preview = output.lineSequence().take(PREVIEW_LINES).joinToString("\n")
                Log.i(
                    TAG,
                    "listOptions(CAMERA_SIZES): parsed sizes=${parsed.sizes.size}, outputPreview=\n$preview",
                )
                ListResult.CameraSizes(
                    sizes = parsed.sizes,
                    rawOutput = output,
                )
            }

            else -> {
                throw IllegalArgumentException("Unsupported list option: $list")
            }
        }
    }

    private fun parseEncoderLists(output: String): ParsedEncoders {
        val video = LinkedHashSet<String>()
        val audio = LinkedHashSet<String>()
        val videoTypes = linkedMapOf<String, String>()
        val audioTypes = linkedMapOf<String, String>()

        VIDEO_ENCODER_REGEX.findAll(output).forEach { match ->
            video.add(match.groupValues[1])
        }
        AUDIO_ENCODER_REGEX.findAll(output).forEach { match ->
            audio.add(match.groupValues[1])
        }
        // Fallback for log formats that include codec+encoder in one line.
        VIDEO_ENCODER_FALLBACK_REGEX.findAll(output).forEach { match ->
            video.add(match.groupValues[1])
        }
        AUDIO_ENCODER_FALLBACK_REGEX.findAll(output).forEach { match ->
            audio.add(match.groupValues[1])
        }
        VIDEO_ENCODER_TYPE_REGEX.findAll(output).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            if (name.isNotBlank() && type.isNotBlank() && !videoTypes.containsKey(name)) {
                videoTypes[name] = type
            }
        }
        AUDIO_ENCODER_TYPE_REGEX.findAll(output).forEach { match ->
            val name = match.groupValues[1]
            val type = match.groupValues[2]
            if (name.isNotBlank() && type.isNotBlank() && !audioTypes.containsKey(name)) {
                audioTypes[name] = type
            }
        }

        return ParsedEncoders(
            videoEncoders = video.toList(),
            audioEncoders = audio.toList(),
            videoEncoderTypes = videoTypes,
            audioEncoderTypes = audioTypes,
        )
    }

    private fun parseCameraSizeLists(output: String): ParsedCameraSizes {
        val sizes = LinkedHashSet<String>()
        CAMERA_SIZE_REGEX.findAll(output).forEach { match ->
            sizes.add(match.groupValues[1])
        }
        CAMERA_SIZE_FALLBACK_REGEX.findAll(output).forEach { match ->
            sizes.add(match.groupValues[1])
        }
        return ParsedCameraSizes(sizes = sizes.toList())
    }

    private data class ParsedEncoders(
        val videoEncoders: List<String>,
        val audioEncoders: List<String>,
        val videoEncoderTypes: Map<String, String>,
        val audioEncoderTypes: Map<String, String>,
    )

    private data class ParsedCameraSizes(
        val sizes: List<String>,
    )

    private suspend fun executeServer(
        serverJar: File,
        options: ClientOptions,
        scid: UInt,
    ): ScrcpySessionManager.SessionInfo {
        adbService.push(serverJar.toPath(), serverRemotePath)

        val serverParams = options.toServerParams(scid)

        val serverCommand = serverParams.build(
            "CLASSPATH=$serverRemotePath",
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            serverVersion,
        )
        Log.d(TAG, "Server command: $serverCommand")

        // Execute server (equivalent to sc_adb_execute in C)
        Log.i(TAG, "executeServer(): Starting scrcpy server")
        logEvent("scrcpy-server args: $serverCommand")
        return sessionManager.start(
            serverJarPath = serverJar.toPath(),
            serverCommand = serverCommand,
            scid = scid,
            options = options,
        )
    }

    private fun extractAssetToCache(assetPath: String): File {
        val clean = assetPath.removePrefix("/")
        val source = context.assets.open(clean)
        val outputFile = File(context.cacheDir, File(clean).name)
        source.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outputFile
    }

    private fun extractUriToCache(uri: Uri): File {
        val fileName = "custom-scrcpy-server.jar"
        val outputFile = File(context.cacheDir, fileName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected server URI" }
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outputFile
    }
}
