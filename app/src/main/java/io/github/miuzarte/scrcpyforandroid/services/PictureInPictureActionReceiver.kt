package io.github.miuzarte.scrcpyforandroid.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.runBlocking

// MIUI 不进
class PictureInPictureActionReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_SCRCPY) return
        val pendingResult = goAsync()
        Thread {
            try {
                val appContext = context.applicationContext
                Storage.init(appContext)
                AppRuntime.init(appContext)
                runBlocking {
                    AppRuntime.scrcpy?.stop()
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_STOP_SCRCPY =
            "io.github.miuzarte.scrcpyforandroid.action.STOP_SCRCPY_FROM_PIP"
    }
}
