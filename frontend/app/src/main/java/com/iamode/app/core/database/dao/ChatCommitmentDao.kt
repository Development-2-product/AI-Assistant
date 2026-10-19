package com.iamode.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.iamode.app.core.database.entity.ChatCommitmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatCommitmentDao {
    @Upsert suspend fun upsert(item: ChatCommitmentEntity)
    @Query("SELECT * FROM chat_commitments WHERE id = :id") suspend fun get(id: String): ChatCommitmentEntity?
    @Query("SELECT * FROM chat_commitments WHERE id = :id") fun observe(id: String): Flow<ChatCommitmentEntity?>
    @Query("SELECT * FROM chat_commitments WHERE conversationId = :id ORDER BY createdAt DESC") fun byConversation(id: String): Flow<List<ChatCommitmentEntity>>
    @Query("SELECT * FROM chat_commitments WHERE contactId = :id ORDER BY createdAt DESC") fun byContact(id: String): Flow<List<ChatCommitmentEntity>>
    @Query("SELECT * FROM chat_commitments WHERE status = 'REVIEW_NEEDED' ORDER BY createdAt DESC") fun reviewNeeded(): Flow<List<ChatCommitmentEntity>>
    @Query("SELECT * FROM chat_commitments WHERE status IN ('CONFIRMED','DUE','OVERDUE','SNOOZED') ORDER BY dueAt") fun active(): Flow<List<ChatCommitmentEntity>>
    @Query("SELECT * FROM chat_commitments ORDER BY createdAt DESC") fun all(): Flow<List<ChatCommitmentEntity>>
    @Query("SELECT COUNT(*) FROM chat_commitments") fun observeCount(): Flow<Int>
    @Query("UPDATE chat_commitments SET status = 'CONFIRMED', needsConfirmation = 0, dueAt = :dueAt, reminderAt = :reminderAt, updatedAt = :now WHERE id = :id") suspend fun confirm(id: String, dueAt: Long?, reminderAt: Long?, now: Long)
    @Query("UPDATE chat_commitments SET commitmentText = :text, normalizedAction = :action, dueAt = :dueAt, reminderAt = :reminderAt, updatedAt = :now WHERE id = :id") suspend fun edit(id: String, text: String, action: String, dueAt: Long?, reminderAt: Long?, now: Long)
    @Query("UPDATE chat_commitments SET status = 'COMPLETED', completedAt = :now, reminderAt = NULL, updatedAt = :now WHERE id = :id") suspend fun complete(id: String, now: Long)
    @Query("UPDATE chat_commitments SET status = 'DISMISSED', dismissedAt = :now, reminderAt = NULL, updatedAt = :now WHERE id = :id") suspend fun dismiss(id: String, now: Long)
    @Query("UPDATE chat_commitments SET status = 'SNOOZED', snoozedUntil = :until, reminderAt = :until, updatedAt = :now WHERE id = :id") suspend fun snooze(id: String, until: Long, now: Long)
    @Query("UPDATE chat_commitments SET reminderSentAt = :now, updatedAt = :now WHERE id = :id") suspend fun markReminderSent(id: String, now: Long)
    @Query("UPDATE chat_commitments SET followUpDraft = :draft, updatedAt = :now WHERE id = :id") suspend fun updateFollowUpDraft(id: String, draft: String?, now: Long)
    @Query("DELETE FROM chat_commitments WHERE id = :id") suspend fun delete(id: String)
    /** Contact deletion is a privacy boundary: retain neither its commitments nor their source excerpts. */
    @Query("DELETE FROM chat_commitments WHERE contactId = :contactId") suspend fun deleteForContact(contactId: String)
    @Query("DELETE FROM chat_commitments WHERE contactId IS NOT NULL") suspend fun deleteContactLinked()
    @Query("DELETE FROM chat_commitments") suspend fun deleteAll()
    /** Terminal history is useful briefly, then removed by normal on-device retention cleanup. */
    @Query("DELETE FROM chat_commitments WHERE status IN ('COMPLETED', 'DISMISSED') AND updatedAt < :before") suspend fun deleteTerminalOlderThan(before: Long)
}
