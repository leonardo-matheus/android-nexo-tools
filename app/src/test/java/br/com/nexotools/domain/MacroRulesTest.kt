package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MacroRulesTest {
    @Test fun externalRoutesAndUnboundedWaitsAreRejected() {
        assertTrue(runCatching{MacroRules.validate("Rotina fictícia",listOf(MacroStep(MacroAction.OPEN_TOOL,"external")))}.isFailure)
        assertTrue(runCatching{MacroRules.validate("Rotina fictícia",listOf(MacroStep(MacroAction.WAIT,seconds=31)))}.isFailure)
        assertTrue(runCatching{MacroRules.validate("Rotina fictícia",List(4){MacroStep(MacroAction.WAIT,seconds=30)})}.isFailure)
    }
    @Test fun uiActionsRequireForegroundAndIrCanRunLocally() {
        assertFalse(MacroRules.needsForeground(listOf(MacroStep(MacroAction.IR,"LG_TV","POWER"))))
        assertTrue(MacroRules.needsForeground(listOf(MacroStep(MacroAction.OPEN_TOOL,"level"))))
        MacroRules.validate("Rotina fictícia",listOf(MacroStep(MacroAction.IR,"LED24","ON"),MacroStep(MacroAction.WAIT,seconds=2)))
    }
    @Test fun dailyScheduleUsesWallClockAcrossDst() {
        val previous=Instant.parse("2026-03-07T13:00:00Z").toEpochMilli()
        val now=Instant.parse("2026-03-08T12:30:00Z").toEpochMilli()
        val next=MacroRules.nextDailyRun(previous,now,ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-09T12:00:00Z").toEpochMilli(),next)
    }
}
