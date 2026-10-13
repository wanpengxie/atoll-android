package dev.atoll.app.app

/** Files replace the conversation body; the Tasks tab keeps that choice for the return trip. */
data class ChannelSurface(val mainTab: String = "conversation", val filesOpen: Boolean = false) {
    val routeTab: String get() = if (mainTab == "tasks") "tasks" else if (filesOpen) "files" else "conversation"
    fun selectTab(tasks: Boolean) = copy(mainTab = if (tasks) "tasks" else "conversation")
    fun files(open: Boolean) = copy(mainTab = if (open) "conversation" else mainTab, filesOpen = open)
    fun encode(): String = "$mainTab:${if (filesOpen) 1 else 0}"
    companion object {
        fun decode(value: String?) = ChannelSurface(if (value?.substringBefore(':') == "tasks") "tasks" else "conversation", value?.substringAfter(':') == "1")
        fun fromRoute(tab: String) = ChannelSurface(if (tab == "tasks") "tasks" else "conversation", tab == "files")
    }
}
