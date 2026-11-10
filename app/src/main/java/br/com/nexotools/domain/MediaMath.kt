package br.com.nexotools.domain

import kotlin.math.pow
import kotlin.math.roundToInt

object AudioGainMath {
    const val MAX_DB = 12f
    fun clampDb(value: Float): Float = if (value.isFinite()) (value.coerceIn(0f, MAX_DB) * 2).roundToInt() / 2f else 0f
    fun milliBels(value: Float): Int = (clampDb(value) * 100).roundToInt()
    /** Percentage refers to amplitude multiplier within this player, not the system volume. */
    fun amplitudePercent(value: Float): Double = 100.0 * 10.0.pow(clampDb(value) / 20.0)
}

enum class NightTint(val red: Int, val green: Int, val blue: Int) {
    GREEN(0x99, 0xD9, 0x8C),
    AMBER(0xD9, 0xED, 0x92),
}

data class LuminanceFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropWidth: Int = width,
    val cropHeight: Int = height,
    val rotationDegrees: Int = 0,
    val mirrored: Boolean = false,
)
data class ProcessedLuminance(val pixels: IntArray, val width: Int, val height: Int)

object NightLuminanceMath {
    fun clampBoost(value: Float) = if (value.isFinite()) value.coerceIn(1f, 4f) else 1f

    /** Gamma changes recorded luminance. It cannot recover photons or detail absent in the frame. */
    fun lookup(boost: Float, tint: NightTint): IntArray {
        val gamma = 1.0 / clampBoost(boost)
        return IntArray(256) { y ->
            val luminance = (y / 255.0).pow(gamma)
            val r = (luminance * tint.red).roundToInt()
            val g = (luminance * tint.green).roundToInt()
            val b = (luminance * tint.blue).roundToInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    fun render(frame: LuminanceFrame, lookup: IntArray): ProcessedLuminance {
        require(lookup.size == 256)
        require(frame.width > 0 && frame.height > 0 && maxOf(frame.width, frame.height) <= 640 && frame.width.toLong() * frame.height <= 640 * 480)
        require(frame.pixelStride > 0 && frame.rowStride >= (frame.width - 1) * frame.pixelStride + 1)
        require(frame.cropLeft >= 0 && frame.cropTop >= 0 && frame.cropWidth > 0 && frame.cropHeight > 0)
        require(frame.cropLeft + frame.cropWidth <= frame.width && frame.cropTop + frame.cropHeight <= frame.height)
        require(frame.rotationDegrees in setOf(0, 90, 180, 270))
        val lastIndex = (frame.cropTop + frame.cropHeight - 1).toLong() * frame.rowStride +
            (frame.cropLeft + frame.cropWidth - 1).toLong() * frame.pixelStride
        require(lastIndex < frame.data.size) { "Plano Y incompleto" }
        val w = frame.cropWidth
        val h = frame.cropHeight
        val outputWidth = if (frame.rotationDegrees % 180 == 0) w else h
        val outputHeight = if (frame.rotationDegrees % 180 == 0) h else w
        val pixels = IntArray(outputWidth * outputHeight)
        for (y in 0 until h) for (x in 0 until w) {
            val index = (frame.cropTop + y) * frame.rowStride + (frame.cropLeft + x) * frame.pixelStride
            val value = lookup[frame.data[index].toInt() and 0xFF]
            val rotatedX: Int
            val rotatedY: Int
            when (frame.rotationDegrees) {
                90 -> { rotatedX = h - 1 - y; rotatedY = x }
                180 -> { rotatedX = w - 1 - x; rotatedY = h - 1 - y }
                270 -> { rotatedX = y; rotatedY = w - 1 - x }
                else -> { rotatedX = x; rotatedY = y }
            }
            val outputX = if (frame.mirrored) outputWidth - 1 - rotatedX else rotatedX
            pixels[rotatedY * outputWidth + outputX] = value
        }
        return ProcessedLuminance(pixels, outputWidth, outputHeight)
    }
}
