package io.github.miuzarte.scrcpyforandroid

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.PictureInPictureParamsCompat
import androidx.core.pip.BasicPictureInPicture
import androidx.core.pip.PictureInPictureDelegate
import io.github.miuzarte.scrcpyforandroid.pages.StreamScreen
import io.github.miuzarte.scrcpyforandroid.services.PictureInPictureActionReceiver
import io.github.miuzarte.scrcpyforandroid.widgets.VideoOutputTarget
import io.github.miuzarte.scrcpyforandroid.widgets.VideoOutputTargetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StreamActivity : ComponentActivity() {
    private lateinit var basicPip: BasicPictureInPicture // = this

    // pip 是否已被配置、允许进入
    private var pipConfigured: Boolean = false
    private var pipParams = PictureInPictureParamsCompat
        .Builder()
        .setEnabled(false)
        .build()

    // 是否处于 PiP
    // MIUI 上进入 PiP 时，动画事件比 onPictureInPictureModeChanged() 更稳定，
    // 所以进入 PiP 直接由动画事件置为 true
    private val _pipModeState = MutableStateFlow(false)
    val pipModeState: StateFlow<Boolean> = _pipModeState

    val pipStopAction: RemoteAction by lazy {
        val intent = Intent(this, PictureInPictureActionReceiver::class.java).apply {
            action = PictureInPictureActionReceiver.ACTION_STOP_SCRCPY
            `package` = packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "停止投屏",
            "停止投屏",
            pendingIntent,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        basicPip = BasicPictureInPicture(this)
        basicPip.addOnPictureInPictureEventListener(
            executor = mainExecutor,
            listener = object : PictureInPictureDelegate.OnPictureInPictureEventListener {
                override fun onPictureInPictureEvent(
                    event: PictureInPictureDelegate.Event,
                    newConfig: Configuration?,
                ) {
                    // HyperOS 3 下稳定收到的是进入动画事件
                    // 这里直接把进入动画开始视为已经进入 PiP，尽量少依赖平台回调
                    when (event) {
                        PictureInPictureDelegate.Event.ENTER_ANIMATION_START -> {
                            _pipModeState.value = true
                            VideoOutputTargetState.set(VideoOutputTarget.PICTURE_IN_PICTURE)
                        }

                        PictureInPictureDelegate.Event.ENTER_ANIMATION_END -> {
                            _pipModeState.value = true
                        }

                        PictureInPictureDelegate.Event.STASHED -> {}
                        PictureInPictureDelegate.Event.UNSTASHED -> {}

                        // 收不到
                        // PictureInPictureDelegate.Event.ENTERED -> {}
                        // PictureInPictureDelegate.Event.EXITED -> {}
                    }
                }
            }
        )

        setContent {
            StreamScreen(activity = this)
        }
    }

    fun configurePictureInPicture(
        enabled: Boolean,
        params: PictureInPictureParamsCompat,
    ) {
        // 由 StreamScreen 决定何时更新 PiP 参数；
        // StreamActivity 这里只负责缓存并转发最新配置
        pipConfigured = enabled
        pipParams = params
        basicPip
            .setEnabled(enabled)
            .setPictureInPictureParams(params)
    }

    fun enterConfiguredPip(): Boolean {
        // onUserLeaveHint() 可能发生在 PiP 还没准备好之前，
        // 所以这里先做一次兜底判断
        if (!pipConfigured) return false
        return runCatching {
            enterPictureInPictureMode(pipParams)
            true
        }.getOrElse {
            VideoOutputTargetState.set(VideoOutputTarget.FULLSCREEN)
            false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        enterConfiguredPip()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 回到全屏也会停止
        /*
        if (_pipModeState.value) {
            Thread {
                runBlocking {
                    AppRuntime.scrcpy?.stop()
                }
            }.start()
        }
         */
    }

    // MIUI 不进
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (!isInPictureInPictureMode) {
            _pipModeState.value = false
            VideoOutputTargetState.set(VideoOutputTarget.FULLSCREEN)
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, StreamActivity::class.java)
        }
    }
}
