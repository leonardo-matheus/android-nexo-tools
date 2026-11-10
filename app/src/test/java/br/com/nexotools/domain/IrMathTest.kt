package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class IrMathTest {
    @Test fun lgPowerMatchesIndependentLsbAndLegacyVectors() {
        val frame = IrProfiles.lgTv.frame("POWER")
        assertEquals(0xF708FB04L, frame)
        assertEquals(0x20DF10EFL, NecEncoder.legacyMsb(frame))
        val pattern = NecEncoder.pattern(frame)
        assertEquals(9_000, pattern[0]); assertEquals(4_500, pattern[1])
        assertEquals(562, pattern[3]) // Address bit 0 is zero.
        assertEquals(1_687, pattern[7]) // Address bit 2 is one: numeric address 0x04.
        val reconstructed = (0..31).fold(0L) { bits, bit -> bits or (if (pattern[3 + bit * 2] == 1_687) 1L shl bit else 0L) }
        assertEquals(frame, reconstructed)
    }
    @Test fun bothLedVariantsPreserveWireAddressAndCommandChecksums() {
        assertEquals(0x00FFB04FL, NecEncoder.legacyMsb(IrProfiles.led24.frame("ON")))
        assertEquals(0x00F7C03FL, NecEncoder.legacyMsb(IrProfiles.led24F7.frame("ON")))
        assertEquals(0x00FF9867L, NecEncoder.legacyMsb(IrProfiles.led24.frame("RED")))
        assertEquals(0xEF00L, IrProfiles.led24F7.frame("ON") and 0xFFFF)
        assertTrue(NecEncoder.hasCommandChecksum(IrProfiles.led24F7.frame("ON")))
    }
    @Test fun profilesHaveStableUniqueKeysAndAllPatternsFitAndroidLimits() {
        assertEquals(24, IrProfiles.led24.commands.size); assertEquals(24, IrProfiles.led24F7.commands.size)
        assertEquals(IrProfiles.led24.commands.map { it.id }, IrProfiles.led24F7.commands.map { it.id })
        IrProfiles.byId.values.forEach { profile ->
            assertEquals(profile.commands.size, profile.commandsById.size)
            profile.commands.forEach {
                val pattern = NecEncoder.pattern(profile.frame(it.id))
                assertEquals(68, pattern.size); assertTrue(pattern.all { duration -> duration > 0 })
                assertTrue(pattern.sumOf { duration -> duration.toLong() } < 2_000_000L)
                assertEquals(562, pattern[66]); assertEquals(40_000, pattern[67])
            }
        }
    }
    @Test fun malformedFramesAndWideCommandsAreRejected() {
        assertFalse(NecEncoder.hasCommandChecksum(0x00000000))
        assertTrue(runCatching { NecEncoder.pattern(0x00000000) }.isFailure)
        assertTrue(runCatching { NecEncoder.frame(256, 8) }.isFailure)
        assertTrue(runCatching { NecEncoder.frame(4, 256) }.isFailure)
        assertTrue(runCatching { NecEncoder.frame(65_536, 8, true) }.isFailure)
    }
    @Test fun hardwareCannotAdvertiseUnsupportedOrUnknownCarrier() {
        val frequencies = listOf(IrFrequencyRange(36_000, 40_000))
        assertTrue(IrHardwareStatus(true, frequencies, "").supports(38_000))
        assertFalse(IrHardwareStatus(false, frequencies, "").supports(38_000))
        assertFalse(IrHardwareStatus(true, emptyList(), "").supports(38_000))
        assertFalse(IrHardwareStatus(true, listOf(IrFrequencyRange(56_000, 60_000)), "").supports(38_000))
    }
}
