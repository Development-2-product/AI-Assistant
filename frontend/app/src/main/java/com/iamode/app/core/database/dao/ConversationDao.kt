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
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>
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

    @Query("SELECT * FROM conversations WHERE status = 'QUEUED' ORDER BY updatedAt DESC")
    fun observeQueued(): Flow<List<ConversationEntity>>

    /** Cross-session review queue. These states always require the person's attention. */
    @Query("SELECT * FROM conversations WHERE status IN ('PENDING_APPROVAL', 'CRISIS', 'CALLBACK') ORDER BY updatedAt DESC")
    fun observeActionable(): Flow<List<ConversationEntity>>

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    /** Atomically reserves a reply so a scheduler wake-up and a user tap cannot send it twice. */
    @Query("UPDATE conversations SET status = 'SENDING', updatedAt = :now WHERE id = :id AND status IN ('PENDING_APPROVAL', 'QUEUED', 'CRISIS')")
    suspend fun claimForSend(id: String, now: Long): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    /** Removes whole completed sessions after the local retention window; active work is never touched. */
    @Query("DELETE FROM conversations WHERE sessionId IN (SELECT id FROM sessions WHERE endedAt IS NOT NULL AND endedAt < :before)")
    suspend fun deleteForExpiredSessions(before: Long)

    @Query("SELECT COUNT(*) FROM conversations") fun observeCount(): Flow<Int>
}

@Dao
interface MessageDao {
    @Query("SELECT conversationId, SUM(CASE WHEN fromMe = 0 THEN 1 ELSE 0 END) AS received, SUM(CASE WHEN fromMe = 1 THEN 1 ELSE 0 END) AS sent FROM messages GROUP BY conversationId")
    fun observeCountsByConversation(): Flow<List<ConversationMessageCount>>
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

    @Query("SELECT COUNT(*) FROM messages") fun observeCount(): Flow<Int>
}

data class ConversationMessageCount(val conversationId: String, val received: Int, val sent: Int)
