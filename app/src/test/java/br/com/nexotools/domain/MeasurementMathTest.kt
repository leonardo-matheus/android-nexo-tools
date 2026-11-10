package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MeasurementMathTest {
    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-6) = assertEquals(expected, actual, tolerance)

    @Test fun `horizontal plane is zero facing up or down`() {
        near(0.0, LevelMath.reading(Vec3(0.0, 0.0, 9.8)).planeDegrees)
        near(0.0, LevelMath.reading(Vec3(0.0, 0.0, -9.8)).planeDegrees)
    }

    @Test fun `inclinations have signed screen axes and total plane angle`() {
        val angle = Math.toRadians(30.0)
        val value = LevelMath.reading(Vec3(sin(angle), 0.0, cos(angle)))
        near(30.0, value.xDegrees)
        near(0.0, value.yDegrees)
        near(30.0, value.planeDegrees)
        near(-30.0, LevelMath.reading(Vec3(-sin(angle), 0.0, cos(angle))).xDegrees)
        near(90.0, LevelMath.reading(Vec3(1.0, 0.0, 0.0)).planeDegrees)
    }

    @Test fun `display rotation maps both signed axes`() {
        val gravity = Vec3(4.903325, 0.0, 8.492808)
        val landscape = LevelMath.reading(gravity, rotation = 1)
        near(0.0, landscape.xDegrees)
        near(30.0, landscape.yDegrees, 1e-5)
        near(-30.0, LevelMath.reading(gravity, rotation = 2).xDegrees, 1e-5)
    }

    @Test fun `relative zero uses complete vector and survives rotated screen`() {
        val reference = Vec3(2.0, 3.0, 8.0)
        for (rotation in 0..3) {
            val value = LevelMath.reading(reference, reference, rotation)
            near(0.0, value.xDegrees)
            near(0.0, value.yDegrees)
            near(0.0, value.planeDegrees)
        }
        near(0.0, LevelMath.reading(Vec3(0.0, 0.0, -9.8), Vec3(0.0, 0.0, -9.8)).planeDegrees)
    }

    @Test fun `gyro prediction rotates gravity opposite device motion`() {
        val fusion = GravityFusion()
        fusion.onAcceleration(Vec3(0.0, 0.0, 9.8), 1_000_000_000L, true)
        fusion.onGyroscope(Vec3(0.0, Math.PI, 0.0), 1_000_000_000L)
        val gravity = requireNotNull(fusion.onGyroscope(Vec3(0.0, Math.PI, 0.0), 1_100_000_000L))
        near(-18.0, LevelMath.reading(gravity).xDegrees)
        near(9.8, gravity.norm())
    }

    @Test fun `fusion rejects dynamic acceleration when gyro is available`() {
        val fusion = GravityFusion()
        fusion.onAcceleration(Vec3(0.0, 0.0, 9.8), 1_000_000_000L, true)
        val result = fusion.onAcceleration(Vec3(9.8, 0.0, 9.8), 1_100_000_000L, true)
        near(0.0, result.x)
    }

    @Test fun `static window needs time before declaring stability`() {
        val window = StabilityWindow()
        assertFalse(window.update(Vec3(0.0, 0.0, 9.80665), 1L).stable)
        var last = StabilityResult(false, null)
        repeat(60) { last = window.update(Vec3(0.0, 0.0, 9.80665), it * 20_000_000L + 1) }
        assertTrue(last.stable)
        near(0.0, requireNotNull(last.estimatedError))
        assertFalse(window.update(Vec3(4.0, 0.0, 9.8), 1_200_000_001L).stable)
    }

    @Test fun `six poses recover synthetic bias and scales`() {
        val calibrator = SixFaceCalibrator()
        val offset = Vec3(0.1, -0.2, 0.3)
        val realScale = Vec3(1.02, 0.98, 1.01)
        val gyro = Vec3(0.001, -0.002, 0.003)
        for (pose in CalibrationPose.entries) {
            val trueValue = DoubleArray(3).apply { this[pose.axis] = LevelMath.STANDARD_GRAVITY * pose.sign }
            val raw = Vec3(trueValue[0] / realScale.x + offset.x, trueValue[1] / realScale.y + offset.y, trueValue[2] / realScale.z + offset.z)
            assertTrue(calibrator.add(pose, List(40) { CalibrationSample(raw, gyro, true) }).isSuccess)
        }
        val calibration = calibrator.finish().getOrThrow()
        near(offset.x, calibration.offset.x)
        near(offset.y, calibration.offset.y)
        near(offset.z, calibration.offset.z)
        near(realScale.x, calibration.scale.x)
        near(realScale.y, calibration.scale.y)
        near(realScale.z, calibration.scale.z)
        near(gyro.x, calibration.gyroBias.x, 1e-12)
        near(gyro.y, calibration.gyroBias.y, 1e-12)
        near(gyro.z, calibration.gyroBias.z, 1e-12)
    }

    @Test fun `incorrect or moving calibration pose is rejected`() {
        val calibrator = SixFaceCalibrator()
        assertTrue(calibrator.add(CalibrationPose.POS_X, List(40) { CalibrationSample(Vec3(0.0, 0.0, 9.8), null, true) }).isFailure)
        assertTrue(calibrator.add(CalibrationPose.POS_Z, List(40) { CalibrationSample(Vec3(0.0, 0.0, 9.8), null, false) }).isFailure)
        assertTrue(calibrator.finish().isFailure)
    }

    @Test fun `PCM sine RMS has expected dBFS and calibration changes only SPL`() {
        val sample = ShortArray(4800) { (32767 * sin(2 * Math.PI * 1000 * it / 48000)).toInt().toShort() }
        val uncalibrated = AudioMath.reading(sample)
        near(-3.0103, uncalibrated.dbFs, 0.003)
        assertNull(uncalibrated.spl)
        val calibrated = AudioMath.reading(sample, offsetDb = 80.0 - uncalibrated.dbFs)
        near(80.0, requireNotNull(calibrated.spl))
        near(uncalibrated.dbFs, calibrated.dbFs)
    }

    @Test fun `silence full scale clipping and energy average`() {
        near(-120.0, AudioMath.reading(ShortArray(100)).dbFs)
        assertTrue(AudioMath.reading(shortArrayOf(Short.MIN_VALUE)).clipped)
        near(67.4036269, requireNotNull(AudioMath.energyMean(listOf(60.0, 70.0))), 0.00001)
        assertNull(AudioMath.energyMean(emptyList()))
    }
}
