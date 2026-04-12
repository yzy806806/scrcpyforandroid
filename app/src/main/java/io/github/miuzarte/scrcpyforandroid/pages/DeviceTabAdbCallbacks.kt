package io.github.miuzarte.scrcpyforandroid.pages

import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import io.github.miuzarte.scrcpyforandroid.models.DeviceShortcut
import io.github.miuzarte.scrcpyforandroid.storage.ScrcpyOptions

internal class DeviceTabAdbCallbacks(
    private val runAdbConnect: (
        label: String,
        onStarted: (() -> Unit)?,
        onFinished: (() -> Unit)?,
        block: suspend () -> Unit,
    ) -> Unit,
    private val runBusy: (
        label: String,
        onFinished: (() -> Unit)?,
        block: suspend () -> Unit,
    ) -> Unit,
    private val disconnectCurrentTargetBeforeConnecting: suspend (host: String, port: Int) -> Unit,
    private val connectWithTimeout: suspend (host: String, port: Int) -> Unit,
    private val handleAdbConnected: suspend (
        host: String,
        port: Int,
        autoStartScrcpy: Boolean,
        autoEnterFullScreen: Boolean,
        scrcpyProfileId: String,
    ) -> Unit,
    private val disconnectAdbConnection: suspend (
        clearQuickOnlineForTarget: ConnectionTarget?,
        logMessage: String?,
        showSnackMessage: String?,
    ) -> Unit,
    private val discoverPairingTarget: suspend () -> Pair<String, Int>?,
    private val pairTarget: suspend (host: String, port: Int, code: String) -> Boolean,
    private val isConnectedToTarget: (host: String, port: Int) -> Boolean,
    private val onConnectionFailed: (Throwable) -> Unit,
    private val onQuickConnectedChanged: (Boolean) -> Unit,
    private val onBlackListHost: (String) -> Unit,
    private val setActiveDeviceActionId: (String?) -> Unit,
) {
    fun onDeviceAction(device: DeviceShortcut) {
        val host = device.host
        val port = device.port
        val connected = isConnectedToTarget(host, port)

        if (!connected) {
            runAdbConnect(
                "连接 ADB",
                { setActiveDeviceActionId(device.id) },
                { setActiveDeviceActionId(null) },
            ) {
                disconnectCurrentTargetBeforeConnecting(host, port)
                try {
                    connectWithTimeout(host, port)
                    handleAdbConnected(
                        host,
                        port,
                        device.startScrcpyOnConnect,
                        device.startScrcpyOnConnect && device.openFullscreenOnStart,
                        device.scrcpyProfileId,
                    )
                    onQuickConnectedChanged(false)
                } catch (error: Exception) {
                    onConnectionFailed(error)
                }
            }
            return
        }

        runAdbConnect(
            "断开 ADB",
            { setActiveDeviceActionId(device.id) },
            { setActiveDeviceActionId(null) },
        ) {
            onBlackListHost(host)
            disconnectAdbConnection(
                ConnectionTarget(host, port),
                "ADB 已断开: ${device.name}",
                "ADB 已断开",
            )
        }
    }

    fun onQuickConnect(target: ConnectionTarget) {
        runAdbConnect(
            "连接 ADB",
            { setActiveDeviceActionId(target.toString()) },
            { setActiveDeviceActionId(null) },
        ) {
            disconnectCurrentTargetBeforeConnecting(target.host, target.port)
            try {
                connectWithTimeout(target.host, target.port)
                handleAdbConnected(
                    target.host,
                    target.port,
                    false,
                    false,
                    ScrcpyOptions.GLOBAL_PROFILE_ID,
                )
                onQuickConnectedChanged(true)
            } catch (error: Exception) {
                onConnectionFailed(error)
            }
        }
    }

    fun onDisconnectCurrent(target: ConnectionTarget?) {
        runAdbConnect(
            "断开 ADB",
            null,
            null,
        ) {
            target?.let {
                onBlackListHost(it.host)
                disconnectAdbConnection(
                    it,
                    "ADB 已断开",
                    "ADB 已断开",
                )
            }
        }
    }

    fun onPair(host: String, port: String, code: String) {
        runBusy("执行配对", null) {
            val resolvedHost = host.trim()
            val resolvedPort = port.trim().toIntOrNull() ?: return@runBusy
            val resolvedCode = code.trim()
            pairTarget(resolvedHost, resolvedPort, resolvedCode)
        }
    }

    suspend fun onDiscoverPairingTarget(): Pair<String, Int>? {
        return discoverPairingTarget()
    }
}
