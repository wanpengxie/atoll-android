package dev.atoll.app.app

/** Files replace the conversation body; the Tasks tab keeps that choice for the return trip. */
data class ChannelSurface(val mainTab: String = "conversation", val filesOpen: Boolean = false, val terminalOpen: Boolean = false, val resourceTab: Boolean = false) {
    val routeTab: String get() = if (mainTab == "tasks") "tasks" else if (terminalOpen) "terminal" else if (filesOpen) { if (resourceTab) "resources" else "files" } else "conversation"
    fun selectTab(tasks: Boolean) = copy(mainTab = if (tasks) "tasks" else "conversation")
    fun files(open: Boolean) = copy(mainTab = if (open) "conversation" else mainTab, filesOpen = open, terminalOpen = if (open) false else terminalOpen, resourceTab = if (open) false else resourceTab)
    fun resources() = copy(mainTab = "conversation", filesOpen = true, terminalOpen = false, resourceTab = true)
    fun terminal(open: Boolean) = copy(mainTab = if (open) "conversation" else mainTab, terminalOpen = open)
    fun encode(): String = "$mainTab:${if (filesOpen) 1 else 0}:${if (terminalOpen) 1 else 0}:${if (resourceTab) 1 else 0}"
    companion object {
        fun decode(value: String?): ChannelSurface {
            val parts = value.orEmpty().split(':')
            return ChannelSurface(if (parts.first() == "tasks") "tasks" else "conversation", parts.getOrNull(1) == "1", parts.getOrNull(2) == "1", parts.getOrNull(3) == "1")
        }
        fun fromRoute(tab: String) = ChannelSurface(if (tab == "tasks") "tasks" else "conversation", tab in setOf("files", "resources"), tab == "terminal", tab == "resources")
    }
}
