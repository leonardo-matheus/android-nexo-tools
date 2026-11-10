package br.com.nexotools.domain

import kotlin.math.*

object AudioMath {
    /** PCM16 full scale = 32768; digital silence uses a -120 dBFS floor. No frequency weighting. */
    fun reading(samples: ShortArray, count: Int = samples.size, offsetDb: Double? = null): AudioReading {
        require(count in 1..samples.size)
        var energy = 0.0
        var clipped = false
        for (i in 0 until count) {
            val value = samples[i].toDouble() / 32768.0
            energy += value * value
            if (abs(samples[i].toInt()) >= 32760) clipped = true
        }
        val rms = sqrt(energy / count)
        val dbFs = (20.0 * log10(rms.coerceAtLeast(1e-6))).coerceIn(-120.0, 0.0)
        return AudioReading(dbFs, offsetDb?.takeIf { it.isFinite() }?.let { dbFs + it }, clipped)
    }

    /** Sound energy average; averaging logarithmic dB values underestimates varying signals. */
    fun energyMean(decibels: List<Double>): Double? = if (decibels.isEmpty()) null else
        10.0 * log10(decibels.sumOf { 10.0.pow(it / 10.0) } / decibels.size)

    fun referenceOffset(referenceSpl: Double, measuredDbFs: Double): Double {
        require(referenceSpl.isFinite() && referenceSpl in 20.0..140.0)
        require(measuredDbFs.isFinite() && measuredDbFs > -100.0 && measuredDbFs < -1.0) { "Referência silenciosa ou saturada" }
        return referenceSpl - measuredDbFs
    }
}
