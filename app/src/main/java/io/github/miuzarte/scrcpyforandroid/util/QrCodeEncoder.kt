package io.github.miuzarte.scrcpyforandroid.util

import io.nayuki.qrcodegen.QrCode
import io.nayuki.qrcodegen.QrSegment

/**
 * Thin wrapper around the vendored Nayuki QR Code generator (`io.nayuki.qrcodegen`,
 * MIT License, upstream master @ 3c6d0b3cefb4e049dc337e82237c9644399716a8).
 *
 * Only byte mode at error correction level M is used here, which is plenty for the
 * short ASCII payloads of the ADB pairing QR code. The module grid is returned as a
 * flat [QrMatrix] so the UI layer can draw it without depending on the generator
 * types. `QrSegmentAdvanced` (kanji / optimal segmentation) is intentionally not
 * vendored.
 *
 * Upstream also ships a `java-fast` variant (package `io.nayuki.fastqrcodegen`).
 * It is deliberately not used: with automatic mask selection it is only ~1.7x faster
 * (191 us -> 108 us for a version 3 / level M symbol, per the author's own benchmark),
 * it needs three more vendored classes on top of these four (including a soft-reference
 * cache that buys nothing for a one-shot encode), and the author describes it as an
 * experimental showcase that is not meant to be used widely. Callers encode once per
 * credential set, so there is no hot path to optimize here.
 */
object QrCodeEncoder {

    /** Quiet zone required around the symbol by the QR spec, in modules. */
    const val SPEC_QUIET_ZONE = 4

    /**
     * Quiet zone actually drawn by the renderer, in modules. Deliberately below
     * [SPEC_QUIET_ZONE] so the white card matches the dialog's inside margin; raise it
     * back to 3-4 if on-device scanning ever turns out unreliable.
     */
    const val RENDER_QUIET_ZONE = 2

    /**
     * Encode [text] into a QR symbol. The smallest version that fits at error
     * correction level M is chosen (no ECC boosting), so the result is deterministic.
     */
    fun encode(text: String): QrMatrix {
        val segment = QrSegment.makeBytes(text.toByteArray(Charsets.UTF_8))
        val code = QrCode.encodeSegments(
            listOf(segment),
            QrCode.Ecc.MEDIUM,
            MIN_VERSION,
            MAX_VERSION,
            MASK_AUTO,
            BOOST_ECC,
        )
        val size = code.size
        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                modules[y * size + x] = code.getModule(x, y)
            }
        }
        return QrMatrix(size, modules)
    }

    private const val MIN_VERSION = 1
    private const val MAX_VERSION = 40
    private const val MASK_AUTO = -1
    private const val BOOST_ECC = false
}

/**
 * Square module grid of a QR symbol. [size] excludes the quiet zone, which the
 * renderer has to add itself.
 */
class QrMatrix(
    val size: Int,
    private val modules: BooleanArray,
) {
    /** True when the module at ([x], [y]) is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}
