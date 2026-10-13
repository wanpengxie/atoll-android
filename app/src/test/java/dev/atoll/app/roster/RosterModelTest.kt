package dev.atoll.app.roster

import dev.atoll.app.core.Envelope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RosterModelTest {
    @Test fun observationProjectsNamesSourcesAndPresenceAndOnlyGeneratedBodyIsHidden() {
        val obs = MemberConfig.parse("""{"items":[{"declared":{"id":"a","name":"A","kind":"agent","body":"class codex"},"actual":{"measures":[{"name":"bound","value":true},{"name":"device_online","value":true}]}},{"declared":{"id":"b","body":"generated"}},{"declared":{"id":"c","body":"human"},"actual":{"measures":[{"name":"bound","unknown":true}]}},{"declared":{"id":"d"},"actual":{"measures":[{"name":"bound","value":true}]}},{"declared":{"id":"e","generated":true}}]}""")
        val rows = RosterModel.rows(obs)
        assertEquals(listOf("a", "c", "d", "e"), rows.map { it.id })
        assertEquals(listOf("在线", "未知", "已绑定", "未绑定"), rows.map { it.presence })
        assertEquals("class codex", rows.first().body)
        assertEquals("c", rows[1].name)
    }
    @Test fun capabilityErrorsAcceptCodesAndSchemaMayBeEncodedAsAString() {
        val manifest = MemberConfig.parse("""{"words":{"agent.demo":{"description":"演示","error_codes":["bad",{"code":"busy"}],"input_schema":"{\"type\":\"object\"}"}}}""")
        val row = RosterModel.capabilities(manifest).single()
        assertEquals("agent.demo", row.type); assertEquals("演示", row.description)
        assertEquals(listOf("bad", "busy"), row.errors)
        assertEquals("object", row.schema!!.text("type"))
    }
    @Test fun memberEventsAndSuccessfulGovernanceWritesRefreshButFailuresDoNot() {
        fun envelope(type: String, kind: String = "event", status: String = "") = Envelope(buildJsonObject {
            put("type", type); put("kind", kind); put("payload", buildJsonObject { put("body", buildJsonObject { put("status", status) }) })
        })
        assertTrue(RosterModel.refreshesRoster(envelope("system.member.created")))
        assertTrue(RosterModel.refreshesRoster(envelope("system.build.finished")))
        assertTrue(RosterModel.refreshesRoster(envelope("system.member.config.set", "response", "completed")))
        assertFalse(RosterModel.refreshesRoster(envelope("system.member.config.set", "response", "failed")))
        assertFalse(RosterModel.refreshesRoster(envelope("system.build.started")))
    }
    @Test fun descriptionOptionsKeepLatestPresentAndPinEveryVersionWithDuplicateNameDisambiguation() {
        val rows = Json.parseToJsonElement("""[{"declared":{"id":"abcd1111","name":"writer","version":1}},{"declared":{"id":"abcd1111","name":"writer","version":2}},{"declared":{"id":"abcd2222","name":"writer","version":1}},{"declared":{"id":"retired","version":4,"status":"retired"}}]""").jsonArray
        val latest = RosterModel.latestDescriptions(rows)
        val options = RosterModel.descriptionOptions(latest, "old@9")
        assertEquals(2, latest.size); assertEquals("old@9", options.first().value)
        assertTrue(options.any { it.value == "abcd1111@2" && it.label.contains("abcd1111") })
        assertTrue(options.none { it.value == "retired" })
    }
}
