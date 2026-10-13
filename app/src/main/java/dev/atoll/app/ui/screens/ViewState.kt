package dev.atoll.app.ui.screens

import androidx.compose.runtime.Immutable

enum class AuthMode { Login, Register }
@Immutable
data class LoginViewState(val server: String = "", val account: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null, val mode: AuthMode = AuthMode.Login, val displayName: String = "")
@Immutable
data class ChannelViewState(val id: String, val name: String, val description: String = "", val unread: Int = 0, val running: Int = 0, val updated: String = "",
    val access: ChannelAccess = ChannelAccess.MemberActive, val unreadPhase: UnreadPhase = UnreadPhase.Ready,
    val activity: List<AgentActivityViewState> = emptyList(), val parentId: String? = null, val depth: Int = 0, val runningElapsed: String = "")
@Immutable
data class AgentViewState(val id: String, val name: String, val active: Boolean = false)
@Immutable
data class MessageAttachmentViewState(val resourceId: String, val name: String, val mime: String = "",
    val size: Long? = null, val uri: String = "")
enum class MessageAttachmentAction { Preview, Download }
typealias MessageAttachmentHandler = (MessageAttachmentViewState, MessageAttachmentAction) -> Unit
enum class MessageStatus(val label: String) { Processing("处理中"), Queued("排队中"), Failed("失败"), Completed("已完成") }
@Immutable
data class RelatedCallViewState(val id: String, val title: String, val sender: String, val recipients: List<String>,
    val time: String, val status: MessageStatus, val depth: Int = 1, val text: String = "",
    val result: dev.atoll.app.ui.components.ResultViewState? = null, val trail: ProgressTrailViewState? = null,
    val attachments: List<MessageAttachmentViewState> = emptyList(), val conversation: Boolean = false)
@Immutable
data class MessageViewState(val id: String, val sender: String, val text: String, val time: String,
    val agent: Boolean = false, val self: Boolean = false, val continuation: Boolean = false, val status: MessageStatus? = null,
    val progress: String? = null, val recipient: String? = null, val latest: Boolean = false,
    val result: dev.atoll.app.ui.components.ResultViewState? = null, val trail: ProgressTrailViewState? = null, val subTasks: List<SubTaskViewState> = emptyList(),
    val attachments: List<MessageAttachmentViewState> = emptyList(), val relatedCalls: List<RelatedCallViewState> = emptyList())
sealed interface TimelineEntry {
    val key: String
    data class Message(val value: MessageViewState) : TimelineEntry { override val key get() = value.id }
    data class Day(override val key: String, val text: String) : TimelineEntry
    data class Notice(override val key: String, val text: String) : TimelineEntry
}
@Immutable
data class TaskViewState(val id: String, val title: String, val kind: String, val assignee: String, val state: String)
@Immutable
data class ConversationViewState(val channel: ChannelViewState, val agents: List<AgentViewState> = emptyList(),
    val entries: List<TimelineEntry> = emptyList(), val tasks: List<TaskViewState> = emptyList(),
    val mineOnly: Boolean = true, val actorFilters: Set<String> = emptySet(), val loadingOlder: Boolean = false,
    val hasOlder: Boolean = true, val connection: String = "已连接", val waitingCount: Int = 0, val filesOpen: Boolean = false, val terminalOpen: Boolean = false, val runningCount: Int = 0, val newMessages: Int = 0,
    val emptyKind: TimelineEmptyKind? = null, val emptySettled: Boolean = false, val historyStartLabel: String? = null)
@Immutable
data class ComposerViewState(val text: String = "", val agent: String = "", val model: String = "", val effort: String = "", val sending: Boolean = false)
enum class ChannelTab { Conversation, Tasks }
enum class TimelineEmptyKind { Channel, Mine, ActorFilter }
