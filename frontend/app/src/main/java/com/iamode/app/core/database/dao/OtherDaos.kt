package com.iamode.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.iamode.app.core.database.entity.AlertEntity
import com.iamode.app.core.database.entity.ContactEntity
import com.iamode.app.core.database.entity.GmailAccountEntity
import com.iamode.app.core.database.entity.SessionEntity
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.core.database.entity.MailAuditEntity
import com.iamode.app.core.database.entity.MailIntelligenceEntity
import com.iamode.app.core.database.entity.ApprovalRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE phone = :phone LIMIT 1")
    suspend fun byPhone(phone: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE email = :email LIMIT 1")
    suspend fun byEmail(email: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE displayName = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): ContactEntity?

    @Upsert
    suspend fun upsert(entity: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts WHERE createdAt >= :since ORDER BY createdAt DESC LIMIT 200")
    fun observeSince(since: Long): Flow<List<AlertEntity>>

    @Insert
    suspend fun insert(entity: AlertEntity)

    @Query("DELETE FROM alerts")
    suspend fun deleteAll()

    @Query("DELETE FROM alerts WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): SessionEntity?

    @Upsert
    suspend fun upsert(entity: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAt IS NOT NULL ORDER BY endedAt DESC LIMIT 1")
    fun observeLastEnded(): Flow<SessionEntity?>

    @Query("UPDATE sessions SET autoKey = :autoKey, autoReason = :autoReason WHERE endedAt IS NULL")
    suspend fun updateAutoTrigger(autoKey: String, autoReason: String)

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE endedAt IS NULL")
    suspend fun endAll(endedAt: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface GmailAccountDao {
    @Query("SELECT * FROM gmail_accounts ORDER BY addedAt")
    fun observeAll(): Flow<List<GmailAccountEntity>>

    @Query("SELECT * FROM gmail_accounts")
    suspend fun all(): List<GmailAccountEntity>

    @Upsert
    suspend fun upsert(entity: GmailAccountEntity)

    @Query("DELETE FROM gmail_accounts WHERE email = :email")
    suspend fun delete(email: String)
}

@Dao
interface MailIntelligenceDao {
    @Upsert suspend fun upsert(entity: MailIntelligenceEntity)
    @Query("SELECT * FROM mail_intelligence WHERE emailId = :emailId LIMIT 1") suspend fun byEmailId(emailId: String): MailIntelligenceEntity?
    @Query("SELECT * FROM mail_intelligence WHERE category = :category ORDER BY updatedAt DESC") fun observeCategory(category: String): Flow<List<MailIntelligenceEntity>>
    @Query("SELECT * FROM mail_intelligence ORDER BY updatedAt DESC") fun observeAll(): Flow<List<MailIntelligenceEntity>>
    @Query("SELECT * FROM mail_intelligence ORDER BY COALESCE(receivedAt, createdAt) DESC LIMIT 500")
    fun observeRecent(): Flow<List<MailIntelligenceEntity>>
    @Query("SELECT * FROM mail_intelligence WHERE emailId = :emailId LIMIT 1") fun observe(emailId: String): Flow<MailIntelligenceEntity?>
    @Query("UPDATE mail_intelligence SET archived = :archived, updatedAt = :now WHERE emailId = :emailId")
    suspend fun setArchived(emailId: String, archived: Boolean, now: Long)
    @Query("SELECT category, COUNT(*) AS n FROM mail_intelligence WHERE fromAddress = :address GROUP BY category")
    suspend fun senderHistory(address: String): List<CategoryCount>
    @Query("SELECT * FROM mail_intelligence WHERE fromAddress = :address") suspend fun fromSender(address: String): List<MailIntelligenceEntity>
    @Query("UPDATE mail_intelligence SET archived = 1, updatedAt = :now WHERE fromAddress = :address")
    suspend fun archiveFrom(address: String, now: Long)
    @Query("DELETE FROM mail_intelligence WHERE COALESCE(receivedAt, createdAt) < :before") suspend fun deleteOlderThan(before: Long)
}

@Dao
interface MailActionDao {
    @Upsert suspend fun upsert(entity: MailActionEntity)
    @Query("SELECT * FROM mail_actions WHERE id = :id LIMIT 1") suspend fun get(id: String): MailActionEntity?
    @Query("SELECT * FROM mail_actions WHERE idempotencyKey = :key LIMIT 1") suspend fun byIdempotencyKey(key: String): MailActionEntity?
    @Query("SELECT * FROM mail_actions ORDER BY updatedAt DESC") fun observeAll(): Flow<List<MailActionEntity>>
    @Insert suspend fun insertApproval(entity: ApprovalRecordEntity)
    @Insert suspend fun insertAudit(entity: MailAuditEntity)
    @Query("SELECT * FROM mail_audit ORDER BY createdAt DESC LIMIT 200") fun observeAudit(): Flow<List<MailAuditEntity>>
    @Query("SELECT * FROM mail_actions WHERE id = :id LIMIT 1") fun observe(id: String): Flow<MailActionEntity?>
    @Query("SELECT * FROM mail_actions WHERE emailId = :emailId ORDER BY createdAt") fun observeForEmail(emailId: String): Flow<List<MailActionEntity>>
    @Query("SELECT * FROM mail_actions WHERE emailId = :emailId ORDER BY createdAt") suspend fun forEmail(emailId: String): List<MailActionEntity>
    @Query("SELECT * FROM mail_actions WHERE status IN ('PROPOSED','REVIEW_REQUIRED','APPROVED','FAILED','EXECUTING') ORDER BY updatedAt DESC")
    fun observePending(): Flow<List<MailActionEntity>>
    @Query("SELECT * FROM mail_actions WHERE status IN ('REVIEW_REQUIRED','APPROVED') AND updatedAt < :before")
    suspend fun stalePending(before: Long): List<MailActionEntity>
    /** Atomic compare-and-set: returns 1 only for the caller that moved the action. */
    @Query("UPDATE mail_actions SET status = :to, updatedAt = :now WHERE id = :id AND status = :from")
    suspend fun transition(id: String, from: String, to: String, now: Long): Int
}

data class CategoryCount(val category: String, val n: Int)
