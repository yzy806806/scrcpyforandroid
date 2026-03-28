package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey

class QuickDevices(context: Context) : Settings(context, "QuickDevices") {
    companion object {
        val QUICK_DEVICES_LIST = Pair(
            stringPreferencesKey("quick_devices_list"),
            "",
        )
        val QUICK_CONNECT_INPUT = Pair(
            stringPreferencesKey("quick_connect_input"),
            "",
        )
    }

    val quickDevicesList by setting(QUICK_DEVICES_LIST)
    val quickConnectInput by setting(QUICK_CONNECT_INPUT)
}
