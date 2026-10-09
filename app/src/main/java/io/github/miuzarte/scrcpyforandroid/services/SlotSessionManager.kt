package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Codec
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.DisplayImePolicy
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Rect
import android.util.Log as AndroidLog
import androidx.compose.ui.unit.IntSize

/**
 * 一个挂机槽位对外暴露的**不可变快照**。
 *
 * 必须不可变：StateFlow 只在 `equals` 变化时发射。早先直接对外暴露可变对象并原地改字段，
 * 引用不变 → 界面永远不刷新（挂着「拉不起来」的现象，其实是两个 bug 叠在一起）。
 */
data class SlotUi(
    val index: Int = 0,
    val displayId: Int = -1,
    val packageName: String = "",
    val label: String = "",
    val full: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
) {
    val occupied: Boolean get() = packageName.isNotEmpty()
}

/**
 * 槽位内部可变状态，只在管理器内部使用；对外一律走 [SlotUi] 快照。
 *
 * 被控端侧由常驻 holder 持有虚拟显示并运行应用；主控端这里只维护「看这一路」的
 * scrcpy 会话（scrcpy server 通过 `display_id` 附着到 holder 的显示上）。
 * 所以停掉/重启这一路**不会影响被控端应用的运行**。
 */
private class SlotSession(val index: Int) {
    var displayId: Int = -1
    var packageName: String = ""
    var label: String = ""

    /** true = 全屏（高画质、可操作），false = 缩略图（1fps 低码率）。 */
    var full: Boolean = false

    var running: Boolean = false
    var error: String? = null

    var surface: Surface? = null
    var scrcpy: Scrcpy? = null
    var renderer: PersistentVideoRenderer? = null
    var controller: VideoDecoderController? = null
    var sizeWatchJob: Job? = null

    /** 该槽位的注入协程域（触摸/按键注入用，不阻塞主线程）。 */
    val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    var thumbBitRate: Int = 500_000
    var fullMaxSize: Int = 0
    var fullFps: String = "60"
    var fullBitRate: Int = 8_000_000
    var useHevc: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val session = List(MAX_SLOTS) { SlotSession(it) }

    private val _slots = MutableStateFlow(List(MAX_SLOTS) { SlotUi(index = it) })
    val slots: StateFlow<List<SlotUi>> = _slots.asStateFlow()

    /**
     * 全屏页是否开着。MainScreen 用它决定两件事：tab 的左右滑动要锁住（否则在全屏里
     * 横滑会翻到别的 tab）、底部 tab 栏要藏起来（全屏就该是沉浸的）。
     */
    /** 全停标志：true 时格子不拉流（应用照跑），恢复时对占用中的槽重新拉起。 */
    private val _pausedFlow = MutableStateFlow(false)
    val pausedFlow: StateFlow<Boolean> = _pausedFlow.asStateFlow()

    private val _fullscreenActive = MutableStateFlow(false)
    val fullscreenActive: StateFlow<Boolean> = _fullscreenActive.asStateFlow()

    fun setFullscreenActive(active: Boolean) {
        _fullscreenActive.value = active
    }

    private val _holderAlive = MutableStateFlow(false)
    val holderAlive: StateFlow<Boolean> = _holderAlive.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 每路会话的当前视频尺寸（供全屏页跟随旋转）。 */
    private val sessionSizes = MutableStateFlow(List(MAX_SLOTS) { IntSize.Zero })
    fun sessionSize(index: Int): StateFlow<IntSize> {
        val flows = sessionSizes
        return object : StateFlow<IntSize> {
            override val value: IntSize get() = flows.value[index]
            override val replayCache: List<IntSize> get() = listOf(value)
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<IntSize>): Nothing {
                flows.collect { list -> collector.emit(list[index]) }
            }
        }
    }

    fun sessionSizeAll(): StateFlow<List<IntSize>> = sessionSizes

    fun sessionInfo(index: Int): Scrcpy.Session.SessionInfo? =
        session.getOrNull(index)?.scrcpy?.currentSessionState?.value

    /** 全屏页进入时调用：确保该槽位的尺寸监听在跑（挂机位运行中但页面晚开的情况）。 */
    fun attachSessionWatcher(index: Int) {
        session.getOrNull(index) ?: return
        // 尺寸监听在 startSession 里已启动；这里只是占位，让调用方语义完整。
    }

    fun slot(index: Int): SlotUi = _slots.value[index.coerceIn(0, MAX_SLOTS - 1)]

    /** 把内部可变状态发布成不可变快照。每次改动后都要调，否则界面不刷新。 */
    private fun publishUi() {
        _slots.value = session.map { s ->
            SlotUi(
                index = s.index,
                displayId = s.displayId,
                packageName = s.packageName,
                label = s.label,
                full = s.full,
                running = s.running,
                error = s.error,
            )
        }
    }

    /** 读 holder 状态并同步到本地槽位（显示 id / 应用）。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val state = DisplayHolderClient.readState()
        _holderAlive.value = state != null
        if (state == null) {
            Log.w(TAG, "refresh(): holder 不可用")
            publishUi()
            return@withContext
        }
        session.forEach { s ->
            val remote = state.slots.firstOrNull { it.slot == s.index }
            val displayId = remote?.displayId ?: -1
            if (displayId != s.displayId) {
                Log.i(TAG, "slot${s.index}: displayId ${s.displayId} -> $displayId")
                s.displayId = displayId
            }
            val pkg = remote?.packageName.orEmpty()
            if (pkg != s.packageName) {
                s.packageName = pkg
                if (pkg.isEmpty()) {
                    s.label = ""
                }
            }
            // holder 侧 launch 失败（应用不支持虚拟显示等）时把原因透传给 UI
            val holderError = remote?.error.orEmpty()
            if (holderError.isNotEmpty() && s.error != holderError) {
                s.error = holderError
                Log.w(TAG, "slot${s.index}: holder launch error: $holderError")
            } else if (holderError.isEmpty() && s.error == holderError) {
                // holder 已清掉 error（比如重新 launch 成功），本地同步清掉
                s.error = null
            }
        }
        publishUi()
    }

    /**
     * 占用一个槽位：让 holder 建显示并拉起应用，然后（若已有画面容器）开始投屏。
     *
     * 整段必须在 IO 线程：adb 的 shell 调用是阻塞 socket 操作，放主线程会抛
     * NetworkOnMainThreadException —— 而且当初用 runCatching 把它吞掉了，
     * 表现为「点了没反应、也没有任何报错」。
     */
    suspend fun startApp(index: Int, packageName: String, label: String) {
        _busy.value = true
        try {
            withContext(Dispatchers.IO) {
                val s = session[index]

                if (!_holderAlive.value) refresh()
                if (!_holderAlive.value) {
                    s.error = "挂机服务未运行"
                    publishUi()
                    return@withContext
                }

                // holder 的 create / launch 都是幂等的
                if (!DisplayHolderClient.create(index)) {
                    s.error = "命令发送失败（create）"
                    publishUi()
                    Log.e(TAG, "startApp(slot=$index): create 命令发送失败")
                    return@withContext
                }
                delay(500)
                if (!DisplayHolderClient.launch(index, packageName)) {
                    s.error = "命令发送失败（launch）"
                    publishUi()
                    Log.e(TAG, "startApp(slot=$index): launch 命令发送失败")
                    return@withContext
                }

                s.packageName = packageName
                s.label = label
                s.error = null
                publishUi()

                // 等 holder 把显示建出来（显示 id 由 holder 分配，需要回读 state.json）
                var waited = 0
                while (waited < 6 && s.displayId < 0) {
                    delay(400)
                    refresh()
                    waited++
                }

                if (s.displayId < 0) {
                    s.error = "显示未就绪"
                    publishUi()
                    Log.e(TAG, "startApp(slot=$index): holder 未在超时内创建显示")
                    return@withContext
                }

                if (s.surface != null) {
                    startSession(s)
                } else {
                    Log.i(TAG, "slot$index: 显示就绪 id=${s.displayId}，等画面容器附着")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startApp(slot=$index, pkg=$packageName) failed", t)
            session[index].error = t.message ?: "启动失败"
            publishUi()
        } finally {
            _busy.value = false
        }
    }

    /**
     * 绑定画面容器。
     *
     * Compose 里进入四宫格 / 全屏时调用。画质模式或 surface 变化时会重建这一路会话
     * （被控端应用不受影响）。
     */
    suspend fun attachSurface(index: Int, surface: Surface, full: Boolean) {
        val s = session[index]
        if (s.running && s.full == full && s.surface === surface) return
        // 全停期间只记录 surface 不拉流：否则设备旋转等 UI 重组事件会单独复活某一路，
        // 和其余格子的"已全停"状态不一致
        if (_pausedFlow.value) {
            s.surface = surface
            s.full = full
            return
        }

        // 同一模式下的 surface 重建（设备旋转/尺寸变化）**不重建会话**：会话（scrcpy 连接 +
        // 解码器）与渲染目标无关，拆了重建既慢又会和紧随其后的销毁回调打架，实测就是
        // 横屏游戏点进全屏后黑屏（会话明明建好了、首包 2378x1080 也到了）。换 surface 即可。
        if (s.running && s.full == full && s.scrcpy != null && s.surface !== surface) {
            s.surface = surface
            runCatching { s.controller?.attachDisplaySurface(surface) }
                .onFailure { t -> AndroidLog.w(TAG, "attachSurface(slot=$index) 复用会话换 surface 失败", t) }
            return
        }

        s.surface = surface
        s.full = full
        if (s.displayId < 0) {
            refresh()
        }
        if (s.displayId < 0) {
            Log.w(TAG, "attachSurface(slot=$index): 显示尚未就绪")
            return
        }
        startSession(s)
    }

    /**
     * 解绑该槽位的渲染 surface 并停掉会话。
     *
     * [full] 标识调用方身份（缩略图 / 全屏）。SurfaceView 的销毁回调可能**晚于**新页面的
     * attach 到达：进全屏时缩略图的 surface 被销毁，若此时无条件停会话，就会把刚建立的
     * 全屏会话杀掉（现象：全屏里触摸毫无反应，日志是 "该槽位没有会话，丢弃"）。
     * 所以只有回调所属的模式仍是当前模式时才生效。
     */
    suspend fun detachSurface(index: Int, full: Boolean) {
        val s = session[index]
        if (s.full != full) return
        s.surface = null
        // 旋转时 surfaceDestroyed 紧跟 surfaceCreated：等一下，如果新的 surface 已经接管，
        // 说明只是换了个渲染目标，会话要保留（否则横屏游戏一进全屏就被自己拆掉）。
        delay(500)
        if (s.surface != null) return
        stopSession(s)
        publishUi()
    }

    /** 关掉一路：停投屏 + 让 holder force-stop 应用并销毁显示。 */
    suspend fun stopSlot(index: Int) = withContext(Dispatchers.IO) {
        val s = session[index]
        stopSession(s)
        runCatching { DisplayHolderClient.kill(index) }
        s.packageName = ""
        s.label = ""
        s.displayId = -1
        s.error = null
        publishUi()
        delay(400)
        refresh()
    }

    /**
     * 离开页面时调用。
     *
     * 不能在 UI 的 rememberCoroutineScope 里跑：组合被销毁时那个 scope 会被取消，
     * 停会话的协程根本执行不到，scrcpy 会话就泄漏了。
     */
    fun stopAllSessionsAsync() {
        scope.launch { stopAllSessions() }
    }

    /** 同 [detachSurface]，但用管理器自己的 scope（SurfaceView 回调可能晚于组合销毁）。 */
    fun detachSurfaceAsync(index: Int, full: Boolean) {
        scope.launch { detachSurface(index, full) }
    }

    /** 停掉所有投屏（不影响被控端应用运行 —— 这正是 holder 架构的意义）。 */
    suspend fun stopAllSessions() {
        // 不重置 paused：onDispose（切 tab）也走这里，全停是用户显式选择，
        // 切个 tab 不该把"已全停"偷偷变成"恢复"
        session.forEach { stopSession(it) }
        publishUi()
    }

    /**
     * 全停：停掉所有格子的拉流（应用照跑）。之后由 UI 把 paused 传进 attachSurface，
     * 或调用 [resumeAll] 重新拉起。
     */
    suspend fun pauseAll() {
        _pausedFlow.value = true
        session.forEach { stopSession(it) }
        publishUi()
    }

    /** 恢复：对占用中的槽重新拉流（用它们当前挂着的 surface）。 */
    suspend fun resumeAll() = withContext(Dispatchers.IO) {
        _pausedFlow.value = false
        session.forEach { s ->
            if (s.packageName.isNotEmpty()) {
                val surface = s.surface
                if (surface != null) {
                    attachSurface(s.index, surface, s.full)
                }
            }
        }
        publishUi()
    }


    /** 停掉投屏并杀光所有挂机应用。 */
    suspend fun stopAllAndKill() = withContext(Dispatchers.IO) {
        session.forEach { s ->
            stopSession(s)
            if (s.packageName.isNotEmpty()) {
                runCatching { DisplayHolderClient.kill(s.index) }
            }
        }
        delay(400)
        refresh()
    }

    private suspend fun startSession(s: SlotSession) = withContext(Dispatchers.IO) {
        stopSession(s)
        val surface = s.surface ?: return@withContext
        if (s.displayId < 0) return@withContext

        try {
            val renderer = PersistentVideoRenderer()
            val controller = VideoDecoderController(renderer)
            // 关键：槽位会话必须关掉 facade 上报，否则会顶掉主投屏的解码器绑定
            val scrcpy = Scrcpy(AppRuntime.context).apply { reportToNativeCoreFacade = false }

            s.renderer = renderer
            s.controller = controller
            s.scrcpy = scrcpy

            controller.attachDisplaySurface(surface)

            val full = s.full
            val options = ClientOptions().apply {
                displayId = s.displayId
                // 缩略图也开 control：实测 control=false 时 Session 的视频读取会停在第 1 帧
                // （原因未深究，疑似 server 在无控制连接时视频流的推送行为不同），黑屏
                control = true
                // 音频只跟着**全屏**那一路：四格同时播音只会混成一团，听不出任何一路；
                // 进全屏才开音频，等于"我现在操作的是这一路，就听这一路"。
                // Scrcpy.start() 内部会为请求了音频流的会话自建 ScrcpyAudioPlayer 播放，
                // 所以这里只要把流要过来即可。
                audio = full
                maxSize = (if (full) fullMaxSize else thumbMaxSize).toUShort()
                maxFps = if (full) fullFps else thumbFps
                videoBitRate = if (full) fullBitRate else thumbBitRate
                // H.265：同码率画质明显更好（被控端有硬件 HEVC 编码器）
                videoCodec = if (useHevc) Codec.H265 else Codec.H264
                // 关键帧间隔从默认 10s 缩短到 2s：进全屏／切那一路时要等下一个 I 帧
                // 才能出画，缩短它能明显减少"点进去黑一下"的时间。
                videoCodecOptions = "i-frame-interval=2"
                // 输入法（IME）策略 —— AOSP DisplayWindowSettings.getImePolicyLocked 写得很明白：
                // 主屏恒为 DISPLAY_IME_POLICY_LOCAL，而**任何副屏（含 holder 建的虚拟显示）默认是
                // DISPLAY_IME_POLICY_FALLBACK_DISPLAY**，即 Android 把输入法送到主屏去显示。
                // 表现就是：在挂机小窗里点输入框，被控端的键盘弹在它自己的屏幕上，我们这一路看不到。
                // （官方镜像的是主屏，主屏恒为 LOCAL，所以官方的全屏一切正常 —— 这就是差距所在。）
                // 上游为此提供 --display-ime-policy，落到 IWindowManager.setDisplayImePolicy：
                // 显式设成 local，让输入法显示在被镜像的这块屏上；会话结束时 server 会把旧策略恢复
                // （上游 CleanUp.java 只在 display_id > 0 时处理，正是副屏场景）。
                // 只给全屏（可交互）那一路设：缩略图那路不碰，避免两路会话互相覆盖策略。
                if (full) displayImePolicy = DisplayImePolicy.LOCAL
            }

            val info = scrcpy.start(options)
            controller.ensureDecoder(info)
            scrcpy.session.attachVideoConsumer { packet -> controller.feed(packet) }

            s.running = true
            s.error = null
            publishUi()
            AndroidLog.i(
                TAG,
                "startSession(slot=${s.index}): display=${s.displayId} full=$full " +
                    "control=${options.control} size=${info.width}x${info.height} " +
                    "sessionState=${s.scrcpy?.currentSessionState?.value != null}",
            )
            Log.i(
                TAG,
                "slot${s.index}: 会话已启动 display=${s.displayId} full=$full " +
                    "${info.width}x${info.height}",
            )

            // v4.0 协议下 start() 返回的宽高是 0，真正的尺寸来自首个视频包，所以解码器
            // 一定是在这里被创建出来的（rebuildDecoderForSize 在无解码器时会新建）。
            // 首次检查要快，否则每次附着都要黑屏一秒。
            s.sizeWatchJob = scope.launch {
                var lastW = -1
                var lastH = -1
                var tick = 0
                while (true) {
                    delay(if (tick++ == 0) 120 else 500)
                    val cur = s.scrcpy?.currentSessionState?.value ?: continue
                    if (cur.width <= 0 || cur.height <= 0) continue
                    if (cur.width != lastW || cur.height != lastH) {
                        lastW = cur.width
                        lastH = cur.height
                        // 发布尺寸（全屏页据此旋转）并按需重建解码器
                        sessionSizes.value = sessionSizes.value.toMutableList().also {
                            it[s.index] = IntSize(cur.width, cur.height)
                        }
                        runCatching { s.controller?.rebuildDecoderForSize(cur) }
                            .onFailure { AndroidLog.e(TAG, "rebuildDecoderForSize(slot=${s.index}) failed", it) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startSession(slot=${s.index}) failed", t)
            s.running = false
            s.error = t.message ?: "投屏失败"
            publishUi()
        }
    }

    private suspend fun stopSession(s: SlotSession) {
        s.sizeWatchJob?.cancel()
        s.sizeWatchJob = null
        runCatching { s.scrcpy?.session?.clearVideoConsumer() }
            .onFailure { AndroidLog.w(TAG, "stopSession(slot=${s.index}): clearVideoConsumer", it) }
        runCatching { s.scrcpy?.stop() }
            .onFailure { AndroidLog.w(TAG, "stopSession(slot=${s.index}): scrcpy.stop", it) }
        runCatching { s.controller?.releaseAll() }
            .onFailure { AndroidLog.w(TAG, "stopSession(slot=${s.index}): releaseAll", it) }
        runCatching { s.renderer?.release() }
            .onFailure { AndroidLog.w(TAG, "stopSession(slot=${s.index}): renderer.release", it) }
        s.scrcpy = null
        s.controller = null
        s.renderer = null
        s.running = false
    }

    // ── 输入注入（全屏页触摸透传 / 返回手势）────────────────────────

    /**
     * 触摸注入。坐标 (x, y) 已是视频坐标系（TouchEventHandler 换算过）。
     *
     * **尺寸必须用会话的视频尺寸**：scrcpy server 的 Controller 会用事件里声明的尺寸做
     * `positionMapper.map()`，与当前视频尺寸不一致时**直接丢弃事件**（源码里那条
     * "Ignore positional event generated for size ..."）。曾经误传触摸区尺寸，表现就是
     * 「按键注入正常、触摸完全无反应」。这里由管理器自己取真实尺寸，调用方不再传。
     */
    fun injectTouch(
        index: Int,
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ) {
        if (index !in session.indices) return
        val s = session[index]
        val scrcpy = s.scrcpy ?: run {
            AndroidLog.e(TAG, "injectTouch(slot=$index): 该槽位没有会话，丢弃")
            return
        }
        val info = scrcpy.currentSessionState.value ?: run {
            AndroidLog.e(TAG, "injectTouch(slot=$index): 会话信息未就绪，丢弃")
            return
        }
        val w = info.width
        val h = info.height
        if (w <= 0 || h <= 0) return
        s.jobScope.launch {
            runCatching {
                scrcpy.injectTouch(
                    action = action,
                    pointerId = pointerId,
                    x = x,
                    y = y,
                    screenWidth = w,
                    screenHeight = h,
                    pressure = pressure,
                    actionButton = actionButton,
                    buttons = buttons,
                )
            }.onFailure { t ->
                AndroidLog.e(TAG, "injectTouch(slot=$index) failed", t)
            }
        }
    }

    /** 返回键（BACK down/up 各发一次，与原版虚拟按键 HOME 的注入方式一致）。 */
    /**
     * 输入法提交文字。
     *
     * 含非 ASCII（中文等）时走剪贴板粘贴 —— 与项目里 `submitImeText` 的做法一致：
     * 键码注入打不出中文，只能整段塞剪贴板再 paste。
     */
    fun commitImeText(index: Int, text: String) {
        if (index !in session.indices || text.isEmpty()) return
        val s = session[index]
        val scrcpy = s.scrcpy ?: run {
            AndroidLog.e(TAG, "commitImeText(slot=$index): 该槽位没有会话，丢弃")
            return
        }
        s.jobScope.launch {
            runCatching {
                val usePaste = text.any { it.code > 0x7F }
                if (usePaste) {
                    scrcpy.setClipboard(text, paste = true)
                } else {
                    text.forEach { ch ->
                        val (keycode, meta) = charToKeycode(ch)
                        if (keycode != null) {
                            scrcpy.injectKeycode(
                                android.view.KeyEvent.ACTION_DOWN, keycode, 0, meta,
                            )
                            scrcpy.injectKeycode(
                                android.view.KeyEvent.ACTION_UP, keycode, 0, meta,
                            )
                        }
                    }
                }
            }.onFailure { t ->
                AndroidLog.w(TAG, "commitImeText(slot=$index) failed", t)
            }
        }
    }

    /** 删除光标周围的文字（输入法回删）。 */
    fun deleteSurroundingText(index: Int, beforeLength: Int, afterLength: Int) {
        if (index !in session.indices) return
        val s = session[index]
        val scrcpy = s.scrcpy ?: return
        s.jobScope.launch {
            runCatching {
                repeat(beforeLength) {
                    scrcpy.injectKeycode(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_DEL,
                    )
                    scrcpy.injectKeycode(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_DEL,
                    )
                }
                repeat(afterLength) {
                    scrcpy.injectKeycode(
                        android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_FORWARD_DEL,
                    )
                    scrcpy.injectKeycode(
                        android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_FORWARD_DEL,
                    )
                }
            }.onFailure { t ->
                AndroidLog.w(TAG, "deleteSurroundingText(slot=$index) failed", t)
            }
        }
    }

    /** 键盘按键事件（输入法回调里的硬件/软键盘按键）。 */
    fun injectKeyEvent(index: Int, event: android.view.KeyEvent) {
        if (index !in session.indices) return
        val s = session[index]
        val scrcpy = s.scrcpy ?: return
        s.jobScope.launch {
            runCatching {
                scrcpy.injectKeycode(
                    event.action, event.keyCode, event.repeatCount, event.metaState,
                )
            }.onFailure { t ->
                AndroidLog.w(TAG, "injectKeyEvent(slot=$index) failed", t)
            }
        }
    }

    private fun charToKeycode(ch: Char): Pair<Int?, Int> {
        val meta = if (ch.isUpperCase()) android.view.KeyEvent.META_SHIFT_ON else 0
        val base = ch.uppercaseChar()
        val keycode = when (base) {
            in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (base - 'A')
            in '0'..'9' -> android.view.KeyEvent.KEYCODE_0 + (base - '0')
            ' ' -> android.view.KeyEvent.KEYCODE_SPACE
            '\n' -> android.view.KeyEvent.KEYCODE_ENTER
            else -> null
        }
        return keycode to meta
    }

    fun injectBack(index: Int) {
        if (index !in session.indices) return
        val s = session[index]
        val scrcpy = s.scrcpy ?: run {
            AndroidLog.e(TAG, "injectBack(slot=$index): 该槽位没有会话，丢弃")
            return
        }
        AndroidLog.i(TAG, "injectBack(slot=$index)")
        s.jobScope.launch {
            runCatching {
                scrcpy.injectKeycode(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)
                scrcpy.injectKeycode(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)
            }.onFailure { t ->
                AndroidLog.w(TAG, "injectBack(slot=$index) failed", t)
            }
        }
    }

    /** TouchEventHandler 的返回回调（ACTION_DOWN / ACTION_UP）。 */
    fun injectBackAction(index: Int, action: Int) {
        if (index !in session.indices) return
        val s = session[index]
        val scrcpy = s.scrcpy ?: return
        s.jobScope.launch {
            runCatching { scrcpy.pressBackOrTurnScreenOn(action) }
        }
    }
}
