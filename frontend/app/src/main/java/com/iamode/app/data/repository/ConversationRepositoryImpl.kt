package com.iamode.app.data.repository

import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.database.dao.MessageDao
import com.iamode.app.core.database.toDomain
import com.iamode.app.core.database.toEntity
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) : ConversationRepository {

    override fun observeForSession(sessionId: String): Flow<List<Conversation>> =
        conversationDao.observeForSession(sessionId).map { list -> list.map { it.toDomain() } }

    override fun observe(id: String): Flow<Conversation?> = conversationDao.observe(id).map { it?.toDomain() }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observe(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String) = conversationDao.get(id)?.toDomain()

    override suspend fun findOpen(channel: Channel, address: String, sessionId: String) =
        conversationDao.findOpen(channel.name, address, sessionId)?.toDomain()

    override suspend fun upsert(conversation: Conversation) = conversationDao.upsert(conversation.toEntity())

    override suspend fun addMessage(message: Message): Boolean = messageDao.insert(message.toEntity()) != -1L

    override suspend fun recentMessages(conversationId: String, limit: Int) =
        messageDao.recent(conversationId, limit).map { it.toDomain() }

    override suspend fun messagesWithPerson(displayName: String, address: String, limit: Int) =
        messageDao.withPerson(displayName, address, limit).map { it.toDomain() }

    override suspend fun queued() = conversationDao.queued().map { it.toDomain() }

    override suspend fun forSession(sessionId: String) = conversationDao.forSession(sessionId).map { it.toDomain() }

    override suspend fun deleteAll() {
        messageDao.deleteAll()
        conversationDao.deleteAll()
    }
}
