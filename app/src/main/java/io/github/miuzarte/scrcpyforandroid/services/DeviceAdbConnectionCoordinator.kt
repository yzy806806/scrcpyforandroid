package io.github.miuzarte.scrcpyforandroid.services

import android.os.Parcelable
import android.util.Log
import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import io.github.miuzarte.scrcpyforandroid.nativecore.WGTunnelManager
import io.github.miuzarte.scrcpyforandroid.nativecore.VpnPermissionRequiredException
import io.github.miuzarte.scrcpyforandroid.storage.ScrcpyOptions
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.parcelize.Parcelize
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

@Parcelize
internal data class DeviceAdbSessionState(
    val isConnected: Boolean = false,
    val statusLine: String = "Disconnected",
    val currentTarget: ConnectionTarget? = null,
    val connectedDeviceLabel: String = "Disconnected",
    val isQuickConnected: Boolean = false,
    val connectedScrcpyProfileId: String = ScrcpyOptions.GLOBAL_PROFILE_ID,
    val audioForwardingSupported: Boolean = true,
    val cameraMirroringSupported: Boolean = true,
): Parcelable

internal class DeviceAdbConnectionCoordinator(
    private val adbService: NativeAdbService = NativeAdbService,
) {
    private companion object {
        const val TAG = "AdbCoordinator"
    }

    /**
     * If WireGuard tunnel mode is enabled, opens the WG tunnel and returns the
     * peer IP + remote port to connect adb to. Otherwise returns the raw target.
     * Returns Pair(connectHost, connectPort).
     */
    private suspend fun resolveConnectTarget(host: String, port: Int): Pair<String, Int> {
        val settings = Storage.appSettings.bundleState.value
        if (WGTunnelManager.isConfigured(settings)) {
            try {
                val peerIp = WGTunnelManager.open(settings)
                val remotePort = settings.wgRemotePort
                Log.i(TAG, "WG tunnel active, adb -> $peerIp:$remotePort (requested $host:$port)")
                return peerIp to remotePort
            } catch (e: VpnPermissionRequiredException) {
                // VPN permission not granted yet — launch the system consent dialog
                // The user needs to grant permission and retry the connection
                val intent = e.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                AppRuntime.context.startActivity(intent)
                throw IllegalStateException("WireGuard tunnel requires VPN permission. Please grant permission and retry.")
            }
        }
        return host to port
    }

    suspend fun connectWithTimeout(host: String, port: Int, timeoutMs: Long) {
        withContext(Dispatchers.IO) {
            val (connectHost, connectPort) = resolveConnectTarget(host, port)
            val resolved = resolveHost(connectHost)
            withTimeout(timeoutMs) {
                adbService.connect(resolved, connectPort)
            }
        }
    }

    suspend fun connectFirstReachable(
        addresses: List<String>,
        timeoutMs: Long,
        probeTimeoutMs: Int,
    ): ConnectionTarget {
        var lastError: Throwable? = null
        return withContext(Dispatchers.IO) {
            if (addresses.size == 1) {
                val target = ConnectionTarget.unmarshalFrom(addresses[0])
                    ?: throw IllegalStateException("Invalid address: ${addresses[0]}")
                val (connectHost, connectPort) = resolveConnectTarget(target.host, target.port)
                val resolved = resolveHost(connectHost)
                withTimeout(timeoutMs) {
                    adbService.connect(resolved, connectPort)
                }
                return@withContext target
            }

            val candidates = addresses.mapNotNull { addr ->
                val target = ConnectionTarget.unmarshalFrom(addr) ?: return@mapNotNull null
                val resolved = resolveHost(target.host)
                val latencyNs = runCatching {
                    val startNs = System.nanoTime()
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(resolved, target.port), probeTimeoutMs)
                    }
                    System.nanoTime() - startNs
                }.getOrElse { e ->
                    lastError = e
                    return@mapNotNull null
                }
                Triple(latencyNs, target, resolved)
            }.sortedBy { it.first }
            for ((_, target, resolved) in candidates) {
                try {
                    withTimeout(timeoutMs) {
                        adbService.connect(resolved, target.port)
                    }
                    return@withContext target
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw (lastError ?: IllegalStateException("All addresses unreachable: $addresses"))
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching { adbService.disconnect() }
            WGTunnelManager.close()
        }
    }

    private fun resolveHost(host: String): String {
        val bareHost = if (host.startsWith('[') && host.endsWith(']'))
            host.substring(1, host.length - 1)
        else
            host
        return runCatching { InetAddress.getByName(bareHost).hostAddress }
            .getOrDefault(host)
    }

    suspend fun isConnected(timeoutMs: Long): Boolean {
        return withContext(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                adbService.isConnected()
            }
        }
    }

    suspend fun probeTcpReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return withContext(Dispatchers.IO) {
            val resolved = resolveHost(host)
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(resolved, port), timeoutMs)
                    true
                }
            }.getOrDefault(false)
        }
    }

    suspend fun fetchConnectedDeviceInfo(host: String, port: Int): ConnectedDeviceInfo {
        return fetchConnectedDeviceInfo(adbService, host, port)
    }

    suspend fun discoverPairingService(
        timeoutMs: Long = 12_000,
        includeLanDevices: Boolean = true,
    ): Pair<String, Int>? {
        return withContext(Dispatchers.IO) {
            adbService.discoverPairingService(
                timeoutMs = timeoutMs,
                includeLanDevices = includeLanDevices,
            )
        }
    }

    suspend fun discoverConnectService(
        timeoutMs: Long = 12_000,
        includeLanDevices: Boolean = true,
    ): Pair<String, Int>? {
        return withContext(Dispatchers.IO) {
            adbService.discoverConnectService(
                timeoutMs = timeoutMs,
                includeLanDevices = includeLanDevices,
            )
        }
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): Boolean {
        return withContext(Dispatchers.IO) {
            val resolved = resolveHost(host)
            adbService.pair(resolved, port, pairingCode)
        }
    }

    suspend fun startApp(
        packageName: String,
        displayId: Int? = null,
        forceStop: Boolean = false,
    ): String {
        return withContext(Dispatchers.IO) {
            adbService.startApp(
                packageName = packageName,
                displayId = displayId,
                forceStop = forceStop,
            )
        }
    }
}
