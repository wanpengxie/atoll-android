package dev.atoll.app.compose

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    @Test fun statusUsesStandardActorLayerWithoutAManifestWord() {
        val command = SlashCommands.parse("/status") as ParsedCommand.Command
        val frame = SlashCommands.frame("c0", "id", command, ComposerDraft(), AGENT, true, json("words" to JsonObject(emptyMap())))
        assertEquals("actor.describe", frame.text("msg_type"))
        assertEquals(JsonArray(listOf(text(AGENT.id))), frame["audience"])
        for ((target, online, code) in listOf(Triple(null, true, "composer_command_no_target"), Triple(AGENT, false, "composer_command_offline"))) {
            try { SlashCommands.frame("c0", "id", command, ComposerDraft(), target, online, null); fail() }
            catch (error: ComposerFailure) { assertEquals(code, error.code) }
        }
    }
    @Test fun unknownAndUsageFailRatherThanBecomingProse() {
        assertNull(SlashCommands.parse("ordinary /message"))
        assertEquals(ParsedCommand.Escaped("/literal"), SlashCommands.parse("//literal"))
        try { SlashCommands.parse("/unknown"); fail() } catch (error: ComposerFailure) { assertEquals("composer_command_unknown", error.code) }
        try { SlashCommands.parse("/admit"); fail() } catch (error: ComposerFailure) { assertEquals("composer_command_usage", error.code) }
    }
    @Test fun staticCommandsProduceExpectedBodiesAndSystemRouting() {
        fun command(value: String) = SlashCommands.parse(value) as ParsedCommand.Command
        assertEquals("gpt-test", command("/model gpt-test high").payload.text("model"))
        assertEquals("high", command("/model gpt-test high").payload.text("effort"))
        assertEquals(json("actor" to text("tester@2")), command("/introduce actor:tester@2 worker").payload["body"])
        assertEquals(json("class" to text("codex")), command("/introduce codex").payload["body"])
        assertEquals("root", command("/admit root").payload.text("principal"))
        assertEquals("c0", command("/channels c0").payload.text("parent_id"))
        val frame = SlashCommands.frame("c0", "id", command("/restart"), ComposerDraft(), AGENT, true, null)
        assertEquals(JsonArray(listOf(text("system"))), frame["audience"])
        assertEquals(AGENT.id, frame.getValue("payload").jsonObject.text("member"))
    }
    @Test fun commandsRefuseReplyAttachmentsOfflineAndUndeclaredWords() {
        val command = SlashCommands.parse("/compact") as ParsedCommand.Command
        val inputs = listOf(ComposerDraft(reply = ReplyTarget("r", AGENT.id, "a")), ComposerDraft(attachments = listOf(ComposerAttachment("c0", "file", "file"))))
        for (draft in inputs) try { SlashCommands.frame("c0", "id", command, draft, AGENT, true, null); fail() } catch (_: ComposerFailure) { }
        try { SlashCommands.frame("c0", "id", command, ComposerDraft(), AGENT, false, null); fail() } catch (e: ComposerFailure) { assertEquals("composer_command_offline", e.code) }
        try { SlashCommands.frame("c0", "id", command, ComposerDraft(), AGENT, true, json("words" to JsonObject(emptyMap()))); fail() } catch (e: ComposerFailure) { assertEquals("composer_command_unsupported", e.code) }
    }
    @Test fun dynamicCommandsRequireSupportedRequestSchemaAndValidateJson() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string","minLength":1},"count":{"type":"integer","minimum":1}},"required":["text"],"additionalProperties":false}""").jsonObject
        val manifest = json("words" to json("agent.custom" to json("input_schema" to schema), "agent.event" to json("input_schema" to schema, "allowedKinds" to JsonArray(listOf(text("event")))), "agent.unsafe" to json("input_schema" to json("type" to text("object"), "\$ref" to text("external")))))
        val definitions = SlashCommands.definitions(manifest)
        assertEquals(listOf("custom"), definitions.filter { it.schema != null }.map { it.name })
        assertEquals("hello", (SlashCommands.parse("/custom {\"text\":\"hello\",\"count\":2}", definitions) as ParsedCommand.Command).payload.text("text"))
        for (value in listOf("/custom []", "/custom {}", "/custom {\"text\":\"\"}", "/custom {\"text\":\"ok\",\"extra\":true}", "/custom invalid")) try { SlashCommands.parse(value, definitions); fail(value) } catch (e: ComposerFailure) { assertEquals("composer_command_payload_invalid", e.code) }
    }
    @Test fun schemaBranchesCollectionsAndLimitsFollowWebSubset() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"values":{"type":"array","items":{"type":"integer","minimum":0,"maximum":3}}},"required":["values"]}""").jsonObject
        assertNull(CommandSchema.definitionError(schema))
        assertNull(CommandSchema.valueError(Json.parseToJsonElement("""{"values":[1,2]}"""), schema))
        assertNotNull(CommandSchema.valueError(Json.parseToJsonElement("""{"values":[4]}"""), schema))
        assertNotNull(CommandSchema.definitionError(json("type" to text("array"))))
    }
}
