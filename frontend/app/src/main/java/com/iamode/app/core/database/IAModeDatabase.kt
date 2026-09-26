package com.iamode.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iamode.app.core.database.dao.AlertDao
import com.iamode.app.core.database.dao.ContactDao
import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.core.database.dao.MessageDao
import com.iamode.app.core.database.dao.SessionDao
import com.iamode.app.core.database.entity.AlertEntity
import com.iamode.app.core.database.entity.ContactEntity
import com.iamode.app.core.database.entity.ConversationEntity
import com.iamode.app.core.database.entity.GmailAccountEntity
import com.iamode.app.core.database.entity.MessageEntity
import com.iamode.app.core.database.entity.SessionEntity
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.core.database.entity.MailAuditEntity
import com.iamode.app.core.database.entity.MailIntelligenceEntity
import com.iamode.app.core.database.entity.ApprovalRecordEntity
import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.entity.AutomationRuleEntity
import com.iamode.app.core.database.entity.CalendarExtractionEntity
import com.iamode.app.core.database.entity.CelebrationEntity
import com.iamode.app.core.database.entity.DocumentCandidateEntity
import com.iamode.app.core.database.entity.DocumentSelectionEntity
import com.iamode.app.core.database.entity.DocumentSourceEntity
import com.iamode.app.core.database.entity.MutedSenderEntity
import com.iamode.app.core.database.entity.WritingStyleEntity
import com.iamode.app.core.database.entity.JobApplicationEntity
import com.iamode.app.core.database.entity.ApplicationEventEntity
import com.iamode.app.core.database.dao.JobApplicationDao
import com.iamode.app.core.database.dao.OutlookAccountDao
import com.iamode.app.core.database.entity.OutlookAccountEntity
import com.iamode.app.core.database.entity.OpportunityEntity
import com.iamode.app.core.database.dao.MailIntelligenceDao

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, ContactEntity::class,
        AlertEntity::class, SessionEntity::class, GmailAccountEntity::class,
        MailIntelligenceEntity::class, MailActionEntity::class, ApprovalRecordEntity::class, MailAuditEntity::class,
        OpportunityEntity::class, CalendarExtractionEntity::class, DocumentCandidateEntity::class,
        DocumentSelectionEntity::class, CelebrationEntity::class, AutomationRuleEntity::class,
        DocumentSourceEntity::class, MutedSenderEntity::class, WritingStyleEntity::class,
        JobApplicationEntity::class, ApplicationEventEntity::class, OutlookAccountEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class IAModeDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun alertDao(): AlertDao
    abstract fun sessionDao(): SessionDao
    abstract fun gmailAccountDao(): GmailAccountDao
    abstract fun mailIntelligenceDao(): MailIntelligenceDao
    abstract fun mailActionDao(): MailActionDao
    abstract fun mailWorkflowDao(): MailWorkflowDao
    abstract fun jobApplicationDao(): JobApplicationDao
    abstract fun outlookAccountDao(): OutlookAccountDao

    companion object {
        const val NAME = "iamode.db"

        /** v1.2: sessions remember whether they were started automatically, and why. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN startedBy TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN autoKey TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN autoReason TEXT")
            }
        }
        /** v1.3: encrypted, metadata-only mail intelligence/action/audit records. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS mail_intelligence (id TEXT NOT NULL, emailId TEXT NOT NULL, threadId TEXT, category TEXT NOT NULL, labelsJson TEXT NOT NULL, priority TEXT NOT NULL, requiresReply INTEGER NOT NULL, requiresAction INTEGER NOT NULL, documentRequestJson TEXT, calendarJson TEXT, opportunityJson TEXT, suspiciousReason TEXT, celebrationType TEXT NOT NULL, celebrationShownAt INTEGER, confidence TEXT, model TEXT, promptVersion TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_mail_intelligence_emailId ON mail_intelligence (emailId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mail_intelligence_category ON mail_intelligence (category)")
                db.execSQL("CREATE TABLE IF NOT EXISTS mail_actions (id TEXT NOT NULL, emailId TEXT NOT NULL, actionType TEXT NOT NULL, payloadJson TEXT NOT NULL, status TEXT NOT NULL, idempotencyKey TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mail_actions_emailId ON mail_actions (emailId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_mail_actions_idempotencyKey ON mail_actions (idempotencyKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mail_actions_status ON mail_actions (status)")
                db.execSQL("CREATE TABLE IF NOT EXISTS mail_approvals (id TEXT NOT NULL, actionId TEXT NOT NULL, userId TEXT, decision TEXT NOT NULL, decidedAt INTEGER NOT NULL, metadataJson TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mail_approvals_actionId ON mail_approvals (actionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS mail_audit (id TEXT NOT NULL, actionId TEXT NOT NULL, userId TEXT, actionType TEXT NOT NULL, target TEXT NOT NULL, status TEXT NOT NULL, requestMetadataJson TEXT, resultMetadataJson TEXT, error TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mail_audit_actionId ON mail_audit (actionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mail_audit_createdAt ON mail_audit (createdAt)")
            }
        }

        /** v1.4: email workflow (opportunities, calendar, documents, celebrations, automation, audit). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("accountEmail", "fromAddress", "fromName", "replyTo", "subject", "summary", "listUnsubscribe",
                    "suspiciousJson", "gmailMessageIdHeader", "gmailReferences").forEach {
                    db.execSQL("ALTER TABLE mail_intelligence ADD COLUMN `$it` TEXT")
                }
                db.execSQL("ALTER TABLE mail_intelligence ADD COLUMN `receivedAt` INTEGER")
                listOf("archived", "requiresAttachment", "containsEvent").forEach {
                    db.execSQL("ALTER TABLE mail_intelligence ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0")
                }
                listOf("approvedAt", "executedAt").forEach { db.execSQL("ALTER TABLE mail_actions ADD COLUMN `$it` INTEGER") }
                listOf("approvedBy", "error", "recipient", "draftSubject", "draftBody").forEach {
                    db.execSQL("ALTER TABLE mail_actions ADD COLUMN `$it` TEXT")
                }
                listOf("emailId", "actor", "attachmentNames", "calendarEventId").forEach {
                    db.execSQL("ALTER TABLE mail_audit ADD COLUMN `$it` TEXT")
                }
                listOf("requestedAt", "approvedAt", "executedAt").forEach { db.execSQL("ALTER TABLE mail_audit ADD COLUMN `$it` INTEGER") }

                db.execSQL("CREATE TABLE IF NOT EXISTS `mail_opportunities` (`id` TEXT NOT NULL, `emailId` TEXT NOT NULL, `company` TEXT, `role` TEXT, `status` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mail_opportunities_emailId` ON `mail_opportunities` (`emailId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_opportunities_status` ON `mail_opportunities` (`status`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `mail_calendar_extractions` (`id` TEXT NOT NULL, `emailId` TEXT NOT NULL, `eventHash` TEXT NOT NULL, `title` TEXT, `date` TEXT, `startTime` TEXT, `endTime` TEXT, `timezone` TEXT, `location` TEXT, `meetingUrl` TEXT, `organizer` TEXT, `description` TEXT, `dateText` TEXT, `ambiguous` INTEGER NOT NULL, `ambiguityReason` TEXT, `calendarEventId` INTEGER, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_calendar_extractions_emailId` ON `mail_calendar_extractions` (`emailId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mail_calendar_extractions_eventHash` ON `mail_calendar_extractions` (`eventHash`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `mail_document_candidates` (`id` TEXT NOT NULL, `actionId` TEXT NOT NULL, `uri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER, `modifiedAt` INTEGER, `label` TEXT NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_document_candidates_actionId` ON `mail_document_candidates` (`actionId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `mail_document_selections` (`id` TEXT NOT NULL, `actionId` TEXT NOT NULL, `emailId` TEXT NOT NULL, `documentType` TEXT, `uri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT, `sizeBytes` INTEGER, `recommended` INTEGER NOT NULL, `selectedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_document_selections_actionId` ON `mail_document_selections` (`actionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_document_selections_documentType` ON `mail_document_selections` (`documentType`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `mail_celebrations` (`id` TEXT NOT NULL, `emailId` TEXT NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL, `title` TEXT, `subtitle` TEXT, `eligibleAt` INTEGER NOT NULL, `shownAt` INTEGER, `detailsViewedAt` INTEGER, `replayCount` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_celebrations_state` ON `mail_celebrations` (`state`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `automation_rules` (`id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `document_sources` (`uri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `muted_senders` (`address` TEXT NOT NULL, `mutedAt` INTEGER NOT NULL, PRIMARY KEY(`address`))")
            }
        }

        /** v1.6: learned writing style. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `writing_style` (`id` TEXT NOT NULL, `profileJson` TEXT NOT NULL, `sampleCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        /** v1.7: job-application tracker. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `job_applications` (`id` TEXT NOT NULL, `company` TEXT, `companyKey` TEXT NOT NULL, `role` TEXT, `location` TEXT, `source` TEXT NOT NULL, `jobUrl` TEXT, `atsPlatform` TEXT, `referenceId` TEXT, `stage` TEXT NOT NULL, `maxStage` TEXT NOT NULL, `appliedAt` INTEGER, `firstResponseAt` INTEGER, `lastActivityAt` INTEGER NOT NULL, `nextStepAt` INTEGER, `nextStepNote` TEXT, `notes` TEXT, `recruiterEmail` TEXT, `lastEmailId` TEXT, `threadIdsJson` TEXT NOT NULL, `senderDomainsJson` TEXT NOT NULL, `followUpsSent` INTEGER NOT NULL, `lastFollowUpAt` INTEGER, `lastReminderType` TEXT, `lastRemindedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_applications_companyKey` ON `job_applications` (`companyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_applications_stage` ON `job_applications` (`stage`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `application_events` (`id` TEXT NOT NULL, `applicationId` TEXT NOT NULL, `type` TEXT NOT NULL, `emailId` TEXT, `at` INTEGER NOT NULL, `detail` TEXT, `dedupeKey` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_application_events_applicationId` ON `application_events` (`applicationId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_application_events_dedupeKey` ON `application_events` (`dedupeKey`)")
            }
        }

        /** v1.8: Outlook accounts. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `outlook_accounts` (`email` TEXT NOT NULL, `homeAccountId` TEXT NOT NULL, `needsReauth` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`email`))")
            }
        }
    }
}
