package br.com.nexotools.domain

import kotlinx.coroutines.flow.Flow
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(value: Double) = Vec3(x * value, y * value, z * value)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun norm() = sqrt(dot(this))
    fun normalized(): Vec3 = if (norm() > 1e-9) this * (1.0 / norm()) else ZERO
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONES = Vec3(1.0, 1.0, 1.0)
        val UP = Vec3(0.0, 0.0, 1.0)
    }
}

data class Calibration(
    val offset: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3.ONES,
    val gyroBias: Vec3 = Vec3.ZERO,
    /** Reference in the natural device coordinates, before display rotation. */
    val reference: Vec3? = null,
) {
    fun correct(value: Vec3) = Vec3(
        (value.x - offset.x) * scale.x,
        (value.y - offset.y) * scale.y,
        (value.z - offset.z) * scale.z,
    )
}

data class LevelReading(
    val xDegrees: Double,
    val yDegrees: Double,
    val planeDegrees: Double,
    val stable: Boolean,
    val estimatedError: Double?,
    val source: String,
    /** Corrected gravity in natural coordinates; use this value for relative zero. */
    val gravity: Vec3,
)

data class CalibrationSample(val acceleration: Vec3, val gyro: Vec3?, val stable: Boolean)

interface OrientationSource {
    fun readings(calibration: Calibration = Calibration(), rotation: Int = 0): Flow<LevelReading>
    fun calibrationSamples(): Flow<CalibrationSample>
}

data class AudioReading(val dbFs: Double, val spl: Double?, val clipped: Boolean)

interface AudioSource {
    fun readings(offsetDb: Double? = null): Flow<AudioReading>
}

class SourceUnavailableException(message: String) : IllegalStateException(message)
