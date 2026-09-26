package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.MessageKind
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.repository.AiRepository
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import com.iamode.app.domain.util.LanguageDetector
import com.iamode.app.domain.util.OfflineTemplates
import com.iamode.app.domain.util.PhoneNumbers
import java.util.UUID
import javax.inject.Inject

/** When the user can't pick up: text the caller in their language, based on the user's situation. */
class HandleMissedCallUseCase @Inject constructor(
    private val conversations: ConversationRepository,
    private val resolveContact: ResolveContactUseCase,
    private val settingsRepo: SettingsRepository,
    private val sessions: SessionRepository,
    private val ai: AiRepository,
    private val alerts: AlertRepository,
    private val scheduler: ReplyScheduler,
    private val situationProvider: SituationProvider,
) {
    suspend operator fun invoke(rawNumber: String) {
        val session = sessions.current() ?: return
        val settings = settingsRepo.current()
        val number = PhoneNumbers.normalize(rawNumber)
        if (number.isBlank()) return // private / hidden number
        val now = System.currentTimeMillis()

        val who = resolveContact(Channel.SMS, number, rawNumber, settings)
        var conv = conversations.findOpen(Channel.SMS, number, session.id) ?: Conversation(
            id = UUID.randomUUID().toString(), channel = Channel.SMS, address = number, displayName = who.displayName,
            relationship = who.relationship, status = ConversationStatus.ANALYZING, sessionId = session.id,
            createdAt = now, updatedAt = now,
        ).also { conversations.upsert(it) }

        conversations.addMessage(Message(conversationId = conv.id, fromMe = false, kind = MessageKind.CALL,
            text = "Missed call", timestamp = now, externalId = "call-$now"))
        val callsLast10 = conversations.recentMessages(conv.id, 20)
            .count { it.kind == MessageKind.CALL && now - it.timestamp < 10 * 60_000 }

        val allowed = settings.missedCallReplies && (conv.relationship != Relationship.UNKNOWN || settings.replyToUnknownCallers)
        if (!allowed) {
            val reason = if (!settings.missedCallReplies) "Missed-call replies are turned off" else "Unknown number, so no message was sent"
            conversations.upsert(conv.copy(status = ConversationStatus.CALLBACK, reason = reason, updatedAt = now))
            alerts.add(AlertKind.CALLBACK, "Missed call from ${conv.displayName}", reason, conv.id)
            return
        }
        if (conv.status == ConversationStatus.QUEUED) scheduler.cancel(conv.id)
        conv = conv.copy(status = ConversationStatus.ANALYZING, updatedAt = now)
        conversations.upsert(conv)

        // Language: their last message > contact setting > user default
        val history = conversations.messagesWithPerson(conv.displayName, number, 12)
        val lastText = history.lastOrNull { !it.fromMe && it.kind == MessageKind.TEXT }?.text
        val contactLanguage = who.contact?.language
        val (language, source) = when {
            lastText != null -> LanguageDetector.detect(lastText) to "conversation"
            contactLanguage != null -> Language(contactLanguage, who.contact?.script ?: Script.ROMAN) to "contact"
            else -> Language(settings.defaultLanguage, settings.defaultScript) to "default"
        }
        val situation = situationProvider.current()
        val aiText = ai.missedCallReply(conv, history, callsLast10, language, source, settings, situation).getOrNull()
        val text = aiText ?: OfflineTemplates.missedCall(language, situation.status, conv.relationship, settings.gender)

        val at = System.currentTimeMillis() + settings.undoSeconds * 1000L
        conv = conv.copy(
            status = ConversationStatus.QUEUED, pendingReply = text, isCallReply = true, closing = false,
            aiGenerated = aiText != null, language = language, sendAt = at,
            reason = "Missed call while ${situation.status.label.lowercase()}", updatedAt = System.currentTimeMillis(),
        )
        conversations.upsert(conv)
        scheduler.schedule(conv.id, at)
    }
}
