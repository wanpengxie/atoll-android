package dev.atoll.app.app

/** Dismiss presentation only; the controller's failure still supports retry and diagnostics. */
internal class ChannelErrorNotices {
    private val dismissed = mutableMapOf<String, String>()
    fun dismiss(channel: String, message: String) { dismissed[channel] = message }
    fun visible(channel: String, message: String?): String? {
        if (message == null || dismissed[channel] != message) dismissed.remove(channel)
        return message.takeUnless { dismissed[channel] == it }
    }
    fun reset(channel: String) { dismissed.remove(channel) }
    fun clear() { dismissed.clear() }
}
