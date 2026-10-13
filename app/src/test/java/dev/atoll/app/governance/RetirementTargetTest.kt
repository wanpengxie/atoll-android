package dev.atoll.app.governance

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RetirementTargetTest {
    private val id="a822bf88-682c-4fdd-abab-127a7bd2a917"
    private fun state(channel: String=id,name: String="",qualified: String="")=GovernanceState(channel=channel,
        directory=GovernanceDirectory(profile=buildJsonObject {
            put("declared",buildJsonObject { put("id",channel);if(name.isNotEmpty()) put("name",name);if(qualified.isNotEmpty()) put("qualified_name",qualified) })
        }))

    @Test fun unknownChannelHasNoUuidConfirmationAndItsLateNameEnablesTheNamedTarget() {
        val unknown=state()
        assertEquals(id,channelName(unknown)) // Display fallback is allowed; destructive confirmation is not.
        assertNull(retirementTarget(unknown))
        val known=retirementTarget(state(name="child",qualified="c0.android-test.child"))!!
        assertEquals(id,known.channel)
        assertEquals("c0.android-test.child",known.name)
        assertNotEquals(id,known.name)
    }

    @Test fun directoryEnrichmentCannotChangeTheExpectedNameWhileTheReaderIsTyping() {
        val target=retirementTarget(state(name="child"))!!
        val typed="child"
        val refreshed=retirementTarget(state(name="child",qualified="c0.android-test.child"),target)!!
        assertSame(target,refreshed)
        assertEquals(typed,refreshed.name)
        assertSame(target,retirementTarget(state(),target))
    }

    @Test fun aDifferentChannelCannotReuseThePreviousConfirmationTarget() {
        val previous=retirementTarget(state(name="child",qualified="c0.child"))!!
        assertNull(retirementTarget(state(channel="another"),previous))
        val next=retirementTarget(state(channel="another",name="other",qualified="c0.other"),previous)!!
        assertEquals("another",next.channel)
        assertEquals("c0.other",next.name)
    }
}
