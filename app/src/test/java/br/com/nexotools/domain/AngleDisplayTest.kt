package br.com.nexotools.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AngleDisplayTest {
    @Test fun subDegreeDispersionDoesNotPromisePerfectZero() {
        assertEquals("<1°", AngleDisplay.dispersion(0.0))
        assertEquals("<1°", AngleDisplay.dispersion(0.99))
        assertEquals("2°", AngleDisplay.dispersion(1.7))
        assertEquals("—", AngleDisplay.dispersion(null))
        assertEquals("—", AngleDisplay.dispersion(-0.1))
    }
    @Test fun zeroAndSubDegreeNegativeValuesNeverShowNegativeZero() {
        assertEquals("0°", AngleDisplay.format(-0.0))
        assertEquals("0°", AngleDisplay.format(-0.49))
        assertEquals("0°", AngleDisplay.format(0.49))
        assertEquals("0°", AngleDisplay.format(0.0, signed = false))
    }
    @Test fun roundingPreservesDirectionAndUnsignedPlaneDisplay() {
        assertEquals("-1°", AngleDisplay.format(-0.5))
        assertEquals("+1°", AngleDisplay.format(0.5))
        assertEquals("-90°", AngleDisplay.format(-89.7))
        assertEquals("90°", AngleDisplay.format(89.7, signed = false))
    }
    @Test fun unavailableMeasurementsAreNotFormattedAsZero() {
        assertEquals("—", AngleDisplay.format(null))
        assertEquals("—", AngleDisplay.format(Double.NaN))
        assertEquals("—", AngleDisplay.format(Double.POSITIVE_INFINITY))
    }
}
