package br.com.nexotools.domain

data class NormalizedPoint(val xPercent: Double, val yPercent: Double) {
    init {
        require(xPercent.isFinite() && yPercent.isFinite() && xPercent in 0.0..100.0 && yPercent in 0.0..100.0) { "Coordenadas devem estar entre 0 e 100%" }
    }
}
data class SwipeSpec(val start: NormalizedPoint, val end: NormalizedPoint, val durationMillis: Long) {
    init {
        require(durationMillis in 100..3000) { "Deslize deve durar de 100 a 3000 ms" }
        require(start != end) { "Escolha pontos diferentes para deslizar" }
    }
}
data class GesturePixelPoint(val x: Float, val y: Float)

object MacroGestureMath {
    fun validPackageName(value: String): Boolean = value.length in 3..255 &&
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

    fun parseTap(value: String): NormalizedPoint {
        val coordinates = value.split(',')
        require(coordinates.size == 2) { "Toque deve usar x,y em percentual" }
        return NormalizedPoint(number(coordinates[0]), number(coordinates[1]))
    }
    fun parseSwipe(value: String): SwipeSpec {
        val coordinates = value.split(',')
        require(coordinates.size == 5) { "Deslize deve usar x1,y1,x2,y2,duração" }
        return SwipeSpec(NormalizedPoint(number(coordinates[0]), number(coordinates[1])),
            NormalizedPoint(number(coordinates[2]), number(coordinates[3])),
            requireNotNull(coordinates[4].trim().toLongOrNull()) { "Informe duração em milissegundos" })
    }
    private fun number(value: String): Double = requireNotNull(value.trim().toDoubleOrNull()) { "Coordenada inválida" }

    /** Map percentages into actual pixels; 100% stops at the final pixel inside the display. */
    fun pixels(point: NormalizedPoint, width: Int, height: Int, left: Int = 0, top: Int = 0): GesturePixelPoint {
        require(width > 0 && height > 0) { "Tela indisponível" }
        return GesturePixelPoint((left + (width - 1) * point.xPercent / 100.0).toFloat(),
            (top + (height - 1) * point.yPercent / 100.0).toFloat())
    }
}
