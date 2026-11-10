package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class MacroGestureMathTest {
    @Test fun tapsParseOnlyNormalizedFiniteCoordinates() {
        assertEquals(NormalizedPoint(50.0, 50.0), MacroGestureMath.parseTap("50,50"))
        assertEquals(NormalizedPoint(0.0, 100.0), MacroGestureMath.parseTap(" 0,100 "))
        assertTrue(runCatching { MacroGestureMath.parseTap("101,0") }.isFailure)
        assertTrue(runCatching { MacroGestureMath.parseTap("NaN,50") }.isFailure)
        assertTrue(runCatching { MacroGestureMath.parseTap("50,50,50") }.isFailure)
        assertTrue(runCatching { MacroGestureMath.parseTap("Infinity,50") }.isFailure)
    }
    @Test fun swipeBoundsAndDurationAreValidated() {
        val swipe = MacroGestureMath.parseSwipe("50,80,50,20,500")
        assertEquals(NormalizedPoint(50.0, 80.0), swipe.start)
        assertEquals(NormalizedPoint(50.0, 20.0), swipe.end)
        assertEquals(500L, swipe.durationMillis)
        assertTrue(runCatching { MacroGestureMath.parseSwipe("50,80,50,20,99") }.isFailure)
        assertTrue(runCatching { MacroGestureMath.parseSwipe("50,80,50,20,3001") }.isFailure)
        assertTrue(runCatching { MacroGestureMath.parseSwipe("50,50,50,50,500") }.isFailure)
    }
    @Test fun percentagesMapInsideDisplayIncludingInsetsOrigin() {
        val start = MacroGestureMath.pixels(NormalizedPoint(0.0, 0.0), 1080, 1920, 5, 7)
        val end = MacroGestureMath.pixels(NormalizedPoint(100.0, 100.0), 1080, 1920, 5, 7)
        assertEquals(5f, start.x, 0f); assertEquals(7f, start.y, 0f)
        assertEquals(1084f, end.x, 0f); assertEquals(1926f, end.y, 0f)
        assertEquals(GesturePixelPoint(0f, 0f), MacroGestureMath.pixels(NormalizedPoint(100.0, 100.0), 1, 1))
        assertTrue(runCatching { MacroGestureMath.pixels(NormalizedPoint(50.0, 50.0), 0, 1920) }.isFailure)
    }
    @Test fun packageNamesCannotContainWildcardsOrLaunchInstructions() {
        assertTrue(MacroGestureMath.validPackageName("com.example.synthetic"))
        assertFalse(MacroGestureMath.validPackageName("com.example.*"))
        assertFalse(MacroGestureMath.validPackageName("com.example.synthetic/Activity"))
        assertFalse(MacroGestureMath.validPackageName(" com.example.synthetic"))
        assertFalse(MacroGestureMath.validPackageName("android"))
    }
}
