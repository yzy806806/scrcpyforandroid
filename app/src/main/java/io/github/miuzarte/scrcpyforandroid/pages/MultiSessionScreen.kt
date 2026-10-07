package io.github.miuzarte.scrcpyforandroid.pages

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
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
            // 离开页面只停投屏，被控端应用继续跑
            scope.launch { SlotSessionManager.stopAllSessions() }
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
                                installed = runCatching { Scrcpy.getApps() }.getOrDefault(emptyList())
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
                label = slots.getOrNull(index)?.label.orEmpty(),
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
            if (slot.occupied) {
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
                        scope.launch { SlotSessionManager.detachSurface(index) }
                    }
                })
            }
        },
    )
}

@Composable
private fun FullscreenSlot(
    index: Int,
    label: String,
    onNextApp: () -> Unit,
    onBackToGrid: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label.ifEmpty { "格 ${index + 1}" }, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNextApp) { Text("下一个") }
                TextButton(onClick = onBackToGrid) { Text("回列表") }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SlotSurface(index = index, full = true)
            }
        }
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

