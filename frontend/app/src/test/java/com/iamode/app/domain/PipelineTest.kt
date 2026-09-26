package com.iamode.app.domain

import com.iamode.app.domain.model.AiException
import com.iamode.app.domain.model.AiFailure
import com.iamode.app.domain.model.AiResult
import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Analysis
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationState
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.IncomingMessage
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.model.Session
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.model.StartSource
import com.iamode.app.domain.model.Situation
import com.iamode.app.domain.model.SituationStatus
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.AiRepository
import com.iamode.app.domain.repository.AiRetryScheduler
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ContactRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.Notifier
import com.iamode.app.domain.repository.Phonebook
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import com.iamode.app.domain.usecase.EndConversationUseCase
import com.iamode.app.domain.usecase.ProcessIncomingMessageUseCase
import com.iamode.app.domain.usecase.ResolveContactUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end test of the reply pipeline with in-memory fakes (no Android, no network). */
class PipelineTest {

    private class FakeConversations : ConversationRepository {
        val convs = linkedMapOf<String, Conversation>()
        val msgs = mutableListOf<Message>()
        override fun observeForSession(sessionId: String): Flow<List<Conversation>> = emptyFlow()
        override fun observe(id: String): Flow<Conversation?> = emptyFlow()
        override fun observeMessages(conversationId: String): Flow<List<Message>> = emptyFlow()
        override suspend fun get(id: String) = convs[id]
        override suspend fun findOpen(channel: Channel, address: String, sessionId: String) =
            convs.values.lastOrNull { it.channel == channel && it.address == address && it.sessionId == sessionId && it.status.isOpen }
        override suspend fun upsert(conversation: Conversation) { convs[conversation.id] = conversation }
        override suspend fun addMessage(message: Message): Boolean {
            if (message.externalId != null && msgs.any { it.conversationId == message.conversationId && it.externalId == message.externalId }) return false
            msgs += message.copy(id = msgs.size + 1L); return true
        }
        override suspend fun recentMessages(conversationId: String, limit: Int) = msgs.filter { it.conversationId == conversationId }.takeLast(limit)
        override suspend fun messagesWithPerson(displayName: String, address: String, limit: Int) = msgs.takeLast(limit)
        override suspend fun queued() = convs.values.filter { it.status == ConversationStatus.QUEUED }
        override suspend fun forSession(sessionId: String) = convs.values.filter { it.sessionId == sessionId }
        override suspend fun deleteAll() { convs.clear(); msgs.clear() }
    }

    private class FakeAi : AiRepository {
        var fail: AiFailure? = null
        var reply = "Sure, I'll send it by evening."
        override suspend fun process(conversation: Conversation, messages: List<Message>, settings: UserSettings, situation: Situation) =
            fail?.let { Result.failure<AiResult>(AiException(it)) } ?: Result.success(AiResult(
                Analysis(Language(LanguageCode.EN, Script.ROMAN), "business", "Invoice request", false, false, false, true,
                    ConversationState.ONGOING, ReplyStyle.PROFESSIONAL), reply))
        override suspend fun restyle(conversation: Conversation, messages: List<Message>, style: ReplyStyle, settings: UserSettings, situation: Situation) = Result.success(reply)
        override suspend fun missedCallReply(conversation: Conversation, history: List<Message>, callsLast10Min: Int, language: Language, languageSource: String, settings: UserSettings, situation: Situation) = Result.success(reply)
        override suspend fun recap(conversation: Conversation, messages: List<Message>) = Result.success("Recap")
    }

    private class Recorder : Notifier, ReplyScheduler, AiRetryScheduler, AlertRepository {
        val scheduled = mutableListOf<String>(); val retries = mutableListOf<String>(); val approvals = mutableListOf<String>()
        val alerts = mutableListOf<AlertKind>()
        override fun approvalNeeded(conversation: Conversation, incomingText: String) { approvals += conversation.id }
        override fun cancel(conversationId: String) {}
        override fun replySent(conversation: Conversation, text: String, auto: Boolean) {}
        override fun crisis(conversation: Conversation) {}
        override fun followUp(conversation: Conversation, text: String) {}
        override fun sendFailed(conversation: Conversation, reason: String) {}
        override fun gmailReauthNeeded(email: String) {}
        override fun sessionSummary(summary: SessionSummary) {}
        override fun schedule(conversationId: String, atMillis: Long) { scheduled += conversationId }
        override fun scheduleRetry(conversationId: String) { retries += conversationId }
        override fun observeSince(timestamp: Long): Flow<List<Alert>> = emptyFlow()
        override suspend fun add(kind: AlertKind, title: String, body: String, conversationId: String?) { alerts += kind }
        override suspend fun clear() {}
    }

    private val convs = FakeConversations()
    private val ai = FakeAi()
    private val rec = Recorder()
    private val contacts = object : ContactRepository {
        val client = Contact("c1", "Anita", phone = null, email = "anita@acme.com", relationship = Relationship.CLIENT)
        override fun observeAll(): Flow<List<Contact>> = emptyFlow()
        override suspend fun findByPhone(phone: String): Contact? = null
        override suspend fun findByEmail(email: String) = client.takeIf { it.email == email }
        override suspend fun findByName(name: String) = client.takeIf { it.displayName == name }
        override suspend fun upsert(contact: Contact) {}
        override suspend fun delete(id: String) {}
    }
    private val settings = object : SettingsRepository {
        override val settings = MutableStateFlow(UserSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (UserSettings) -> UserSettings) { settings.value = transform(settings.value) }
    }
    private val sessions = object : SessionRepository {
        val s = Session("s1", 0L)
        override val activeSession = MutableStateFlow<Session?>(s)
        override val lastEnded: Flow<Session?> = emptyFlow()
        override suspend fun current(): Session? = activeSession.value
        override suspend fun get(id: String) = s
        override suspend fun start(source: StartSource, autoKey: String?, autoReason: String?) = s
        override suspend fun updateAutoTrigger(autoKey: String, autoReason: String) {}
        override suspend fun stop() { activeSession.value = null }
    }
    private val phonebook = object : Phonebook { override suspend fun nameForNumber(number: String): String? = null }
    private val situation = object : SituationProvider { override suspend fun current() = Situation(SituationStatus.MEETING, "") }

    private val pipeline = ProcessIncomingMessageUseCase(
        convs, ResolveContactUseCase(contacts, phonebook), settings, sessions, ai, rec, rec, rec, rec, situation,
        EndConversationUseCase(convs, ai, rec, rec, rec),
    )

    private fun msg(id: String, text: String = "Can you send the invoice?") =
        IncomingMessage(Channel.WHATSAPP, "Anita", "Anita", text, id, System.currentTimeMillis())

    @Test fun `client message is queued for automatic send`() = runBlocking {
        pipeline(msg("m1"))
        val c = convs.convs.values.single()
        assertEquals(ConversationStatus.QUEUED, c.status)
        assertEquals(listOf(c.id), rec.scheduled)
    }

    @Test fun `duplicate notification is ignored`() = runBlocking {
        pipeline(msg("m1")); pipeline(msg("m1"))
        assertEquals(1, convs.msgs.size)
        assertEquals(1, rec.scheduled.size)
    }

    @Test fun `offline asks the user and schedules a retry, never auto-sends`() = runBlocking {
        ai.fail = AiFailure.OFFLINE
        pipeline(msg("m1"))
        val c = convs.convs.values.single()
        assertEquals(ConversationStatus.PENDING_APPROVAL, c.status)
        assertTrue(rec.scheduled.isEmpty())
        assertEquals(listOf(c.id), rec.retries)
        assertEquals(listOf(c.id), rec.approvals)
    }

    @Test fun `retry succeeds once back online`() = runBlocking {
        ai.fail = AiFailure.SERVER_BUSY
        pipeline(msg("m1"))
        val id = convs.convs.keys.single()
        assertTrue(!pipeline.retry(id)) // still failing -> worker should retry later
        ai.fail = null
        assertTrue(pipeline.retry(id))
        assertEquals(ConversationStatus.QUEUED, convs.convs[id]!!.status)
    }

    @Test fun `auth failure is not retried`() = runBlocking {
        ai.fail = AiFailure.AUTH
        pipeline(msg("m1"))
        assertTrue(rec.retries.isEmpty())
    }

    @Test fun `group mention always needs approval and keeps earlier messages as context`() = runBlocking {
        val ctx = listOf(com.iamode.app.domain.model.ContextMessage(false, "Priya: movie tonight?", 1L, "g0"))
        pipeline(IncomingMessage(Channel.WHATSAPP, "College Gang", "College Gang", "Ravi: @Kasi you coming?", "g1",
            System.currentTimeMillis(), isGroup = true, context = ctx))
        val c = convs.convs.values.single()
        assertEquals(Relationship.GROUP, c.relationship)
        assertEquals(ConversationStatus.PENDING_APPROVAL, c.status)
        assertEquals(2, convs.msgs.size)
        assertTrue(rec.scheduled.isEmpty())
    }

    @Test fun `retry does nothing after IA Mode is turned off`() = runBlocking {
        ai.fail = AiFailure.OFFLINE
        pipeline(msg("m1"))
        val id = convs.convs.keys.single()
        sessions.stop()
        ai.fail = null
        assertTrue(pipeline.retry(id))
        assertEquals(ConversationStatus.PENDING_APPROVAL, convs.convs[id]!!.status)
    }
}
