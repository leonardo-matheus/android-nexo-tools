package br.com.nexotools.domain

import kotlin.math.abs

enum class CalibrationPose(val instruction: String, val axis: Int, val sign: Int) {
    POS_X("Borda esquerda apoiada: eixo X para cima", 0, 1),
    NEG_X("Borda direita apoiada: eixo X para baixo", 0, -1),
    POS_Y("Borda inferior apoiada: eixo Y para cima", 1, 1),
    NEG_Y("Borda superior apoiada: eixo Y para baixo", 1, -1),
    POS_Z("Tela voltada para cima", 2, 1),
    NEG_Z("Tela voltada para baixo", 2, -1),
}

/** Six axis-aligned static poses estimate diagonal scale/bias, not cross-axis misalignment. */
class SixFaceCalibrator {
    private val faces = mutableMapOf<CalibrationPose, Vec3>()
    private val gyroMeans = mutableMapOf<CalibrationPose, Vec3>()

    val capturedPoses: Set<CalibrationPose> get() = faces.keys.toSet()

    fun add(pose: CalibrationPose, samples: List<CalibrationSample>): Result<Unit> = runCatching {
        require(samples.size >= 40) { "Colete ao menos 40 amostras durante dois segundos" }
        require(samples.all { it.stable && it.acceleration.isFinite() }) { "Mantenha o aparelho imóvel" }
        val mean = samples.fold(Vec3.ZERO) { total, sample -> total + sample.acceleration } * (1.0 / samples.size)
        val components = doubleArrayOf(mean.x, mean.y, mean.z)
        require(components[pose.axis] * pose.sign > 8.0 && abs(components[pose.axis]) < 12.0) { "A posição não corresponde à face indicada" }
        require(components.indices.filter { it != pose.axis }.all { abs(components[it]) < 1.5 }) { "Alinhe a face perpendicular à superfície" }
        val maximumDeviation = samples.maxOf { (it.acceleration - mean).norm() }
        require(maximumDeviation < 0.3) { "Houve movimento durante a captura" }
        val gyro = samples.mapNotNull { it.gyro }
        require(gyro.all { it.norm() < 0.15 }) { "Mantenha o aparelho imóvel" }
        faces[pose] = mean
        if (gyro.isNotEmpty()) gyroMeans[pose] = gyro.fold(Vec3.ZERO, Vec3::plus) * (1.0 / gyro.size)
    }

    fun finish(): Result<Calibration> = runCatching {
        require(faces.size == 6) { "Capture as seis posições" }
        val px = requireNotNull(faces[CalibrationPose.POS_X]).x
        val nx = requireNotNull(faces[CalibrationPose.NEG_X]).x
        val py = requireNotNull(faces[CalibrationPose.POS_Y]).y
        val ny = requireNotNull(faces[CalibrationPose.NEG_Y]).y
        val pz = requireNotNull(faces[CalibrationPose.POS_Z]).z
        val nz = requireNotNull(faces[CalibrationPose.NEG_Z]).z
        val g = LevelMath.STANDARD_GRAVITY
        val scale = Vec3(2 * g / (px - nx), 2 * g / (py - ny), 2 * g / (pz - nz))
        require(listOf(scale.x, scale.y, scale.z).all { it in 0.8..1.2 }) { "Escala fora da faixa: repita a calibração" }
        val gyroBias = if (gyroMeans.isEmpty()) Vec3.ZERO else gyroMeans.values.fold(Vec3.ZERO, Vec3::plus) * (1.0 / gyroMeans.size)
        Calibration(Vec3((px + nx) / 2, (py + ny) / 2, (pz + nz) / 2), scale, gyroBias)
    }
}
