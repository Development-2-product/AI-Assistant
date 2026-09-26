package com.iamode.app.domain.repository

import com.iamode.app.domain.model.AiResult
import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.model.SendResult
import com.iamode.app.domain.model.Session
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.model.Situation
import com.iamode.app.domain.model.StartSource
import com.iamode.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeForSession(sessionId: String): Flow<List<Conversation>>
    fun observe(id: String): Flow<Conversation?>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun get(id: String): Conversation?
    suspend fun findOpen(channel: Channel, address: String, sessionId: String): Conversation?
    suspend fun upsert(conversation: Conversation)
    /** Returns false when the message was already stored (de-duplication). */
    suspend fun addMessage(message: Message): Boolean
    suspend fun recentMessages(conversationId: String, limit: Int): List<Message>
    /** Messages across every channel with this person, newest last. Used for missed-call context. */
    suspend fun messagesWithPerson(displayName: String, address: String, limit: Int): List<Message>
    suspend fun queued(): List<Conversation>
    suspend fun forSession(sessionId: String): List<Conversation>
    suspend fun deleteAll()
}

interface ContactRepository {
    fun observeAll(): Flow<List<Contact>>
    suspend fun findByPhone(phone: String): Contact?
    suspend fun findByEmail(email: String): Contact?
    suspend fun findByName(name: String): Contact?
    suspend fun upsert(contact: Contact)
    suspend fun delete(id: String)
}

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun current(): UserSettings
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

interface SessionRepository {
    val activeSession: Flow<Session?>
    /** The most recent finished session (for the "While you were busy" card). */
    val lastEnded: Flow<Session?>
    suspend fun current(): Session?
    suspend fun get(id: String): Session?
    suspend fun start(source: StartSource = StartSource.MANUAL, autoKey: String? = null, autoReason: String? = null): Session
    suspend fun updateAutoTrigger(autoKey: String, autoReason: String)
    suspend fun stop()
}

interface AlertRepository {
    fun observeSince(timestamp: Long): Flow<List<Alert>>
    suspend fun add(kind: AlertKind, title: String, body: String, conversationId: String?)
    suspend fun clear()
}

interface AiRepository {
    suspend fun process(conversation: Conversation, messages: List<Message>, settings: UserSettings, situation: Situation): Result<AiResult>
    suspend fun restyle(conversation: Conversation, messages: List<Message>, style: ReplyStyle, settings: UserSettings, situation: Situation): Result<String>
    suspend fun missedCallReply(
        conversation: Conversation, history: List<Message>, callsLast10Min: Int,
        language: Language, languageSource: String, settings: UserSettings, situation: Situation,
    ): Result<String>
    suspend fun recap(conversation: Conversation, messages: List<Message>): Result<String>
}

/** Sends a reply on the conversation's channel (WhatsApp reply action, Gmail API, SMS). */
interface MessageSender {
    suspend fun send(conversation: Conversation, text: String): SendResult
}

interface Notifier {
    fun approvalNeeded(conversation: Conversation, incomingText: String)
    fun cancel(conversationId: String)
    fun replySent(conversation: Conversation, text: String, auto: Boolean)
    fun crisis(conversation: Conversation)
    fun followUp(conversation: Conversation, text: String)
    fun sendFailed(conversation: Conversation, reason: String)
    fun gmailReauthNeeded(email: String)
    fun sessionSummary(summary: SessionSummary)
}

interface SituationProvider {
    suspend fun current(): Situation
}

/** Sends a queued reply after the undo window. */
interface ReplyScheduler {
    fun schedule(conversationId: String, atMillis: Long)
    fun cancel(conversationId: String)
}
