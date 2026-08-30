package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import android.os.Parcelable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize

/**
 * Stores named tunnel devices for the TCP/QUIC tunnel quick-switch feature.
 * Each device holds host/port/pre-shared-key; the "active" device is the
 * one currently selected, and its config is mirrored into AppSettings
 * tunnel fields when switched.
 */
class TunnelDevicesStore(context: Context): Settings(context, "TunnelDevices") {
    companion object {
        val TUNNEL_DEVICES_LIST = Pair(
            stringPreferencesKey("tunnel_devices_list"),
            "",
        )
        val TUNNEL_DEVICE_SELECTED_ID = Pair(
            stringPreferencesKey("tunnel_device_selected_id"),
            "",
        )
    }

    val tunnelDevicesList by setting(TUNNEL_DEVICES_LIST)
    val tunnelDeviceSelectedId by setting(TUNNEL_DEVICE_SELECTED_ID)

    @Parcelize
    data class Bundle(
        val tunnelDevicesList: String,
        val tunnelDeviceSelectedId: String,
    ): Parcelable {
    }

    private val bundleFields = arrayOf<BundleField<Bundle>>(
        bundleField(TUNNEL_DEVICES_LIST) { it.tunnelDevicesList },
        bundleField(TUNNEL_DEVICE_SELECTED_ID) { it.tunnelDeviceSelectedId },
    )

    val bundleState: StateFlow<Bundle> = createBundleState(::bundleFromPreferences)

    private fun bundleFromPreferences(preferences: Preferences) = Bundle(
        tunnelDevicesList = preferences.read(TUNNEL_DEVICES_LIST),
        tunnelDeviceSelectedId = preferences.read(TUNNEL_DEVICE_SELECTED_ID),
    )

    suspend fun loadBundle() = loadBundle(::bundleFromPreferences)

    suspend fun saveBundle(new: Bundle) = saveBundle(bundleState.value, new, bundleFields)

    suspend fun updateBundle(transform: (Bundle) -> Bundle) {
        saveBundle(transform(bundleState.value))
    }
}
