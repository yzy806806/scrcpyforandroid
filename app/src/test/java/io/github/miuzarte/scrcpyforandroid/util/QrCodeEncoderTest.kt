package io.github.miuzarte.scrcpyforandroid.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.miuzarte.scrcpyforandroid.nativecore.QrPairingCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes the modules produced by [QrCodeEncoder] with an independent QR decoder
 * (ZXing, test scope only) to verify the vendored generator is wired up correctly.
 */
class QrCodeEncoderTest {

    @Test
    fun generatedCredentialsDecode() {
        assertDecodesTo(QrPairingCredentials.generate().payload)
    }

    /** The renderer draws a shorter border than the spec asks for; make sure it decodes. */
    @Test
    fun renderedQuietZoneDecodes() {
        val payload = QrPairingCredentials.generate().payload
        assertEquals(payload, decode(QrCodeEncoder.encode(payload), QrCodeEncoder.RENDER_QUIET_ZONE))
    }

    /** The decoding round-trips above are blind to the payload format, so pin it down. */
    @Test
    fun payloadUsesAdbQrFormat() {
        val credentials = QrPairingCredentials("adb-abc123", "0123456789")
        assertEquals("WIFI:T:ADB;S:adb-abc123;P:0123456789;;", credentials.payload)
        // Characters AOSP treats as delimiters must be escaped
        assertEquals(
            "WIFI:T:ADB;S:a\\;b;P:c\\:d;;",
            QrPairingCredentials("a;b", "c:d").payload,
        )
    }

    @Test
    fun shortAndLongPayloadsDecode() {
        assertDecodesTo("WIFI:T:ADB;S:adb-a;P:1;;")
        assertDecodesTo("WIFI:T:ADB;S:adb-0123456789;P:0123456789;;")
        assertDecodesTo("WIFI:T:ADB;S:" + "a".repeat(40) + ";P:" + "9".repeat(20) + ";;")
    }

    @Test
    fun generatedCredentialsStayWithinCompactVersions() {
        // 42 bytes at error correction level M fit in version 3/4 (29/33 modules);
        // a larger symbol would mean the ECC level or the payload changed.
        val size = QrCodeEncoder.encode(QrPairingCredentials.generate().payload).size
        assertTrue("unexpected symbol size: $size", size in 21..33)
    }

    @Test
    fun credentialsUseSafeCharsets() {
        repeat(50) {
            val credentials = QrPairingCredentials.generate()
            assertTrue(credentials.serviceName.matches(Regex("adb-[a-z0-9]{10}")))
            assertTrue(credentials.password.matches(Regex("[0-9]{10}")))
        }
    }

    private fun assertDecodesTo(payload: String) {
        assertEquals(payload, decode(QrCodeEncoder.encode(payload), QrCodeEncoder.SPEC_QUIET_ZONE))
    }

    private fun decode(matrix: QrMatrix, quiet: Int): String {
        val side = matrix.size + quiet * 2
        // Upscale: one pixel per module is below what the ZXing detector expects.
        val scale = SCALE
        val width = side * scale
        val pixels = IntArray(width * width)
        for (y in 0 until width) {
            for (x in 0 until width) {
                val moduleX = x / scale - quiet
                val moduleY = y / scale - quiet
                val dark = moduleX in 0 until matrix.size &&
                        moduleY in 0 until matrix.size &&
                        matrix[moduleX, moduleY]
                pixels[y * width + x] = if (dark) DARK else LIGHT
            }
        }
        val source = RGBLuminanceSource(width, width, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private companion object {
        const val DARK = -0x1000000 // 0xFF000000
        const val LIGHT = -0x1 // 0xFFFFFFFF
        const val SCALE = 8
    }
}
