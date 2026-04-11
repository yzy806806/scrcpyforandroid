package io.github.miuzarte.scrcpyforandroid

import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.PictureInPictureParamsCompat
import androidx.core.content.ContextCompat
import androidx.core.pip.BasicPictureInPicture
import io.github.miuzarte.scrcpyforandroid.pages.StreamScreen
import io.github.miuzarte.scrcpyforandroid.services.PictureInPictureActionReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StreamActivity : ComponentActivity() {
    private val basicPip = BasicPictureInPicture(this)
    private val pipActionReceiver = PictureInPictureActionReceiver()
    private var isPipActionReceiverRegistered = false

    // 是否处于 pip
    // 回到全屏时会因重建而变回初始值
    private val _pipModeState = MutableStateFlow(false)
    val pipModeState: StateFlow<Boolean> = _pipModeState

    val pipStopAction: RemoteAction by lazy {
        RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "停止投屏",
            "停止投屏",
            PictureInPictureActionReceiver.createPendingIntent(this),
        )
    }

    // 每次 进出全屏/进出画中画
    // 都会重建 activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerPipActionReceiver()

        // 声明要画中画
        basicPip.setEnabled(true)

        setContent {
            StreamScreen(activity = this)
        }

        /*
        // 可能以后有用
        basicPip.addOnPictureInPictureEventListener(
            executor = mainExecutor,
            listener = object : PictureInPictureDelegate.OnPictureInPictureEventListener {
                override fun onPictureInPictureEvent(
                    event: PictureInPictureDelegate.Event,
                    config: Configuration?,
                ) {
                    // MIUI 只有这些事件
                    when (event) {
                        PictureInPictureDelegate.Event.ENTER_ANIMATION_START -> {}
                        PictureInPictureDelegate.Event.ENTER_ANIMATION_END -> {}

                        PictureInPictureDelegate.Event.STASHED -> {}
                        PictureInPictureDelegate.Event.UNSTASHED -> {}

                        // 收不到
                        // PictureInPictureDelegate.Event.ENTERED -> {}
                        // PictureInPictureDelegate.Event.EXITED -> {}
                    }
                }
            }
        )
         */
    }

    fun configurePip(params: PictureInPictureParamsCompat) {
        basicPip.setPictureInPictureParams(params)
    }

    override fun onDestroy() {
        unregisterPipActionReceiver()
        super.onDestroy()
    }

    /*
    // 回到全屏也会停止, 暂时不做
    override fun onDestroy() {
        super.onDestroy()

        if (_pipModeState.value) {
            Thread {
                runBlocking {
                    AppRuntime.scrcpy?.stop()
                }
            }.start()
        }
    }
     */

    //- onPictureInPictureModeChanged
    //+ onPictureInPictureUiStateChanged
    //- onUserLeaveHint

    // @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)

        _pipModeState.value = true

        /*
        when {
            // 进入画中画
            pipState.isTransitioningToPip -> {}
            // 收进边缘
            pipState.isStashed -> {}
        }
         */
    }

    private fun registerPipActionReceiver() {
        if (isPipActionReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            PictureInPictureActionReceiver.createIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isPipActionReceiverRegistered = true
    }

    private fun unregisterPipActionReceiver() {
        if (!isPipActionReceiverRegistered) return
        unregisterReceiver(pipActionReceiver)
        isPipActionReceiverRegistered = false
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, StreamActivity::class.java)
        }
    }
}
