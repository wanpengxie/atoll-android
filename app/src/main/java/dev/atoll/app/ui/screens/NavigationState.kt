package dev.atoll.app.ui.screens

import androidx.compose.runtime.Immutable

enum class ChannelAccess(val label: String, val member: Boolean = false) {
    MemberActive("", true), Available("可访问", true), Connecting("连接中", true), Inaccessible("不可访问", true), MemberStale("离线缓存", true), MemberUnavailable("暂不可用", true),
    ObserverActive("只读旁观"), ObserverStale("旁观中断"), Discoverable("可发现"), Denied("无权访问"), Loading("确认中")
}
enum class UnreadPhase { Ready, Recovering, Unknown }
enum class ConnectionPhase { OPEN, CONNECTING, RECONNECTING, CLOSED }
@Immutable data class AgentActivityViewState(val id: String, val name: String, val startedAtMillis: Long)
@Immutable data class AccountViewState(val id: String, val name: String = "已登录用户", val root: Boolean = false)
@Immutable data class UpgradeViewState(val current: String, val latest: String = "最新版", val phase: String = "available", val detail: String = "") {
    fun label(connection: ConnectionPhase): String = when (phase) {
        "starting" -> "准备升级…"; "downloading" -> "正在下载…"; "verifying" -> "正在校验…"
        "installing" -> "正在安装…"; "restarting" -> if (connection == ConnectionPhase.OPEN) "正在重启…" else "正在重连…"
        "failed" -> "升级失败，重试"; "unsupported", "unavailable" -> detail.ifEmpty { "当前节点升级能力不可用" }
        else -> "升级到 $latest"
    }
    val canStart get() = phase == "available" || phase == "failed"
}
@Immutable data class ChannelNavigationViewState(val channels: List<ChannelViewState>, val connection: ConnectionPhase,
    val account: AccountViewState, val activeChannelId: String? = null, val upgrade: UpgradeViewState? = null)
fun activityDuration(startedAt: Long, now: Long): String {
    val seconds = ((now - startedAt).coerceAtLeast(0) / 1000)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
fun unreadLabel(count: Int, phase: UnreadPhase): String = when (phase) {
    UnreadPhase.Recovering -> "…"; UnreadPhase.Unknown -> "?"; UnreadPhase.Ready -> if (count > 99) "99+" else if (count > 0) count.toString() else ""
}

/** Hide descendants of collapsed branches without hiding peers at the same depth. */
fun visibleChannels(channels: List<ChannelViewState>, collapsed: Set<String>): List<ChannelViewState> {
    val byId = channels.associateBy { it.id }
    return channels.filter { channel ->
        var parent = channel.parentId
        val visited = mutableSetOf<String>()
        var visible = true
        while (parent != null && visited.add(parent)) {
            if (parent in collapsed) { visible = false; break }
            parent = byId[parent]?.parentId
        }
        visible
    }
}
