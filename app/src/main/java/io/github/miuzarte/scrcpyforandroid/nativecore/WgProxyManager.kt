package io.github.miuzarte.scrcpyforandroid.nativecore

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings

/**
 * Manages a WireGuard local TCP proxy — NO VpnService required.
 *
 * Uses wireguard-go's gvisor/netstack (userspace TCP/IP stack) to establish
 * a WireGuard tunnel and exposes a local TCP port. adb connects to this local
 * port, and traffic is forwarded through the WG tunnel to the remote device's
 * adbd. This coexists with other VPN apps (V2Ray, etc.) since it does NOT
 * claim the Android VpnService slot.
 *
 * The actual WireGuard + gvisor implementation is in libwgproxy.aar (compiled
 * from Go via gomobile). This Kotlin object manages its lifecycle.
 */
object WgProxyManager {

    private const val TAG = "ScrcpyWG"

    @Volatile
    private var localPort: Int = -1

    @Volatile
    private var running: Boolean = false

    /** True when WG proxy config is present and enabled. */
    fun isConfigured(settings: AppSettings.Bundle): Boolean {
        return settings.wgTunnelEnabled
                && settings.wgEndpointHost.isNotBlank()
                && settings.wgPrivateKey.isNotBlank()
                && settings.wgPeerPublicKey.isNotBlank()
    }

    /**
     * Opens the WG proxy. Returns a Pair of (connectHost, connectPort) that
     * adb should connect to (always 127.0.0.1:localPort).
     * Throws on failure.
     */
    @Synchronized
    fun open(settings: AppSettings.Bundle): Pair<String, Int> {
        // Reuse existing proxy if already running
        if (running && localPort > 0) {
            Log.i(TAG, "WG proxy already running on port $localPort, reusing")
            return "127.0.0.1" to localPort
        }

        val endpointHost = settings.wgEndpointHost.trim()
        val endpointPort = settings.wgEndpointPort
        val privateKeyStr = settings.wgPrivateKey.trim()
        val peerPublicKeyStr = settings.wgPeerPublicKey.trim()
        val peerIp = settings.wgPeerIp.trim()
        val tunnelIp = settings.wgTunnelIp.trim()
        val remotePort = settings.wgRemotePort
        val listenPort = settings.wgLocalPort

        require(endpointHost.isNotBlank()) { "WG endpoint host is empty" }
        require(privateKeyStr.isNotBlank()) { "WG private key is empty" }
        require(peerPublicKeyStr.isNotBlank()) { "WG peer public key is empty" }

        try {
            // Build the remote address that adb traffic will be forwarded to
            val remoteAddr = "$peerIp:$remotePort"

            // Resolve endpoint host to IP address (wireguard-go's bind may not resolve DNS)
            val resolvedEndpoint = try {
                java.net.InetAddress.getByName(endpointHost).hostAddress
            } catch (e: Exception) {
                endpointHost // fallback to raw hostname
            }

            // Call into Go via JNI to start the WG proxy
            val result = wgproxy.Wgproxy.startProxy(
                privateKeyStr,
                peerPublicKeyStr,
                "$resolvedEndpoint:$endpointPort",
                tunnelIp,
                peerIp,
                remoteAddr,
                listenPort.toLong(),
            )

            // result is either "12345" (port number) or "ERROR:something went wrong"
            if (result.startsWith("ERROR:")) {
                val errMsg = result.removePrefix("ERROR:")
                throw IllegalStateException("WG: $errMsg")
            }

            val port = result.toIntOrNull()
            if (port == null || port <= 0) {
                throw IllegalStateException("WG: unexpected response from Go: $result")
            }

            localPort = port
            running = true

            Log.i(TAG, "WG proxy up: 127.0.0.1:$localPort -> $remoteAddr via $endpointHost:$endpointPort")
            AppRuntime.snackbar("WG: proxy up on port $localPort")

            // Test TCP connectivity through the proxy
            try {
                val testConn = java.net.Socket()
                testConn.connect(java.net.InetSocketAddress("127.0.0.1", localPort), 5000)
                val reachable = testConn.isConnected
                testConn.close()
                Log.i(TAG, "WG: proxy TCP test: 127.0.0.1:$localPort reachable=$reachable")
                AppRuntime.snackbar("WG: proxy port reachable=$reachable")
            } catch (e: Exception) {
                Log.e(TAG, "WG: proxy TCP test failed: ${e.message}")
                AppRuntime.snackbar("WG: proxy port unreachable - ${e.message}")
            }

            return "127.0.0.1" to localPort
        } catch (e: Exception) {
            Log.e(TAG, "WG proxy open failed: ${e.message}")
            AppRuntime.snackbar("WG: proxy failed - ${e.message}")
            close()
            throw IllegalStateException("WG proxy failed: ${e.message}", e)
        }
    }

    /** Returns the current local port, or -1 if no proxy is running. */
    fun currentLocalPort(): Int = localPort

    fun isOpen(): Boolean = running && localPort > 0

    @Synchronized
    fun close() {
        if (running) {
            try {
                wgproxy.Wgproxy.stopProxy()
            } catch (e: Exception) {
                Log.w(TAG, "WG proxy close: ${e.message}")
            }
        }
        running = false
        localPort = -1
    }
}
