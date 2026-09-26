package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.MessageKind
import com.iamode.app.domain.model.SendResult
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.MessageSender
import com.iamode.app.domain.repository.Notifier
import javax.inject.Inject

class SendReplyUseCase @Inject constructor(
    private val conversations: ConversationRepository,
    private val sender: MessageSender,
    private val alerts: AlertRepository,
    private val notifier: Notifier,
    private val endConversation: EndConversationUseCase,
) {
    suspend operator fun invoke(conversationId: String, auto: Boolean) {
        val c = conversations.get(conversationId) ?: return
        val text = c.pendingReply?.trim().orEmpty()
        if (text.isEmpty() || !c.status.isOpen) return
        notifier.cancel(c.id)

        when (val result = sender.send(c, text)) {
            is SendResult.Failed -> {
                conversations.upsert(c.copy(status = ConversationStatus.PENDING_APPROVAL, autopilot = false, sendAt = null,
                    reason = "Couldn't send: ${result.reason}", updatedAt = System.currentTimeMillis()))
                alerts.add(AlertKind.ERROR, "Couldn't send to ${c.displayName}", result.reason, c.id)
                notifier.sendFailed(c, result.reason)
                return
            }
            SendResult.Sent -> Unit
        }

        val now = System.currentTimeMillis()
        conversations.addMessage(Message(conversationId = c.id, fromMe = true,
            kind = if (c.isCallReply) MessageKind.CALL_REPLY else MessageKind.TEXT,
            text = text, timestamp = now, auto = auto, externalId = "me-$now"))

        val title = when (c.channel) {
            Channel.GMAIL -> "Mail sent to ${c.displayName}" + (c.subject?.let { " · Re: $it" } ?: "")
            Channel.SMS -> "SMS sent to ${c.displayName}" + if (c.isCallReply) " after missed call" else ""
            Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS, Channel.TELEGRAM, Channel.INSTAGRAM ->
                "${c.channel.label} reply action handed to ${c.displayName}"
        }
        alerts.add(AlertKind.SENT, (if (auto) "Auto-replied · " else "") + title, text, c.id)
        notifier.replySent(c, text, auto)
        if (c.followUp) {
            alerts.add(AlertKind.FOLLOW_UP, "Follow up with ${c.displayName}",
                "They asked about money or a commitment. IA Mode replied without agreeing to anything.", c.id)
            notifier.followUp(c, text)
        }

        val next = c.copy(
            autoTurns = if (auto && !c.isCallReply) c.autoTurns + 1 else c.autoTurns,
            pendingReply = null, sendAt = null, followUp = false, updatedAt = now,
            status = if (c.isCallReply) ConversationStatus.CALLBACK else ConversationStatus.WAITING,
            reason = if (c.isCallReply) "Call them back when you're free" else c.reason,
            isCallReply = false,
        )
        conversations.upsert(next)
        if (c.closing && !c.isCallReply) endConversation(c.id, "Sent a closing reply and stopped")
    }
}
