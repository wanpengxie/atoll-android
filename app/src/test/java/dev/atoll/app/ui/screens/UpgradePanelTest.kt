package dev.atoll.app.ui.screens

import org.junit.Assert.*
import org.junit.Test

class UpgradePanelTest {
    @Test fun requestDownloadAndRestartStepsComeOnlyFromMonitorFacts() {
        fun steps(phase: String) = upgradeSteps(UpgradeViewState("v1", "v2", phase), ConnectionPhase.OPEN)
        assertEquals(List(3) { UpgradeStepState.Pending }, steps("available").map { it.state })
        assertEquals(listOf(UpgradeStepState.Active, UpgradeStepState.Pending, UpgradeStepState.Pending), steps("starting").map { it.state })
        for (phase in listOf("downloading", "verifying", "installing", "restarting"))
            assertEquals(listOf(UpgradeStepState.Done, UpgradeStepState.Active, UpgradeStepState.Pending), steps(phase).map { it.state })
    }
    @Test fun reconnectStepDoesNotClaimTheNewVersionWasVerified() {
        val steps = upgradeSteps(UpgradeViewState("v1", "v2", "restarting"), ConnectionPhase.RECONNECTING)
        assertEquals("节点已断开", steps[1].text)
        assertEquals(UpgradeStepState.Active, steps.last().state)
        assertEquals("重新连接并核对版本", steps.last().text)
    }
    @Test fun currentOrUnsupportedVersionsCannotInventAPastUpgradeRequest() {
        for (phase in listOf("current", "checking", "unsupported", "unavailable"))
            assertTrue(upgradeSteps(UpgradeViewState("v2", "v2", phase, available = false), ConnectionPhase.OPEN).isEmpty())
        assertTrue(upgradeSteps(UpgradeViewState("v1", "v2", "failed", available = false), ConnectionPhase.OPEN).isEmpty())
    }
}
