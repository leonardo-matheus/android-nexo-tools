package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class InstalledAppRulesTest {
    @Test fun onlyOtherNonSystemUserAppsAreCandidates() {
        val self = "org.example.fixture.nexo"
        assertTrue(InstalledAppRules.isCandidate("org.example.fixture.user", self, false, false))
        assertFalse(InstalledAppRules.isCandidate(self, self, false, false))
        assertFalse(InstalledAppRules.isCandidate("org.example.fixture.system", self, true, false))
        assertFalse(InstalledAppRules.isCandidate("org.example.fixture.updated", self, false, true))
        assertFalse(InstalledAppRules.isCandidate("", self, false, false))
    }
    @Test fun searchUsesLabelOrPackageWithoutCaseOrSurroundingSpaces() {
        val app = InstalledUserApp("org.example.fixture.tools", "Ferramentas sintéticas")
        assertTrue(InstalledAppRules.matches(app, "  FERRAMENTAS  "))
        assertTrue(InstalledAppRules.matches(app, "FIXTURE.TOOLS"))
        assertTrue(InstalledAppRules.matches(app, "  "))
        assertFalse(InstalledAppRules.matches(app, "outro"))
    }
}

