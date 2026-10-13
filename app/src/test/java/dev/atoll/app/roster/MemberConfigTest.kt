package dev.atoll.app.roster

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemberConfigTest {
    private fun json(text: String) = Json.parseToJsonElement(text)
    @Test fun mergePatchPreservesUntouchedRedactedValuesAndOnlyPatchesChangedKeys() {
        val before = json("""{"model":"a","api_key":"${'$'}global.k","nested":{"x":1,"y":[1,2]},"redacted":"已隐藏"}""")
        val after = json("""{"model":"b","nested":{"y":[1,2],"x":1},"redacted":"已隐藏","temperature":0.2}""")
        assertEquals(json("""{"model":"b","api_key":null,"temperature":0.2}"""), MemberConfig.mergePatch(before, after))
        assertEquals(json("""{"a":{"b":{"d":null}}}"""), MemberConfig.mergePatch(json("""{"a":{"b":{"c":1,"d":2}}}"""), json("""{"a":{"b":{"c":1}}}""")))
        assertEquals(json("""{"list":[2]}"""), MemberConfig.mergePatch(json("""{"list":[1,2]}"""), json("""{"list":[2]}""")))
        assertEquals(json("""{"a":"x"}"""), MemberConfig.mergePatch(json("""{"a":{"b":1}}"""), json("""{"a":"x"}""")))
        assertEquals(MemberConfig.empty, MemberConfig.mergePatch(before, before))
        assertEquals(json("""{"a":1}"""), MemberConfig.mergePatch(null, json("""{"a":1}""")))
        assertEquals(json("""{"a":null}"""), MemberConfig.mergePatch(json("""{"a":1}"""), null))
    }
    @Test fun dottedPathAndValueLookupMatchWebIncludingEmptyPathSegments() {
        assertEquals(json("""{"service":{"api_key":"value"}}"""), MemberConfig.patchAtPath("service.api_key", JsonPrimitive("value")))
        assertEquals(json("""{"a":{"b":1}}"""), MemberConfig.patchAtPath("a..b", JsonPrimitive(1)))
        assertThrows(IllegalArgumentException::class.java) { MemberConfig.patchAtPath("", JsonPrimitive(1)) }
        val value = json("""{"service":{"region":"cn"},"list":[1]}""")
        assertEquals(JsonPrimitive("cn"), MemberConfig.valueAtPath(value, "service.region"))
        assertNull(MemberConfig.valueAtPath(value, "service.missing")); assertNull(MemberConfig.valueAtPath(value, "list.0"))
    }
    @Test fun sortedSourceRowsAndMissingPlaceholdersPreserveUnknownLayers() {
        val detail = MemberConfig.parse("""{"effective":{"model":"m","service":{"region":"cn"}},"sources":{"service.region":"actor","model":"default","other":"future"},"missing":[{"key":"key","hint":"说明"},{"key":"model"},{"hint":"no key"},null]}""")
        val rows = MemberConfig.sourceRows(detail)
        assertEquals(listOf("model", "other", "service.region"), rows.map { it.path })
        assertEquals(listOf("Class 默认值", "future", "Actor 描述"), rows.map { it.label })
        assertNull(rows[1].value)
        assertEquals(listOf(Placeholder("key", "说明"), Placeholder("model", "")), MemberConfig.missing(detail))
        assertTrue(MemberConfig.sourceRows(null).isEmpty())
    }
    @Test fun configJSONMustBeObjectAndGlobalReferencesInsertAtCursor() {
        assertEquals(MemberConfig.empty, MemberConfig.parse(""))
        assertThrows(IllegalArgumentException::class.java) { MemberConfig.parse("[1]") }
        assertThrows(IllegalArgumentException::class.java) { MemberConfig.parse("{") }
        val text = "{\n  \"api_key\": \n}"
        val cursor = text.indexOf(": ") + 2
        val inserted = MemberConfig.insertGlobal(text, cursor, cursor, "deepseek_prod")
        assertEquals(JsonPrimitive("\$global.deepseek_prod"), MemberConfig.parse(inserted.text)["api_key"])
        assertEquals(cursor + "\"\$global.deepseek_prod\"".length, inserted.cursor)
        assertThrows(IllegalArgumentException::class.java) { MemberConfig.insertGlobal(text, 0, 0, "Bad Name") }
        val refs = buildJsonObject { put("a", "\$global.one"); put("b", buildJsonArray { add("\$global.two") }); put("c", "\$global.") }
        assertEquals(listOf("global/two"), MemberConfig.missingGlobals(refs, listOf("one")))
    }
    @Test fun JSONEqualityIgnoresObjectOrderAndEquivalentNumbersButKeepsArrayOrder() {
        assertTrue(MemberConfig.sameJSON(json("""{"a":1,"b":2}"""), json("""{"b":2.0,"a":1.0}""")))
        assertFalse(MemberConfig.sameJSON(json("[1,2]"), json("[2,1]")))
        assertFalse(MemberConfig.sameJSON(JsonPrimitive("1"), JsonPrimitive(1)))
    }
    @Test fun entryAndOwnConfigPatchNeverSendUnchangedFieldsAndEmptyRequiresClears() {
        val info = MemberConfig.parse("""{"body":{"class":"codex"},"params":{"a":1},"requires":["x"],"own_config":{"desired_host":"mini","values":{"keep":"已隐藏","remove":1}}}""")
        val entry = MemberConfig.entryChange("agent:a", info, "actor", "writer@2", "{\"a\":2}", "")
        assertEquals(json("""{"member":"agent:a","body":{"actor":"writer@2"},"params":{"a":2},"requires":null}"""), entry.payload)
        assertFalse(MemberConfig.entryChange("agent:a", info, "class", "codex", "{\"a\":1}", "x").changed)
        val own = MemberConfig.configChange("agent:a", info, "", "{\"keep\":\"已隐藏\"}")
        assertEquals(json("""{"member":"agent:a","desired_host":"","values":{"remove":null}}"""), own.payload)
    }
    @Test fun bodyLabelsAndEditableKindsMatchWeb() {
        assertEquals("Actor 描述 writer@1", MemberConfig.bodyLabel(json("""{"actor":"writer@1"}""")))
        assertEquals("Class codex", MemberConfig.bodyLabel(json("""{"class":"codex"}""")))
        assertEquals("人", MemberConfig.bodyLabel(json("""{"human":true}""")))
        assertEquals("人", MemberConfig.rosterBodyLabel("human"))
        assertEquals("", MemberConfig.bodyLabel(JsonPrimitive("class codex")))
        assertTrue(MemberConfig.editable("tool")); assertFalse(MemberConfig.editable("peer"))
    }
}
