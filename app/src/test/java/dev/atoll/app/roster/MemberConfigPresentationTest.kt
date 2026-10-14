package dev.atoll.app.roster

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemberConfigPresentationTest {
    @Test fun formUsesActualValuesAndEditsOnlyTheSelectedOverride() {
        val info = MemberConfig.parse("""{"effective":{"config":{"api_key":"${'$'}global.provider","limit":10,"selections":[{"model":"real","effort":"high"}],"default":0}},"own_config":{"values":{}},"sources":{"config.api_key":"actor"}}""")
        val editor = MemberEditor("config", info)
        val fields = memberConfigFields(info, editor)
        assertEquals(setOf("config.api_key", "config.limit"), fields.map { it.path }.toSet())
        assertEquals("Actor 描述", fields.first { it.path == "config.api_key" }.source)
        val changed = editedMemberValue(editor, "config.limit", JsonPrimitive(20))
        val patch = MemberConfig.configChange("a", info, "", changed.text).payload
        assertEquals(MemberConfig.parse("""{"member":"a","values":{"config":{"limit":20}}}"""), patch)
        assertEquals("${'$'}global.provider", fields.first { it.path == "config.api_key" }.value.content)
    }
    @Test fun unknownClassesKeepStructuredValuesInTheJsonEditor() {
        val info = MemberConfig.parse("""{"effective":{"custom":{"list":[1,2]},"enabled":true},"body":{"class":"custom"}}""")
        val editor = MemberEditor("config", info, text = """{"custom":{"list":[1,2]}}""")
        assertEquals(listOf("enabled"), memberConfigFields(info, editor).map { it.path })
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), MemberConfig.parse(editor.text).obj("custom")["list"])
        assertFalse(memberConfigFields(info, editor).any { it.path.contains("model") || it.path.contains("key") })
    }
}
