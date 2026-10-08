package io.github.miuzarte.scrcpyforandroid.pages

import android.view.MotionEvent
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.pm.ActivityInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.onSizeChanged
import io.github.miuzarte.scrcpyforandroid.scrcpy.TouchEventHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonAction
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonActions
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonBar
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.SlotSessionManager
import io.github.miuzarte.scrcpyforandroid.storage.MultiSessionPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 多应用会话首页。
 *
 * 上方是收藏应用（点击即占用一个挂机位并在被控端拉起），下方是 2×2 挂机画格，
 * 底部是操作栏。画格里的每一路都是独立 scrcpy 会话，附着在 holder 持有的虚拟显示上，
 * 因此：
 *   - 主控端断开 / 退出，被控端应用继续运行
 *   - 双击某格 = 停掉缩略图会话、按高画质重连（被控端应用不重启，只换画面通道）
 */
@Composable
fun MultiSessionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prefs by remember { mutableStateOf(MultiSessionPrefs.load(context)) }
    val slots by SlotSessionManager.slots.collectAsState()
    val holderAlive by SlotSessionManager.holderAlive.collectAsState()
    val busy by SlotSessionManager.busy.collectAsState()

    var selected by remember { mutableStateOf<Int?>(null) }
    var fullscreen by remember { mutableStateOf<Int?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf<List<Scrcpy.AppInfo>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MultiSessionPrefs.applyToSessionManager(prefs)
        SlotSessionManager.refresh()
    }

    // 周期性同步被控端状态
    LaunchedEffect(fullscreen) {
        while (true) {
            SlotSessionManager.refresh()
            delay(if (fullscreen != null) 5000 else 2500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 离开页面只停投屏，被控端应用继续跑。
            // 必须走管理器自己的 scope：onDispose 之后本组合的协程已被取消。
            SlotSessionManager.stopAllSessionsAsync()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 状态条 ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (holderAlive) "挂机服务在线" else "挂机服务未运行",
                    color = if (holderAlive) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                val used = slots.count { it.occupied }
                Text("$used/${SlotSessionManager.MAX_SLOTS}", fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showQuality = !showQuality }) { Text("画质") }
                TextButton(onClick = onBack) { Text("返回") }
            }

            if (showQuality) {
                QualitySection(
                    prefs = prefs,
                    onChange = {
                        prefs = it
                        MultiSessionPrefs.save(context, it)
                        MultiSessionPrefs.applyToSessionManager(it)
                    },
                )
            }

            // ── 收藏应用 ────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的应用", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        showPicker = true
                        if (installed.isEmpty() && !loadingApps) {
                            loadingApps = true
                            scope.launch {
                                // 应用列表挂在当前 Scrcpy 实例的 listings 上（列表模式跑一次 scrcpy server）
                                val instance = AppRuntime.scrcpy
                                installed = runCatching {
                                    instance?.listings?.getApps(forceRefresh = true).orEmpty()
                                }.getOrDefault(emptyList())
                                    .sortedBy { it.label ?: it.packageName }
                                loadingApps = false
                            }
                        }
                    },
                ) { Text("+ 添加") }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(prefs.favorites, key = { it.packageName }) { fav ->
                    val running = slots.any { it.packageName == fav.packageName }
                    OutlinedButton(
                        onClick = {
                            val target = slots.indexOfFirst { !it.occupied }
                                .takeIf { it >= 0 }
                                ?: slots.indexOfFirst { it.packageName == fav.packageName }.takeIf { it >= 0 }
                            if (target != null && target >= 0) {
                                selected = target
                                scope.launch {
                                    SlotSessionManager.startApp(target, fav.packageName, fav.label)
                                }
                            }
                        },
                        enabled = !busy && holderAlive,
                    ) {
                        Text(if (running) "● ${fav.label}" else fav.label, fontSize = 13.sp)
                    }
                }
            }

            // ── 四宫格 ─────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0 until 2) {
                            val index = row * 2 + col
                            SlotCell(
                                index = index,
                                // 全屏时把缩略图节点移出组合：返回四宫格时 surface 会重建，
                                // 否则那格会停在最后一帧（会话已被全屏那路挤掉）
                                showVideo = fullscreen == null,
                                selected = selected == index,
                                onSelect = { selected = index },
                                onFullscreen = {
                                    selected = index
                                    fullscreen = index
                                },
                                onStop = {
                                    selected = null
                                    scope.launch { SlotSessionManager.stopSlot(index) }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("处理中…", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 操作栏 ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) { Text("手机主页") }

                Button(
                    onClick = {
                        val index = selected
                        if (index != null) {
                            scope.launch { SlotSessionManager.stopSlot(index) }
                            selected = null
                        }
                    },
                    enabled = selected != null && slots.getOrNull(selected ?: -1)?.occupied == true,
                    modifier = Modifier.weight(1f),
                ) { Text("关闭选中") }

                OutlinedButton(
                    onClick = { scope.launch { SlotSessionManager.stopAllSessions() } },
                    modifier = Modifier.weight(1f),
                ) { Text("断开") }
            }
        }

        // ── 全屏 ───────────────────────────────────────────────
        fullscreen?.let { index ->
            FullscreenSlot(
                index = index,
                onNextApp = {
                    val occupied = slots.indices.filter { slots[it].occupied }
                    if (occupied.size > 1) {
                        val cur = occupied.indexOf(index)
                        fullscreen = occupied[(cur + 1) % occupied.size]
                    }
                },
                onBackToGrid = { fullscreen = null },
            )
        }

        // ── 应用选择 ───────────────────────────────────────────
        if (showPicker) {
            AlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text("添加应用") },
                text = {
                    if (loadingApps) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("正在读取被控端应用列表…")
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            installed.forEach { app ->
                                val name = app.label ?: app.packageName
                                val already = prefs.favorites.any { it.packageName == app.packageName }
                                TextButton(
                                    onClick = {
                                        if (!already) {
                                            prefs = MultiSessionPrefs.addFavorite(
                                                context,
                                                MultiSessionPrefs.FavoriteApp(app.packageName, name),
                                            )
                                        }
                                        showPicker = false
                                    },
                                ) {
                                    Text(
                                        if (already) "✓ $name" else name,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPicker = false }) { Text("关闭") }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotCell(
    index: Int,
    showVideo: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onFullscreen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots by SlotSessionManager.slots.collectAsState()
    val slot = slots.getOrNull(index) ?: return

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "格 ${index + 1}  " + (slot.label.ifEmpty { "空" }),
                fontSize = 12.sp,
                maxLines = 1,
                color = if (slot.error != null) Color(0xFFC62828) else Color.Unspecified,
            )
            Spacer(Modifier.weight(1f))
            if (slot.occupied) {
                TextButton(onClick = onStop) { Text("✕", fontSize = 12.sp) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF101010), RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onSelect,
                    onDoubleClick = { if (slot.occupied) onFullscreen() },
                ),
        ) {
            if (slot.occupied && showVideo) {
                SlotSurface(index = index, full = false)
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x3300AAFF), RoundedCornerShape(8.dp)),
                )
            }
            if (!slot.occupied) {
                Text(
                    "空位",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF666666),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** 一路视频的 SurfaceView 容器。 */
@Composable
private fun SlotSurface(index: Int, full: Boolean) {
    val scope = rememberCoroutineScope()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        scope.launch { SlotSessionManager.attachSurface(index, holder.surface, full) }
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // 传 full：该销毁回调可能是过期的（页面已切到另一种模式），
                        // 过期回调不得停掉新页面的会话
                        SlotSessionManager.detachSurfaceAsync(index, full)
                    }
                })
            }
        },
    )
}

@Composable
private fun FullscreenSlot(
    index: Int,
    onNextApp: () -> Unit,
    onBackToGrid: () -> Unit,
) {
    val slot = SlotSessionManager.slot(index)
    val scope = rememberCoroutineScope()

    // ── 方向跟随：游戏横屏 → 本页横屏（照抄 StreamScreen 的做法）──
    val activity = LocalActivity.current
    LaunchedEffect(slot.displayId) {
        if (slot.displayId >= 0) {
            SlotSessionManager.attachSessionWatcher(index)
        }
    }
    val sessionSize by SlotSessionManager.sessionSize(index).collectAsState()
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(sessionSize) {
        val (w, h) = sessionSize
        if (w > 0 && h > 0 && activity != null) {
            activity.requestedOrientation = if (w >= h) {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
        }
    }

    // ── 返回手势/返回键：注入被控端（不退出全屏）──
    BackHandler {
        scope.launch { SlotSessionManager.injectBack(index) }
    }

    // 结构与原版 FullscreenControlPage 同构：透传层挂在**根容器**上，悬浮球是它的
    // 子节点 —— 这样球自己收事件（父层的透传只在子节点不消费后才看到），球可点可拖。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val rootWidth = constraints.maxWidth
        val rootHeight = constraints.maxHeight
        var touchAreaSize by remember { mutableStateOf(IntSize(rootWidth, rootHeight)) }

        // 悬浮球占用的屏幕区域。
        //
        // 透传层用 pointerInteropFilter（View 层拦截）：它返回 true 时 Compose 的
        // pointerInput 派发整轮被跳过，于是**球永远收不到点击**，事件反而被注入到被控端
        // （实测：点球 = 被控端右下角的一次点击）。所以 down 落在球上时必须返回 false，
        // 把这一轮事件让给 Compose，由球自己处理（点击开菜单 / 拖动）。
        // 球的实际矩形，由球自己上报（见 FloatingBall 的 onBoundsChanged）。不用设置换算：
        // 拖动会改变位置，换算值早晚与实际不符，而判断偏一点就等于球仍然点不到。
        var ballBounds by remember { mutableStateOf<Rect?>(null) }
        val ballHitPadPx = with(LocalDensity.current) { 6.dp.toPx() }
        // 一旦这一轮触摸被判给球，后续 move/up 也要一直让路，拖动才不会被中途透传
        var ballOwnsPointer by remember { mutableStateOf(false) }

        val sessionInfo = SlotSessionManager.sessionInfo(index)
        val scope2 = rememberCoroutineScope()
        val touchEventHandler = remember(sessionInfo, touchAreaSize) {
            if (sessionInfo == null) {
                android.util.Log.w("SlotFullscreen", "touchEventHandler=null sessionInfo=null（透传层未挂）slot=$index")
            } else {
                android.util.Log.i(
                    "SlotFullscreen",
                    "touchEventHandler 挂上 slot=$index session=${sessionInfo.width}x${sessionInfo.height}",
                )
            }
            sessionInfo?.let { info ->
                TouchEventHandler(
                    coroutineScope = scope2,
                    session = info,
                    touchAreaSize = touchAreaSize,
                    activePointerIds = linkedSetOf(),
                    activePointerPositions = linkedMapOf(),
                    activePointerDevicePositions = linkedMapOf(),
                    pointerLabels = linkedMapOf(),
                    nextPointerLabel = 1,
                    mouseHoverEnabled = info.mouseHover,
                    onInjectTouch = { action, pointerId, x, y, pressure, actionButton, buttons ->
                        // 尺寸由 SlotSessionManager 自己按会话取（必须与视频尺寸一致，
                        // 否则 scrcpy server 会丢弃该触摸事件）
                        SlotSessionManager.injectTouch(
                            index, action, pointerId, x, y,
                            pressure, actionButton, buttons,
                        )
                    },
                    onBackOrScreenOn = { action ->
                        SlotSessionManager.injectBackAction(index, action)
                        Unit
                    },
                    onActiveTouchCountChanged = {},
                    onActiveTouchDebugChanged = {},
                    onNextPointerLabelChanged = {},
                )
            }
        }

        // 视频区（透传层：把 MotionEvent 实时注入被控端）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (touchEventHandler != null) {
                        Modifier.pointerInteropFilter { event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    val b = ballBounds
                                    ballOwnsPointer = b != null && b.inflate(ballHitPadPx)
                                        .contains(Offset(event.x, event.y))
                                    android.util.Log.i(
                                        "SlotFullscreen",
                                        "DOWN at ${event.x},${event.y} -> ball=$ballOwnsPointer bounds=$b",
                                    )
                                    if (ballOwnsPointer) false
                                    else touchEventHandler.handleMotionEvent(event)
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    val wasBall = ballOwnsPointer
                                    ballOwnsPointer = false
                                    if (wasBall) false
                                    else touchEventHandler.handleMotionEvent(event)
                                }

                                else -> if (ballOwnsPointer) false
                                else touchEventHandler.handleMotionEvent(event)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            SlotSurface(index = index, full = true)
        }

        // 悬浮球：原版 VirtualButtonBar.FloatingBall（可拖动、位置持久化、外观一致）。
        // 菜单 = 用户配置的动作 + 多会话新增两项（退出全屏 / 切换下一个 / 回应用列表）。
        //
        // **结构要点（照原版 FullscreenControlPage）**：球必须在透传层**之外**的
        // 兄弟层。若放进透传层内，点球会被注入到被控端 —— 原版正是这样摆放的
        // （FullscreenControlScreen 里球在 Page 外、透传层在 Page 根）。
        val ballBar = VirtualButtonBar(
            outside = emptyList(),
            more = VirtualButtonActions.mergedOrder(
                items = VirtualButtonActions.parseStoredLayout(asBundle.virtualButtonsLayout),
                excluded = setOf(VirtualButtonAction.MORE),
            ) + listOf(
                VirtualButtonAction.SLOT_NEXT_APP,
                VirtualButtonAction.SLOT_BACK_TO_GRID,
            ),
        )
        ballBar.FloatingBall(
            onBoundsChanged = { ballBounds = it },
            onAction = { action ->
                when (action) {
                    VirtualButtonAction.EXIT_FULLSCREEN -> onBackToGrid()
                    VirtualButtonAction.SLOT_NEXT_APP -> onNextApp()
                    VirtualButtonAction.SLOT_BACK_TO_GRID -> onBackToGrid()
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun QualitySection(
    prefs: MultiSessionPrefs.Prefs,
    onChange: (MultiSessionPrefs.Prefs) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x11888888), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("缩略图（四宫格）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("分辨率上限 ${prefs.thumbMaxSize} · 帧率 ${prefs.thumbFps} · 码率 ${prefs.thumbBitRate / 1000}kbps", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(480, 720, 1080).forEach { size ->
                OutlinedButton(
                    onClick = { onChange(prefs.copy(thumbMaxSize = size)) },
                    enabled = prefs.thumbMaxSize != size,
                ) { Text("${size}p", fontSize = 12.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1", "5", "15").forEach { fps ->
                OutlinedButton(
                    onClick = { onChange(prefs.copy(thumbFps = fps)) },
                    enabled = prefs.thumbFps != fps,
                ) { Text("${fps}fps", fontSize = 12.sp) }
            }
        }
        Text("全屏（打游戏）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            "复用主屏参数：${if (prefs.fullMaxSize == 0) "原生" else "${prefs.fullMaxSize}p"} · " +
                "${prefs.fullFps.ifEmpty { "不限" }}fps · ${prefs.fullBitRate / 1000}kbps",
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1080, 720).forEach { size ->
                OutlinedButton(
                    onClick = { onChange(prefs.copy(fullMaxSize = size)) },
                    enabled = prefs.fullMaxSize != size,
                ) { Text(if (size == 0) "原生" else "${size}p", fontSize = 12.sp) }
            }
        }
    }
}

