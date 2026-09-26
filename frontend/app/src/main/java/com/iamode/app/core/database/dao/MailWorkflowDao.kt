package com.iamode.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.iamode.app.core.database.entity.AutomationRuleEntity
import com.iamode.app.core.database.entity.CalendarExtractionEntity
import com.iamode.app.core.database.entity.CelebrationEntity
import com.iamode.app.core.database.entity.DocumentCandidateEntity
import com.iamode.app.core.database.entity.DocumentSelectionEntity
import com.iamode.app.core.database.entity.DocumentSourceEntity
import com.iamode.app.core.database.entity.MutedSenderEntity
import com.iamode.app.core.database.entity.OpportunityEntity
import com.iamode.app.core.database.entity.WritingStyleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailWorkflowDao {
    // ---- opportunities ----
    @Upsert suspend fun upsertOpportunity(e: OpportunityEntity)
    @Query("SELECT * FROM mail_opportunities ORDER BY receivedAt DESC") fun observeOpportunities(): Flow<List<OpportunityEntity>>
    @Query("SELECT * FROM mail_opportunities WHERE emailId = :emailId LIMIT 1") suspend fun opportunityFor(emailId: String): OpportunityEntity?

    // ---- calendar ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCalendar(e: CalendarExtractionEntity): Long
    @Upsert suspend fun upsertCalendar(e: CalendarExtractionEntity)
    @Query("SELECT * FROM mail_calendar_extractions WHERE emailId = :emailId ORDER BY createdAt LIMIT 1")
    suspend fun calendarFor(emailId: String): CalendarExtractionEntity?
    @Query("SELECT * FROM mail_calendar_extractions WHERE emailId = :emailId ORDER BY createdAt LIMIT 1")
    fun observeCalendar(emailId: String): Flow<CalendarExtractionEntity?>
    @Query("SELECT * FROM mail_calendar_extractions WHERE eventHash = :hash LIMIT 1") suspend fun calendarByHash(hash: String): CalendarExtractionEntity?

    // ---- documents ----
    @Query("DELETE FROM mail_document_candidates WHERE actionId = :actionId") suspend fun clearCandidates(actionId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCandidates(list: List<DocumentCandidateEntity>)
    @Query("SELECT * FROM mail_document_candidates WHERE actionId = :actionId ORDER BY rank")
    fun observeCandidates(actionId: String): Flow<List<DocumentCandidateEntity>>
    @Query("DELETE FROM mail_document_selections WHERE actionId = :actionId") suspend fun clearSelection(actionId: String)
    @Query("DELETE FROM mail_document_selections WHERE id = :id") suspend fun deleteSelection(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSelection(e: DocumentSelectionEntity)
    @Query("SELECT * FROM mail_document_selections WHERE actionId = :actionId") suspend fun selectionsFor(actionId: String): List<DocumentSelectionEntity>
    @Query("SELECT * FROM mail_document_selections WHERE actionId = :actionId") fun observeSelections(actionId: String): Flow<List<DocumentSelectionEntity>>
    @Query("SELECT * FROM mail_document_selections") fun observeAllSelections(): Flow<List<DocumentSelectionEntity>>
    @Query("SELECT * FROM mail_calendar_extractions") fun observeAllCalendars(): Flow<List<CalendarExtractionEntity>>
    @Query("SELECT uri, COUNT(*) AS times FROM mail_document_selections WHERE documentType = :type GROUP BY uri")
    suspend fun selectionCounts(type: String): List<UriCount>

    @Upsert suspend fun upsertSource(e: DocumentSourceEntity)
    @Query("DELETE FROM document_sources WHERE uri = :uri") suspend fun deleteSource(uri: String)
    @Query("SELECT * FROM document_sources ORDER BY addedAt") suspend fun sources(): List<DocumentSourceEntity>
    @Query("SELECT * FROM document_sources ORDER BY addedAt") fun observeSources(): Flow<List<DocumentSourceEntity>>

    // ---- celebrations (once-only) ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCelebration(e: CelebrationEntity): Long
    @Query("SELECT * FROM mail_celebrations WHERE state = 'CELEBRATION_ELIGIBLE' ORDER BY eligibleAt LIMIT 1")
    fun observeNextEligible(): Flow<CelebrationEntity?>
    /** Returns 1 for exactly one caller: the same email + celebration can only be shown once. */
    @Query("UPDATE mail_celebrations SET state = 'CELEBRATION_SHOWN', shownAt = :now WHERE id = :id AND state = 'CELEBRATION_ELIGIBLE'")
    suspend fun markShown(id: String, now: Long): Int
    @Query("UPDATE mail_celebrations SET state = :state WHERE emailId = :emailId AND state IN (:allowedFrom)")
    suspend fun advance(emailId: String, state: String, allowedFrom: List<String>): Int
    @Query("UPDATE mail_celebrations SET detailsViewedAt = :now WHERE emailId = :emailId AND detailsViewedAt IS NULL")
    suspend fun markDetailsViewed(emailId: String, now: Long)
    @Query("UPDATE mail_celebrations SET replayCount = replayCount + 1 WHERE id = :id") suspend fun countReplay(id: String)
    @Query("SELECT * FROM mail_celebrations WHERE emailId = :emailId LIMIT 1") suspend fun celebrationFor(emailId: String): CelebrationEntity?

    // ---- writing style ----
    @Upsert suspend fun upsertStyle(e: WritingStyleEntity)
    @Query("SELECT * FROM writing_style WHERE id = 'me' LIMIT 1") suspend fun style(): WritingStyleEntity?
    @Query("SELECT * FROM writing_style WHERE id = 'me' LIMIT 1") fun observeStyle(): Flow<WritingStyleEntity?>
    @Query("DELETE FROM writing_style") suspend fun deleteStyle()

    // ---- automation + muting ----
    @Upsert suspend fun upsertRule(e: AutomationRuleEntity)
    @Query("SELECT * FROM automation_rules") fun observeRules(): Flow<List<AutomationRuleEntity>>
    @Query("SELECT * FROM automation_rules WHERE enabled = 1") suspend fun enabledRules(): List<AutomationRuleEntity>
    @Upsert suspend fun mute(e: MutedSenderEntity)
    @Query("DELETE FROM muted_senders WHERE address = :address") suspend fun unmute(address: String)
    @Query("SELECT COUNT(*) FROM muted_senders WHERE address = :address") suspend fun isMuted(address: String): Int
    @Query("SELECT * FROM muted_senders ORDER BY mutedAt DESC") fun observeMuted(): Flow<List<MutedSenderEntity>>
}

data class UriCount(val uri: String, val times: Int)
