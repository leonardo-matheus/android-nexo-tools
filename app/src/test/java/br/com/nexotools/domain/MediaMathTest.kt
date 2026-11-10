package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class MediaMathTest {
    @Test fun gainUsesAmplitudeAndMilliBelsWithoutExceedingTwelveDb() {
        assertEquals(100.0, AudioGainMath.amplitudePercent(0f), 1e-9)
        assertEquals(398.1071705, AudioGainMath.amplitudePercent(12f), 1e-6)
        assertEquals(1200, AudioGainMath.milliBels(50f))
        assertEquals(0, AudioGainMath.milliBels(-5f))
        assertEquals(0f, AudioGainMath.clampDb(Float.NaN), 0f)
        assertEquals(2f, AudioGainMath.clampDb(2.24f), 0f)
        assertEquals(2.5f, AudioGainMath.clampDb(2.26f), 0f)
    }

    private fun smallFrame(rotation: Int = 0, mirrored: Boolean = false) = LuminanceFrame(
        byteArrayOf(10, 0, 20, 0, 0, 30, 0, 40), 2, 2, 5, 2,
        rotationDegrees = rotation, mirrored = mirrored,
    )
    private val identity = IntArray(256) { it }

    @Test fun stridePaddingAndUnsignedLuminanceAreRespected() {
        assertArrayEquals(intArrayOf(10, 20, 30, 40), NightLuminanceMath.render(smallFrame(), identity).pixels)
        assertArrayEquals(intArrayOf(255, 128), NightLuminanceMath.render(LuminanceFrame(byteArrayOf(-1, -128), 2, 1, 2, 1), identity).pixels)
    }

    @Test fun everyRotationAndFrontCameraMirroringHaveCorrectPositions() {
        assertArrayEquals(intArrayOf(30, 10, 40, 20), NightLuminanceMath.render(smallFrame(90), identity).pixels)
        assertArrayEquals(intArrayOf(40, 30, 20, 10), NightLuminanceMath.render(smallFrame(180), identity).pixels)
        assertArrayEquals(intArrayOf(20, 40, 10, 30), NightLuminanceMath.render(smallFrame(270), identity).pixels)
        assertArrayEquals(intArrayOf(10, 30, 20, 40), NightLuminanceMath.render(smallFrame(90, true), identity).pixels)
    }

    @Test fun cropUsesSourceCoordinatesBeforeRotation() {
        val result = NightLuminanceMath.render(smallFrame(90).copy(cropLeft = 1, cropWidth = 1), identity)
        assertEquals(2, result.width); assertEquals(1, result.height)
        assertArrayEquals(intArrayOf(40, 20), result.pixels)
    }

    @Test fun gammaCannotProduceLightFromZeroAndBoostsAvailableDarkPixels() {
        val neutral = NightLuminanceMath.lookup(1f, NightTint.GREEN)
        val bright = NightLuminanceMath.lookup(4f, NightTint.GREEN)
        assertEquals(0xFF000000.toInt(), bright[0])
        assertEquals(neutral[255], bright[255])
        assertTrue((bright[16] and 0x00FFFFFF) > (neutral[16] and 0x00FFFFFF))
        assertEquals(1f, NightLuminanceMath.clampBoost(Float.NaN), 0f)
    }

    @Test(expected = IllegalArgumentException::class) fun incompleteYPlaneIsRejected() {
        NightLuminanceMath.render(smallFrame().copy(data = byteArrayOf(1)), identity)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidCropIsRejected() {
        NightLuminanceMath.render(smallFrame().copy(cropLeft = 1, cropWidth = 2), identity)
    }
}
