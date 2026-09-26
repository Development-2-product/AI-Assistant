package com.iamode.app.service.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.iamode.app.core.datastore.SeenMessageStore
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.model.ContextMessage
import com.iamode.app.domain.model.IncomingMessage
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.usecase.ConversationActionsUseCase
import com.iamode.app.domain.usecase.ProcessIncomingMessageUseCase
import com.iamode.app.domain.util.MentionDetector
import com.iamode.app.service.call.CallTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The system keeps this service bound, so IA Mode needs no foreground service.
 * It reads chat apps, notices new Gmail, and hosts the call-state listener.
 */
@AndroidEntryPoint
class IANotificationListener : NotificationListenerService() {

    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var processIncoming: ProcessIncomingMessageUseCase
    @Inject lateinit var actions: ConversationActionsUseCase
    @Inject lateinit var replySender: ChatReplySender
    @Inject lateinit var seen: SeenMessageStore
    @Inject lateinit var callTracker: CallTracker
    @Inject lateinit var log: DiagnosticsLog
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    /** One pipeline run at a time per chat, so replies stay in order. */
    private val chatLocks = ConcurrentHashMap<String, Mutex>()

    override fun onListenerConnected() {
        callTracker.start()
        // The process may have been recreated while a chat notification remained active. Re-register
        // only the live RemoteInput actions Android provides now; never persist or revive PendingIntents.
        activeNotifications.orEmpty().forEach { sbn ->
            ChatNotificationParser.parse(sbn)?.let { replySender.registerActive(sbn.key, it) }
        }
        log.record("Listener", true, "Notification access connected")
    }

    override fun onListenerDisconnected() {
        callTracker.stop()
        replySender.clearActiveActions()
        log.record("Listener", false, "Notification access disconnected (Android stopped it). Reopen IA Mode")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // WhatsApp/WhatsApp Business may replace or retract a notification. Its RemoteInput action
        // is then no longer a safe reply channel, even if the app process remains alive.
        replySender.unregisterNotification(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        when (sbn.packageName) {
            OUTLOOK_PACKAGE -> com.iamode.app.data.mail.MailSyncWorker.syncNow(applicationContext) // near-instant Outlook sync
            GMAIL_PACKAGE -> com.iamode.app.data.mail.MailSyncWorker.syncNow(applicationContext) // email intelligence, IA Mode on or off
            in ChatNotificationParser.packages -> handleChat(sbn)
        }
    }

    private fun handleChat(sbn: StatusBarNotification) {
        val chat = ChatNotificationParser.parse(sbn) ?: return
        replySender.registerActive(sbn.key, chat)

        scope.launch {
            val session = sessions.current() ?: return@launch
            val s = settings.current()
            if (chat.channel !in s.enabledApps) return@launch
            if (chat.isGroup && !s.groupReplies) return@launch

            chatLocks.getOrPut("${chat.channel}:${chat.chat}") { Mutex() }.withLock {
                // Unread group messages that don't mention you become context for the one that does.
                val groupContext = mutableListOf<ContextMessage>()
                for (m in chat.messages.sortedBy { it.timestamp }) {
                    // Only act on messages that arrived after IA Mode was turned on.
                    if (m.timestamp < session.startedAt) continue
                    val id = "${chat.channel.name.lowercase()}:${chat.chat}:${m.timestamp}:${m.text.hashCode()}"
                    val text = if (chat.isGroup && !m.fromMe) "${m.sender ?: "Someone"}: ${m.text}" else m.text
                    if (!seen.markIfNew(id)) continue

                    if (m.fromMe) {
                        if (!replySender.wasSentByUs(chat.channel, chat.chat, m.text)) {
                            actions.userRepliedManually(chat.channel, chat.chat)
                        }
                        groupContext += ContextMessage(true, m.text, m.timestamp, id)
                        continue
                    }
                    if (chat.isGroup && !MentionDetector.isMentioned(m.text, s.mentionNames)) {
                        groupContext += ContextMessage(false, text, m.timestamp, id)
                        continue
                    }
                    processIncoming(
                        IncomingMessage(
                            channel = chat.channel, address = chat.chat, displayName = chat.chat,
                            text = text, externalId = id, timestamp = m.timestamp,
                            isGroup = chat.isGroup, context = groupContext.toList(),
                        )
                    )
                    groupContext.clear()
                }
            }
        }
    }

    private companion object {
        const val GMAIL_PACKAGE = "com.google.android.gm"
        const val OUTLOOK_PACKAGE = "com.microsoft.office.outlook"
    }
}
