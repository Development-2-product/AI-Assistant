package com.iamode.app.data.mail

import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.dao.MailIntelligenceDao
import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.core.database.entity.MailAuditEntity
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.domain.mail.ActionCheck
import com.iamode.app.domain.mail.Actor
import com.iamode.app.domain.mail.AttachmentInfo
import com.iamode.app.domain.mail.AttachmentSafety
import com.iamode.app.domain.mail.CelebrationState
import com.iamode.app.domain.mail.IdempotencyKeys
import com.iamode.app.domain.mail.MailActionStatus
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MailIds
import com.iamode.app.domain.mail.MailPolicyEngine
import com.iamode.app.domain.mail.MailProviderKind
import com.iamode.app.domain.mail.PolicyDecision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only code that touches the outside world for email actions (send, attach, create event).
 * Every run: load stored data -> policy check -> atomic APPROVED->EXECUTING -> idempotent effect -> audit.
 * The AI never calls this; it only produced the proposal the user approved.
 */
@Singleton
class MailActionExecutor @Inject constructor(
    private val actions: MailActionDao,
    private val intelligence: MailIntelligenceDao,
    private val workflow: MailWorkflowDao,
    private val gmail: GmailRepository,
    private val documents: DocumentSources,
    private val calendar: CalendarWriter,
    private val log: DiagnosticsLog,
    private val tracker: com.iamode.app.data.jobs.JobTrackerRepository,
    private val outlook: com.iamode.app.data.outlook.OutlookRepository,
) {
    private fun payloadValue(json: String?, key: String): String? = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(json ?: "{}") as kotlinx.serialization.json.JsonObject)[key]
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }.getOrNull()

    /** Adds a key to the action's small JSON payload without losing what's there (e.g. the tracker's applicationId). */
    private fun withPayloadValue(json: String?, key: String, value: String): String {
        val current = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json ?: "{}") as kotlinx.serialization.json.JsonObject }
            .getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap()))
        return kotlinx.serialization.json.JsonObject(current + (key to kotlinx.serialization.json.JsonPrimitive(value))).toString()
    }

    sealed interface Outcome {
        data class Done(val message: String) : Outcome
        data class Blocked(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val mutex = Mutex()

    suspend fun execute(actionId: String, calendarId: Long? = null, remindersMinutes: List<Int> = listOf(24 * 60, 60)): Outcome =
        mutex.withLock { run(actionId, calendarId, remindersMinutes) }

    private suspend fun run(actionId: String, calendarId: Long?, reminders: List<Int>): Outcome {
        val a = actions.get(actionId) ?: return Outcome.Blocked("Action not found")
        if (a.status == MailActionStatus.COMPLETED.name) return Outcome.Done("Already done") // idempotent
        val type = MailActionType.valueOf(a.actionType)
        val item = intelligence.byEmailId(a.emailId) ?: return Outcome.Blocked("Email not found")
        val selections = workflow.selectionsFor(actionId)
        val extraction = workflow.calendarFor(a.emailId)
        val proposal = extraction?.let(MailMapper::calendar)

        val allowed = buildSet {
            item.fromAddress?.let(::add)
            item.replyTo?.let(::add)
            if (type == MailActionType.UNSUBSCRIBE_EMAIL) a.recipient?.let(::add) // came from the List-Unsubscribe header
        }
        val recovering = a.status == MailActionStatus.EXECUTING.name // app died mid-action last time
        if (!recovering) {
            val check = ActionCheck(
                type = type, status = MailActionStatus.valueOf(a.status), approvedAt = a.approvedAt,
                approvedBy = a.approvedBy?.let { runCatching { Actor.valueOf(it) }.getOrNull() },
                recipient = a.recipient, allowedRecipients = allowed, draftBody = a.draftBody,
                attachments = selections.map { AttachmentInfo(it.displayName, it.mimeType, it.sizeBytes, userConfirmed = true) },
                calendar = proposal,
            )
            when (val d = MailPolicyEngine.canExecute(check, System.currentTimeMillis(), ZoneId.systemDefault())) {
                is PolicyDecision.Denied -> return Outcome.Blocked(d.reason)
                PolicyDecision.Allowed -> Unit
            }
            // Atomic claim: if two triggers race, only one executes.
            if (actions.transition(a.id, MailActionStatus.APPROVED.name, MailActionStatus.EXECUTING.name, System.currentTimeMillis()) != 1) {
                return Outcome.Blocked("Already in progress")
            }
        }

        val result: Result<Pair<String, String?>> = when (type) {
            MailActionType.SEND_DOCUMENT_REPLY, MailActionType.SEND_REPLY, MailActionType.UNSUBSCRIBE_EMAIL,
            MailActionType.SEND_FOLLOW_UP -> send(a, item.accountEmail,
                item.threadId, item.gmailMessageIdHeader, item.gmailReferences, selections)
            MailActionType.CREATE_CALENDAR_EVENT -> createEvent(extraction, proposal, calendarId, reminders)
            MailActionType.REVIEW_EMAIL -> Result.failure(IllegalStateException("Nothing to execute"))
        }

        val now = System.currentTimeMillis()
        val fresh = actions.get(actionId) ?: a
        return result.fold(
            onSuccess = { (message, eventId) ->
                actions.upsert(fresh.copy(status = MailActionStatus.COMPLETED.name, executedAt = now, error = null,
                    draftBody = null, updatedAt = now))
                audit(fresh, "SUCCESS", null, selections.joinToString { it.displayName }.ifBlank { null }, eventId, now)
                workflow.advance(a.emailId, CelebrationState.ACTION_COMPLETED.name,
                    CelebrationState.entries.filter { it.ordinal >= CelebrationState.CELEBRATION_SHOWN.ordinal &&
                        it != CelebrationState.ACTION_COMPLETED }.map { it.name })
                if (type == MailActionType.SEND_FOLLOW_UP) runCatching { tracker.onFollowUpSent(fresh.payloadJson) }
                log.record("Mail", true, "${type.label}: done")
                Outcome.Done(message)
            },
            onFailure = { e ->
                val reason = e.message ?: "Something went wrong"
                actions.upsert(fresh.copy(status = MailActionStatus.FAILED.name, error = reason, updatedAt = now))
                audit(fresh, "FAILED", reason, selections.joinToString { it.displayName }.ifBlank { null }, null, now)
                log.record("Mail", false, "${type.label} failed: $reason")
                Outcome.Failed(reason)
            },
        )
    }

    private suspend fun send(
        a: MailActionEntity, account: String?, threadId: String?, inReplyTo: String?, references: String?,
        selections: List<com.iamode.app.core.database.entity.DocumentSelectionEntity>,
    ): Result<Pair<String, String?>> = runCatching {
        val acct = requireNotNull(account) { "No mail account for this email" }
        val messageId = IdempotencyKeys.messageIdHeader(a.id)
        val isOutlook = MailIds.providerOf(a.emailId) == MailProviderKind.OUTLOOK
        // Idempotency across crashes/retries. Gmail: our Message-ID is already in Sent. Outlook: handled by the saved draft ID.
        if (!isOutlook && gmail.alreadySent(acct, messageId)) return@runCatching "Already sent" to null
        val attachments = selections.map { s ->
            val bytes = requireNotNull(documents.readConfirmed(s.uri, AttachmentSafety.MAX_TOTAL_BYTES)) {
                "Couldn't read ${s.displayName}. Choose the file again"
            }
            GmailRepository.OutgoingAttachment(s.displayName, s.mimeType ?: documents.mimeFor(s.displayName), bytes)
        }
        require(attachments.sumOf { it.bytes.size.toLong() } <= AttachmentSafety.MAX_TOTAL_BYTES) { "Attachments are too large to send" }
        val mail = GmailRepository.OutgoingMail(
            accountEmail = acct, to = requireNotNull(a.recipient), subject = a.draftSubject ?: "Re:", body = requireNotNull(a.draftBody),
            threadId = threadId, inReplyTo = inReplyTo, references = references, messageIdHeader = messageId, attachments = attachments,
        )
        val outcome = if (isOutlook) {
            outlook.sendReply(mail, a.emailId, draftId = payloadValue(a.payloadJson, OUTLOOK_DRAFT)) { draft ->
                actions.get(a.id)?.let { cur -> actions.upsert(cur.copy(payloadJson = withPayloadValue(cur.payloadJson, OUTLOOK_DRAFT, draft))) }
            }
        } else gmail.sendMail(mail)
        when (outcome) {
            is GmailRepository.SendOutcome.Sent -> (if (attachments.isEmpty()) "Reply sent" else "Sent with ${attachments.size} attachment(s)") to null
            is GmailRepository.SendOutcome.Failed -> throw IllegalStateException(outcome.reason)
        }
    }

    private suspend fun createEvent(
        extraction: com.iamode.app.core.database.entity.CalendarExtractionEntity?,
        proposal: com.iamode.app.domain.mail.CalendarProposal?,
        calendarId: Long?, reminders: List<Int>,
    ): Result<Pair<String, String?>> = runCatching {
        val e = requireNotNull(extraction) { "No event details" }
        val resolved = requireNotNull(proposal?.resolve(ZoneId.systemDefault())) { "Confirm the date and time first" }
        val target = calendarId ?: calendar.writableCalendars().firstOrNull()?.id ?: error("No calendar on this phone accepts new events")
        val description = listOfNotNull(e.description, e.meetingUrl?.let { "Join: $it" }, e.organizer?.let { "Organizer: $it" },
            "Added by IA Mode from an email.").joinToString("\n")
        when (val r = calendar.create(target, e.eventHash, e.title ?: "Interview", resolved.start, resolved.end,
            e.location ?: e.meetingUrl, description, reminders)) {
            is CalendarWriter.Result.Created -> {
                workflow.upsertCalendar(e.copy(calendarEventId = r.eventId, status = "CREATED", updatedAt = System.currentTimeMillis()))
                (if (r.existed) "Already in your calendar" else "Added to your calendar") to r.eventId.toString()
            }
            is CalendarWriter.Result.Failed -> throw IllegalStateException(r.reason)
        }
    }

    private suspend fun audit(a: MailActionEntity, status: String, error: String?, attachments: String?, eventId: String?, now: Long) {
        actions.insertAudit(MailAuditEntity(
            id = UUID.randomUUID().toString(), actionId = a.id, userId = null, actionType = a.actionType,
            target = maskEmail(a.recipient) ?: a.emailId, status = status, requestMetadataJson = null, resultMetadataJson = null,
            error = error, createdAt = now, emailId = a.emailId, actor = a.approvedBy ?: Actor.USER.name, requestedAt = a.createdAt,
            approvedAt = a.approvedAt, executedAt = if (status == "SUCCESS") now else null, attachmentNames = attachments,
            calendarEventId = eventId,
        ))
    }

    private fun maskEmail(v: String?): String? = v?.replace(Regex("^(.)[^@]*@"), "$1***@")

    private companion object { const val OUTLOOK_DRAFT = "outlookDraftId" }
}
