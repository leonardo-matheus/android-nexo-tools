package br.com.nexotools.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/** Display rounding only; sensor, calibration and stored parameters retain full precision. */
object AngleDisplay {
    fun dispersion(value: Double?): String = when {
        value == null || !value.isFinite() || value < 0 -> "—"
        value < 1 -> "<1°"
        else -> format(value, signed = false)
    }
    fun format(value: Double?, signed: Boolean = true): String {
        if (value == null || !value.isFinite()) return "—"
        val magnitude = abs(value).roundToInt()
        if (magnitude == 0) return "0°"
        return when {
            value < 0 -> "-$magnitude°"
            signed -> "+$magnitude°"
            else -> "$magnitude°"
        }
    }
}
