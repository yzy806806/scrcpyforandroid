package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.miuzarte.scrcpyforandroid.models.DeviceShortcut
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class QuickDevices(context: Context) : Settings(context, "QuickDevices") {
    companion object {
        private val QUICK_DEVICES = Pair(
            stringPreferencesKey("quick_devices"),
            "",
        )
        private val QUICK_CONNECT_INPUT = Pair(
            stringPreferencesKey("quick_connect_input"),
            "",
        )
    }

    override suspend fun toMap(): Map<String, Any> {
        return mapOf(
            QUICK_DEVICES.name to getQuickDevicesRaw(),
            QUICK_CONNECT_INPUT.name to getQuickConnectInput(),
        )
    }

    override fun validate(): Boolean = true

    private suspend fun getQuickDevicesRaw(): String = getValue(QUICK_DEVICES)
    private suspend fun setQuickDevicesRaw(value: String) = setValue(QUICK_DEVICES, value)
    private fun observeQuickDevicesRaw(): Flow<String> = observe(QUICK_DEVICES)

    suspend fun getQuickDevices(): List<DeviceShortcut> {
        val raw = getQuickDevicesRaw()
        if (raw.isBlank()) return emptyList()

        val result = mutableListOf<DeviceShortcut>()
        raw.lineSequence().forEach { line ->
            DeviceShortcut.unmarshalFrom(line)?.let { result.add(it) }
        }
        return result
    }

    suspend fun setQuickDevices(quickDevices: List<DeviceShortcut>) =
        setQuickDevicesRaw(quickDevices.joinToString("\n") { it.marshalToString() })

    fun observeQuickDevices(): Flow<List<DeviceShortcut>> = observeQuickDevicesRaw()
        .map { raw ->
            if (raw.isBlank()) return@map emptyList()

            val result = mutableListOf<DeviceShortcut>()
            raw.lineSequence().forEach { line ->
                DeviceShortcut.unmarshalFrom(line)?.let { result.add(it) }
            }
            result
        }

    /**
     * 插入或更新快速设备
     */
    suspend fun upsertQuickDevice(
        host: String,
        port: Int,
        online: Boolean,
        index: Int? = 0,
    ) {
        val quickDevices = getQuickDevices().toMutableList()
        val id = "$host:$port"
        val idx = quickDevices.indexOfFirst { it.id == id }
        val existingName = if (idx >= 0) quickDevices[idx].name else ""
        val item = DeviceShortcut(
            name = existingName,
            host = host,
            port = port,
            online = online,
        )
        if (idx >= 0) {
            quickDevices[idx] = item
        } else {
            if (index != null)
                quickDevices.add(index, item)
            else quickDevices.add(item)
        }
        setQuickDevices(quickDevices)
    }

    /**
     * 如果设备名称为空，则更新为备用名称
     */
    suspend fun updateQuickDeviceNameIfEmpty(
        host: String,
        port: Int,
        fallbackName: String,
    ) {
        val quickDevices = getQuickDevices().toMutableList()
        val idx = quickDevices.indexOfFirst { it.host == host && it.port == port }
        if (idx >= 0 && quickDevices[idx].name.isBlank()) {
            quickDevices[idx] = quickDevices[idx].copy(name = fallbackName)
            setQuickDevices(quickDevices)
        }
    }

    /**
     * 替换快速设备的端口
     */
    suspend fun replaceQuickDevicePort(
        host: String,
        oldPort: Int,
        newPort: Int,
        online: Boolean,
    ) {
        val quickDevices = getQuickDevices().toMutableList()
        val idx = quickDevices.indexOfFirst { it.host == host && it.port == oldPort }
        if (idx < 0) return

        val old = quickDevices[idx]
        val updated = old.copy(
            port = newPort,
            online = online,
        )

        quickDevices[idx] = updated
        val dedup = quickDevices.distinctBy { it.id }
        setQuickDevices(dedup)
    }

    /**
     * 删除快速设备
     */
    suspend fun removeQuickDevice(id: String) {
        val quickDevices = getQuickDevices().toMutableList()
        quickDevices.removeAll { it.id == id }
        setQuickDevices(quickDevices)
    }

    /**
     * 更新设备在线状态
     */
    suspend fun updateDeviceOnlineStatus(host: String, port: Int, online: Boolean) {
        val quickDevices = getQuickDevices().toMutableList()
        val idx = quickDevices.indexOfFirst { it.host == host && it.port == port }
        if (idx >= 0) {
            quickDevices[idx] = quickDevices[idx].copy(online = online)
            setQuickDevices(quickDevices)
        }
    }

    /**
     * 更新设备名称
     */
    suspend fun updateDeviceName(id: String, name: String) {
        val quickDevices = getQuickDevices().toMutableList()
        val idx = quickDevices.indexOfFirst { it.id == id }
        if (idx >= 0) {
            quickDevices[idx] = quickDevices[idx].copy(name = name)
            setQuickDevices(quickDevices)
        }
    }

    /**
     * 清空所有快速设备
     */
    suspend fun clearQuickDevices() {
        setQuickDevicesRaw("")
    }

    private suspend fun getQuickConnectInput(): String = getValue(QUICK_CONNECT_INPUT)
    private suspend fun setQuickConnectInput(value: String) = setValue(QUICK_CONNECT_INPUT, value)
    private fun observeQuickConnectInput(): Flow<String> = observe(QUICK_CONNECT_INPUT)
}
