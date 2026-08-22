package io.github.miuzarte.scrcpyforandroid.nativecore

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.AppSettings

/**
 * Manages a WireGuard tunnel inside the app, replacing the old SSH tunnel (SSHTunnelManager).
 *
 * Uses VpnService backend (GoBackend) on non-rooted devices, or WgQuickBackend (kernel)
 * on rooted devices with kernel WireGuard support.
 *
 * The tunnel creates a point-to-point link to the remote device's WireGuard peer.
 * adb then connects directly to the peer's WG IP:5555, avoiding the TCP-in-TCP
 * overhead of SSH forwarding and the Java user-space crypto cost of JSch.
 */
object WGTunnelManager {

    private const val TAG = "ScrcpyWG"
    private const val TUNNEL_NAME = "scrcpy-wg0"

    @Volatile
    private var tunnel: WGTunnel? = null

    @Volatile
    private var backend: com.wireguard.android.backend.Backend? = null

    @Volatile
    private var currentPeerIp: String? = null

    /** True when WG tunnel config is present and enabled. */
    fun isConfigured(settings: AppSettings.Bundle): Boolean {
        return settings.wgTunnelEnabled
                && settings.wgEndpointHost.isNotBlank()
                && settings.wgPrivateKey.isNotBlank()
                && settings.wgPeerPublicKey.isNotBlank()
    }

    /**
     * Returns true if VpnService permission needs to be requested (first-time only).
     * Call this before open() to check if user consent is needed.
     */
    fun needsVpnPermission(): Boolean {
        return GoBackend.VpnService.prepare(AppRuntime.context) != null
    }

    /**
     * Opens the WireGuard tunnel. Returns the peer IP that adb should connect to,
     * or throws if the tunnel cannot be established.
     */
    @Synchronized
    fun open(settings: AppSettings.Bundle): String {
        close() // drop any stale tunnel first

        val endpointHost = settings.wgEndpointHost.trim()
        val endpointPort = settings.wgEndpointPort
        val privateKeyStr = settings.wgPrivateKey.trim()
        val peerPublicKeyStr = settings.wgPeerPublicKey.trim()
        val peerIp = settings.wgPeerIp.trim()
        val tunnelIp = settings.wgTunnelIp.trim()
        val remotePort = settings.wgRemotePort

        require(endpointHost.isNotBlank()) { "WG endpoint host is empty" }
        require(privateKeyStr.isNotBlank()) { "WG private key is empty" }
        require(peerPublicKeyStr.isNotBlank()) { "WG peer public key is empty" }

        try {
            val context = AppRuntime.context

            // Parse keys
            val keyPair = KeyPair(Key.fromBase64(privateKeyStr))
            val peerKey = Key.fromBase64(peerPublicKeyStr)

            // Build WG config
            val endpoint = InetEndpoint.parse("$endpointHost:$endpointPort")
            val peer = Peer.Builder()
                .setPublicKey(peerKey)
                .setEndpoint(endpoint)
                .setAllowedIps(InetNetwork.parse("$peerIp/32"))
                .setPersistentKeepalive(25) // keep NAT mapping alive
                .build()

            val iface = Interface.Builder()
                .addAddress(InetNetwork.parse("$tunnelIp/32"))
                .parsePrivateKey(privateKeyStr)
                .build()

            val config = Config.Builder()
                .setInterface(iface)
                .addPeer(peer)
                .build()

            // Choose backend: prefer kernel (WgQuickBackend) if root, else VpnService (GoBackend)
            val backend = getOrCreateBackend(context)

            val wgTunnel = WGTunnel(TUNNEL_NAME)
            backend.setState(wgTunnel, Tunnel.State.UP, config)

            tunnel = wgTunnel
            currentPeerIp = peerIp

            Log.i(TAG, "WG tunnel up: $tunnelIp -> $peerIp (endpoint $endpointHost:$endpointPort)")
            return peerIp
        } catch (e: Exception) {
            Log.e(TAG, "WG tunnel open failed: ${e.message}")
            close()
            throw IllegalStateException("WG tunnel failed: ${e.message}", e)
        }
    }

    /** Returns the current peer IP, or null if no tunnel is open. */
    fun currentPeerIp(): String? = currentPeerIp

    fun isOpen(): Boolean = tunnel?.state == Tunnel.State.UP

    @Synchronized
    fun close() {
        val t = tunnel
        val b = backend
        if (t != null && b != null) {
            try {
                b.setState(t, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "WG tunnel close: ${e.message}")
            }
        }
        tunnel = null
        currentPeerIp = null
    }

    private fun getOrCreateBackend(context: Context): com.wireguard.android.backend.Backend {
        backend?.let { return it }

        // Try WgQuickBackend first (kernel, requires root)
        // Fall back to GoBackend (VpnService userspace)
        val b = try {
            Log.i(TAG, "Trying WgQuickBackend (kernel)...")
            WgQuickBackend(context)
        } catch (e: Exception) {
            Log.i(TAG, "WgQuickBackend unavailable, using GoBackend (VpnService): ${e.message}")
            GoBackend(context)
        }
        backend = b
        return b
    }
}

/**
 * Minimal Tunnel implementation for the WireGuard backend.
 */
private class WGTunnel(
    private val name: String,
) : Tunnel {
    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN
        private set

    override fun getName() = name

    override fun onStateChange(newState: Tunnel.State) {
        state = newState
        Log.i("ScrcpyWG", "Tunnel state: $newState")
    }
}
