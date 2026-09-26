package com.iamode.app.domain.model

data class Analysis(
    val language: Language,
    val tone: String,
    val summary: String,
    val mentionsMoney: Boolean,
    val asksCommitment: Boolean,
    val crisis: Boolean,
    val needsReply: Boolean,
    val conversationState: ConversationState,
    val style: ReplyStyle,
)

data class AiResult(val analysis: Analysis, val reply: String)

/** A normalized incoming message from any channel. */
data class IncomingMessage(
    val channel: Channel,
    val address: String,
    val displayName: String,
    val text: String,
    val externalId: String,
    val timestamp: Long,
    val subject: String? = null,
    val gmail: GmailMeta? = null,
    /** Group chat: [displayName] is the group, and [text] starts with the sender's name. */
    val isGroup: Boolean = false,
    /** Earlier messages (Gmail thread, unread group messages) stored for the AI's context, never replied to. */
    val context: List<ContextMessage> = emptyList(),
)

data class ContextMessage(val fromMe: Boolean, val text: String, val timestamp: Long, val externalId: String)

data class GmailMeta(
    val accountEmail: String,
    val threadId: String,
    val messageIdHeader: String?,
    val references: String?,
)

sealed interface SendResult {
    data object Sent : SendResult
    data class Failed(val reason: String) : SendResult
}

/** Why the AI couldn't be used, in words the user understands, and whether trying again can help. */
enum class AiFailure(val userMessage: String, val retryable: Boolean) {
    OFFLINE("No internet right now. IA Mode will try again when you're back online", true),
    SERVER_BUSY("The AI is busy right now. IA Mode will try again in a minute", true),
    AUTH("IA Mode couldn't sign in to its server. Check the app setup", false),
    REJECTED("The server couldn't process this message, so reply yourself", false),
    UNKNOWN("IA Mode couldn't reach the AI. It will try again shortly", true),
}

class AiException(val failure: AiFailure, cause: Throwable? = null) : Exception(failure.userMessage, cause)
