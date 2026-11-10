package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class AttitudeTest {
    @Test fun composedTiltUsesEulerRollRatherThanAxisInclination() {
        val gravity=Vec3(1.0,1.0,1.0)
        val attitude=LevelMath.attitude(gravity)
        assertEquals(-35.2643897,attitude.pitchDegrees,1e-5)
        assertEquals(45.0,attitude.rollDegrees,1e-9)
        assertEquals(35.2643897,LevelMath.reading(gravity).xDegrees,1e-5)
    }
    @Test fun relativeReferenceAndDisplayRotationAffectBothAngles() {
        val reference=Vec3(1.0,2.0,9.0)
        val zero=LevelMath.attitude(reference,reference,1)
        assertEquals(0.0,zero.pitchDegrees,1e-8)
        assertEquals(0.0,zero.rollDegrees,1e-8)
        val rotated=LevelMath.attitude(Vec3(1.0,0.0,1.0),rotation=1)
        assertEquals(-45.0,rotated.pitchDegrees,1e-9)
        assertEquals(0.0,rotated.rollDegrees,1e-9)
    }
}
