package com.iamode.app.domain.model

data class Conversation(
    val id: String,
    val channel: Channel,
    /** Phone number (SMS/calls), email (Gmail) or chat title (WhatsApp). */
    val address: String,
    val displayName: String,
    val relationship: Relationship,
    val status: ConversationStatus,
    val sessionId: String,
    val autopilot: Boolean = false,
    val autoTurns: Int = 0,
    val tone: String? = null,
    val style: ReplyStyle? = null,
    val language: Language? = null,
    val summary: String? = null,
    val pendingReply: String? = null,
    val closing: Boolean = false,
    val followUp: Boolean = false,
    val mentionsMoney: Boolean = false,
    val asksCommitment: Boolean = false,
    val reason: String? = null,
    val sendAt: Long? = null,
    val aiGenerated: Boolean = true,
    val isCallReply: Boolean = false,
    val recap: String? = null,
    val subject: String? = null,
    val gmailAccount: String? = null,
    val gmailThreadId: String? = null,
    val gmailMessageId: String? = null,
    val gmailReferences: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long? = null,
)

data class Message(
    val id: Long = 0,
    val conversationId: String,
    val fromMe: Boolean,
    val kind: MessageKind = MessageKind.TEXT,
    val text: String,
    val timestamp: Long,
    val auto: Boolean = false,
    /** Source id used to de-duplicate (notification key + time, Gmail message id). */
    val externalId: String? = null,
)

data class Contact(
    val id: String,
    val displayName: String,
    val phone: String? = null,
    val email: String? = null,
    val relationship: Relationship,
    /** null = detect automatically from their messages. */
    val language: LanguageCode? = null,
    val script: Script = Script.ROMAN,
)

data class Alert(
    val id: Long,
    val kind: AlertKind,
    val title: String,
    val body: String,
    val conversationId: String?,
    val createdAt: Long,
)

data class Session(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startedBy: StartSource = StartSource.MANUAL,
    /** Which automatic trigger is keeping IA Mode on (e.g. "driving", "meeting:42:…"). */
    val autoKey: String? = null,
    val autoReason: String? = null,
)
