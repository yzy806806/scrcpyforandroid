@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.miuzarte.scrcpyforandroid.pages

import io.github.miuzarte.scrcpyforandroid.StreamActivity
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.Scaffold
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
    val activity = LocalActivity.current

    // 主屏投屏会话被系统收进**画中画**后，那个小窗会浮在页面之上；而全屏页的触摸层是
    // 全屏覆盖、所有触摸都透传给被控端，画中画窗口因此**收不到任何触摸** —— 用户看到
    // 的就是「有个浮窗关不掉」。进到挂机/全屏这一页就退出画中画。
    DisposableEffect(activity) {
        // 项目里画中画归 StreamActivity 管，用它自己的入口（内部会检查状态）
        runCatching { StreamActivity.dismissActivePictureInPicture() }
        onDispose { }
    }

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

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prefs.favorites.forEach { fav ->
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
        //
        // 外面这层 Scaffold 不是为了布局，而是为了**弹层宿主**：miuix 的悬浮球菜单是通过
        // Scaffold 提供的 LocalPopupStates 集中渲染的。本页原先没有 Scaffold，菜单被
        // trigger 打开后只是塞进了一个没人渲染的状态列表 —— 表现就是「点球没反应」
        // （日志里 show=true、条目 20 个，但屏幕上什么都没有）。
        fullscreen?.let { index ->
          Scaffold(containerColor = Color.Black) {
            // key(index)：切到下一个挂机位时，AndroidView 里的 SurfaceView 不会因为参数
            // 变化而重建，会继续画上一个槽位的画面 —— 表现就是「下一个应用」按钮像是没反应。
            key(index) {
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
          }
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
                // 标签：优先中文应用名，取不到就退回包名 —— 之前取不到就写「空」，
                // 于是有画面运行的格子也显示「格 N 空」，看起来像没跑起来。
                text = "格 ${index + 1}  " + slot.label.ifEmpty {
                    slot.packageName.substringAfterLast('.').ifEmpty { "空" }
                },
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
    // 按会话的宽高比显示：之前直接 fillMaxSize()，横屏应用会被拉成方格、竖屏应用被压扁，
    // 看着就是「比例怪怪的」。尺寸未知时先铺满，等首包给了尺寸再收敛到正确比例。
    val sizePair by SlotSessionManager.sessionSize(index).collectAsState()
    val ratio = if (sizePair.width > 0 && sizePair.height > 0) {
        sizePair.width.toFloat() / sizePair.height.toFloat()
    } else {
        0f
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    AndroidView(
        modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
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
        // 全屏沉浸：隐藏系统栏（照原版全屏页的做法）
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val w = activity?.window
            if (w != null) {
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
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
        // 必须跟随容器尺寸重建：设备旋转后本页从竖屏变横屏，画布尺寸跟着变，
        // 若只在首次组合取一次，触摸坐标会一直按旋转前的画布换算（横屏游戏点不准/点不动）。
        var touchAreaSize by remember(rootWidth, rootHeight) {
            mutableStateOf(IntSize(rootWidth, rootHeight))
        }

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
        // sessionSize 是被 collectAsState 的可观察尺寸，进 key 才能保证「会话尺寸从 0x0
        // 变成真实尺寸」时一定重组：否则 handler 会停在 0x0，mapToDevice 把每个触摸都
        // 算成 (0,0) —— 竖屏应用恰好还能用，横屏游戏则完全点不动。
        val touchEventHandler = remember(sessionInfo, touchAreaSize, sessionSize) {
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
        val asBundle by Storage.appSettings.bundleState.collectAsState()
        // 不用手动加多会话的两个动作: parseStoredLayout 会把「未出现在已存布局里的动作」
        // 统一追加到末尾（新增动作本来就不需要迁移存储）。手动再加一遍会让菜单里出现两份。
        // 应用挂机跑在**虚拟显示**上，没有 launcher、也没有独立的任务栈：
        // 主页 / 多任务 / 最近任务 / 所有应用 这几个动作在这里按下去不会有用，
        // 与其留着让人以为"坏了"，不如直接从菜单里去掉。
        val slotUnsupportedActions = remember {
            setOf(
                VirtualButtonAction.HOME,
                VirtualButtonAction.APP_SWITCH,
                VirtualButtonAction.RECENT_TASKS,
                VirtualButtonAction.ALL_APPS,
            )
        }
        val ballActions = remember(asBundle.virtualButtonsLayout, slotUnsupportedActions) {
            VirtualButtonActions.mergedOrder(
                items = VirtualButtonActions.parseStoredLayout(asBundle.virtualButtonsLayout),
                excluded = setOf(VirtualButtonAction.MORE) + slotUnsupportedActions,
            )
        }
        // **必须 remember**：球内部的弹层状态槽是 `remember(this) { PopupSlots() }`，
        // this 就是 VirtualButtonBar 实例。每次重组重建实例 → 状态槽被换成新的（关闭的）
        // 那一份 → 菜单刚被 trigger(MORE) 打开就被重置，永远弹不出来。
        // 原版 FullscreenControlScreen 同样是 remember(...) 出来的。
        val ballBar = remember(ballActions) {
            VirtualButtonBar(outside = emptyList(), more = ballActions)
        }
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

