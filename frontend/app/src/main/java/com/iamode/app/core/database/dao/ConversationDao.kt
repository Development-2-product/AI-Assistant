package com.iamode.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.iamode.app.core.database.entity.ConversationEntity
import com.iamode.app.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeForSession(sessionId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query(
        """SELECT * FROM conversations WHERE channel = :channel AND address = :address AND sessionId = :sessionId
           AND status NOT IN ('ENDED','SKIPPED') ORDER BY updatedAt DESC LIMIT 1"""
    )
    suspend fun findOpen(channel: String, address: String, sessionId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun forSession(sessionId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE status = 'QUEUED'")
    suspend fun queued(): List<ConversationEntity>

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun observe(conversationId: String): Flow<List<MessageEntity>>

    /** Returns -1 when the (conversationId, externalId) pair already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MessageEntity): Long

    @Query(
        """SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId
           ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC"""
    )
    suspend fun recent(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        """SELECT * FROM (SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId
           WHERE c.displayName = :name OR c.address = :address
           ORDER BY m.timestamp DESC LIMIT :limit) ORDER BY timestamp ASC"""
    )
    suspend fun withPerson(name: String, address: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
