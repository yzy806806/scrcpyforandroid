package io.github.miuzarte.scrcpyforandroid.services

import android.util.Log
import android.view.Surface
import io.github.miuzarte.scrcpyforandroid.nativecore.PersistentVideoRenderer
import io.github.miuzarte.scrcpyforandroid.nativecore.VideoDecoderController
import io.github.miuzarte.scrcpyforandroid.scrcpy.ClientOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个挂机槽位的运行时状态。
 *
 * 被控端侧由常驻 holder 持有虚拟显示并运行应用；主控端这里只维护「看这一路」的
 * scrcpy 会话（scrcpy server 通过 `display_id` 附着到 holder 的显示上）。
 * 所以停掉/重启这一路**不会影响被控端应用的运行**。
 */
class SlotSession(val index: Int) {
    var displayId: Int = -1
    var packageName: String = ""
    var label: String = ""

    /** true = 全屏（高画质、可操作），false = 缩略图（1fps 低码率）。 */
    var full: Boolean = false

    var running: Boolean = false
    var error: String? = null

    internal var surface: Surface? = null
    internal var scrcpy: Scrcpy? = null
    internal var renderer: PersistentVideoRenderer? = null
    internal var controller: VideoDecoderController? = null
    internal var sizeWatchJob: Job? = null

    val occupied: Boolean get() = packageName.isNotEmpty()
}

/**
 * 多路挂机会话管理。
 *
 * 每路一个独立的 [Scrcpy] + [PersistentVideoRenderer] + [VideoDecoderController] 组合
 * （现有 [NativeCoreFacade] 是单例，无法同时解码多路，这里走并行的独立管线，不动原路径）。
 */
object SlotSessionManager {
    private const val TAG = "SlotSessionManager"

    const val MAX_SLOTS = 4

    // 缩略图 / 全屏参数（设置项，默认值见设计文档 §5.4）
    var thumbMaxSize: Int = 720
    var thumbFps: String = "1"
    var thumbBitRate: Int = 1_000_000
    var fullMaxSize: Int = 0
    var fullFps: String = ""
    var fullBitRate: Int = 8_000_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _slots = MutableStateFlow(List(MAX_SLOTS) { SlotSession(it) })
    val slots: StateFlow<List<SlotSession>> = _slots.asStateFlow()

    private val _holderAlive = MutableStateFlow(false)
    val holderAlive: StateFlow<Boolean> = _holderAlive.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun slot(index: Int): SlotSession = _slots.value[index.coerceIn(0, MAX_SLOTS - 1)]

    private fun updateSlot(index: Int, block: (SlotSession) -> Unit) {
        _slots.update { list ->
            list[index].also(block)
            list
        }
    }

    /** 读 holder 状态并同步到本地槽位（显示 id / 应用）。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val state = DisplayHolderClient.readState()
        _holderAlive.value = state != null
        if (state == null) {
            Log.w(TAG, "refresh(): holder 不可用")
            return@withContext
        }
        _slots.update { list ->
            list.forEach { slot ->
                val remote = state.slots.firstOrNull { it.slot == slot.index }
                val displayId = remote?.displayId ?: -1
                if (displayId != slot.displayId) {
                    Log.i(TAG, "slot${slot.index}: displayId $displayId (was ${slot.displayId})")
                    slot.displayId = displayId
                }
                val pkg = remote?.packageName.orEmpty()
                if (pkg != slot.packageName) {
                    slot.packageName = pkg
                    if (pkg.isEmpty()) {
                        slot.label = ""
                    }
                }
            }
            list
        }
    }

    /** 占用一个槽位：让 holder 建显示并拉起应用，然后（若已有画面容器）开始投屏。 */
    suspend fun startApp(index: Int, packageName: String, label: String) {
        _busy.value = true
        try {
            val slot = slot(index)
            if (!_holderAlive.value) refresh()
            if (!_holderAlive.value) {
                updateSlot(index) { it.error = "holder 未运行" }
                return
            }
            // holder 的 create / launch 都是幂等的
            DisplayHolderClient.create(index)
            delay(500)
            DisplayHolderClient.launch(index, packageName)
            updateSlot(index) {
                it.packageName = packageName
                it.label = label
                it.error = null
            }
            // 等显示就绪再取 id
            repeat(6) {
                delay(400)
                refresh()
                if (slot(index).displayId >= 0) return@repeat
            }
            if (slot(index).displayId >= 0 && slot(index).surface != null) {
                startSession(slot(index))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startApp(slot=$index, pkg=$packageName) failed", t)
            updateSlot(index) { it.error = t.message ?: "启动失败" }
        } finally {
            _busy.value = false
            refresh()
        }
    }

    /**
     * 绑定画面容器。
     *
     * Compose 里进入四宫格 / 全屏时调用。画质模式或 surface 变化时会重建这一路会话
     * （被控端应用不受影响）。
     */
    suspend fun attachSurface(index: Int, surface: Surface, full: Boolean) {
        val slot = slot(index)
        if (slot.running && slot.full == full && slot.surface === surface) return
        slot.surface = surface
        slot.full = full
        if (slot.displayId < 0) {
            refresh()
        }
        if (slot.displayId < 0) {
            Log.w(TAG, "attachSurface(slot=$index): 显示尚未就绪")
            return
        }
        startSession(slot)
    }

    suspend fun detachSurface(index: Int) {
        val slot = slot(index)
        slot.surface = null
        stopSession(slot)
    }

    /** 关掉一路：停投屏 + 让 holder force-stop 应用并销毁显示。 */
    suspend fun stopSlot(index: Int) = withContext(Dispatchers.IO) {
        val slot = slot(index)
        stopSession(slot)
        runCatching { DisplayHolderClient.kill(index) }
        updateSlot(index) {
            it.packageName = ""
            it.label = ""
            it.displayId = -1
            it.error = null
        }
        delay(400)
        refresh()
    }

    /** 停掉所有投屏（不影响被控端应用运行 —— 这正是 holder 架构的意义）。 */
    suspend fun stopAllSessions() {
        _slots.value.forEach { stopSession(it) }
    }

    /** 停掉投屏并杀光所有挂机应用。 */
    suspend fun stopAllAndKill() = withContext(Dispatchers.IO) {
        _slots.value.forEach { slot ->
            stopSession(slot)
            if (slot.occupied) {
                runCatching { DisplayHolderClient.kill(slot.index) }
            }
        }
        delay(400)
        refresh()
    }

    private suspend fun startSession(slot: SlotSession) = withContext(Dispatchers.IO) {
        stopSession(slot)
        val surface = slot.surface ?: return@withContext
        if (slot.displayId < 0) return@withContext

        try {
            val renderer = PersistentVideoRenderer()
            val controller = VideoDecoderController(renderer)
            val scrcpy = Scrcpy(AppRuntime.context)

            slot.renderer = renderer
            slot.controller = controller
            slot.scrcpy = scrcpy

            controller.attachDisplaySurface(surface)

            val full = slot.full
            val options = ClientOptions().apply {
                displayId = slot.displayId
                control = full
                audio = false
                maxSize = (if (full) fullMaxSize else thumbMaxSize).toUShort()
                maxFps = if (full) fullFps else thumbFps
                videoBitRate = if (full) fullBitRate else thumbBitRate
            }

            val info = scrcpy.start(options)
            controller.ensureDecoder(info)
            scrcpy.session.attachVideoConsumer { packet -> controller.feed(packet) }

            updateSlot(slot.index) {
                it.running = true
                it.error = null
            }
            Log.i(
                TAG,
                "slot${slot.index}: 会话已启动 display=${slot.displayId} full=$full " +
                    "${info.width}x${info.height}",
            )

            // 尺寸变化（例如应用旋转）要重建解码器；用轮询避开对 currentSessionState 类型细节的依赖
            slot.sizeWatchJob = scope.launch {
                var lastW = -1
                var lastH = -1
                while (true) {
                    delay(1000)
                    val cur = slot.scrcpy?.currentSessionState?.value ?: continue
                    if (cur.width <= 0 || cur.height <= 0) continue
                    if (cur.width != lastW || cur.height != lastH) {
                        lastW = cur.width
                        lastH = cur.height
                        runCatching { slot.controller?.rebuildDecoderForSize(cur) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startSession(slot=${slot.index}) failed", t)
            updateSlot(slot.index) {
                it.running = false
                it.error = t.message ?: "投屏失败"
            }
        }
    }

    private suspend fun stopSession(slot: SlotSession) {
        slot.sizeWatchJob?.cancel()
        slot.sizeWatchJob = null
        runCatching { slot.scrcpy?.session?.clearVideoConsumer() }
        runCatching { slot.scrcpy?.stop() }
        runCatching { slot.controller?.releaseAll() }
        runCatching { slot.renderer?.release() }
        slot.scrcpy = null
        slot.controller = null
        slot.renderer = null
        slot.running = false
    }
}
