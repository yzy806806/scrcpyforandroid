package io.github.miuzarte.scrcpyforandroid.storage

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey

class AdbClientData(context: Context) : Settings(context, "AdbClient") {
    companion object {
        val RSA_PRIVATE_KEY = Pair(
            stringPreferencesKey("rsa_private_key"),
            "",
        )
        val RSA_PUBLIC_KEY_X509 = Pair(
            stringPreferencesKey("rsa_public_key_x509"),
            "",
        )
    }

    val rsaPrivateKey by setting(RSA_PRIVATE_KEY)
    val rsaPublicKeyX509 by setting(RSA_PUBLIC_KEY_X509)
}
