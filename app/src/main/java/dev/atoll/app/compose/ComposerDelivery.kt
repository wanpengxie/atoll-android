package dev.atoll.app.compose

import kotlinx.serialization.json.*

data class Delivery(val recipients: List<ComposerActor>, val source: String, val missing: List<ComposerActor> = emptyList()) {
    val valid: Boolean get() = recipients.isNotEmpty() && missing.isEmpty() && recipients.all { it.kind in setOf("agent", "human") }
    val label: String get() = when {
        missing.isNotEmpty() -> "⚠ ${missing.joinToString { it.name }} 已不在频道"
        recipients.isEmpty() -> "⚠ 无收件人"
        source == "reply" -> "回复 @${recipients.first().name}"
        else -> "@${recipients.first().name}${if (recipients.size > 1) " +${recipients.size - 1}" else ""}"
    }
}
object ComposerDelivery {
    fun resolve(draft: ComposerDraft, roster: List<ComposerActor>, agentFilter: Set<String> = emptySet(), recentAgentId: String = ""): Delivery {
        val byId = roster.associateBy { it.id }
        draft.reply?.let { reply ->
            val actor = byId[reply.senderId]
            return if (actor == null) Delivery(emptyList(), "reply", listOf(ComposerActor(reply.senderId, reply.senderName, ""))) else Delivery(listOf(actor), "reply")
        }
        if (draft.recipients.isNotEmpty()) {
            val mentions = draft.recipients.distinctBy { it.id }
            val missing = mentions.filter { it.id !in byId }
            return Delivery(mentions.map { byId[it.id] ?: it }, "mention", missing)
        }
        val agents = roster.filter { it.kind == "agent" }
        val filtered = agents.filter { it.id in agentFilter }.singleOrNull()
        if (filtered != null) return Delivery(listOf(filtered), "filter")
        val manual = agents.find { it.id == draft.manualAgentId }
        if (manual != null) return Delivery(listOf(manual), "manual")
        val recent = agents.find { it.id == recentAgentId }
        if (recent != null) return Delivery(listOf(recent), "recent")
        return agents.singleOrNull()?.let { Delivery(listOf(it), "only") } ?: Delivery(emptyList(), "")
    }
    fun messageFrames(channelId: String, draft: ComposerDraft, delivery: Delivery, newId: () -> String, expiresAt: Long = 0): List<JsonObject> {
        require(delivery.valid) { delivery.label }
        require(draft.text.isNotBlank() || draft.attachments.isNotEmpty()) { "消息内容不能为空" }
        val parsed = SlashCommands.parse(draft.text)
        require(parsed !is ParsedCommand.Command) { "斜杠命令必须通过命令入口发送" }
        val text = (parsed as? ParsedCommand.Escaped)?.text ?: draft.text.trim().ifEmpty { "发送 ${draft.attachments.size} 个附件" }
        return delivery.recipients.map { actor -> buildJsonObject {
            put("channel_id", channelId); put("id", newId()); put("msg_type", if (actor.kind == "human") "human.message" else "agent.ask")
            put("kind", "request"); put("visibility", "public"); put("audience", JsonArray(listOf(JsonPrimitive(actor.id))))
            put("payload", buildJsonObject { put("text", text); if (draft.attachments.isNotEmpty()) put("attachments", JsonArray(draft.attachments.map { it.attachment() })) })
            draft.reply?.sourceId?.takeIf { it.isNotEmpty() }?.let { put("parent_id", it) }
            if (expiresAt > 0) put("expires_at_ms", expiresAt)
        } }
    }
    fun mention(text: String, cursor: Int, roster: List<ComposerActor>, selfId: String): MentionQuery? {
        val before = text.take(cursor.coerceIn(0, text.length))
        val match = Regex("(?:^|\\s)@([^\\s@]*)$").find(before) ?: return null
        val query = match.groupValues[1]
        val start = match.range.first + match.value.lastIndexOf('@')
        val candidates = roster.filter { it.id != selfId && it.kind in setOf("agent", "human") && (query.isEmpty() || (it.name + " " + it.id).contains(query, ignoreCase = true)) }.take(8)
        return MentionQuery(start, cursor, query, candidates)
    }
}
data class MentionQuery(val start: Int, val end: Int, val query: String, val candidates: List<ComposerActor>)

enum class EditorAction { NONE, SEND, NEWLINE, REMOVE_MENTION, CLEAR_REPLY, DISMISS_MENU, NEXT, PREVIOUS, PICK }
object ComposerEditor {
    fun key(key: String, shift: Boolean, composing: Boolean, cursorAtStart: Boolean, hasMentions: Boolean, hasReply: Boolean, menuCount: Int): EditorAction {
        if (composing) return EditorAction.NONE
        if (key == "Enter" && shift) return EditorAction.NEWLINE
        if (menuCount > 0) when (key) { "ArrowDown" -> return EditorAction.NEXT; "ArrowUp" -> return EditorAction.PREVIOUS; "Enter" -> return EditorAction.PICK; "Escape" -> return EditorAction.DISMISS_MENU }
        return when {
            key == "Enter" && shift -> EditorAction.NEWLINE
            key == "Enter" -> EditorAction.SEND
            key == "Backspace" && cursorAtStart && hasMentions -> EditorAction.REMOVE_MENTION
            key == "Escape" && hasReply -> EditorAction.CLEAR_REPLY
            else -> EditorAction.NONE
        }
    }
    fun rests(draft: ComposerDraft, focused: Boolean) = !focused && draft.text.isEmpty() && draft.recipients.isEmpty() && draft.attachments.isEmpty() && draft.reply == null
}
