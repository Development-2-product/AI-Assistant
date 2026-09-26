package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.repository.AiRepository
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.Notifier
import com.iamode.app.domain.repository.ReplyScheduler
import javax.inject.Inject

class EndConversationUseCase @Inject constructor(
    private val conversations: ConversationRepository,
    private val ai: AiRepository,
    private val alerts: AlertRepository,
    private val notifier: Notifier,
    private val scheduler: ReplyScheduler,
) {
    suspend operator fun invoke(conversationId: String, reason: String) {
        val c = conversations.get(conversationId) ?: return
        scheduler.cancel(c.id)
        notifier.cancel(c.id)
        val ended = c.copy(status = ConversationStatus.ENDED, autopilot = false, pendingReply = null, sendAt = null,
            reason = reason, endedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        conversations.upsert(ended)

        val messages = conversations.recentMessages(c.id, 40)
        val recap = if (messages.count { it.fromMe } > 0) ai.recap(ended, messages).getOrNull() else null
        if (recap != null) conversations.upsert(ended.copy(recap = recap))
        alerts.add(AlertKind.ENDED, "Conversation with ${c.displayName} ended",
            listOfNotNull(recap, "$reason.").joinToString(" "), c.id)
    }
}
