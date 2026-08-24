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
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight UDP tunnel with pre-shared key authentication.
 *
 * Opens a local TCP listener for adb. When adb connects, the tunnel
 * establishes a UDP "virtual connection" to the remote server with
 * a simple seq/ACK reliability layer, avoiding TCP's congestion control
 * and Nagle delays for lower latency.
 *
 * Protocol:
 *   UDP packets: [seq:4bytes][flags:1byte][data:0-1400bytes]
 *   flags: 0=data, 1=auth, 2=auth_ok, 3=fin
 *   Auth: client sends [seq:0][flags:1][sha256(key)]
 *   Server replies [seq:0][flags:2]
 *   Data: [seq:N][flags:0][data], receiver ACKs [seq:N][flags:0][empty]
 *   Close: [seq:0xFFFFFFFF][flags:3]
 *
 * No encryption, no VpnService. Coexists with V2Ray.
 */
object UdpTunnelManager {

    private const val TAG = "ScrcpyTunnel"
    private const val AUTH_MAGIC = "SCRPY1"
    private const val AUTH_TIMEOUT_MS = 5000
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val UDP_PAYLOAD_MAX = 1400
    private const val ACK_TIMEOUT_MS = 50L
    private const val MAX_RETRIES = 20
    private const val FLAG_DATA: Byte = 0
    private const val FLAG_AUTH: Byte = 1
    private const val FLAG_AUTH_OK: Byte = 2
    private const val FLAG_FIN: Byte = 3
    private const val FIN_SEQ = 0xFFFFFFFFL

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
            val ss = ServerSocket(listenPort)
            localPort = ss.localPort
            serverSocket = ss
            running = true

            Log.i(TAG, "UDP tunnel listening on 127.0.0.1:$localPort -> $host:$port")

            acceptThread = Thread {
                while (running) {
                    try {
                        val clientConn = ss.accept()
                        Log.d(TAG, "adb connected, establishing UDP tunnel to $host:$port")
                        Thread { handleTunnel(clientConn, host, port, key) }.start()
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
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

    private fun handleTunnel(clientConn: Socket, host: String, port: Int, key: String) {
        try {
            val remoteAddr = InetAddress.getByName(host)

            // Create UDP socket for this tunnel
            val udpSock = DatagramSocket()
            udpSock.soTimeout = 2000
            val remotePort = port

            // 1. Authenticate via UDP
            val digest = MessageDigest.getInstance("SHA-256")
            val keyHash = digest.digest(key.toByteArray())
            val authPacket = buildPacket(0, FLAG_AUTH, keyHash)
            udpSock.send(DatagramPacket(authPacket, authPacket.size, remoteAddr, remotePort))

            // Wait for auth response
            val recvBuf = ByteArray(UDP_PAYLOAD_MAX + 5)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            var authOk = false
            var retryCount = 0
            while (!authOk && retryCount < MAX_RETRIES) {
                try {
                    udpSock.receive(recvPacket)
                    val (seq, flag, data) = parsePacket(recvPacket)
                    if (flag == FLAG_AUTH_OK) {
                        authOk = true
                        Log.i(TAG, "UDP auth OK")
                    }
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount % 5 == 0) {
                        udpSock.send(DatagramPacket(authPacket, authPacket.size, remoteAddr, remotePort))
                    }
                }
            }

            if (!authOk) {
                Log.e(TAG, "UDP auth failed after $retryCount retries")
                clientConn.close()
                udpSock.close()
                return
            }

            Log.i(TAG, "UDP tunnel established, forwarding data")

            // 2. Bidirectional forwarding: TCP <-> UDP with seq/ACK
            val sendSeq = AtomicInteger(1)
            val recvSeq = AtomicInteger(1)
            val done = AtomicBoolean(false)

            // TCP -> UDP (read from adb, send via UDP)
            Thread {
                try {
                    val tcpIn = clientConn.getInputStream()
                    val buf = ByteArray(UDP_PAYLOAD_MAX)
                    while (!done.get()) {
                        val read = tcpIn.read(buf)
                        if (read < 0) break
                        val data = buf.copyOfRange(0, read)
                        val seq = sendSeq.getAndIncrement()
                        sendReliable(udpSock, remoteAddr, remotePort, seq, FLAG_DATA, data, done)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TCP->UDP error: ${e.message}")
                } finally {
                    done.set(true)
                    // Send FIN
                    val finPkt = buildPacket(FIN_SEQ, FLAG_FIN, ByteArray(0))
                    try { udpSock.send(DatagramPacket(finPkt, finPkt.size, remoteAddr, remotePort)) } catch (_: Exception) {}
                }
            }.also { it.isDaemon = true; it.start() }

            // UDP -> TCP (receive from UDP, write to adb)
            Thread {
                try {
                    val tcpOut = clientConn.getOutputStream()
                    val rBuf = ByteArray(UDP_PAYLOAD_MAX + 5)
                    val rPkt = DatagramPacket(rBuf, rBuf.size)
                    udpSock.soTimeout = 500
                    while (!done.get()) {
                        try {
                            udpSock.receive(rPkt)
                            val (seq, flag, data) = parsePacket(rPkt)
                            if (flag == FLAG_FIN) { break }
                            if (flag == FLAG_DATA && data.isNotEmpty()) {
                                val expected = recvSeq.get()
                                if (seq == expected) {
                                    tcpOut.write(data)
                                    tcpOut.flush()
                                    recvSeq.incrementAndGet()
                                }
                                // ACK
                                val ackPkt = buildPacket(seq, FLAG_DATA, ByteArray(0))
                                udpSock.send(DatagramPacket(ackPkt, ackPkt.size, remoteAddr, remotePort))
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            // continue
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UDP->TCP error: ${e.message}")
                } finally {
                    done.set(true)
                }
            }.also { it.isDaemon = true; it.start() }

            // Wait for completion
            while (!done.get()) Thread.sleep(100)
            udpSock.close()
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error: ${e.message}")
        } finally {
            try { clientConn.close() } catch (_: Exception) {}
        }
    }

    private fun sendReliable(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        seq: Long,
        flag: Byte,
        data: ByteArray,
        done: AtomicBoolean,
    ) {
        val pkt = buildPacket(seq, flag, data)
        val dp = DatagramPacket(pkt, pkt.size, addr, port)
        val ackBuf = ByteArray(UDP_PAYLOAD_MAX + 5)
        val ackPkt = DatagramPacket(ackBuf, ackBuf.size)
        var retries = 0

        while (retries < MAX_RETRIES && !done.get()) {
            sock.send(dp)
            try {
                sock.receive(ackPkt)
                val (ackSeq, ackFlag, _) = parsePacket(ackPkt)
                if (ackSeq == seq && ackFlag == FLAG_DATA) return // ACKed
            } catch (e: java.net.SocketTimeoutException) {
                retries++
            }
        }
        if (retries >= MAX_RETRIES) {
            Log.w(TAG, "UDP send seq=$seq failed after $MAX_RETRIES retries")
        }
    }

    private fun buildPacket(seq: Long, flag: Byte, data: ByteArray): ByteArray {
        val pkt = ByteArray(5 + data.size)
        pkt[0] = ((seq shr 24) and 0xFF).toByte()
        pkt[1] = ((seq shr 16) and 0xFF).toByte()
        pkt[2] = ((seq shr 8) and 0xFF).toByte()
        pkt[3] = (seq and 0xFF).toByte()
        pkt[4] = flag
        System.arraycopy(data, 0, pkt, 5, data.size)
        return pkt
    }

    private fun parsePacket(p: DatagramPacket): Triple<Long, Byte, ByteArray> {
        val d = p.data
        val offset = p.offset
        val seq = ((d[offset].toLong() and 0xFF) shl 24) or
                  ((d[offset + 1].toLong() and 0xFF) shl 16) or
                  ((d[offset + 2].toLong() and 0xFF) shl 8) or
                  (d[offset + 3].toLong() and 0xFF)
        val flag = d[offset + 4]
        val data = if (p.length > 5) d.copyOfRange(offset + 5, offset + p.length) else ByteArray(0)
        return Triple(seq, flag, data)
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
