package com.iamode.app.service.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.iamode.app.domain.model.Channel

data class ParsedChatMessage(val text: String, val timestamp: Long, val fromMe: Boolean, val sender: String?)

data class ParsedChat(
    val channel: Channel,
    /** Contact name as saved in the app, the number if not saved, or the group's name. */
    val chat: String,
    val isGroup: Boolean,
    val messages: List<ParsedChatMessage>,
    val replyAction: Notification.Action?,
)

/**
 * Reads WhatsApp, WhatsApp Business, Telegram and Instagram DM notifications.
 * All four use Android's standard messaging notification with a Reply button, so one parser covers them.
 */
object ChatNotificationParser {

    private val PACKAGES: Map<String, Channel> = mapOf(
        "com.whatsapp" to Channel.WHATSAPP,
        "com.whatsapp.w4b" to Channel.WHATSAPP_BUSINESS,
        "org.telegram.messenger" to Channel.TELEGRAM,
        "org.telegram.messenger.web" to Channel.TELEGRAM,
        "org.thunderdog.challegram" to Channel.TELEGRAM,
        "com.instagram.android" to Channel.INSTAGRAM,
    )

    private val COUNT_SUFFIX = Regex("""\s*\(\d+\s+\w+\)\s*$""")
    private val SUMMARY_TEXT = Regex("""^\d+\s+new messages?|^\d+ messages from \d+ chats""", RegexOption.IGNORE_CASE)
    private val APP_TITLES = setOf("whatsapp", "whatsapp business", "telegram", "instagram")

    fun channelFor(packageName: String): Channel? = PACKAGES[packageName]

    val packages: Set<String> get() = PACKAGES.keys

    fun parse(sbn: StatusBarNotification): ParsedChat? {
        val channel = channelFor(sbn.packageName) ?: return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val replyAction = findReplyAction(n)
        // Telegram and Instagram also notify about likes, follows, calls etc. Only real chats have a Reply button.
        if (replyAction == null && channel != Channel.WHATSAPP && channel != Channel.WHATSAPP_BUSINESS) return null

        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.replace(COUNT_SUFFIX, "")?.trim()
        if (title.isNullOrBlank() || title.lowercase() in APP_TITLES) return null

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            val me = style.user.name?.toString()
            val messages = style.messages.mapNotNull { m ->
                val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = m.person?.name?.toString()
                val fromMe = m.person == null || (me != null && sender == me)
                ParsedChatMessage(text, m.timestamp, fromMe, sender)
            }
            val chatName = if (style.isGroupConversation) style.conversationTitle?.toString()?.replace(COUNT_SUFFIX, "")?.trim() ?: title
            else title
            return ParsedChat(channel, chatName, style.isGroupConversation, messages, replyAction)
        }

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        if (SUMMARY_TEXT.containsMatchIn(text)) return null
        return ParsedChat(channel, title, false, listOf(ParsedChatMessage(text, sbn.postTime, false, title)), replyAction)
    }

    private fun findReplyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
}
