package io.github.miuzarte.scrcpyforandroid.pages

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade.ScrcpySessionInfo
import io.github.miuzarte.scrcpyforandroid.constants.AppDefaults
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import io.github.miuzarte.scrcpyforandroid.haptics.rememberAppHaptics
import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import io.github.miuzarte.scrcpyforandroid.models.DeviceShortcut
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import io.github.miuzarte.scrcpyforandroid.scaffolds.AppPageLazyColumn
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.ListOptions
import io.github.miuzarte.scrcpyforandroid.services.EventLogger
import io.github.miuzarte.scrcpyforandroid.services.EventLogger.logEvent
import io.github.miuzarte.scrcpyforandroid.services.fetchConnectedDeviceInfo
import io.github.miuzarte.scrcpyforandroid.services.loadDevicePageSettings
import io.github.miuzarte.scrcpyforandroid.services.loadQuickDevices
import io.github.miuzarte.scrcpyforandroid.services.parseQuickTarget
import io.github.miuzarte.scrcpyforandroid.services.replaceQuickDevicePort
import io.github.miuzarte.scrcpyforandroid.services.saveQuickDevices
import io.github.miuzarte.scrcpyforandroid.services.updateQuickDeviceNameIfEmpty
import io.github.miuzarte.scrcpyforandroid.services.upsertQuickDevice
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings
import io.github.miuzarte.scrcpyforandroid.storage.ScrcpyOptions
import io.github.miuzarte.scrcpyforandroid.widgets.ConfigPanel
import io.github.miuzarte.scrcpyforandroid.widgets.DeviceEditorScreen
import io.github.miuzarte.scrcpyforandroid.widgets.DeviceTile
import io.github.miuzarte.scrcpyforandroid.widgets.LogsPanel
import io.github.miuzarte.scrcpyforandroid.widgets.PairingCard
import io.github.miuzarte.scrcpyforandroid.widgets.PreviewCard
import io.github.miuzarte.scrcpyforandroid.widgets.QuickConnectCard
import io.github.miuzarte.scrcpyforandroid.widgets.ReorderableList
import io.github.miuzarte.scrcpyforandroid.widgets.SectionSmallTitle
import io.github.miuzarte.scrcpyforandroid.widgets.StatusCard
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonAction
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonActions
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

private const val ADB_CONNECT_TIMEOUT_MS = 3_000L
private const val ADB_KEEPALIVE_INTERVAL_MS = 3_000L
private const val ADB_KEEPALIVE_TIMEOUT_MS = 1_500L
private const val ADB_AUTO_RECONNECT_DISCOVER_TIMEOUT_MS = 1_200L
private const val ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS = 1_500L
private const val ADB_TCP_PROBE_TIMEOUT_MS = 600
private const val DEVICE_SHORTCUT_SEPARATOR = "\u001F"

private val DeviceShortcutStateListSaver =
    listSaver<SnapshotStateList<DeviceShortcut>, String>(
        save = { list ->
            list.map { item ->
                listOf(
                    item.id,
                    item.name,
                    item.host,
                    item.port.toString(),
                    if (item.online) "1" else "0",
                ).joinToString(DEVICE_SHORTCUT_SEPARATOR)
            }
        },
        restore = { saved ->
            saved.mapNotNull { line ->
                val parts = line.split(DEVICE_SHORTCUT_SEPARATOR)
                if (parts.size != 5) return@mapNotNull null
                val port = parts[3].toIntOrNull() ?: return@mapNotNull null
                DeviceShortcut(
                    name = parts[1],
                    host = parts[2],
                    port = port,
                    online = parts[4] == "1",
                )
            }.toMutableStateList()
        },
    )

private val StringStateListSaver =
    listSaver<SnapshotStateList<String>, String>(
        save = { it.toList() },
        restore = { it.toMutableStateList() },
    )

@Composable
fun DeviceTabScreen(
    contentPadding: PaddingValues,
    nativeCore: NativeCoreFacade,
    adbService: NativeAdbService,
    scrcpy: Scrcpy,
    snack: SnackbarHostState,
    scrollBehavior: ScrollBehavior,

    videoEncoderOptions: List<String>,
    onVideoEncoderOptionsChange: (List<String>) -> Unit,
    onVideoEncoderTypeMapChange: (Map<String, String>) -> Unit,
    audioEncoderOptions: List<String>,
    onAudioEncoderOptionsChange: (List<String>) -> Unit,
    onAudioEncoderTypeMapChange: (Map<String, String>) -> Unit,
    cameraSizeOptions: List<String>,
    onCameraSizeOptionsChange: (List<String>) -> Unit,
    onSessionStartedChange: (Boolean) -> Unit,
    onRefreshEncodersActionChange: ((() -> Unit)?) -> Unit,
    onRefreshCameraSizesActionChange: ((() -> Unit)?) -> Unit,
    onClearLogsActionChange: ((() -> Unit)?) -> Unit,
    onCanClearLogsChange: (Boolean) -> Unit,
    onOpenReorderDevicesActionChange: ((() -> Unit)?) -> Unit,
    onOpenAdvancedPage: () -> Unit,
    onOpenFullscreenPage: (ScrcpySessionInfo) -> Unit,
) {
    val context = LocalContext.current

    val appSettings = remember(context) { AppSettings(context) }
    val scrcpyOptions = remember(context) { ScrcpyOptions(context) }

    val haptics = rememberAppHaptics()

    val virtualButtonsLayout by appSettings.virtualButtonsLayout.asState()
    val virtualButtonLayout = remember(virtualButtonsLayout) {
        VirtualButtonActions.splitLayout(VirtualButtonActions.parseStoredLayout(virtualButtonsLayout))
    }
    val initialSettings = remember(context) { loadDevicePageSettings(context) }
    val scope = rememberCoroutineScope()
    val adbWorkerDispatcher = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "adb-connect-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    // Run adb operations on a dedicated single thread.
    // Try to avoid blocking UI/recomposition and keeps adb call ordering deterministic.

    DisposableEffect(adbWorkerDispatcher) {
        onDispose {
            adbWorkerDispatcher.close()
        }
    }

    var busy by rememberSaveable { mutableStateOf(false) }
    var statusLine by rememberSaveable { mutableStateOf("未连接") }
    var adbConnected by rememberSaveable { mutableStateOf(false) }
    var currentTargetHost by rememberSaveable { mutableStateOf("") }
    var currentTargetPort by rememberSaveable { mutableIntStateOf(AppDefaults.ADB_PORT) }
    var connectedDeviceLabel by rememberSaveable { mutableStateOf("未连接") }
    var sessionInfoWidth by rememberSaveable { mutableIntStateOf(0) }
    var sessionInfoHeight by rememberSaveable { mutableIntStateOf(0) }
    var sessionInfoDeviceName by rememberSaveable { mutableStateOf("") }
    var sessionInfoCodec by rememberSaveable { mutableStateOf("") }
    var sessionInfoControlEnabled by rememberSaveable { mutableStateOf(false) }
    var sessionInfo by remember {
        mutableStateOf<ScrcpySessionInfo?>(null)
    }
    LaunchedEffect(
        sessionInfoWidth,
        sessionInfoHeight,
        sessionInfoDeviceName,
        sessionInfoCodec,
        sessionInfoControlEnabled
    ) {
        sessionInfo = if (sessionInfoDeviceName.isNotBlank()) {
            ScrcpySessionInfo(
                width = sessionInfoWidth,
                height = sessionInfoHeight,
                deviceName = sessionInfoDeviceName,
                codec = sessionInfoCodec,
                controlEnabled = sessionInfoControlEnabled,
            )
        } else {
            null
        }
    }
    var previewControlsVisible by rememberSaveable { mutableStateOf(false) }
    var editingDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeDeviceActionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReorderSheet by rememberSaveable { mutableStateOf(false) }
    var adbConnecting by rememberSaveable { mutableStateOf(false) }

    var connectHost by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf(AppDefaults.ADB_PORT.toString()) }
    var quickConnectInput by rememberSaveable { mutableStateOf(initialSettings.quickConnectInput) }
    var audioForwardingSupported by rememberSaveable { mutableStateOf(true) }
    var cameraMirroringSupported by rememberSaveable { mutableStateOf(true) }

    var videoBitRateMbps by rememberSaveable { mutableFloatStateOf(initialSettings.videoBitRateMbps) }
    var videoBitRateInput by rememberSaveable { mutableStateOf(initialSettings.videoBitRateInput) }
    var audioBitRateKbps by rememberSaveable { mutableIntStateOf(initialSettings.audioBitRateKbps) }
    val currentTarget = if (currentTargetHost.isNotBlank()) ConnectionTarget(
        currentTargetHost,
        currentTargetPort
    ) else null

    val quickDevices =
        rememberSaveable(saver = DeviceShortcutStateListSaver) { mutableStateListOf() }
    val sessionReconnectBlacklistHosts = remember { mutableSetOf<String>() }

    LaunchedEffect(EventLogger.eventLog.size) {
        onCanClearLogsChange(EventLogger.hasLogs())
    }

    /**
     * Disconnect the current ADB connection and stop any running scrcpy session.
     *
     * Concurrency / thread boundary:
     * - Native calls that may block are executed on [adbWorkerDispatcher] using [withContext].
     * - This ensures UI coroutines are never blocked by synchronous native I/O.
     *
     * Side effects:
     * - Calls `nativeCore.scrcpyStop()` and `nativeCore.adbDisconnect()` (best-effort).
     * - Resets UI-visible connection state: `adbConnected`, `currentTargetHost/Port`,
     *   `sessionInfo`, device capability flags, `statusLine`, and `connectedDeviceLabel`.
     * - Updates the saved quick-device list via [upsertQuickDevice] when a target is provided.
     * - Logs an optional [logMessage] to the local event log.
     * - Shows an optional snackbar message asynchronously (launched on the composition scope)
     *   so callers don't get blocked by `snack.showSnackbar` (it is suspending).
     *
     * Usage notes:
     * - Prefer calling this from UI code wrapped by `runAdbConnect`/`runBusy` where appropriate
     *   so the UI busy/connect gates are respected.
     * - This function is idempotent from the UI state perspective: calling it when already
     *   disconnected will simply reset UI fields and not throw.
     */
    suspend fun disconnectAdbConnection(
        clearQuickOnlineForTarget: ConnectionTarget? = currentTarget,
        logMessage: String? = null,
        showSnackMessage: String? = null,
    ) {
        withContext(adbWorkerDispatcher) {
            // Also stops scrcpy.
            runCatching { scrcpy.stop() }
            runCatching { adbService.disconnect() }
        }
        adbConnected = false
        currentTargetHost = ""
        currentTargetPort = AppDefaults.ADB_PORT
        audioForwardingSupported = true
        cameraMirroringSupported = true
        sessionInfo = null
        statusLine = "未连接"
        connectedDeviceLabel = "未连接"
        clearQuickOnlineForTarget?.let { target ->
            if (target.host.isNotBlank()) {
                upsertQuickDevice(context, quickDevices, target.host, target.port, false)
            }
        }
        logMessage?.let { logEvent(it) }
        if (!showSnackMessage.isNullOrBlank()) {
            scope.launch {
                snack.showSnackbar(showSnackMessage)
            }
        }
    }

    suspend fun disconnectCurrentTargetBeforeConnecting(newHost: String, newPort: Int) {
        // Force old target cleanup before switching to another endpoint.
        val current = currentTarget
        if (!adbConnected || current == null) return
        if (current.host == newHost && current.port == newPort) return

        sessionReconnectBlacklistHosts += current.host
        disconnectAdbConnection(clearQuickOnlineForTarget = current)
    }

    var audio by scrcpyOptions.audio.asMutableState()
    var videoSource by scrcpyOptions.videoSource.asMutableState()

    fun applyConnectedDeviceCapabilities(sdkInt: Int, release: String) {
        val audioSupported = sdkInt !in 0..<30
        audioForwardingSupported = audioSupported
        if (!audioSupported && audio) {
            audio = false
            logEvent(
                "设备 Android ${release.ifBlank { "?" }} (SDK $sdkInt) 不支持音频转发，已自动关闭",
                Log.WARN
            )
        }
        val cameraSupported = sdkInt !in 0..<31
        cameraMirroringSupported = cameraSupported
        if (!cameraSupported && videoSource == "camera") {
            videoSource = "display"
            logEvent(
                "设备 Android ${release.ifBlank { "?" }} (SDK $sdkInt) 不支持 camera mirroring，已切换为 display",
                Log.WARN
            )
        }
    }

    /**
     * Attempt to connect to an adb endpoint within a short timeout.
     *
     * Execution:
     * - Runs `nativeCore.adbConnect(host, port)` on [adbWorkerDispatcher] and wraps it with
     *   [withTimeout] to avoid hanging forever. Returns true on success, false / throws on failure
     *   depending on the underlying native behavior.
     *
     * Why this exists:
     * - Some adb endpoints can take long to accept TCP connects; the UI should not wait
     *   indefinitely. Use a small, caller-chosen timeout to keep UX snappy.
     */
    suspend fun connectWithTimeout(host: String, port: Int) {
        return withContext(adbWorkerDispatcher) {
            withTimeout(ADB_CONNECT_TIMEOUT_MS) {
                adbService.connect(host, port)
            }
        }
    }

    /**
     * Validate that the current ADB connection is still alive.
     *
     * Behavior:
     * - Runs on [adbWorkerDispatcher] with a short timeout.
     * - First checks `nativeCore.adbIsConnected()` to avoid unnecessary shell calls.
     * - Executes a lightweight `adb shell` command (`echo -n 1`) to verify the remote side is
     *   responsive. Returns true only when both checks succeed.
     *
     * Notes for reliability:
     * - Some devices may accept TCP connections but have a hung adb-server process; the shell
     *   echo check helps detect that state.
     */
    suspend fun keepAliveCheck(host: String, port: Int): Boolean {
        return withContext(adbWorkerDispatcher) {
            withTimeout(ADB_KEEPALIVE_TIMEOUT_MS) {
                val connected = adbService.isConnected()
                if (!connected) {
                    return@withTimeout false
                }
                runCatching {
                    adbService.shell("echo -n 1")
                    true
                }.getOrElse { false }
            }
        }
    }

    /**
     * Quickly test TCP reachability to an endpoint.
     *
     * - Uses a plain Socket connect on [Dispatchers.IO] with a very short timeout.
     * - This is useful before attempting an adb connect to avoid long native timeouts.
     * - Returns true when TCP handshake succeeds within [ADB_TCP_PROBE_TIMEOUT_MS].
     */
    suspend fun probeTcpReachable(host: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), ADB_TCP_PROBE_TIMEOUT_MS)
                    true
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Execute a suspend [block] while toggling the `busy` UI gate.
     *
     * - Intended for non-adb user actions (UI-level operations) that should disable
     *   certain controls while active (e.g. scrcpy start/stop, pairing, listing).
     * - Errors are logged and surfaced via a snackbar where appropriate. The snackbar
     *   is launched asynchronously so the outer coroutine can continue to unwind.
     * - Ensures `busy` is reset in `finally` so the UI recovers even on exceptions.
     */
    fun runBusy(label: String, onFinished: (() -> Unit)? = null, block: suspend () -> Unit) {
        // For non-adb actions (start/stop/pair/list refresh...).
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                logEvent("$label 超时", Log.WARN)
            } catch (e: IllegalArgumentException) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                logEvent("$label 参数错误: $detail", Log.WARN, e)
                scope.launch {
                    snack.showSnackbar("$label 参数错误: $detail")
                }
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                logEvent("$label 失败: $detail", Log.ERROR, e)
            } finally {
                busy = false
                onFinished?.invoke()
            }
        }
    }

    /**
     * Execute a manual ADB-related suspend [block] while toggling `adbConnecting`.
     *
     * Purpose:
     * - Called from explicit user actions (pressing "connect" / "disconnect").
     * - Keeps the UI responsive by marking only user-initiated connect operations as in-progress.
     *
     * Concurrency notes:
     * - Background auto-reconnect attempts deliberately DO NOT set `adbConnecting` so that
     *   UI controls remain actionable while background retries occur.
     * - Errors and timeouts are logged and surfaced similarly to `runBusy`.
     */
    fun runAdbConnect(label: String, onFinished: (() -> Unit)? = null, block: suspend () -> Unit) {
        // For manual adb operations from user actions.
        if (adbConnecting) return
        scope.launch {
            adbConnecting = true
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                logEvent("$label 超时", Log.WARN)
            } catch (e: IllegalArgumentException) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                logEvent("$label 参数错误: $detail", Log.WARN, e)
                scope.launch {
                    snack.showSnackbar("$label 参数错误: $detail")
                }
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                logEvent("$label 失败: $detail", Log.ERROR, e)
            } finally {
                adbConnecting = false
                onFinished?.invoke()
            }
        }
    }

    suspend fun runAutoAdbConnect(host: String, port: Int) {
        runCatching {
            connectWithTimeout(host, port)
        }.getOrElse { error ->
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            logEvent("自动重连失败: $host:$port ($detail)", Log.WARN)
        }
    }

    var videoEncoder by scrcpyOptions.videoEncoder.asMutableState()
    var audioEncoder by scrcpyOptions.audioEncoder.asMutableState()

    suspend fun refreshEncoderLists() {
        if (!adbConnected) return
        runCatching {
            scrcpy.listOptions(list = ListOptions.ENCODERS)
        }.onSuccess { result ->
            val lists = result as Scrcpy.ListResult.Encoders
            onVideoEncoderOptionsChange(lists.videoEncoders)
            onAudioEncoderOptionsChange(lists.audioEncoders)
            onVideoEncoderTypeMapChange(lists.videoEncoderTypes)
            onAudioEncoderTypeMapChange(lists.audioEncoderTypes)
            if (videoEncoder.isNotBlank() && videoEncoder !in videoEncoderOptions) {
                videoEncoder = ""
            }
            if (audioEncoder.isNotBlank() && audioEncoder !in audioEncoderOptions) {
                audioEncoder = ""
            }
            EventLogger.logEvent("编码器列表已刷新: video=${lists.videoEncoders.size} audio=${lists.audioEncoders.size}")
            if (lists.videoEncoders.isEmpty() && lists.audioEncoders.isEmpty()) {
                EventLogger.logEvent(
                    "提示: 编码器为空，请检查 server 路径/版本与设备系统日志",
                    Log.WARN
                )
                val preview = lists.rawOutput.lineSequence().take(20).joinToString(" | ")
                if (preview.isNotBlank()) {
                    EventLogger.logEvent("编码器原始输出: $preview", Log.DEBUG)
                }
            }
        }.onFailure { e ->
            onVideoEncoderOptionsChange(emptyList())
            onAudioEncoderOptionsChange(emptyList())
            onVideoEncoderTypeMapChange(emptyMap())
            onAudioEncoderTypeMapChange(emptyMap())
            EventLogger.logEvent(
                "读取编码器列表失败: ${e.message ?: e.javaClass.simpleName}",
                Log.ERROR,
                e
            )
        }
    }

    var cameraSize by scrcpyOptions.cameraSize.asMutableState()

    suspend fun refreshCameraSizeLists() {
        if (!adbConnected) return
        runCatching {
            scrcpy.listOptions(ListOptions.CAMERA_SIZES)
        }.onSuccess { result ->
            val lists = result as Scrcpy.ListResult.CameraSizes
            onCameraSizeOptionsChange(lists.sizes)
            if (cameraSize.isNotBlank() && cameraSize != "custom" && cameraSize !in lists.sizes) {
                cameraSize = ""
            }
            EventLogger.logEvent("camera sizes 已刷新: count=${lists.sizes.size}")
            if (lists.sizes.isEmpty()) {
                val preview = lists.rawOutput.lineSequence().take(20).joinToString(" | ")
                if (preview.isNotBlank()) {
                    EventLogger.logEvent("camera sizes 原始输出: $preview", Log.DEBUG)
                }
            }
        }.onFailure { e ->
            onCameraSizeOptionsChange(emptyList())
            EventLogger.logEvent(
                "读取 camera sizes 失败: ${e.message ?: e.javaClass.simpleName}",
                Log.ERROR,
                e
            )
        }
    }

    suspend fun handleAdbConnected(host: String, port: Int) {
        currentTargetHost = host
        currentTargetPort = port

        val info = fetchConnectedDeviceInfo(adbService, host, port)
        val fullLabel = if (info.serial.isNotBlank()) {
            "${info.model} (${info.serial})"
        } else {
            info.model
        }

        connectedDeviceLabel = info.model
        applyConnectedDeviceCapabilities(info.sdkInt, info.androidRelease)
        updateQuickDeviceNameIfEmpty(context, quickDevices, host, port, fullLabel)
        connectHost = host
        connectPort = port.toString()
        statusLine = "$host:$port"

        logEvent("ADB 已连接: model=${info.model}, serial=${info.serial.ifBlank { "unknown" }}, manufacturer=${info.manufacturer.ifBlank { "unknown" }}, brand=${info.brand.ifBlank { "unknown" }}, device=${info.device.ifBlank { "unknown" }}, android=${info.androidRelease.ifBlank { "unknown" }}, sdk=${info.sdkInt}")
        scope.launch {
            snack.showSnackbar("ADB 已连接")
        }
        refreshEncoderLists()
        refreshCameraSizeLists()
    }

    LaunchedEffect(videoBitRateInput) {
        val parsed = videoBitRateInput.toFloatOrNull() ?: return@LaunchedEffect
        videoBitRateMbps = parsed.coerceAtLeast(0.1f)
    }


    LaunchedEffect(Unit) {
        if (quickDevices.isEmpty()) {
            quickDevices.clear()
            quickDevices.addAll(loadQuickDevices(context))
        }
    }

    LaunchedEffect(adbConnected, currentTargetHost, currentTargetPort, quickDevices.size) {
        val activeId = if (adbConnected && currentTargetHost.isNotBlank()) {
            "$currentTargetHost:$currentTargetPort"
        } else {
            null
        }
        for (index in quickDevices.indices) {
            val item = quickDevices[index]
            val shouldOnline = activeId != null && item.id == activeId
            if (item.online != shouldOnline) {
                quickDevices[index] = item.copy(online = shouldOnline)
            }
        }
    }

    LaunchedEffect(adbConnected, currentTargetHost, currentTargetPort) {
        if (!adbConnected || currentTargetHost.isBlank()) return@LaunchedEffect

        // Keep-alive loop for current target.
        // On failure: try to reconnect once; if failed, fully disconnect and reset UI state.
        val host = currentTargetHost
        val port = currentTargetPort
        while (adbConnected && currentTargetHost == host && currentTargetPort == port) {
            delay(ADB_KEEPALIVE_INTERVAL_MS)
            val alive = runCatching { keepAliveCheck(host, port) }.getOrElse { false }
            if (alive) continue

            logEvent("ADB 长连接中断，尝试自动重连: $host:$port", Log.WARN)
            try {
                connectWithTimeout(host, port)
                adbConnected = true
                statusLine = "$host:$port"
                logEvent("ADB 自动重连成功: $host:$port")
                scope.launch {
                    snack.showSnackbar("ADB 自动重连成功")
                }
            } catch (e: Exception) {
                disconnectAdbConnection()
                statusLine = "ADB 连接断开"
                logEvent("ADB 自动重连失败: $e", Log.ERROR)
                scope.launch {
                    snack.showSnackbar("ADB 自动重连失败")
                }
                break
            }
        }
    }

    val adbPairingAutoDiscoverOnDialogOpen by appSettings.adbPairingAutoDiscoverOnDialogOpen.asState()
    val adbAutoReconnectPairedDevice by appSettings.adbAutoReconnectPairedDevice.asState()
    val adbMdnsLanDiscovery by appSettings.adbMdnsLanDiscovery.asState()
    LaunchedEffect(adbConnected, adbAutoReconnectPairedDevice, adbMdnsLanDiscovery) {
        if (adbConnected || !adbAutoReconnectPairedDevice) return@LaunchedEffect

        // Background auto reconnect pipeline:
        // 1) try quick list targets with reachable TCP ports
        // 2) fallback to mDNS discovery
        val quickConnectTriedOnce = mutableSetOf<String>()
        while (!adbConnected && adbAutoReconnectPairedDevice) {
            if (busy || adbConnecting || sessionInfo != null) {
                delay(ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS)
                continue
            }

            val quickCandidates = quickDevices.toList()
            if (quickCandidates.isNotEmpty()) {
                for (target in quickCandidates) {
                    if (adbConnected || adbConnecting) break
                    if (sessionReconnectBlacklistHosts.contains(target.host)) continue
                    val targetKey = "${target.host}:${target.port}"
                    if (quickConnectTriedOnce.contains(targetKey)) continue

                    val portReachable = probeTcpReachable(target.host, target.port)
                    if (!portReachable) continue

                    quickConnectTriedOnce += targetKey
                    try {
                        runAutoAdbConnect(target.host, target.port)
                        adbConnected = true
                        upsertQuickDevice(
                            context,
                            quickDevices,
                            target.host,
                            target.port,
                            true,
                        )
                        handleAdbConnected(target.host, target.port)
                        logEvent("ADB 快速探测连接成功: ${target.host}:${target.port}")
                    } catch (_: Exception) {
                    }
                    break
                }
                if (adbConnected) break
            }

            val discovered = withContext(Dispatchers.IO) {
                adbService.discoverConnectService(
                    timeoutMs = ADB_AUTO_RECONNECT_DISCOVER_TIMEOUT_MS,
                    includeLanDevices = adbMdnsLanDiscovery,
                )
            }

            if (discovered == null) {
                delay(ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS)
                continue
            }

            val (discoveredHost, discoveredPort) = discovered
            if (sessionReconnectBlacklistHosts.contains(discoveredHost)) {
                delay(ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS)
                continue
            }
            val knownDevice = quickDevices.firstOrNull { it.host == discoveredHost }
            if (knownDevice == null) {
                delay(ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS)
                continue
            }
            val portToReplace = quickDevices.firstOrNull {
                it.host == discoveredHost &&
                        it.port != AppDefaults.ADB_PORT &&
                        it.port != discoveredPort
            }?.port
            if (portToReplace != null) {
                replaceQuickDevicePort(
                    context = context,
                    quickDevices = quickDevices,
                    host = discoveredHost,
                    oldPort = portToReplace,
                    newPort = discoveredPort,
                    online = false,
                )
                logEvent(
                    "mDNS 发现新端口，已更新快速设备: $discoveredHost:$portToReplace -> $discoveredHost:$discoveredPort"
                )
            }

            if (adbConnected || adbConnecting) {
                delay(ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS)
                continue
            }

            try {
                runAutoAdbConnect(discoveredHost, discoveredPort)
                adbConnected = true
                upsertQuickDevice(
                    context,
                    quickDevices,
                    discoveredHost,
                    discoveredPort,
                    true
                )
                handleAdbConnected(discoveredHost, discoveredPort)
                logEvent("ADB 自动重连成功: $discoveredHost:$discoveredPort")
            } catch (_: Exception) {
            }

            delay(ADB_AUTO_RECONNECT_RETRY_INTERVAL_MS)
        }
    }

    DisposableEffect(nativeCore) {
        val listener: (Int, Int) -> Unit = { width, height ->
            sessionInfo = sessionInfo?.copy(width = width, height = height)
        }
        nativeCore.addVideoSizeListener(listener)
        onDispose {
            nativeCore.removeVideoSizeListener(listener)
        }
    }

    LaunchedEffect(sessionInfo) {
        if (sessionInfo != null) {
            sessionInfoWidth = sessionInfo?.width ?: 0
            sessionInfoHeight = sessionInfo?.height ?: 0
            sessionInfoDeviceName = sessionInfo?.deviceName.orEmpty()
            sessionInfoCodec = sessionInfo?.codec.orEmpty()
            sessionInfoControlEnabled = sessionInfo?.controlEnabled == true
        } else {
            sessionInfoWidth = 0
            sessionInfoHeight = 0
            sessionInfoDeviceName = ""
            sessionInfoCodec = ""
            sessionInfoControlEnabled = false
        }
        onSessionStartedChange(sessionInfo != null)
    }

    DisposableEffect(Unit) {
        onRefreshEncodersActionChange {
            runBusy("刷新编码器") { refreshEncoderLists() }
        }
        onRefreshCameraSizesActionChange {
            runBusy("刷新 Camera Sizes") { refreshCameraSizeLists() }
        }
        onClearLogsActionChange {
            EventLogger.clearLogs()
        }
        onOpenReorderDevicesActionChange {
            showReorderSheet = true
        }
        onDispose {
            onRefreshEncodersActionChange(null)
            onRefreshCameraSizesActionChange(null)
            onClearLogsActionChange(null)
            onCanClearLogsChange(false)
            onOpenReorderDevicesActionChange(null)
        }
    }

    SuperBottomSheet(
        show = showReorderSheet,
        title = "快速设备排序",
        onDismissRequest = { showReorderSheet = false },
    ) {
        val list = remember {
            ReorderableList(
                {
                    quickDevices.map { device ->
                        ReorderableList.Item(
                            id = device.id,
                            title = device.name.ifBlank { device.host },
                            subtitle = "${device.host}:${device.port}",
                        )
                    }
                },
                onSettle = { fromIndex, toIndex ->
                    if (fromIndex < 0) return@ReorderableList
                    val to = toIndex.coerceIn(0, quickDevices.size)
                    if (fromIndex == to) return@ReorderableList

                    val moved = quickDevices.removeAt(fromIndex)
                    quickDevices.add(to.coerceIn(0, quickDevices.size), moved)
                    saveQuickDevices(context, quickDevices)
                },
            )
        }
        list()
        Spacer(Modifier.height(UiSpacing.BottomSheetBottom))
    }

    fun sendVirtualButtonAction(action: VirtualButtonAction) {
        val keycode = action.keycode ?: return
        runBusy("发送 ${action.title}") {
            nativeCore.sessionManager.injectKeycode(0, keycode)
            nativeCore.sessionManager.injectKeycode(1, keycode)
        }
    }

    if (editingDeviceId != null) {
        val device = quickDevices.firstOrNull { it.id == editingDeviceId }
        if (device != null) {
            DeviceEditorScreen(
                contentPadding = contentPadding,
                device = device,
                onSave = { updated ->
                    val idx = quickDevices.indexOfFirst { it.id == device.id }
                    if (idx >= 0) {
                        quickDevices[idx] = updated.copy(online = quickDevices[idx].online)
                        saveQuickDevices(context, quickDevices)
                    }
                    editingDeviceId = null
                },
                onDelete = {
                    quickDevices.removeAll { it.id == device.id }
                    saveQuickDevices(context, quickDevices)
                    editingDeviceId = null
                },
                onBack = { editingDeviceId = null },
            )
            return
        }
        editingDeviceId = null
    }

    val devicePreviewCardHeightDp by appSettings.devicePreviewCardHeightDp.asState()
    val previewVirtualButtonShowText by appSettings.previewVirtualButtonShowText.asState()
    // 设备
    AppPageLazyColumn(
        contentPadding = contentPadding,
        scrollBehavior = scrollBehavior,
    ) {
        item {
            StatusCard(
                statusLine = statusLine,
                adbConnected = adbConnected,
                streaming = sessionInfo != null,
                sessionInfo = sessionInfo,
                busyLabel = null,
                connectedDeviceLabel = connectedDeviceLabel,
            )
        }

        itemsIndexed(quickDevices, key = { _, device -> device.id }) { _, device ->
            val host = device.host
            val port = device.port
            val isConnectedTarget = adbConnected
                    && currentTarget?.host == host
                    && currentTarget.port == port

            DeviceTile(
                device = device,
                actionText = if (!isConnectedTarget) "连接" else "断开",
                actionEnabled = !busy && !adbConnecting,
                actionInProgress = adbConnecting && activeDeviceActionId == device.id,
                onLongPress = { editingDeviceId = device.id },
                onContentClick = {
                    scope.launch {
                        snack.showSnackbar("长按可编辑设备")
                    }
                },
                onAction = {
                    haptics.contextClick()
                    if (!isConnectedTarget) {
                        activeDeviceActionId = device.id
                        runAdbConnect("连接 ADB", onFinished = { activeDeviceActionId = null }) {
                            disconnectCurrentTargetBeforeConnecting(host, port)
                            try {
                                connectWithTimeout(host, port)
                                adbConnected = true
                                upsertQuickDevice(context, quickDevices, host, port, true)
                                handleAdbConnected(host, port)
                            } catch (e: Exception) {
                                statusLine = "ADB 连接失败"
                                logEvent("ADB 连接失败: $e", Log.ERROR)
                                scope.launch {
                                    snack.showSnackbar("ADB 连接失败")
                                }
                            }
                        }
                    } else {
                        activeDeviceActionId = device.id
                        runAdbConnect("断开 ADB", onFinished = { activeDeviceActionId = null }) {
                            sessionReconnectBlacklistHosts += host
                            disconnectAdbConnection(
                                clearQuickOnlineForTarget = ConnectionTarget(host, port),
                                logMessage = "ADB 已断开: ${device.name}",
                                showSnackMessage = "ADB 已断开",
                            )
                        }
                    }
                },
            )
        }

        if (!adbConnected) item {
            // "快速连接"
            QuickConnectCard(
                input = quickConnectInput,
                onInputChange = { quickConnectInput = it },
                enabled = !adbConnecting,
                onAddDevice = {
                    val target = parseQuickTarget(quickConnectInput) ?: return@QuickConnectCard
                    upsertQuickDevice(
                        context,
                        quickDevices,
                        target.host,
                        target.port,
                        online = false
                    )
                    scope.launch {
                        snack.showSnackbar("已添加设备: ${target.host}:${target.port}")
                    }
                },
                onConnect = {
                    val target = parseQuickTarget(quickConnectInput) ?: return@QuickConnectCard
                    runAdbConnect("连接 ADB", onFinished = { activeDeviceActionId = null }) {
                        disconnectCurrentTargetBeforeConnecting(target.host, target.port)
                        try {
                            connectWithTimeout(target.host, target.port)
                            adbConnected = true
                            upsertQuickDevice(context, quickDevices, target.host, target.port, true)
                            handleAdbConnected(target.host, target.port)
                        } catch (e: Exception) {
                            statusLine = "ADB 连接失败"
                            logEvent("ADB 连接失败: $e", Log.ERROR)
                            scope.launch {
                                snack.showSnackbar("ADB 连接失败")
                            }
                        }
                    }
                },
            )
            SectionSmallTitle("无线配对")
            // "使用配对码配对设备"
            PairingCard(
                busy = busy,
                autoDiscoverOnDialogOpen = adbPairingAutoDiscoverOnDialogOpen,
                onDiscoverTarget = {
                    adbService.discoverPairingService(
                        includeLanDevices = adbMdnsLanDiscovery,
                    )
                },
                onPair = { host, port, code ->
                    runBusy("执行配对") {
                        val resolvedHost = host.trim()
                        val resolvedPort = port.toIntOrNull() ?: return@runBusy
                        val resolvedCode = code.trim()
                        val ok = adbService.pair(
                            resolvedHost,
                            resolvedPort,
                            resolvedCode,
                        )
                        logEvent(
                            if (ok) "配对成功" else "配对失败",
                            if (ok) Log.INFO else Log.ERROR
                        )
                        scope.launch {
                            snack.showSnackbar(if (ok) "配对成功" else "配对失败")
                        }
                    }
                },
            )
        }

        if (adbConnected) {
            item {
                ConfigPanel(
                    busy = busy,
                    audioForwardingSupported = audioForwardingSupported,
                    onOpenAdvanced = onOpenAdvancedPage,
                    onStartStopHaptic = { haptics.contextClick() },
                    onStart = {
                        runBusy("启动 scrcpy") {
                            val options = scrcpyOptions.toClientOptions()
                            val session = scrcpy.start(options)
                            sessionInfo = session
                            statusLine = "scrcpy 运行中"
                            @SuppressLint("DefaultLocale")
                            val videoDetail = if (!options.video) {
                                "off"
                            } else {
                                "${session.codec} ${session.width}x${session.height} " +
                                        "@${String.format("%.1f", videoBitRateMbps)}Mbps"
                            }
                            val audioDetail = if (!audio) {
                                "off"
                            } else {
                                val playback = if (!options.audioPlayback) "(no-playback)" else ""
                                "${options.audioCodec} ${audioBitRateKbps}kbps source=${options.audioSource}$playback"
                            }
                            logEvent(
                                "scrcpy 已启动: device=${session.deviceName}" +
                                        ", video=$videoDetail, audio=$audioDetail" +
                                        ", control=${options.control}, turnScreenOff=${options.turnScreenOff}" +
                                        ", maxSize=${options.maxSize}, maxFps=${options.maxFps}"
                            )
                            scope.launch {
                                snack.showSnackbar("scrcpy 已启动")
                            }
                            scrcpy.getLastServerCommand()?.let { command ->
                                logEvent("scrcpy-server args: $command")
                            }
                        }
                    },
                    onStop = {
                        runBusy("停止 scrcpy") {
                            scrcpy.stop()
                            sessionInfo = null
                            statusLine = "${currentTarget!!.host}:${currentTarget.port}"
                            logEvent("scrcpy 已停止")
                            scope.launch {
                                snack.showSnackbar("scrcpy 已停止")
                            }
                        }
                    },
                    sessionStarted = sessionInfo != null,
                )
            }

            if (
                sessionInfo != null &&
                sessionInfo!!.width > 0 &&
                sessionInfo!!.height > 0
            ) {
                item {
                    PreviewCard(
                        sessionInfo = sessionInfo,
                        nativeCore = nativeCore,
                        previewHeightDp = devicePreviewCardHeightDp.coerceAtLeast(120),
                        controlsVisible = previewControlsVisible,
                        onTapped = {
                            previewControlsVisible = !previewControlsVisible
                        },
                        onOpenFullscreen = {
                            val info = sessionInfo ?: return@PreviewCard
                            onOpenFullscreenPage(info)
                        },
                    )
                }

                item {
                    VirtualButtonCard(
                        busy = busy,
                        outsideActions = virtualButtonLayout.first,
                        moreActions = virtualButtonLayout.second,
                        showText = previewVirtualButtonShowText,
                        onAction = ::sendVirtualButtonAction,
                    )
                }
            }
        }

        if (EventLogger.hasLogs()) item {
            Spacer(Modifier.height(UiSpacing.PageItem))
            LogsPanel(lines = EventLogger.eventLog)
        }

        // TODO: 放进 [AppPageLazyColumn] 里
        item { Spacer(Modifier.height(UiSpacing.BottomContent)) }
    }
}

private fun buildNewDisplayArg(width: String, height: String, dpi: String): String {
    val w = width.toIntOrNull()?.takeIf { it > 0 }
    val h = height.toIntOrNull()?.takeIf { it > 0 }
    val d = dpi.toIntOrNull()?.takeIf { it > 0 }
    val sizePart = if (w != null && h != null) "${w}x${h}" else ""
    return when {
        sizePart.isNotEmpty() && d != null -> "$sizePart/$d"
        sizePart.isNotEmpty() -> sizePart
        d != null -> "/$d"
        else -> ""
    }
}

private fun buildCropArg(width: String, height: String, x: String, y: String): String {
    val w = width.toIntOrNull()?.takeIf { it > 0 } ?: return ""
    val h = height.toIntOrNull()?.takeIf { it > 0 } ?: return ""
    val ox = x.toIntOrNull()?.takeIf { it >= 0 } ?: return ""
    val oy = y.toIntOrNull()?.takeIf { it >= 0 } ?: return ""
    return "$w:$h:$ox:$oy"
}
