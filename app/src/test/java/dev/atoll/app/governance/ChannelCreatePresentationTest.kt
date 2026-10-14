package dev.atoll.app.governance

import org.junit.Assert.*
import org.junit.Test

class ChannelCreatePresentationTest {
    @Test fun serviceStepWaitsForMembershipEvenWhenTheServiceIsOpen() {
        val creation = ChannelCreation("request", "parent", "release-1-4", 0,
            accepted = true, ledger = true, targetId = "target", observable = true, serving = true)
        assertEquals(listOf(true, true, false), channelCreationSteps(creation).map { it.second })
        assertFalse(creation.ready)
        assertTrue(creation.copy(membership = true).ready)
        assertTrue(channelCreationSteps(creation.copy(membership = true)).all { it.second })
    }
    @Test fun nameHintMatchesTheNodeRatherThanTheArtboardExample() {
        assertEquals("", validateGovernanceChannelName("a".repeat(63)))
        listOf("a".repeat(64), "release_1", "-release", "release-").forEach {
            assertTrue(validateGovernanceChannelName(it).isNotEmpty())
        }
        assertEquals("", validateGovernanceChannelName("release-1-4"))
    }
}
