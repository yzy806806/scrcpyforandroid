package io.github.miuzarte.scrcpyforandroid.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.miuzarte.scrcpyforandroid.constants.ScrcpyPresets
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import io.github.miuzarte.scrcpyforandroid.scaffolds.AppPageLazyColumn
import io.github.miuzarte.scrcpyforandroid.scaffolds.SuperSlide
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSpinner
import top.yukonga.miuix.kmp.extra.SuperSwitch
import kotlin.math.roundToInt

private val AUDIO_SOURCE_OPTIONS = listOf(
    "output" to "output",
    "playback" to "playback",
    "mic" to "mic",
    "mic-unprocessed" to "mic-unprocessed",
    "mic-camcorder" to "mic-camcorder",
    "mic-voice-recognition" to "mic-voice-recognition",
    "mic-voice-communication" to "mic-voice-communication",
    "voice-call" to "voice-call",
    "voice-call-uplink" to "voice-call-uplink",
    "voice-call-downlink" to "voice-call-downlink",
    "voice-performance" to "voice-performance",
    "custom" to "自定义",
)

// TODO: Scrcpy.VideoSource
private val VIDEO_SOURCE_OPTIONS = listOf(
    "display" to "display",
    "camera" to "camera",
)

private val CAMERA_FACING_OPTIONS = listOf(
    "" to "默认",
    "front" to "front",
    "back" to "back",
    "external" to "external",
)

private val CAMERA_FPS_PRESETS = listOf(0, 10, 15, 24, 30, 60, 120, 240, 480, 960)

@Composable
internal fun AdvancedConfigPage(
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    snackbarHostState: SnackbarHostState,
    cameraSizeOptions: SnapshotStateList<String>,
    cameraSizeDropdownItems: List<String>,
    videoEncoderDropdownItems: List<String>,
    videoEncoderTypeMap: Map<String, String>,
    videoEncoderIndex: Int,
    audioEncoderDropdownItems: List<String>,
    audioEncoderTypeMap: Map<String, String>,
    audioEncoderIndex: Int,
    onRefreshEncoders: () -> Unit,
    onRefreshCameraSizes: () -> Unit,
) {
    val scrcpyOptions = Storage.scrcpyOptions

    val context = LocalContext.current

    val focusManager = LocalFocusManager.current

    val scope = rememberCoroutineScope()

    val videoEncoderEntries = videoEncoderDropdownItems.map { encoderName ->
        if (encoderName == "默认") {
            SpinnerEntry(title = encoderName)
        } else {
            val type = resolveEncoderTypeLabel(videoEncoderTypeMap[encoderName])
            SpinnerEntry(
                title = encoderName,
                summary = type.ifBlank { null },
            )
        }
    }
    val audioEncoderEntries = audioEncoderDropdownItems.map { encoderName ->
        if (encoderName == "默认") {
            SpinnerEntry(title = encoderName)
        } else {
            val type = resolveEncoderTypeLabel(audioEncoderTypeMap[encoderName])
            SpinnerEntry(
                title = encoderName,
                summary = type.ifBlank { null },
            )
        }
    }

    // TODO: handle custom value
    // TODO: handle empty input
    var turnScreenOff by scrcpyOptions.turnScreenOff.asMutableState()
    var control by scrcpyOptions.control.asMutableState()
    var video by scrcpyOptions.video.asMutableState()

    var videoSource by scrcpyOptions.videoSource.asMutableState()
    val videoSourceItems = VIDEO_SOURCE_OPTIONS.map { it.second }
    val videoSourceIndex = VIDEO_SOURCE_OPTIONS.indexOfFirst {
        it.first == videoSource
    }.let { if (it >= 0) it else 0 }
    var displayId by scrcpyOptions.displayId.asMutableState()

    var cameraId by scrcpyOptions.cameraId.asMutableState()
    var cameraFacing by scrcpyOptions.cameraFacing.asMutableState()
    val cameraFacingItems = CAMERA_FACING_OPTIONS.map { it.second }
    val cameraFacingIndex = CAMERA_FACING_OPTIONS.indexOfFirst {
        it.first == cameraFacing
    }.let { if (it >= 0) it else 0 }
    var cameraSize by scrcpyOptions.cameraSize.asMutableState()
    val cameraSizeIndex = when (cameraSize) {
        "custom" -> cameraSizeOptions.size + 1
        in cameraSizeOptions -> cameraSizeOptions.indexOf(cameraSize) + 1
        else -> 0
    }
    var cameraAr by scrcpyOptions.cameraAr.asMutableState()
    var cameraFps by scrcpyOptions.cameraFps.asMutableState()
    val cameraFpsPresetIndex = presetIndexFromInputForAdvancedPage(
        cameraFps, CAMERA_FPS_PRESETS
    )
    var cameraHighSpeed by scrcpyOptions.cameraHighSpeed.asMutableState()

    var audioSource by scrcpyOptions.audioSource.asMutableState()
    val audioSourceItems = AUDIO_SOURCE_OPTIONS.map { it.second }
    val audioSourceIndex = AUDIO_SOURCE_OPTIONS.indexOfFirst {
        it.first == audioSource
    }.let { if (it >= 0) it else 0 }
    var audioDup by scrcpyOptions.audioDup.asMutableState()
    var audioPlayback by scrcpyOptions.audioPlayback.asMutableState()
    var requireAudio by scrcpyOptions.requireAudio.asMutableState()

    var maxSize by scrcpyOptions.maxSize.asMutableState()
    val maxSizePresetIndex = presetIndexFromInputForAdvancedPage(
        maxSize.toString(), ScrcpyPresets.MaxSize
    )
    var maxFps by scrcpyOptions.maxFps.asMutableState()
    val maxFpsPresetIndex = presetIndexFromInputForAdvancedPage(
        maxFps, ScrcpyPresets.MaxFPS
    )

    var videoEncoder by scrcpyOptions.videoEncoder.asMutableState()
    var videoCodecOptions by scrcpyOptions.videoCodecOptions.asMutableState()
    var audioEncoder by scrcpyOptions.audioEncoder.asMutableState()
    var audioCodecOptions by scrcpyOptions.audioCodecOptions.asMutableState()

    var newDisplayWidth by remember {
        mutableStateOf("")
    }
    var newDisplayHeight by remember {
        mutableStateOf("")
    }
    var newDisplayDpi by remember {
        mutableStateOf("")
    }
    // [<width>x<height>][/<dpi>]
    // TODO: 填充当前值到输入框
    var newDisplay by scrcpyOptions.newDisplay.asMutableState()
    fun updateNewDisplay() {
        var nd = ""
        if (newDisplayWidth.isNotBlank() && newDisplayHeight.isNotBlank()) {
            nd += "${newDisplayWidth}x${newDisplayHeight}"
        }
        if (newDisplayDpi.isNotBlank()) {
            nd += "/$newDisplayDpi"
        }
        newDisplay = nd
    }

    var cropWidth by remember {
        mutableStateOf("")
    }
    var cropHeight by remember {
        mutableStateOf("")
    }
    var cropX by remember {
        mutableStateOf("")
    }
    var cropY by remember {
        mutableStateOf("")
    }
    // width:height:x:y
    // TODO: 填充当前值到输入框
    var crop by scrcpyOptions.crop.asMutableState()
    fun updateCrop(): Unit {
        if (cropWidth.isNotBlank()
            && cropHeight.isNotBlank()
            && cropX.isNotBlank()
            && cropY.isNotBlank()
        ) crop = "$cropWidth:$cropHeight:$cropX:$cropY"
    }


    // 高级参数
    AppPageLazyColumn(
        contentPadding = contentPadding,
        scrollBehavior = scrollBehavior,
    ) {
        item {
            Card {
                SuperSwitch(
                    title = "启动后关闭屏幕",
                    summary = "--turn-screen-off",
                    checked = turnScreenOff,
                    onCheckedChange = {
                        turnScreenOff = it
                        if (it) scope.launch {
                            // github.com/Genymobile/scrcpy/issues/3376
                            // github.com/Genymobile/scrcpy/issues/4587
                            // github.com/Genymobile/scrcpy/issues/5676
                            snackbarHostState.showSnackbar("注意：大部分设备在关闭屏幕后刷新率会降低/减半")
                        }
                    },
                )
                SuperSwitch(
                    title = "禁用控制",
                    summary = "--no-control",
                    checked = !control,
                    onCheckedChange = { control = !it },
                )
                SuperSwitch(
                    title = "禁用视频",
                    summary = "--no-video",
                    checked = !video,
                    onCheckedChange = { video = !it },
                )
            }
        }

        item {
            Card {
                SuperDropdown(
                    title = "视频来源",
                    summary = "--video-source",
                    items = videoSourceItems,
                    selectedIndex = videoSourceIndex,
                    onSelectedIndexChange = {
                        videoSource = VIDEO_SOURCE_OPTIONS[it].first
                    },
                )
                if (videoSource == "display") {
                    TextField(
                        value = displayId.toString(),
                        onValueChange = { displayId = it.toInt() },
                        label = "--display-id",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiSpacing.CardContent)
                            .padding(bottom = UiSpacing.CardContent),
                    )
                }
                if (videoSource == "camera") {
                    TextField(
                        value = cameraId,
                        onValueChange = { cameraId = it },
                        label = "--camera-id",
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiSpacing.CardContent)
                            .padding(bottom = UiSpacing.CardContent),
                    )
                    SuperArrow(
                        title = "重新获取 Camera Sizes",
                        summary = "--list-camera-sizes",
                        onClick = onRefreshCameraSizes,
                    )
                    SuperDropdown(
                        title = "摄像头朝向",
                        summary = "--camera-facing",
                        items = cameraFacingItems,
                        selectedIndex = cameraFacingIndex,
                        onSelectedIndexChange = {
                            cameraFacing = CAMERA_FACING_OPTIONS[it].first
                        },
                    )
                    SuperDropdown(
                        title = "摄像头分辨率",
                        summary = "--camera-size",
                        items = cameraSizeDropdownItems,
                        selectedIndex = cameraSizeIndex.coerceIn(
                            0, (cameraSizeDropdownItems.size - 1).coerceAtLeast(0)
                        ),
                        onSelectedIndexChange = {
                            cameraSize = when (it) {
                                0 -> ""
                                cameraSizeDropdownItems.lastIndex -> "custom"
                                else -> cameraSizeDropdownItems[it]
                            }
                        },
                    )
                    if (cameraSize == "custom") {
                        TextField(
                            value = cameraSize,
                            onValueChange = { cameraSize = it },
                            label = "--camera-size",
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = UiSpacing.CardContent)
                                .padding(bottom = UiSpacing.CardContent),
                        )
                    }
                    TextField(
                        value = cameraAr,
                        onValueChange = { cameraAr = it },
                        label = "--camera-ar",
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiSpacing.CardContent)
                            .padding(bottom = UiSpacing.CardContent),
                    )
                    SuperSlide(
                        title = "摄像头帧率",
                        summary = "--camera-fps",
                        value = cameraFpsPresetIndex.toFloat(),
                        onValueChange = { value ->
                            val idx = value.roundToInt().coerceIn(0, CAMERA_FPS_PRESETS.lastIndex)
                            cameraFps = CAMERA_FPS_PRESETS[idx]
                        },
                        valueRange = 0f..CAMERA_FPS_PRESETS.lastIndex.toFloat(),
                        steps = (CAMERA_FPS_PRESETS.size - 2).coerceAtLeast(0),
                        unit = "fps",
                        zeroStateText = "默认",
                        showUnitWhenZeroState = false,
                        showKeyPoints = true,
                        keyPoints = CAMERA_FPS_PRESETS.indices.map { it.toFloat() },
                        displayText = cameraFps.toString(),
                        inputHint = "0 或留空表示默认",
                        inputInitialValue = cameraFps.toString(),
                        inputFilter = { it.filter(Char::isDigit) },
                        inputValueRange = 0f..Float.MAX_VALUE,
                        onInputConfirm = { cameraFps = it.toInt() },
                    )
                    SuperSwitch(
                        title = "高帧率模式",
                        summary = "--camera-high-speed",
                        checked = cameraHighSpeed,
                        onCheckedChange = { cameraHighSpeed = it },
                    )
                }
            }
        }

        item {
            Card {
                SuperDropdown(
                    title = "音频来源",
                    summary = "--audio-source",
                    items = audioSourceItems,
                    selectedIndex = audioSourceIndex,
                    onSelectedIndexChange = { audioSource = AUDIO_SOURCE_OPTIONS[it].first },
                )
                if (audioSource == "custom") {
                    TextField(
                        value = audioSource,
                        onValueChange = { audioSource = it },
                        label = "--audio-source",
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiSpacing.CardContent)
                            .padding(bottom = UiSpacing.CardContent),
                    )
                }
                SuperSwitch(
                    title = "音频双路输出",
                    summary = "--audio-dup",
                    checked = audioDup,
                    onCheckedChange = { audioDup = it },
                )
                SuperSwitch(
                    title = "仅转发不播放",
                    summary = "--no-audio-playback",
                    checked = !audioPlayback,
                    onCheckedChange = { audioPlayback = !it },
                )
                SuperSwitch(
                    title = "音频失败时终止 [TODO]",
                    summary = "--require-audio",
                    checked = requireAudio,
                    onCheckedChange = { requireAudio = it },
                    enabled = false,
                )
            }
        }

        item {
            Card {
                SuperSlide(
                    title = "最大分辨率",
                    summary = "--max-size",
                    value = maxSizePresetIndex.toFloat(),
                    onValueChange = { value ->
                        val idx = value.roundToInt().coerceIn(0, ScrcpyPresets.MaxSize.lastIndex)
                        maxSize = ScrcpyPresets.MaxSize[idx]
                    },
                    valueRange = 0f..ScrcpyPresets.MaxSize.lastIndex.toFloat(),
                    steps = (ScrcpyPresets.MaxSize.size - 2).coerceAtLeast(0),
                    unit = "px",
                    zeroStateText = "关闭",
                    showUnitWhenZeroState = false,
                    showKeyPoints = true,
                    keyPoints = ScrcpyPresets.MaxSize.indices.map { it.toFloat() },
                    displayText = maxSize.toString(),
                    inputHint = "0 或留空表示关闭",
                    inputInitialValue = maxSize.toString(),
                    inputFilter = { it.filter(Char::isDigit) },
                    inputValueRange = 0f..Float.MAX_VALUE,
                    onInputConfirm = { maxSize = it.toInt() },
                )
                SuperSlide(
                    title = "最大帧率",
                    summary = "--max-fps",
                    value = maxFpsPresetIndex.toFloat(),
                    onValueChange = { value ->
                        val idx = value.roundToInt().coerceIn(0, ScrcpyPresets.MaxFPS.lastIndex)
                        maxFps = ScrcpyPresets.MaxFPS[idx].toString()
                    },
                    valueRange = 0f..ScrcpyPresets.MaxFPS.lastIndex.toFloat(),
                    steps = (ScrcpyPresets.MaxFPS.size - 2).coerceAtLeast(0),
                    unit = "fps",
                    zeroStateText = "关闭",
                    showUnitWhenZeroState = false,
                    showKeyPoints = true,
                    keyPoints = ScrcpyPresets.MaxFPS.indices.map { it.toFloat() },
                    displayText = maxFps,
                    inputHint = "0 或留空表示关闭",
                    inputInitialValue = maxFps,
                    inputFilter = { it.filter(Char::isDigit) },
                    inputValueRange = 0f..Float.MAX_VALUE,
                    onInputConfirm = { maxFps = it },
                )
            }
        }

        item {
            Card {
                SuperArrow(
                    title = "重新获取编码器列表",
                    summary = "--list-encoders",
                    onClick = onRefreshEncoders,
                )
                SuperSpinner(
                    title = "视频编码器",
                    summary = "--video-encoder",
                    items = videoEncoderEntries,
                    selectedIndex = videoEncoderIndex,
                    onSelectedIndexChange = { videoEncoder = videoEncoderEntries[it].title ?: "" },
                )
                TextField(
                    value = videoCodecOptions,
                    onValueChange = { videoCodecOptions = it },
                    label = "--video-codec-options",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiSpacing.CardContent)
                        .padding(bottom = UiSpacing.CardContent),
                )
                SuperSpinner(
                    title = "音频编码器",
                    summary = "--audio-encoder",
                    items = audioEncoderEntries,
                    selectedIndex = audioEncoderIndex,
                    onSelectedIndexChange = { audioEncoder = audioEncoderEntries[it].title ?: "" },
                )
                TextField(
                    value = audioCodecOptions,
                    onValueChange = { audioCodecOptions = it },
                    label = "--audio-codec-options",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiSpacing.CardContent)
                        .padding(bottom = UiSpacing.CardContent),
                )
            }
        }

        item {
            Card {
                Text(
                    text = "--new-display",
                    modifier = Modifier
                        .padding(horizontal = UiSpacing.CardTitle)
                        .padding(
                            top = UiSpacing.CardContent,
                            bottom = UiSpacing.FieldLabelBottom,
                        ),
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiSpacing.CardContent)
                        .padding(bottom = UiSpacing.CardContent),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
                ) {
                    TextField(
                        value = newDisplayWidth,
                        onValueChange = { newDisplayWidth = it; updateNewDisplay() },
                        label = "width",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextField(
                        value = newDisplayHeight,
                        onValueChange = { newDisplayHeight = it; updateNewDisplay() },
                        label = "height",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextField(
                        value = newDisplayDpi,
                        onValueChange = { newDisplayDpi = it; updateNewDisplay() },
                        label = "dpi",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Card {
                Text(
                    text = "--crop",
                    modifier = Modifier
                        .padding(horizontal = UiSpacing.CardTitle)
                        .padding(
                            top = UiSpacing.CardContent,
                            bottom = UiSpacing.FieldLabelBottom,
                        ),
                    fontWeight = FontWeight.Medium,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiSpacing.CardContent)
                        .padding(bottom = UiSpacing.CardContent),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
                    ) {
                        TextField(
                            value = cropWidth,
                            onValueChange = { cropWidth = it },
                            label = "width",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        TextField(
                            value = cropHeight,
                            onValueChange = { cropHeight = it },
                            label = "height",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
                    ) {
                        TextField(
                            value = cropX,
                            onValueChange = { cropX = it },
                            label = "x",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        TextField(
                            value = cropY,
                            onValueChange = { cropY = it },
                            label = "y",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // TODO: 放进 [AppPageLazyColumn] 里
        item { Spacer(Modifier.height(UiSpacing.BottomContent)) }
    }
}

private fun presetIndexFromInputForAdvancedPage(raw: Int, presets: List<Int>): Int {
    val exact = presets.indexOf(raw)
    if (exact >= 0) return exact
    val nearest = presets.withIndex().minByOrNull { (_, preset) -> kotlin.math.abs(preset - raw) }
    return nearest?.index ?: 0
}

private fun presetIndexFromInputForAdvancedPage(raw: String, presets: List<Int>): Int {
    if (raw.isBlank()) return 0
    val value = raw.toIntOrNull() ?: return 0
    val exact = presets.indexOf(value)
    if (exact >= 0) return exact
    val nearest = presets.withIndex().minByOrNull { (_, preset) -> kotlin.math.abs(preset - value) }
    return nearest?.index ?: 0
}

private fun resolveEncoderTypeLabel(raw: String?): String {
    return when (raw?.trim()?.lowercase()) {
        "hw" -> "hw"
        "sw" -> "sw"
        else -> ""
    }
}
