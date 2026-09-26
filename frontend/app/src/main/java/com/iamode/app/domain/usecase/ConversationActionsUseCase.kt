package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.repository.AiRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.Notifier
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import javax.inject.Inject

/** Every user action on a conversation, from the app UI or from a notification. */
class ConversationActionsUseCase @Inject constructor(
    private val conversations: ConversationRepository,
    private val sessions: SessionRepository,
    private val scheduler: ReplyScheduler,
    private val notifier: Notifier,
    private val sendReply: SendReplyUseCase,
    private val endConversation: EndConversationUseCase,
    private val ai: AiRepository,
    private val settings: SettingsRepository,
    private val situation: SituationProvider,
) {
    /** Approve the pending reply. [handleChat] = keep replying automatically until the chat ends. */
    suspend fun approve(id: String, editedText: String? = null, handleChat: Boolean = true) {
        val c = conversations.get(id) ?: return
        if (c.status != ConversationStatus.PENDING_APPROVAL && c.status != ConversationStatus.CRISIS) return
        val text = editedText ?: c.pendingReply
        if (text.isNullOrBlank()) return
        conversations.upsert(c.copy(pendingReply = text, autopilot = handleChat,
            autoTurns = if (handleChat) 0 else c.autoTurns))
        sendReply(id, auto = false)
    }

    suspend fun editReply(id: String, text: String) {
        val c = conversations.get(id) ?: return
        conversations.upsert(c.copy(pendingReply = text))
    }

    suspend fun dontReply(id: String) = endConversation(id, "You chose not to reply")

    suspend fun endChat(id: String) = endConversation(id, "You ended the conversation")

    suspend fun undoSend(id: String) {
        scheduler.cancel(id)
        val c = conversations.get(id) ?: return
        if (c.status != ConversationStatus.QUEUED) return
        conversations.upsert(
            if (c.isCallReply) c.copy(status = ConversationStatus.CALLBACK, pendingReply = null, sendAt = null,
                isCallReply = false, reason = "You stopped the automatic message")
            else c.copy(status = ConversationStatus.PENDING_APPROVAL, autopilot = false, sendAt = null,
                reason = "You stopped the automatic send")
        )
    }

    suspend fun sendNow(id: String) {
        scheduler.cancel(id)
        sendReply(id, auto = true)
    }

    suspend fun takeOver(id: String) {
        val c = conversations.get(id) ?: return
        if (c.status == ConversationStatus.QUEUED) scheduler.cancel(id)
        conversations.upsert(c.copy(autopilot = false,
            status = if (c.status == ConversationStatus.QUEUED) ConversationStatus.PENDING_APPROVAL else c.status,
            reason = "You took over this chat"))
    }

    suspend fun letIAModeHandle(id: String) {
        val c = conversations.get(id) ?: return
        conversations.upsert(c.copy(autopilot = true, autoTurns = 0))
    }

    suspend fun markCalledBack(id: String) {
        val c = conversations.get(id) ?: return
        conversations.upsert(c.copy(status = ConversationStatus.ENDED, reason = "You called them back",
            endedAt = System.currentTimeMillis()))
        notifier.cancel(id)
    }

    suspend fun restyle(id: String, style: ReplyStyle): Result<String> {
        val c = conversations.get(id) ?: return Result.failure(IllegalStateException("Conversation not found"))
        val messages = conversations.recentMessages(id, 20)
        return ai.restyle(c, messages, style, settings.current(), situation.current()).onSuccess { text ->
            conversations.get(id)?.let { conversations.upsert(it.copy(pendingReply = text, style = style)) }
        }
    }

    /** The user typed a reply themselves in WhatsApp/Gmail: stop IA Mode from replying in that chat. */
    suspend fun userRepliedManually(channel: Channel, address: String) {
        val session = sessions.current() ?: return
        val c = conversations.findOpen(channel, address, session.id) ?: return
        scheduler.cancel(c.id)
        notifier.cancel(c.id)
        conversations.upsert(c.copy(autopilot = false, pendingReply = null, sendAt = null,
            status = ConversationStatus.WAITING, reason = "You replied yourself, so IA Mode stepped back"))
    }
}
