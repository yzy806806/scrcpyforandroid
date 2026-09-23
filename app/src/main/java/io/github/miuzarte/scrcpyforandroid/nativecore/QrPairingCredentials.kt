package io.github.miuzarte.scrcpyforandroid.nativecore

import java.security.SecureRandom

/**
 * Credentials for the ADB "pair device with QR code" flow.
 *
 * The phone parses them out of [payload], starts a pairing server with [password] and
 * advertises it over mDNS under exactly [serviceName]. This side then has to find the
 * `_adb-tls-pairing._tcp` service whose instance name equals [serviceName] and pair
 * with [password], so both values must be generated here.
 */
internal data class QrPairingCredentials(
    val serviceName: String,
    val password: String,
) {
    /**
     * ZXing "WIFI:" payload understood by `AdbQrCode` in Settings:
     * `WIFI:T:ADB;S:myname;P:mypassword;;`. Values are backslash-escaped the same way
     * AOSP expects, even though [generate] never produces characters that need it.
     */
    val payload: String get() = "WIFI:T:ADB;S:${serviceName.escaped()};P:${password.escaped()};;"

    companion object {
        /**
         * Instance names have to stay valid DNS-SD labels, and a collision makes mDNS
         * rename the service (e.g. `xxx (2)`), which would break exact name matching.
         */
        private const val NAME_PREFIX = "adb-"
        private const val NAME_LENGTH = 10
        private const val NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** AOSP only requires a non-empty password; digits avoid any escaping. */
        private const val PASSWORD_LENGTH = 10

        fun generate(random: SecureRandom = SecureRandom()): QrPairingCredentials {
            val name = buildString(NAME_PREFIX.length + NAME_LENGTH) {
                append(NAME_PREFIX)
                repeat(NAME_LENGTH) {
                    append(NAME_ALPHABET[random.nextInt(NAME_ALPHABET.length)])
                }
            }
            val password = buildString(PASSWORD_LENGTH) {
                repeat(PASSWORD_LENGTH) {
                    append('0' + random.nextInt(10))
                }
            }
            return QrPairingCredentials(serviceName = name, password = password)
        }
    }
}

/** Escapes the characters AOSP's QR parser treats as delimiters or escape markers. */
private fun String.escaped(): String = buildString(length) {
    for (ch in this@escaped) {
        if (ch == '\\' || ch == ';' || ch == ',' || ch == ':') append('\\')
        append(ch)
    }
}
