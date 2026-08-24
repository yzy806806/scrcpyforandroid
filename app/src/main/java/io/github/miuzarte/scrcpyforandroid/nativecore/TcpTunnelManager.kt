package io.github.miuzarte.scrcpyforandroid.nativecore

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Lightweight TCP tunnel with pre-shared key authentication — no VpnService, no encryption.
 *
 * Opens a local TCP listener. When adb connects, the tunnel connects to the remote
 * device's tunnel server, authenticates with a SHA-256 pre-shared key, then does
 * bidirectional plain TCP forwarding.
 *
 * OnePlus side runs a matching server (Magisk module) that verifies the auth
 * and forwards to 127.0.0.1:5555 (adbd).
 *
 * Coexists with V2Ray and other VPN apps — no VpnService slot claimed.
 */
object TcpTunnelManager {

    private const val TAG = "ScrcpyTunnel"
    private const val AUTH_MAGIC = "SCRPY1"  // protocol magic + version
    private const val AUTH_TIMEOUT_MS = 5000
    private const val CONNECT_TIMEOUT_MS = 10000

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var localPort: Int = -1

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var acceptThread: Thread? = null

    fun isConfigured(settings: AppSettings.Bundle): Boolean {
        return settings.tunnelEnabled
                && settings.tunnelHost.isNotBlank()
                && settings.tunnelKey.isNotBlank()
    }

    /**
     * Opens the TCP tunnel. Returns a Pair of (connectHost, connectPort) that
     * adb should connect to (always 127.0.0.1:localPort).
     */
    @Synchronized
    fun open(settings: AppSettings.Bundle): Pair<String, Int> {
        if (running && localPort > 0) {
            Log.i(TAG, "Tunnel already running on port $localPort, reusing")
            return "127.0.0.1" to localPort
        }

        val host = settings.tunnelHost.trim()
        val port = settings.tunnelPort
        val key = settings.tunnelKey.trim()
        val listenPort = settings.tunnelLocalPort

        require(host.isNotBlank()) { "Tunnel host is empty" }
        require(key.isNotBlank()) { "Tunnel key is empty" }

        try {
            // Create local TCP listener
            val ss = ServerSocket(listenPort)
            ss.soTimeout = 0  // block forever
            localPort = ss.localPort
            serverSocket = ss
            running = true

            Log.i(TAG, "Tunnel listening on 127.0.0.1:$localPort -> $host:$port")

            // Start accept loop in background
            acceptThread = Thread {
                while (running) {
                    try {
                        val clientConn = ss.accept()
                        Log.d(TAG, "adb connected, establishing tunnel to $host:$port")
                        Thread { handleTunnel(clientConn, host, port, key) }.start()
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }

            AppRuntime.snackbar("Tunnel: listening on port $localPort")
            return "127.0.0.1" to localPort
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel open failed: ${e.message}")
            AppRuntime.snackbar("Tunnel: failed - ${e.message}")
            close()
            throw IllegalStateException("Tunnel failed: ${e.message}", e)
        }
    }

    /**
     * Handles a single adb connection: connect to remote, authenticate, then
     * bidirectional forward.
     */
    private fun handleTunnel(clientConn: Socket, host: String, port: Int, key: String) {
        var remoteConn: Socket? = null
        try {
            // Resolve host
            val resolvedAddr = InetAddress.getByName(host)

            // Connect to remote tunnel server
            remoteConn = Socket()
            remoteConn.connect(InetSocketAddress(resolvedAddr, port), CONNECT_TIMEOUT_MS)
            remoteConn.tcpNoDelay = true

            // Authenticate: send magic + SHA-256(key)
            val digest = MessageDigest.getInstance("SHA-256")
            val keyHash = digest.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
            val authMsg = "$AUTH_MAGIC:$keyHash\n"
            val out = remoteConn.getOutputStream()
            out.write(authMsg.toByteArray())
            out.flush()

            // Read auth response (1 byte: 'O' = OK, 'F' = fail)
            remoteConn.soTimeout = AUTH_TIMEOUT_MS
            val input = remoteConn.getInputStream()
            val response = input.read()
            remoteConn.soTimeout = 0

            if (response != 'O'.code) {
                Log.e(TAG, "Auth failed (response=$response)")
                clientConn.close()
                remoteConn.close()
                return
            }

            Log.i(TAG, "Auth OK, forwarding data")

            // Bidirectional plain TCP forwarding
            val done = java.util.concurrent.CountDownLatch(2)
            Thread {
                try { copyStream(clientConn.getInputStream(), remoteConn.getOutputStream()) } finally { done.countDown() }
            }.also { it.isDaemon = true; it.start() }
            Thread {
                try { copyStream(remoteConn.getInputStream(), clientConn.getOutputStream()) } finally { done.countDown() }
            }.also { it.isDaemon = true; it.start() }

            done.await()
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error: ${e.message}")
        } finally {
            try { clientConn.close() } catch (_: Exception) {}
            try { remoteConn?.close() } catch (_: Exception) {}
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    fun currentLocalPort(): Int = localPort

    fun isOpen(): Boolean = running && localPort > 0

    @Synchronized
    fun close() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        localPort = -1
        acceptThread = null
    }
}
