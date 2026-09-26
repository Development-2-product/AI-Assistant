package com.iamode.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index("sessionId"), Index(value = ["channel", "address", "sessionId"]), Index("status")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val channel: String,
    val address: String,
    val displayName: String,
    val relationship: String,
    val status: String,
    val sessionId: String,
    val autopilot: Boolean,
    val autoTurns: Int,
    val tone: String?,
    val style: String?,
    val languageCode: String?,
    val script: String?,
    val summary: String?,
    val pendingReply: String?,
    val closing: Boolean,
    val followUp: Boolean,
    val mentionsMoney: Boolean,
    val asksCommitment: Boolean,
    val reason: String?,
    val sendAt: Long?,
    val aiGenerated: Boolean,
    val isCallReply: Boolean,
    val recap: String?,
    val subject: String?,
    val gmailAccount: String?,
    val gmailThreadId: String?,
    val gmailMessageId: String?,
    val gmailReferences: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long?,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index(value = ["conversationId", "externalId"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val fromMe: Boolean,
    val kind: String,
    val text: String,
    val timestamp: Long,
    val auto: Boolean,
    val externalId: String?,
)

@Entity(tableName = "contacts", indices = [Index("phone"), Index("email"), Index("displayName")])
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val phone: String?,
    val email: String?,
    val relationship: String,
    val languageCode: String?,
    val script: String,
)

@Entity(tableName = "alerts", indices = [Index("createdAt")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val body: String,
    val conversationId: String?,
    val createdAt: Long,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    @ColumnInfo(defaultValue = "MANUAL") val startedBy: String = "MANUAL",
    val autoKey: String? = null,
    val autoReason: String? = null,
)

@Entity(tableName = "gmail_accounts")
data class GmailAccountEntity(
    @PrimaryKey val email: String,
    val needsReauth: Boolean,
    val addedAt: Long,
)

/** Structured AI understanding only; JSON fields contain extracted metadata, never OAuth tokens or file contents. */
@Entity(tableName = "mail_intelligence", indices = [Index(value = ["emailId"], unique = true), Index("category")])
data class MailIntelligenceEntity(
    @PrimaryKey val id: String,
    val emailId: String,
    val threadId: String?,
    val category: String,
    val labelsJson: String,
    val priority: String,
    val requiresReply: Boolean,
    val requiresAction: Boolean,
    val documentRequestJson: String?,
    val calendarJson: String?,
    val opportunityJson: String?,
    val suspiciousReason: String?,
    val celebrationType: String,
    val celebrationShownAt: Long?,
    val confidence: String?,
    val model: String?,
    val promptVersion: String?,
    val createdAt: Long,
    val updatedAt: Long,
    // v4: what the cards show. Metadata only: the email body is fetched from Gmail when needed, never stored.
    val accountEmail: String? = null,
    val fromAddress: String? = null,
    val fromName: String? = null,
    val replyTo: String? = null,
    val subject: String? = null,
    val summary: String? = null,
    val receivedAt: Long? = null,
    val listUnsubscribe: String? = null,
    val suspiciousJson: String? = null,
    val gmailMessageIdHeader: String? = null,
    val gmailReferences: String? = null,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "0") val requiresAttachment: Boolean = false,
    @ColumnInfo(defaultValue = "0") val containsEvent: Boolean = false,
)

/** A proposal is inert until the user explicitly approves it in the app. */
@Entity(tableName = "mail_actions", indices = [Index("emailId"), Index(value = ["idempotencyKey"], unique = true), Index("status")])
data class MailActionEntity(
    @PrimaryKey val id: String,
    val emailId: String,
    val actionType: String,
    val payloadJson: String,
    val status: String,
    val idempotencyKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    // v4: approval + execution record. draftBody is cleared once the action is finished.
    val approvedAt: Long? = null,
    val approvedBy: String? = null,
    val executedAt: Long? = null,
    val error: String? = null,
    val recipient: String? = null,
    val draftSubject: String? = null,
    val draftBody: String? = null,
)

@Entity(tableName = "mail_approvals", indices = [Index("actionId")])
data class ApprovalRecordEntity(
    @PrimaryKey val id: String,
    val actionId: String,
    val userId: String?,
    val decision: String,
    val decidedAt: Long,
    val metadataJson: String?,
)

/** Metadata-only local audit trail. Never put email bodies, access tokens or file bytes here. */
@Entity(tableName = "mail_audit", indices = [Index("actionId"), Index("createdAt")])
data class MailAuditEntity(
    @PrimaryKey val id: String,
    val actionId: String,
    val userId: String?,
    val actionType: String,
    val target: String,
    val status: String,
    val requestMetadataJson: String?,
    val resultMetadataJson: String?,
    val error: String?,
    val createdAt: Long,
    // v4: full audit trail of external actions (no email content)
    val emailId: String? = null,
    val actor: String? = null,
    val requestedAt: Long? = null,
    val approvedAt: Long? = null,
    val executedAt: Long? = null,
    val attachmentNames: String? = null,
    val calendarEventId: String? = null,
)

/** A job/interview/offer tracked across emails. One record per email that created it. */
@Entity(tableName = "mail_opportunities", indices = [Index(value = ["emailId"], unique = true), Index("status")])
data class OpportunityEntity(
    @PrimaryKey val id: String,
    val emailId: String,
    val company: String?,
    val role: String?,
    val status: String,
    val receivedAt: Long,
    val updatedAt: Long,
)

/** What the email said about an event, and the event IA Mode created from it (if any). */
@Entity(tableName = "mail_calendar_extractions", indices = [Index("emailId"), Index(value = ["eventHash"], unique = true)])
data class CalendarExtractionEntity(
    @PrimaryKey val id: String,
    val emailId: String,
    val eventHash: String,
    val title: String?,
    val date: String?,
    val startTime: String?,
    val endTime: String?,
    val timezone: String?,
    val location: String?,
    val meetingUrl: String?,
    val organizer: String?,
    val description: String?,
    val dateText: String?,
    val ambiguous: Boolean,
    val ambiguityReason: String?,
    val calendarEventId: Long?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Files the resolver suggested for one action (names and URIs only, never contents). */
@Entity(tableName = "mail_document_candidates", indices = [Index("actionId")])
data class DocumentCandidateEntity(
    @PrimaryKey val id: String,
    val actionId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAt: Long?,
    val label: String,
    val rank: Int,
)

/** The file the user confirmed. Also teaches the resolver what the user sends for each document type. */
@Entity(tableName = "mail_document_selections", indices = [Index("actionId"), Index("documentType")])
data class DocumentSelectionEntity(
    @PrimaryKey val id: String,
    val actionId: String,
    val emailId: String,
    val documentType: String?,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val recommended: Boolean,
    val selectedAt: Long,
)

/** Once-only celebrations. The primary key is IdempotencyKeys.celebration(emailId, type). */
@Entity(tableName = "mail_celebrations", indices = [Index("state")])
data class CelebrationEntity(
    @PrimaryKey val id: String,
    val emailId: String,
    val type: String,
    val state: String,
    val title: String?,
    val subtitle: String?,
    val eligibleAt: Long,
    val shownAt: Long?,
    val detailsViewedAt: Long?,
    val replayCount: Int,
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

/** Folders the user explicitly allowed IA Mode to search (Storage Access Framework tree URIs). */
@Entity(tableName = "document_sources")
data class DocumentSourceEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val addedAt: Long,
)

@Entity(tableName = "muted_senders")
data class MutedSenderEntity(
    @PrimaryKey val address: String,
    val mutedAt: Long,
)

/** The user's writing style learned from their sent mail (compact profile, masked excerpts). Encrypted DB. */
@Entity(tableName = "writing_style")
data class WritingStyleEntity(
    @PrimaryKey val id: String,
    val profileJson: String,
    val sampleCount: Int,
    val updatedAt: Long,
)

/** One job application, updated from career emails, shared links or by hand. Local only. */
@Entity(tableName = "job_applications", indices = [Index("companyKey"), Index("stage")])
data class JobApplicationEntity(
    @PrimaryKey val id: String,
    val company: String?,
    val companyKey: String,
    val role: String?,
    val location: String?,
    val source: String,
    val jobUrl: String?,
    val atsPlatform: String?,
    val referenceId: String?,
    val stage: String,
    val maxStage: String,
    val appliedAt: Long?,
    val firstResponseAt: Long?,
    val lastActivityAt: Long,
    val nextStepAt: Long?,
    val nextStepNote: String?,
    val notes: String?,
    val recruiterEmail: String?,
    val lastEmailId: String?,
    val threadIdsJson: String,
    val senderDomainsJson: String,
    val followUpsSent: Int,
    val lastFollowUpAt: Long?,
    val lastReminderType: String?,
    val lastRemindedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Timeline entry. [dedupeKey] keeps a re-synced email from adding the same event twice. */
@Entity(tableName = "application_events", indices = [Index("applicationId"), Index(value = ["dedupeKey"], unique = true)])
data class ApplicationEventEntity(
    @PrimaryKey val id: String,
    val applicationId: String,
    val type: String,
    val emailId: String?,
    val at: Long,
    val detail: String?,
    val dedupeKey: String,
)

/** A connected Outlook / Microsoft 365 account. Tokens stay inside MSAL's own encrypted cache. */
@Entity(tableName = "outlook_accounts")
data class OutlookAccountEntity(
    @PrimaryKey val email: String,
    val homeAccountId: String,
    val needsReauth: Boolean,
    val addedAt: Long,
)
