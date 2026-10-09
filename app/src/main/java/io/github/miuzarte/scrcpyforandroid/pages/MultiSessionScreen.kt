@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.miuzarte.scrcpyforandroid.pages

import io.github.miuzarte.scrcpyforandroid.widgets.ScrcpyInputSurfaceView
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.miuzarte.scrcpyforandroid.services.LocalInputService
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonSurface
import androidx.compose.material3.TextButton
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.Dp
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import androidx.compose.foundation.layout.fillMaxHeight
import io.github.miuzarte.scrcpyforandroid.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
fun MultiSessionScreen(
    onBack: () -> Unit,
    /** 「直接连接」：复用设备页那套连接逻辑（含隧道/超时），由 MainScreen 注入。 */
    onDirectConnect: () -> Unit = {},
    /** 底部 tab 栏占掉的高度（由 MainScreen 传入），否则页面底部的按钮会被 tab 栏压住看不见。 */
    bottomPadding: Dp = 0.dp,
) {
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
    val paused by SlotSessionManager.pausedFlow.collectAsState()

    var fullscreen by remember { mutableStateOf<Int?>(null) }

    // 全屏时告诉 MainScreen：锁住 tab 滑动、藏起底部 tab 栏
    LaunchedEffect(fullscreen) {
        SlotSessionManager.setFullscreenActive(fullscreen != null)
    }
    DisposableEffect(Unit) {
        onDispose { SlotSessionManager.setFullscreenActive(false) }
    }
    var showPicker by remember { mutableStateOf(false) }
    // 长按某个收藏时，为它打开的操作菜单（null = 没有打开）
    var favMenuFor by remember { mutableStateOf<String?>(null) }
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
        // 进入页面：非全停状态下，把占用中的槽拉起（覆盖"全停→切 tab→切回"的场景：
        // onDispose 停了流、paused 仍为 true 时 surface 不会自动重连，但此刻用户回到
        // 页面就是要看画面 —— 全停语义只在用户显式点击时生效）。
        if (!SlotSessionManager.pausedFlow.value) {
            scope.launch { SlotSessionManager.resumeAll() }
        }
        onDispose {
            // 离开页面只停投屏，被控端应用继续跑。
            // 必须走管理器自己的 scope：onDispose 之后本组合的协程已被取消。
            SlotSessionManager.stopAllSessionsAsync()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(12.dp)
                .padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 状态条 ──────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpacing.Large, vertical = UiSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (holderAlive) "挂机服务在线" else "挂机服务未运行",
                    color = if (holderAlive) colorScheme.primary else colorScheme.error,
                    fontSize = textStyles.body2.fontSize,
                )
                Spacer(Modifier.width(UiSpacing.Medium))
                val used = slots.count { it.occupied }
                Text(
                    "$used/${SlotSessionManager.MAX_SLOTS}",
                    fontSize = textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                // 原来这里的「画质」「返回」已按要求移除：画质设置搬进设置页
                // （QUIC 隧道配置下方），返回用手势/系统返回键。
            }
            }

            // ── 收藏应用 ────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UiSpacing.MediumLarge),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.Small),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的应用", fontWeight = FontWeight.Medium, fontSize = textStyles.body1.fontSize)
                Spacer(Modifier.weight(1f))
                Text(
                    "+ 添加",
                    color = colorScheme.primary,
                    fontSize = textStyles.body2.fontSize,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
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
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
            ) {
                prefs.favorites.forEach { fav ->
                    val running = slots.any { it.packageName == fav.packageName }
                    // 自绘小 chip：Material3 按钮默认尺寸太大，五个收藏就能占掉三行，
                    // 把下面的四格挤成扁的（用户实测反馈）。这里只保留必要的内边距。
                    val chipEnabled = !busy && holderAlive
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (running) colorScheme.primary.copy(alpha = 0.18f)
                                else colorScheme.surfaceVariant,
                            )
                            .combinedClickable(
                                enabled = chipEnabled,
                                onClick = {
                                    // 先看这个应用是不是已经在某个格子跑着 —— 必须优先复用。
                                    // 反过来的顺序（先找空槽）会让同一个应用被启动两次，
                                    // 而 Android 上一个应用不能同时在两个显示上跑实例，第二个
                                    // 格子就变成"占用中但没有画面"的黑格子。
                                    val target = slots
                                        .indexOfFirst { it.packageName == fav.packageName }
                                        .takeIf { it >= 0 }
                                        ?: slots.indexOfFirst { !it.occupied }.takeIf { it >= 0 }
                                    if (target != null && target >= 0) {
                                        scope.launch {
                                            SlotSessionManager.startApp(
                                                target, fav.packageName, fav.label,
                                            )
                                        }
                                    }
                                },
                                onLongClick = { favMenuFor = fav.packageName },
                            )
                            .padding(horizontal = UiSpacing.MediumLarge, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (running) "● ${fav.label}" else fav.label,
                            fontSize = textStyles.body2.fontSize,
                            color = if (chipEnabled) colorScheme.onSurface
                            else colorScheme.onSurface.copy(alpha = 0.38f),
                            maxLines = 1,
                        )
                    }

                    // 长按收藏 → 管理菜单（删除 / 调序）。收藏多了以后没有这个就只能一直攒着。
                    if (favMenuFor == fav.packageName) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { favMenuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("上移") },
                                onClick = {
                                    prefs = MultiSessionPrefs.moveFavorite(
                                        context, fav.packageName, -1,
                                    )
                                    favMenuFor = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("下移") },
                                onClick = {
                                    prefs = MultiSessionPrefs.moveFavorite(
                                        context, fav.packageName, 1,
                                    )
                                    favMenuFor = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    prefs = MultiSessionPrefs.removeFavorite(
                                        context, fav.packageName,
                                    )
                                    favMenuFor = null
                                },
                            )
                        }
                    }
                }
            }

            }
            }

            // ── 四宫格 ─────────────────────────────────────────
            // 2×2 撑满页面剩余高度：格子因此是竖的（微信这类竖屏应用一眼能看清，
            // 横屏游戏歪头看个状态足够，需要操作就点进全屏）。
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (col in 0 until 2) {
                            val index = row * 2 + col
                            SlotCell(
                                index = index,
                                // 全屏时把缩略图节点移出组合：返回四宫格时 surface 会重建，
                                // 否则那格会停在最后一帧（会话已被全屏那路挤掉）
                                showVideo = fullscreen == null,
                                onFullscreen = { fullscreen = index },
                                onStop = {
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

            // ── 操作栏 ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 「手机主页」与「关闭选中」都按要求去掉了：
                //   手机主页 → 换成「直接连接」（复用设备页的连接逻辑）
                //   关闭选中 → 不需要，每格右上角的 ✕ 就够
                Button(
                    onClick = onDirectConnect,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.button_direct_connect)) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .border(
                            1.dp,
                            colorScheme.onSurface.copy(alpha = 0.25f),
                            RoundedCornerShape(50),
                        )
                        .clickable {
                            scope.launch {
                                if (paused) SlotSessionManager.resumeAll()
                                else SlotSessionManager.pauseAll()
                            }
                        }
                        .padding(vertical = UiSpacing.MediumLarge),
                    contentAlignment = Alignment.Center,
                ) {
                    // 全停 = 停掉所有格子的拉流，被控端应用继续跑（省电省流量）；
                    // 恢复 = 对占用中的槽重新拉流
                    Text(
                        if (paused) "恢复" else "全停",
                        color = colorScheme.onSurface,
                        fontSize = textStyles.body2.fontSize,
                    )
                }
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
    onFullscreen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots by SlotSessionManager.slots.collectAsState()
    val slot = slots.getOrNull(index) ?: return
    // 会话的首个视频包带来真实尺寸；在那之前是"占用中但还没有画面"
    val sizeForCell by SlotSessionManager.sessionSize(index).collectAsState()
    val hasPicture = sizeForCell.width > 0 && sizeForCell.height > 0

    // 整个格子就是一块画面，标签与 ✕ 浮在格子内部顶部：
    // 之前标签单独占一格外面的一整行，四格就白吃掉四行高度，格子和按钮都被挤扁了。
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101010))
            // 只保留双击：单击进全屏太容易误触，而"选中"已经没有任何功能了
            .combinedClickable(
                onClick = {},
                onDoubleClick = { if (slot.occupied) onFullscreen() },
            ),
    ) {

            // 标签（左上）与 ✕（右上）分开两处：之前同处一行且文字不限宽，
            // 标签一长就把 ✕ 顶出格子被裁掉（实测格1 有画面却没有 ✕ 就是这个原因）。
            Text(
                // 标签：优先中文应用名，取不到就退回包名 —— 之前取不到就写「空」，
                // 于是有画面运行的格子也显示「格 N 空」，看起来像没跑起来。
                text = "格 ${index + 1}  " + slot.label.ifEmpty {
                    slot.packageName.substringAfterLast('.').ifEmpty { "空" }
                },
                fontSize = 11.sp,
                maxLines = 1,
                color = if (slot.error != null) Color(0xFFFF8A80) else Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(UiSpacing.Medium)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (slot.occupied) {
                Text(
                    text = "✕",
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(UiSpacing.Medium)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }

            if (slot.occupied && showVideo) {
                SlotSurface(index = index, full = false)
            }
            if (!slot.occupied) {
                Text(
                    "空位",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF666666),
                    fontSize = 13.sp,
                )
            } else if (!hasPicture) {
                // 槽位被占用、但还没有画面：以前这里什么都不显示，就是一个黑格子，
                // 从外面完全看不出是"在启动"还是"卡住了"。给一句话。
                Text(
                    text = if (slot.error != null) "画面异常" else "等待画面…",
                    modifier = Modifier.align(Alignment.Center),
                    color = if (slot.error != null) Color(0xFFFF8A80) else Color(0xFF9E9E9E),
                    fontSize = 13.sp,
                )
            }
        }
}

/** 一路视频的 SurfaceView 容器。 */
@Composable
private fun SlotSurface(
    index: Int,
    full: Boolean,
    /** >0 时唤起本机输入法（原版全屏页的「拉起输入法」就是靠它）。 */
    imeRequestToken: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val imeTarget = remember { mutableStateOf<ScrcpyInputSurfaceView?>(null) }
    // 时序照原版 DeviceWidgets（官方方案）：token > 0 时才 setCommitTextEnabled +
    // showSoftKeyboard 一起做。提前到 view 出现时调会闪退 —— setCommitTextEnabled
    // 内部 requestFocus()，view 还没 attach 到 window 就调会抛异常。
    LaunchedEffect(imeRequestToken, imeTarget.value) {
        if (imeRequestToken == 0) return@LaunchedEffect
        val sv = imeTarget.value ?: return@LaunchedEffect
        sv.setCommitTextEnabled(true)
        LocalInputService.showSoftKeyboard(sv)
    }
    // 按会话的宽高比显示：之前直接 fillMaxSize()，横屏应用会被拉成方格、竖屏应用被压扁，
    // 看着就是「比例怪怪的」。尺寸未知时先铺满，等首包给了尺寸再收敛到正确比例。
    val sizePair by SlotSessionManager.sessionSize(index).collectAsState()
    val ratio = if (sizePair.width > 0 && sizePair.height > 0) {
        sizePair.width.toFloat() / sizePair.height.toFloat()
    } else {
        0f
    }
    // 按宽高比"装进去"：只写 aspectRatio 的话，横屏视频的宽度会超出格子（实测表现为画面
    // 靠左溢出、右边被裁掉），所以要么限宽、要么限高 —— 跟原版全屏页同一个做法。
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val containerRatio =
            if (maxHeight > 0.dp) maxWidth.value / maxHeight.value else 1f
        val videoModifier = when {
            ratio <= 0f -> Modifier.fillMaxSize()
            ratio > containerRatio -> Modifier.fillMaxWidth().aspectRatio(ratio)
            else -> Modifier.fillMaxHeight().aspectRatio(ratio)
        }
    AndroidView(
        modifier = videoModifier,
        factory = { ctx ->
            // 必须用 ScrcpyInputSurfaceView 而不是裸 SurfaceView：它是项目自带的定制
            // View，支持 setCommitTextEnabled + InputCallbacks —— 原版全屏页能弹键盘、
            // 输入能进被控端，靠的就是它。用裸 SurfaceView 弹不出键盘也收不到输入。
            ScrcpyInputSurfaceView(ctx).also { sv -> imeTarget.value = sv }.apply {
                inputCallbacks = object : ScrcpyInputSurfaceView.InputCallbacks {
                    override fun handleKeyEvent(event: android.view.KeyEvent): Boolean {
                        SlotSessionManager.injectKeyEvent(index, event)
                        return true
                    }

                    override fun handleCommitText(text: CharSequence): Boolean {
                        SlotSessionManager.commitImeText(index, text.toString())
                        return true
                    }

                    override fun handleDeleteSurroundingText(
                        beforeLength: Int,
                        afterLength: Int,
                    ): Boolean {
                        SlotSessionManager.deleteSurroundingText(index, beforeLength, afterLength)
                        return true
                    }
                }
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
    // 「拉起输入法」：递增它即可唤起本机键盘（原版全屏页用同一套机制）
    var imeRequestToken by rememberSaveable { mutableIntStateOf(0) }

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
            SlotSurface(index = index, full = true, imeRequestToken = imeRequestToken)
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
        // SLOT_* 动作已由 visibleOn(SLOT_FULLSCREEN) 过滤保证不出现在别的页面，
        // 这里不再需要手动排除 Slot 动作；虚拟显示下无效的动作（主页/多任务等）仍要排除。
        val slotUnsupportedActions = remember {
            setOf(
                VirtualButtonAction.HOME,
                VirtualButtonAction.APP_SWITCH,
                VirtualButtonAction.RECENT_TASKS,
                VirtualButtonAction.ALL_APPS,
            )
        }
        val ballActions = remember(asBundle.virtualButtonsLayout, slotUnsupportedActions) {
            val base = VirtualButtonActions.mergedOrder(
                items = VirtualButtonActions.parseStoredLayout(asBundle.virtualButtonsLayout),
                excluded = setOf(VirtualButtonAction.MORE) + slotUnsupportedActions,
                surface = VirtualButtonSurface.SLOT_FULLSCREEN,
            ).toMutableList()

            // 「回应用列表」放到「填充锁屏密码」所在的位置（菜单第二位），而不是像新动作那样
            // 默认被追加到末尾 —— 它是这一页最常用的动作之一，放末尾够不着。
            base.remove(VirtualButtonAction.SLOT_BACK_TO_GRID)
            val anchor = base.indexOf(VirtualButtonAction.PASSWORD_INPUT)
            base.add(if (anchor >= 0) anchor else base.size, VirtualButtonAction.SLOT_BACK_TO_GRID)
            base
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
                    VirtualButtonAction.TOGGLE_IME -> imeRequestToken++
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
