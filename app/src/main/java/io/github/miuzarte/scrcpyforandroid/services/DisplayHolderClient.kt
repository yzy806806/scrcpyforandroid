package io.github.miuzarte.scrcpyforandroid.services

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 被控端 display-holder 的控制通道。
 *
 * holder 是 Magisk 模块里的一个常驻 app_process 进程，独立于任何 scrcpy 客户端持有虚拟显示，
 * 因此主控端断开后被控端应用仍继续运行。这里只做两件事：
 *
 *  - 读 [DIR]/state.json   获知各 slot 的显示 id 与在跑的应用
 *  - 往 [DIR]/cmd 追加命令  （append 一行执行一条，简单可靠，不需要常驻连接）
 *
 * 状态目录由 holder 放开权限（0777/0666），所以 adb shell 的 shell 用户即可读写。
 */
object DisplayHolderClient {
    private const val TAG = "DisplayHolderClient"

    const val DIR = "/data/local/tmp/display-holder"
    private const val CMD_PATH = "$DIR/cmd"
    private const val STATE_PATH = "$DIR/state.json"

    data class Slot(
        val slot: Int,
        val displayId: Int,
        val packageName: String,
        val state: String,
    )

    data class State(
        val pid: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val slots: List<Slot>,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 读取 holder 状态；holder 未运行时返回 null。 */
    suspend fun readState(): State? = runCatching {
        val raw = NativeAdbService.shell("cat $STATE_PATH")
        val obj = json.parseToJsonElement(raw).jsonObject
        val slots = (obj["slots"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                Slot(
                    slot = o["slot"]!!.jsonPrimitive.int,
                    displayId = o["displayId"]!!.jsonPrimitive.int,
                    packageName = o["package"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    state = o["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.getOrNull()
        }
        State(
            pid = obj["pid"]?.jsonPrimitive?.int ?: 0,
            width = obj["width"]?.jsonPrimitive?.int ?: 0,
            height = obj["height"]?.jsonPrimitive?.int ?: 0,
            dpi = obj["dpi"]?.jsonPrimitive?.int ?: 0,
            slots = slots,
        )
    }.onFailure {
        Log.w(TAG, "readState() failed", it)
    }.getOrNull()

    /** holder 是否可用（进程在跑且状态文件可读）。 */
    suspend fun isAvailable(): Boolean = readState() != null

    private suspend fun send(command: String): Boolean = runCatching {
        NativeAdbService.shell("echo ${quote(command)} >> $CMD_PATH")
        true
    }.onFailure {
        Log.w(TAG, "send() failed: $command", it)
    }.getOrDefault(false)

    suspend fun create(slot: Int): Boolean = send("create $slot")

    suspend fun launch(slot: Int, packageName: String): Boolean = send("launch $slot $packageName")

    /** force-stop 应用并销毁该 slot 的虚拟显示（资源归还）。 */
    suspend fun kill(slot: Int): Boolean = send("kill $slot")

    suspend fun destroy(slot: Int): Boolean = send("destroy $slot")

    suspend fun status(): Boolean = send("status")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
