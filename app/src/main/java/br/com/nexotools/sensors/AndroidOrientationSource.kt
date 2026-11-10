package br.com.nexotools.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import br.com.nexotools.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Singleton
class AndroidOrientationSource @Inject constructor(@ApplicationContext context: Context) : OrientationSource {
    private val manager = context.getSystemService(SensorManager::class.java)

    override fun readings(calibration: Calibration, rotation: Int): Flow<LevelReading> = callbackFlow {
        val acceleration = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val primary = acceleration ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (primary == null) {
            close(SourceUnavailableException("Este aparelho não fornece acelerômetro nem gravidade"))
            return@callbackFlow
        }
        val gyro = if (acceleration != null) manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) else null
        val thread = HandlerThread("NexoOrientation").apply { start() }
        val handler = Handler(thread.looper)
        val fusion = GravityFusion()
        val stability = StabilityWindow()
        var gyroRegistered = false
        var lastEmit = 0L
        var gyroMoving = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                if (!value.isFinite()) return
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    val corrected = value - calibration.gyroBias
                    gyroMoving = corrected.norm() > 0.025
                    fusion.onGyroscope(corrected, event.timestamp)
                    return
                }
                // Six-face bias/scale belongs to the accelerometer; vendor gravity is already fused.
                val corrected = if (acceleration != null) calibration.correct(value) else value
                if (corrected.norm() < 1e-6) return
                val quality = stability.update(corrected, event.timestamp)
                val filtered = if (acceleration != null) fusion.onAcceleration(corrected, event.timestamp, gyroRegistered) else corrected
                if (event.timestamp - lastEmit >= 50_000_000L) {
                    lastEmit = event.timestamp
                    val source = when {
                        gyroRegistered -> "Acelerômetro + giroscópio"
                        acceleration == null -> "Gravidade do Android"
                        else -> "Acelerômetro filtrado"
                    }
                    trySend(LevelMath.reading(filtered, calibration.reference, rotation, quality.stable && !gyroMoving, quality.estimatedError, source))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = manager.registerListener(listener, primary, 20_000, handler)
        if (registered && gyro != null) gyroRegistered = manager.registerListener(listener, gyro, 20_000, handler)
        if (!registered) close(SourceUnavailableException("Não foi possível iniciar o sensor de orientação"))
        awaitClose {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    override fun calibrationSamples(): Flow<CalibrationSample> = callbackFlow {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            close(SourceUnavailableException("Calibração multiposição exige acelerômetro"))
            return@callbackFlow
        }
        val thread = HandlerThread("NexoCalibration").apply { start() }
        val handler = Handler(thread.looper)
        val stability = StabilityWindow(gravityTolerance = 2.0)
        var gyro: Vec3? = null
        var lastEmit = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                if (!value.isFinite()) return
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    gyro = value
                    return
                }
                val state = stability.update(value, event.timestamp)
                if (event.timestamp - lastEmit >= 50_000_000L) {
                    lastEmit = event.timestamp
                    trySend(CalibrationSample(value, gyro, state.stable && (gyro?.norm()?.let { it < 0.15 } ?: true)))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = manager.registerListener(listener, sensor, 20_000, handler)
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { manager.registerListener(listener, it, 20_000, handler) }
        if (!registered) close(SourceUnavailableException("Não foi possível iniciar a calibração"))
        awaitClose {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }
}
