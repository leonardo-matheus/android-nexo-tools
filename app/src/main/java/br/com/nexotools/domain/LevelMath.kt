package br.com.nexotools.domain

import kotlin.math.*

object LevelMath {
    const val STANDARD_GRAVITY = 9.80665

    /** Android convention: pitch about screen X, roll about screen Y; azimuth is unavailable. */
    fun attitude(gravity: Vec3, reference: Vec3? = null, rotation: Int = 0): Attitude {
        require(gravity.isFinite() && gravity.norm() > 1e-6)
        val screen = remap(relative(gravity, reference), rotation)
        return Attitude(
            Math.toDegrees(atan2(-screen.y, hypot(screen.x, screen.z))),
            Math.toDegrees(atan2(screen.x, screen.z)),
        )
    }

    /** Screen right, screen top, and perpendicular to the screen. Surface rotation constants 0..3. */
    fun remap(value: Vec3, rotation: Int): Vec3 = when (rotation) {
        1 -> Vec3(-value.y, value.x, value.z)
        2 -> Vec3(-value.x, -value.y, value.z)
        3 -> Vec3(value.y, -value.x, value.z)
        else -> value
    }

    /** Rodrigues shortest rotation maps reference to +Z. This is relative tilt, never yaw. */
    fun relative(gravity: Vec3, reference: Vec3?): Vec3 {
        if (reference == null || reference.norm() < 1e-6) return gravity
        val from = reference.normalized()
        val cosine = from.dot(Vec3.UP).coerceIn(-1.0, 1.0)
        if (cosine > 1.0 - 1e-9) return gravity
        if (cosine < -1.0 + 1e-9) return Vec3(gravity.x, -gravity.y, -gravity.z)
        val cross = from.cross(Vec3.UP)
        return gravity + cross.cross(gravity) + cross.cross(cross.cross(gravity)) * (1.0 / (1.0 + cosine))
    }

    fun reading(
        gravity: Vec3,
        reference: Vec3? = null,
        rotation: Int = 0,
        stable: Boolean = false,
        estimatedError: Double? = null,
        source: String = "Acelerômetro",
    ): LevelReading {
        require(gravity.isFinite() && gravity.norm() > 1e-6) { "Vetor gravitacional inválido" }
        val screen = remap(relative(gravity, reference), rotation).normalized()
        // Axis inclination is asin of the normalized component: valid through vertical orientations.
        val x = Math.toDegrees(asin(screen.x.coerceIn(-1.0, 1.0)))
        val y = Math.toDegrees(asin(screen.y.coerceIn(-1.0, 1.0)))
        // A plane is horizontal both face up and face down. Do not label this quantity Z yaw.
        val total = Math.toDegrees(acos(abs(screen.z).coerceIn(0.0, 1.0)))
        return LevelReading(x, y, total, stable, estimatedError, source, gravity)
    }
}

data class Attitude(val pitchDegrees: Double, val rollDegrees: Double)

/** Time-based low-pass avoids changing the cut-off when the sensor sample rate varies. */
class VectorLowPass(private val timeConstantSeconds: Double = 0.25) {
    private var lastTimestamp = 0L
    private var state: Vec3? = null
    fun update(value: Vec3, timestampNanos: Long): Vec3 {
        require(value.isFinite())
        val previous = state
        val dt = if (lastTimestamp == 0L) 0.0 else ((timestampNanos - lastTimestamp) / 1e9).coerceIn(0.0, 1.0)
        val next = if (previous == null) value else previous + (value - previous) * (1.0 - exp(-dt / timeConstantSeconds))
        if (timestampNanos >= lastTimestamp) lastTimestamp = timestampNanos
        state = next
        return next
    }
}

/** Gravity complementary filter. Gyroscope prediction uses inverse device rotation. */
class GravityFusion(private val correctionTimeSeconds: Double = 0.55) {
    private var gravity: Vec3? = null
    private var lastGyro = 0L
    private var lastAcceleration = 0L

    fun onGyroscope(angularVelocity: Vec3, timestampNanos: Long): Vec3? {
        val current = gravity
        val dt = if (lastGyro == 0L) 0.0 else (timestampNanos - lastGyro) / 1e9
        if (timestampNanos > lastGyro) lastGyro = timestampNanos
        if (current != null && dt > 0.0 && dt < 0.2) {
            val speed = angularVelocity.norm()
            if (speed > 1e-9) {
                val axis = angularVelocity * (1.0 / speed)
                val angle = -speed * dt
                gravity = current * cos(angle) + axis.cross(current) * sin(angle) + axis * (axis.dot(current) * (1.0 - cos(angle)))
            }
        }
        return gravity
    }

    fun onAcceleration(acceleration: Vec3, timestampNanos: Long, fused: Boolean): Vec3 {
        require(acceleration.isFinite() && acceleration.norm() > 1e-6)
        val dt = if (lastAcceleration == 0L) 0.02 else ((timestampNanos - lastAcceleration) / 1e9).coerceIn(0.0, 0.2)
        if (timestampNanos > lastAcceleration) lastAcceleration = timestampNanos
        val current = gravity
        if (current == null) {
            gravity = acceleration
        } else {
            val plausible = abs(acceleration.norm() - LevelMath.STANDARD_GRAVITY) < 1.5
            val alpha = when {
                fused && !plausible -> 0.0
                fused -> 1.0 - exp(-dt / correctionTimeSeconds)
                else -> 1.0 - exp(-dt / 0.25)
            }
            gravity = current + (acceleration - current) * alpha
        }
        return requireNotNull(gravity)
    }
}

/** Last one second of raw gravity. Scatter is repeatability, not certified absolute accuracy. */
class StabilityWindow(
    private val durationNanos: Long = 1_000_000_000L,
    private val gravityTolerance: Double = 0.4,
) {
    private val values = ArrayDeque<Pair<Long, Vec3>>()
    fun update(value: Vec3, timestamp: Long): StabilityResult {
        values.addLast(timestamp to value)
        while (values.size > 1 && timestamp - values.first().first > durationNanos) values.removeFirst()
        val mean = values.fold(Vec3.ZERO) { sum, item -> sum + item.second } * (1.0 / values.size)
        val variance = values.sumOf { (it.second - mean).dot(it.second - mean) } / values.size
        val spread = sqrt(variance)
        val span = timestamp - values.first().first
        val stable = span >= durationNanos * 0.8 && values.size >= 10 && spread < 0.08 && abs(mean.norm() - LevelMath.STANDARD_GRAVITY) < gravityTolerance
        val repeatability = if (span >= durationNanos * 0.8 && mean.norm() > 1.0) Math.toDegrees(atan2(spread, mean.norm())) else null
        return StabilityResult(stable, repeatability)
    }
}

data class StabilityResult(val stable: Boolean, val estimatedError: Double?)
