package dev.atoll.app.governance

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GovernanceProjectionTest {
    private fun row(value: String)=Json.parseToJsonElement(value).jsonObject
    @Test fun canonicalActorMeasuresAndEntryLocatorArePreservedAndGeneratedHandlesAreHidden() {
        val visible=row("""{"key":"agent:a","declared":{"id":"agent:a","name":"Worker","kind":"agent","entry_id":"e7","body":"class codex"},"actual":{"measures":[{"name":"bound","value":true,"unknown":false},{"name":"device_online","value":null,"unknown":true}]}}""")
        val generated=row("""{"declared":{"id":"peer:hidden","body":"generated"}}""")
        val member=governanceMembers(listOf(visible,generated)).single()
        assertEquals("e7",member.entryId);assertEquals("agent:a",member.id);assertEquals(true,member.bound);assertNull(member.online)
    }
    @Test fun principalCandidatesExcludeGuestNonHumanAndRetired() {
        val rows=listOf("""{"id":"alice","kind":"human","status":"present"}""","""{"id":"guest"}""","""{"id":"tool","kind":"tool"}""","""{"id":"retired","status":"retired"}""").map(::row)
        assertEquals(listOf("alice"),governancePrincipals(rows).map { it.text("id") })
    }
    @Test fun availableDescriptionCandidateIsLatestPresentVersionAndIdsDisambiguateRepeatedNames() {
        val rows=listOf("""{"id":"id123456a","name":"same","version":1,"status":"present"}""","""{"id":"id123456a","name":"same","version":2,"status":"retired"}""","""{"id":"id987654b","name":"same","version":3,"status":"present"}""").map(::row)
        val latest=governanceDescriptions(rows,true)
        assertEquals(listOf("id123456a@1","id987654b@3"),latest.map(::descriptionRef))
        assertEquals("same（id123456） @1",descriptionLabel(latest.first(),latest));assertEquals(3,governanceDescriptions(rows).size)
    }
    @Test fun copiesRequireMembershipAndKernelChannelsAreExcludedEvenWithAnotherDisplayName() {
        val channels=listOf("""{"declared":{"id":"c0","name":"renamed"}}""","""{"declared":{"id":"c0.lobby"}}""","""{"declared":{"id":"a","qualified_name":"c0.dev"}}""","""{"declared":{"id":"observer","qualified_name":"c0.observer"}}""").map(::row)
        assertEquals(listOf("a"),governanceCopyableChannels(channels,setOf("c0","c0.lobby","a")).map { it.directoryId() })
    }
    @Test fun channelProfilePreservesDeclaredAndActualWhenTheTreeHasNoCurrentRow() {
        val profile=row("""{"declared":{"id":"nested","qualified_name":"c0.test.nested"},"actual":{"measures":[{"name":"open","value":true}]}}""")
        val state=GovernanceState(channel="nested",directory=GovernanceDirectory(profile=profile))
        assertEquals("c0.test.nested",channelName(state));assertEquals(true,currentChannelRow(state).measure("open"))
        assertEquals(profile,currentChannelRow(state))
    }
    @Test fun descriptionReadabilityAndChannelNameValidationMatchWeb() {
        assertEquals("",validateGovernanceChannelName("a"));assertEquals("",validateGovernanceChannelName("a".repeat(63)));assertFalse(validateGovernanceChannelName("-bad").isEmpty());assertFalse(validateGovernanceChannelName("bad-").isEmpty());assertFalse(validateGovernanceChannelName("UPPER").isEmpty())
        val kernel=GovernanceState(channelView=row("""{"description":{"body":{"readonly":true}}}"""));assertFalse(descriptionEditable(kernel))
        assertFalse(descriptionEditable(GovernanceState(channelView=row("""{"description":{"problem":"broken","raw":"..."}}"""))))
        assertTrue(descriptionEditable(GovernanceState(channelView=row("""{"description":{"body":{"description":"ok"}}}"""))))
    }
    @Test fun activityOperationsUseCreationRequestIdentityAndSeparateUncertainFailedReady() {
        val pending=ChannelCreation("r","parent","child",10,accepted=true,uncertain=true,error="timeout")
        val item=governanceActivityOperations(listOf(pending)).single();assertEquals("uncertain",item.state);assertEquals("r",item.source.text("requestId"));assertEquals("parent",item.channelId)
        assertEquals("failed",governanceActivityOperations(listOf(pending.copy(failed=true,uncertain=false))).single().state)
        val ready=pending.copy(ledger=true,targetId="child",observable=true,membership=true,serving=true,uncertain=false,error="")
        assertEquals("completed",governanceActivityOperations(listOf(ready)).single().state)
    }
}
