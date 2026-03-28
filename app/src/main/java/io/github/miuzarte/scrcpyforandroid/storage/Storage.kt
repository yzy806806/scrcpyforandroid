package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context

// settings singleton
object Storage {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val appSettings: AppSettings by lazy { AppSettings(appContext) }
    val quickDevices: QuickDevices by lazy { QuickDevices(appContext) }
    val scrcpyOptions: ScrcpyOptions by lazy { ScrcpyOptions(appContext) }
    val adbClientData: AdbClientData by lazy { AdbClientData(appContext) }
}
