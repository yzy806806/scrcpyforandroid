package io.github.miuzarte.scrcpyforandroid.nativecore

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings
import java.io.ByteArrayInputStream
import java.net.ServerSocket

/**
 * Establishes an SSH tunnel to a remote host and exposes a local TCP port that forwards
 * to the remote adbd (default 127.0.0.1:5555).
 *
 * Rationale: OnePlus adbd skips RSA authentication entirely (auth_required is compiled
 * to false), so exposing port 5555 publicly — including over IPv6, which home routers
 * cannot filter — lets anyone control the device. Wrapping adb in an SSH tunnel moves
 * the authentication to SSH key auth and keeps 5555 reachable only from localhost.
 *
 * The local forward port is a free ephemeral port; the caller connects adb to
 * 127.0.0.1:<localPort>.
 */
object SSHTunnelManager {

    private const val TAG = "ScrcpySSH"

    @Volatile
    private var session: Session? = null

    @Volatile
    private var localPort = -1

    @Volatile
    private var tunnelHost: String? = null

    /** True when SSH tunnel config is present and enabled. */
    fun isConfigured(settings: AppSettings.Bundle): Boolean {
        return settings.sshTunnelEnabled && settings.sshHost.isNotBlank() && settings.sshPrivateKey.isNotBlank()
    }

    /**
     * Opens the SSH tunnel. Returns the local port adb should connect to, or throws
     * if the SSH connection / port forward fails.
     */
    @Synchronized
    fun open(settings: AppSettings.Bundle): Int {
        close() // drop any stale session first

        val host = settings.sshHost.trim()
        val port = settings.sshPort
        val user = settings.sshUser.trim().ifBlank { "root" }
        val privateKey = settings.sshPrivateKey.trim()
        val remotePort = settings.sshRemotePort

        require(host.isNotBlank()) { "SSH host is empty" }
        require(privateKey.isNotEmpty()) { "SSH private key is empty" }

        try {
            val jsch = JSch()
            // known-hosts check would fail on a fresh key; we authenticate the key itself
            jsch.setKnownHosts(ByteArrayInputStream(ByteArray(0)))
            jsch.addIdentity(
                "scrcpy-ssh-key",
                privateKey.toByteArray(Charsets.UTF_8),
                null,
                null,
            )

            val s = jsch.getSession(user, host, port)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("ServerAliveInterval", "30")
            s.setConfig("ServerAliveCountMax", "3")
            s.setTimeout(15_000)
            s.connect(15_000)

            // bind a free local port, forward to the remote adbd loopback
            val localSocket = ServerSocket(0)
            val freePort = localSocket.localPort
            localSocket.close()

            val assigned = s.setPortForwardingL(freePort, "127.0.0.1", remotePort)
            session = s
            localPort = assigned
            tunnelHost = host
            Log.i(TAG, "SSH tunnel up: 127.0.0.1:$assigned -> $host:$remotePort")
            return assigned
        } catch (e: Exception) {
            Log.e(TAG, "SSH tunnel open failed: ${e.message}")
            close()
            throw IllegalStateException("SSH tunnel failed: ${e.message}", e)
        }
    }

    /** Returns the current local port, or -1 if no tunnel is open. */
    fun currentLocalPort(): Int = localPort

    fun isOpen(): Boolean = session?.isConnected == true && localPort > 0

    @Synchronized
    fun close() {
        val s = session
        if (s != null) {
            try {
                if (s.isConnected) s.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "SSH disconnect: ${e.message}")
            }
        }
        session = null
        localPort = -1
        tunnelHost = null
    }
}
