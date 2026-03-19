package io.github.miuzarte.scrcpyforandroid.nativecore

import android.content.Context
import android.util.Log
import java.nio.file.Path

class NativeAdbService(appContext: Context) {

    private val transport = DirectAdbTransport(appContext)
    @Volatile private var connection: DirectAdbConnection? = null
    @Volatile private var connectedHost: String? = null
    @Volatile private var connectedPort: Int? = null

    var keyName: String
        get() = transport.keyName
        set(value) { transport.keyName = value }

    @Synchronized
    fun pair(host: String, port: Int, pairingCode: String): Boolean {
        throw UnsupportedOperationException(
            "Wireless pairing is not yet implemented. Please enable TCP ADB via USB first.",
        )
    }

    @Synchronized
    fun connect(host: String, port: Int): Boolean {
        Log.i(TAG, "connect(): host=$host port=$port")
        val existing = connection
        if (existing != null && existing.isAlive() && connectedHost == host && connectedPort == port) {
            return true
        }
        disconnect()
        try {
            val conn = transport.connect(host, port)
            connection = conn
            connectedHost = host
            connectedPort = port
            return true
        } catch (e: Exception) {
            Log.e(TAG, "connect(): failed host=$host port=$port", e)
            val detail = e.message ?: "${e.javaClass.simpleName} (no message)"
            throw IllegalStateException("ADB connect failed to $host:$port -> $detail", e)
        }
    }

    @Synchronized
    fun disconnect() {
        runCatching { connection?.close() }
        connection = null
        connectedHost = null
        connectedPort = null
    }

    @Synchronized
    fun isConnected(): Boolean = connection?.isAlive() == true

    @Synchronized
    fun shell(command: String): String = requireConnection().shell(command)

    @Synchronized
    internal fun openShellStream(command: String): AdbSocketStream =
        requireConnection().openStream("shell:$command")

    @Synchronized
    fun push(localPath: Path, remotePath: String) {
        requireConnection().push(localPath.toFile().readBytes(), remotePath)
    }

    @Synchronized
    internal fun openAbstractSocket(name: String): AdbSocketStream =
        requireConnection().openStream("localabstract:$name")

    @Synchronized
    fun close() = disconnect()

    private fun requireConnection(): DirectAdbConnection {
        return connection?.takeIf { it.isAlive() }
            ?: throw IllegalStateException("ADB not connected")
    }

    companion object {
        private const val TAG = "NativeAdbService"
    }
}
