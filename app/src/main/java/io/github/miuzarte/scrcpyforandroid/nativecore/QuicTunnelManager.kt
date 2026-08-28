package io.github.miuzarte.scrcpyforandroid.nativecore

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings
import java.net.InetAddress

/**
 * QUIC-based tunnel — no VpnService, coexists with V2Ray.
 *
 * Uses quic-go (via gomobile/JNI) for UDP transport with built-in
 * reliability, TLS 1.3, stream multiplexing, and congestion control.
 *
 * Opens a local TCP listener for adb. When adb connects, it opens a QUIC
 * stream to the remote server and bidirectionally copies data.
 */
object QuicTunnelManager {

    private const val TAG = "ScrcpyTunnel"

    @Volatile
    private var localPort: Int = -1

    @Volatile
    private var running: Boolean = false

    fun isConfigured(settings: AppSettings.Bundle): Boolean {
        return settings.tunnelEnabled
                && settings.tunnelHost.isNotBlank()
                && settings.tunnelKey.isNotBlank()
    }

    @Synchronized
    fun open(settings: AppSettings.Bundle): Pair<String, Int> {
        if (running && localPort > 0) {
            Log.i(TAG, "QUIC tunnel already running on port $localPort, reusing")
            return "127.0.0.1" to localPort
        }

        val host = settings.tunnelHost.trim()
        val port = settings.tunnelPort
        val key = settings.tunnelKey.trim()
        val listenPort = settings.tunnelLocalPort

        require(host.isNotBlank()) { "Tunnel host is empty" }
        require(key.isNotBlank()) { "Tunnel key is empty" }

        try {
            // Resolve DNS — prefer IPv6 (OnePlus only has AAAA record)
            val resolvedHost = try {
                // Force IPv6 resolution first
                val allAddrs = InetAddress.getAllByName(host)
                val ipv6 = allAddrs.firstOrNull { it is java.net.Inet6Address }
                (ipv6 ?: allAddrs.firstOrNull())?.hostAddress ?: host
            } catch (e: Exception) {
                host
            }
            val serverAddr = "$resolvedHost:$port"

            // Call Go via JNI to start QUIC client
            val result = quictunnel.Quictunnel.startClient(serverAddr, listenPort.toLong(), key)

            if (result.startsWith("ERROR:")) {
                throw IllegalStateException(result.removePrefix("ERROR:"))
            }

            val portNum = result.toIntOrNull()
            if (portNum == null || portNum <= 0) {
                throw IllegalStateException("unexpected response: $result")
            }

            localPort = portNum
            running = true

            Log.i(TAG, "QUIC tunnel up: 127.0.0.1:$localPort -> $serverAddr")
            AppRuntime.snackbar("Tunnel: QUIC up on port $localPort")
            return "127.0.0.1" to localPort
        } catch (e: Exception) {
            Log.e(TAG, "QUIC tunnel open failed: ${e.message}")
            AppRuntime.snackbar("Tunnel: failed - ${e.message}")
            close()
            throw IllegalStateException("QUIC tunnel failed: ${e.message}", e)
        }
    }

    fun currentLocalPort(): Int = localPort
    fun isOpen(): Boolean = running && localPort > 0

    @Synchronized
    fun close() {
        if (running) {
            try { quictunnel.Quictunnel.stopClient() } catch (e: Exception) {
                Log.w(TAG, "QUIC close: ${e.message}")
            }
        }
        running = false
        localPort = -1
    }
}
