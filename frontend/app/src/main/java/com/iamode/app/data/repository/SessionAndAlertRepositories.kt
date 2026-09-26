package com.iamode.app.data.repository

import com.iamode.app.core.database.dao.AlertDao
import com.iamode.app.core.database.dao.SessionDao
import com.iamode.app.core.database.entity.AlertEntity
import com.iamode.app.core.database.entity.SessionEntity
import com.iamode.app.core.database.toDomain
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Session
import com.iamode.app.domain.model.StartSource
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.SessionRepository
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(private val dao: SessionDao) : SessionRepository {
    override val activeSession = dao.observeActive().map { it?.toDomain() }
    override val lastEnded = dao.observeLastEnded().map { it?.toDomain() }
    override suspend fun current() = dao.active()?.toDomain()
    override suspend fun get(id: String) = dao.get(id)?.toDomain()

    override suspend fun start(source: StartSource, autoKey: String?, autoReason: String?): Session {
        dao.endAll(System.currentTimeMillis())
        val session = Session(UUID.randomUUID().toString(), System.currentTimeMillis(), null, source, autoKey, autoReason)
        dao.upsert(SessionEntity(session.id, session.startedAt, null, source.name, autoKey, autoReason))
        return session
    }

    override suspend fun updateAutoTrigger(autoKey: String, autoReason: String) = dao.updateAutoTrigger(autoKey, autoReason)

    private fun SessionEntity.toDomain() = Session(id, startedAt, endedAt,
        StartSource.entries.firstOrNull { it.name == startedBy } ?: StartSource.MANUAL, autoKey, autoReason)

    override suspend fun stop() = dao.endAll(System.currentTimeMillis())
}

@Singleton
class AlertRepositoryImpl @Inject constructor(private val dao: AlertDao) : AlertRepository {
    override fun observeSince(timestamp: Long) = dao.observeSince(timestamp).map { list -> list.map { it.toDomain() } }
    override suspend fun add(kind: AlertKind, title: String, body: String, conversationId: String?) =
        dao.insert(AlertEntity(kind = kind.name, title = title, body = body, conversationId = conversationId,
            createdAt = System.currentTimeMillis()))
    override suspend fun clear() = dao.deleteAll()
}
