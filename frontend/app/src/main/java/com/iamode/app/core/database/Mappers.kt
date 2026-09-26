package com.iamode.app.core.database

import com.iamode.app.core.database.entity.AlertEntity
import com.iamode.app.core.database.entity.ContactEntity
import com.iamode.app.core.database.entity.ConversationEntity
import com.iamode.app.core.database.entity.MessageEntity
import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.MessageKind
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.model.Script

private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

fun ConversationEntity.toDomain() = Conversation(
    id = id, channel = enumOr(channel, Channel.WHATSAPP), address = address, displayName = displayName,
    relationship = enumOr(relationship, Relationship.UNKNOWN), status = enumOr(status, ConversationStatus.ENDED),
    sessionId = sessionId, autopilot = autopilot, autoTurns = autoTurns, tone = tone,
    style = style?.let { enumOr(it, ReplyStyle.FRIENDLY) },
    language = languageCode?.let { Language(enumOr(it, LanguageCode.EN), enumOr(script, Script.ROMAN)) },
    summary = summary, pendingReply = pendingReply, closing = closing, followUp = followUp,
    mentionsMoney = mentionsMoney, asksCommitment = asksCommitment, reason = reason, sendAt = sendAt,
    aiGenerated = aiGenerated, isCallReply = isCallReply, recap = recap, subject = subject,
    gmailAccount = gmailAccount, gmailThreadId = gmailThreadId, gmailMessageId = gmailMessageId,
    gmailReferences = gmailReferences, createdAt = createdAt, updatedAt = updatedAt, endedAt = endedAt,
)

fun Conversation.toEntity() = ConversationEntity(
    id = id, channel = channel.name, address = address, displayName = displayName, relationship = relationship.name,
    status = status.name, sessionId = sessionId, autopilot = autopilot, autoTurns = autoTurns, tone = tone,
    style = style?.name, languageCode = language?.code?.name, script = language?.script?.name, summary = summary,
    pendingReply = pendingReply, closing = closing, followUp = followUp, mentionsMoney = mentionsMoney,
    asksCommitment = asksCommitment, reason = reason, sendAt = sendAt, aiGenerated = aiGenerated,
    isCallReply = isCallReply, recap = recap, subject = subject, gmailAccount = gmailAccount,
    gmailThreadId = gmailThreadId, gmailMessageId = gmailMessageId, gmailReferences = gmailReferences,
    createdAt = createdAt, updatedAt = updatedAt, endedAt = endedAt,
)

fun MessageEntity.toDomain() = Message(
    id = id, conversationId = conversationId, fromMe = fromMe, kind = enumOr(kind, MessageKind.TEXT),
    text = text, timestamp = timestamp, auto = auto, externalId = externalId,
)

fun Message.toEntity() = MessageEntity(
    id = id, conversationId = conversationId, fromMe = fromMe, kind = kind.name, text = text,
    timestamp = timestamp, auto = auto, externalId = externalId,
)

fun ContactEntity.toDomain() = Contact(
    id = id, displayName = displayName, phone = phone, email = email,
    relationship = enumOr(relationship, Relationship.FRIEND),
    language = languageCode?.let { enumOr(it, LanguageCode.EN) }, script = enumOr(script, Script.ROMAN),
)

fun Contact.toEntity() = ContactEntity(
    id = id, displayName = displayName, phone = phone, email = email?.lowercase(),
    relationship = relationship.name, languageCode = language?.name, script = script.name,
)

fun AlertEntity.toDomain() = Alert(
    id = id, kind = enumOr(kind, AlertKind.SENT), title = title, body = body,
    conversationId = conversationId, createdAt = createdAt,
)
