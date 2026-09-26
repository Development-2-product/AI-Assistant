package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.MessageKind
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.SessionRepository
import javax.inject.Inject

class BuildSessionSummaryUseCase @Inject constructor(
    private val sessions: SessionRepository,
    private val conversations: ConversationRepository,
) {
    suspend operator fun invoke(sessionId: String): SessionSummary? {
        val session = sessions.get(sessionId) ?: return null
        val convs = conversations.forSession(sessionId)
        var sent = 0
        var auto = 0
        var calls = 0
        convs.forEach { c ->
            conversations.recentMessages(c.id, 500).filter { it.timestamp >= session.startedAt }.forEach { m ->
                when {
                    m.kind == MessageKind.CALL -> calls++
                    m.fromMe -> { sent++; if (m.auto) auto++ }
                }
            }
        }
        return SessionSummary(
            session = session,
            conversations = convs.sortedByDescending { it.updatedAt },
            repliesSent = sent,
            autoReplies = auto,
            missedCalls = calls,
            emails = convs.count { it.channel == Channel.GMAIL },
        )
    }
}
