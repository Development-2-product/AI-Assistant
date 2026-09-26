package com.iamode.app.domain.model

/** "While you were busy": everything that happened during one IA Mode session. */
data class SessionSummary(
    val session: Session,
    val conversations: List<Conversation>,
    val repliesSent: Int,
    val autoReplies: Int,
    val missedCalls: Int,
    val emails: Int,
) {
    val needsYou: List<Conversation>
        get() = conversations.filter {
            it.status in setOf(ConversationStatus.PENDING_APPROVAL, ConversationStatus.CRISIS, ConversationStatus.CALLBACK)
        }

    /** Money or commitments came up; IA Mode replied without agreeing to anything. */
    val followUps: List<Conversation>
        get() = conversations.filter { (it.mentionsMoney || it.asksCommitment) && it !in needsYou }

    val handled: List<Conversation>
        get() = conversations.filter { it !in needsYou && it.status != ConversationStatus.SKIPPED }

    val durationMinutes: Long get() = ((session.endedAt ?: System.currentTimeMillis()) - session.startedAt) / 60_000

    val headline: String
        get() = buildString {
            append("${conversations.size} chat").append(if (conversations.size == 1) "" else "s")
            if (repliesSent > 0) append(", $repliesSent repl").append(if (repliesSent == 1) "y" else "ies").append(" sent")
            if (needsYou.isNotEmpty()) append(", ${needsYou.size} need").append(if (needsYou.size == 1) "s" else "").append(" you")
        }
}
