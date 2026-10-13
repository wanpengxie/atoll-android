package dev.atoll.app.ui.screens

/** Labels are presentation; consumers dispatch on these identities. */
sealed interface ChannelMenuAction {
    enum class Item(val label: String) : ChannelMenuAction {
        Members("成员"), Details("频道详情"), Timers("定时动作"), Resources("高级资源工具"),
        NewChild("新建子频道"), ExportPerformance("导出性能记录"), Restart("重启频道"),
        OpenFiles("打开文件"), CloseFiles("关闭文件"), Recent("最近阅读"), Stars("星标消息"),
        Running("后台任务"), OpenTerminal("打开终端"), CloseTerminal("关闭终端"), Waiting("等待区"),
        SelectAgent("选择 Agent"), Attachments("附件"), ComposerMore("更多操作"),
        Schedule("安排自动动作"), NewTask("新建任务"), WorkItems("工作项"), Mine("与我相关"),
        All("全部"), StatusFilter("状态筛选"), TypeFilter("类型筛选")
    }
    data class Process(val messageId: String) : ChannelMenuAction
    data class Task(val taskId: String) : ChannelMenuAction
}
