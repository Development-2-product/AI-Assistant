package com.iamode.app.data.mail

import android.net.Uri
import com.iamode.app.core.AppVisibility
import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.dao.MailIntelligenceDao
import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.entity.ApprovalRecordEntity
import com.iamode.app.core.database.entity.AutomationRuleEntity
import com.iamode.app.core.database.entity.CalendarExtractionEntity
import com.iamode.app.core.database.entity.CelebrationEntity
import com.iamode.app.core.database.entity.DocumentCandidateEntity
import com.iamode.app.core.database.entity.DocumentSelectionEntity
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.core.database.entity.MailAuditEntity
import com.iamode.app.core.database.entity.MailIntelligenceEntity
import com.iamode.app.core.database.entity.MutedSenderEntity
import com.iamode.app.core.database.entity.OpportunityEntity
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.core.network.IAModeApi
import com.iamode.app.core.network.dto.MailIntelligenceRequestDto
import com.iamode.app.core.network.dto.MailSignalsDto
import com.iamode.app.core.network.dto.ReplyDraftRequestDto
import com.iamode.app.core.notifications.AppNotifier
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.domain.mail.ActionStateMachine
import com.iamode.app.domain.mail.Actor
import com.iamode.app.domain.mail.AutomationRuleType
import com.iamode.app.domain.mail.CalendarProposal
import com.iamode.app.domain.mail.CelebrationState
import com.iamode.app.domain.mail.DocumentFile
import com.iamode.app.domain.mail.DocumentResolver
import com.iamode.app.domain.mail.IdempotencyKeys
import com.iamode.app.domain.mail.MailActionEvent
import com.iamode.app.domain.mail.MailActionPlanner
import com.iamode.app.domain.mail.MailActionStatus
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MailPolicyEngine
import com.iamode.app.domain.mail.RankedDocument
import com.iamode.app.domain.mail.SenderHistory
import com.iamode.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Email understanding and everything that follows it, up to (not including) execution:
 * classify -> store -> plan proposals -> celebrate -> resolve documents -> draft -> approve/reject.
 * Execution is MailActionExecutor's job, behind the policy engine.
 */
@Singleton
class MailIntelligenceRepository @Inject constructor(
    private val api: IAModeApi,
    private val gmail: GmailRepository,
    private val outlook: com.iamode.app.data.outlook.OutlookRepository,
    private val intelligence: MailIntelligenceDao,
    private val actions: MailActionDao,
    private val workflow: MailWorkflowDao,
    private val documents: DocumentSources,
    private val settings: SettingsRepository,
    private val notifier: AppNotifier,
    private val mailbox: MailboxActions,
    private val extractor: DocumentTextExtractor,
    private val style: WritingStyleRepository,
    private val tracker: com.iamode.app.data.jobs.JobTrackerRepository,
    private val log: DiagnosticsLog,
    private val json: Json,
) {
    // ---------------- reads for the UI ----------------
    val mail: Flow<List<MailItem>> = intelligence.observeRecent().map { list -> list.map { MailMapper.item(json, it) } }
    fun mailItem(emailId: String): Flow<MailItem?> = intelligence.observe(emailId).map { it?.let { e -> MailMapper.item(json, e) } }
    val pendingActions = actions.observePending()
    val audit = actions.observeAudit()
    val opportunities = workflow.observeOpportunities()
    val rules = workflow.observeRules()
    val muted = workflow.observeMuted()
    fun action(id: String) = actions.observe(id)
    fun actionsFor(emailId: String) = actions.observeForEmail(emailId)
    fun calendar(emailId: String) = workflow.observeCalendar(emailId)
    fun candidates(actionId: String) = workflow.observeCandidates(actionId)
    fun selections(actionId: String) = workflow.observeSelections(actionId)
    val allSelections = workflow.observeAllSelections()
    val allCalendars = workflow.observeAllCalendars()
    suspend fun actionOnce(id: String) = actions.get(id)

    // ---------------- classification (background sync) ----------------

    /** Understands new inbox mail. Returns the actions that an enabled automation rule may run now. */
    suspend fun sync(limit: Int = 15): List<String> {
        // Gmail and Outlook share the budget; each email costs at most one AI call.
        val known: suspend (String) -> Boolean = { intelligence.byEmailId(it) != null }
        val fromGmail = gmail.fetchForIntelligence(limit, newerThanDays = 7, isKnown = known)
        val batch = fromGmail + outlook.fetchForIntelligence((limit - fromGmail.size).coerceAtLeast(0), 7, known)
        val auto = mutableListOf<String>()
        for (m in batch) {
            try {
                auto += classify(m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                log.record("Mail", false, "Understanding an email failed: HTTP ${e.code()}")
                if (e.code() == 429) break // free-tier quota: stop and try again later
            } catch (e: Exception) {
                log.record("Mail", false, "Understanding an email failed: ${e.javaClass.simpleName}")
            }
        }
        if (batch.isNotEmpty()) log.record("Mail", true, "Understood ${batch.size} new email(s)")
        return auto
    }

    private suspend fun classify(m: GmailRepository.IntelligenceMail): List<String> {
        val p = m.mail
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val base = MailIntelligenceEntity(
            id = UUID.nameUUIDFromBytes("mail:${p.gmailId}".toByteArray()).toString(), emailId = p.gmailId, threadId = p.threadId,
            category = "general", labelsJson = "[]", priority = "normal", requiresReply = false, requiresAction = false,
            documentRequestJson = null, calendarJson = null, opportunityJson = null, suspiciousReason = null,
            celebrationType = "none", celebrationShownAt = null, confidence = null, model = null, promptVersion = null,
            createdAt = now, updatedAt = now, accountEmail = m.accountEmail, fromAddress = p.fromEmail, fromName = p.fromName,
            replyTo = p.replyTo, subject = p.subject, receivedAt = p.timestamp, listUnsubscribe = p.listUnsubscribe,
            gmailMessageIdHeader = p.messageIdHeader, gmailReferences = p.references,
        )
        // Muted senders cost no AI call: file them away directly.
        if (workflow.isMuted(p.fromEmail) > 0) {
            intelligence.upsert(base.copy(category = "promotion", priority = "low", archived = true, summary = "Muted sender"))
            mailbox.setArchived(p.gmailId, true, Actor.AUTOMATION) // mirrored to Gmail when that setting is on
            return emptyList()
        }

        val history = SenderHistory.from(intelligence.senderHistory(p.fromEmail).associate { it.category to it.n })
        val res = api.mailIntelligence(MailIntelligenceRequestDto(
            emailId = p.gmailId, fromAddress = p.fromEmail, fromName = p.fromName, replyTo = p.replyTo, subject = p.subject.take(300),
            body = p.body.take(12_000), threadContext = m.threadContext.takeLast(6).map { it.take(1500) },
            receivedAt = Instant.ofEpochMilli(p.timestamp).atZone(zone).toLocalDate().toString(), userTimezone = zone.id,
            signals = MailSignalsDto(
                replyTo = p.replyTo, listUnsubscribe = p.listUnsubscribe != null, autoSubmitted = p.autoSubmitted,
                precedenceBulk = p.precedenceBulk, gmailCategory = p.gmailCategory, linkDomains = p.linkDomains.take(30),
                attachmentNames = p.attachmentNames.take(20),
                senderHistoryTotal = history.total, senderHistoryBulk = history.promotions + history.notifications,
            ),
        ))
        val ai = res.intelligence
        val cal = ai.calendar?.let {
            CalendarProposal.parse(it.title, it.date, it.startTime, it.endTime, it.timezone, it.location, it.meetingUrl,
                it.organizer, it.description, it.dateText, it.ambiguous, it.ambiguityReason)
        }
        val c = MailMapper.classification(p.gmailId, ai, cal)
        val celebration = MailActionPlanner.celebration(c)

        intelligence.upsert(base.copy(
            category = ai.primaryCategory, labelsJson = MailMapper.encodeLabels(json, ai.labels), priority = ai.priority,
            requiresReply = ai.requiresReply, requiresAction = ai.requiresUserAction, summary = ai.summary.take(400),
            documentRequestJson = ai.documentRequest?.let {
                json.encodeToString(StoredDocumentRequest.serializer(), StoredDocumentRequest(it.documentType, it.description,
                    it.requestedFormat, it.recipient, it.deadline, it.requiresAttachment))
            },
            calendarJson = null, // calendar lives in mail_calendar_extractions
            opportunityJson = ai.opportunity?.takeIf { it.status != "none" || it.company != null || it.role != null }?.let {
                json.encodeToString(StoredOpportunity.serializer(), StoredOpportunity(it.company, it.role, it.status))
            },
            suspiciousReason = ai.suspiciousReasons.firstOrNull(),
            suspiciousJson = ai.suspiciousReasons.takeIf { it.isNotEmpty() }?.let { json.encodeToString(ListSerializer(String.serializer()), it) },
            celebrationType = celebration?.name?.lowercase() ?: "none",
            confidence = ai.confidence, promptVersion = res.promptVersion,
            requiresAttachment = ai.requiresAttachment, containsEvent = ai.containsEvent,
        ))

        ai.opportunity?.takeIf { it.status != "none" }?.let { o ->
            workflow.upsertOpportunity(OpportunityEntity(
                id = UUID.nameUUIDFromBytes("opp:${p.gmailId}".toByteArray()).toString(), emailId = p.gmailId,
                company = o.company, role = o.role, status = o.status, receivedAt = p.timestamp, updatedAt = now,
            ))
        }
        // Job tracker: career emails update (or start) an application. Never blocks classification.
        val app = ai.application
        val opp = ai.opportunity
        if ((app != null && (app.stage != "none" || app.referenceId != null)) || (opp != null && opp.status != "none")) {
            runCatching {
                tracker.onCareerEmail(com.iamode.app.data.jobs.CareerEmail(
                    emailId = p.gmailId, threadId = p.threadId, fromAddress = p.fromEmail, replyTo = p.replyTo,
                    company = opp?.company, role = opp?.role,
                    stage = app?.stage?.takeIf { it != "none" } ?: opp?.status?.takeIf { it in setOf("selected", "offer", "interview", "applied", "rejected") },
                    referenceId = app?.referenceId, atsPlatform = app?.atsPlatform, interviewRound = app?.interviewRound,
                    deadline = app?.deadline, nextStep = app?.nextStep, receivedAt = p.timestamp,
                ))
            }.onFailure { log.record("Jobs", false, "Couldn't update the job tracker: ${it.javaClass.simpleName}") }
        }

        if (cal != null) {
            val hash = cal.eventHash(p.gmailId)
            workflow.insertCalendar(CalendarExtractionEntity(
                id = UUID.nameUUIDFromBytes("cal:$hash".toByteArray()).toString(), emailId = p.gmailId, eventHash = hash,
                title = cal.title ?: listOfNotNull(ai.opportunity?.role, ai.opportunity?.company).joinToString(" · ").ifBlank { p.subject },
                date = cal.date?.toString(), startTime = cal.start?.toString(), endTime = cal.end?.toString(), timezone = cal.zone?.id,
                location = cal.location, meetingUrl = cal.meetingUrl, organizer = cal.organizer, description = cal.description,
                dateText = cal.dateText, ambiguous = cal.ambiguous, ambiguityReason = cal.ambiguityReason, calendarEventId = null,
                status = "PROPOSED", createdAt = now, updatedAt = now,
            ))
        }

        runCatching { mailbox.autoLabel(p.gmailId, m.accountEmail, c.primary) }

        val planned = MailActionPlanner.plan(c)
        val autoIds = mutableListOf<String>()
        val rules = workflow.enabledRules().mapNotNull { r -> AutomationRuleType.entries.firstOrNull { it.name == r.id } }.toSet()
        for (plan in planned) {
            if (actions.byIdempotencyKey(plan.idempotencyKey) != null) continue
            val id = UUID.nameUUIDFromBytes(plan.idempotencyKey.toByteArray()).toString()
            actions.upsert(MailActionEntity(
                id = id, emailId = p.gmailId, actionType = plan.type.name,
                payloadJson = "{}", status = MailActionStatus.REVIEW_REQUIRED.name, idempotencyKey = plan.idempotencyKey,
                createdAt = now, updatedAt = now, recipient = ai.documentRequest?.recipient ?: p.replyTo ?: p.fromEmail,
            ))
            if (MailPolicyEngine.shouldAutoExecute(plan.type, c.primary, c.confidence, cal, rules, zone, c.suspiciousReasons.isNotEmpty())) {
                autoIds += id
            }
        }

        if (celebration != null && settings.current().celebrations) {
            val key = IdempotencyKeys.celebration(p.gmailId, celebration)
            val subtitle = listOfNotNull(ai.opportunity?.role, ai.opportunity?.company).joinToString("\n").ifBlank { null }
            val inserted = workflow.insertCelebration(CelebrationEntity(
                id = key, emailId = p.gmailId, type = celebration.name, state = CelebrationState.CELEBRATION_ELIGIBLE.name,
                title = celebration.headline, subtitle = subtitle, eligibleAt = now, shownAt = null, detailsViewedAt = null, replayCount = 0,
            ))
            // In the background, a notification brings the user back; the celebration plays when they open the app.
            if (inserted != -1L && !AppVisibility.foreground) notifier.celebration(p.gmailId, celebration.headline, subtitle)
        }
        return autoIds
    }

    // ---------------- documents ----------------

    /** Finds candidate files in authorized sources and ranks them on the phone. */
    suspend fun resolveDocuments(actionId: String): List<RankedDocument> {
        val action = actions.get(actionId) ?: return emptyList()
        val item = intelligence.byEmailId(action.emailId) ?: return emptyList()
        val request = MailMapper.document(json, item.documentRequestJson) ?: return emptyList()
        val history = workflow.selectionCounts(request.type.name).associate { it.uri to it.times }
        val local = documents.listFiles()
        val keywords = (request.type.keywords + DocumentResolver.tokens(request.description.orEmpty())).distinct()
        val drive = runCatching { documents.searchDrive(keywords) }.getOrDefault(DriveDocuments.SearchResult(emptyList(), emptySet()))
        val content = runCatching { extractor.extract(local) }.getOrDefault(DocumentTextExtractor.Extraction(emptyMap(), emptySet()))
        val ranked = DocumentResolver.rank(request, local + drive.files, System.currentTimeMillis(), history, limit = 6,
            contentText = content.text, contentHits = drive.contentHits)
        workflow.clearCandidates(actionId)
        workflow.insertCandidates(ranked.mapIndexed { i, r ->
            DocumentCandidateEntity("$actionId:$i", actionId, r.file.uri, r.file.name, r.file.mimeType, r.file.sizeBytes,
                r.file.modifiedAt, r.label.name + (if (r.contentMatch) "_CONTENT" else "") + (if (r.file.uri in content.locked) "_LOCKED" else ""), i)
        })
        return ranked
    }

    /**
     * The user ticked or unticked a file. Ticking is the only way an attachment becomes "confirmed".
     * Several files can be attached; the total must stay under Gmail's limit (checked again by the policy).
     */
    suspend fun toggleDocument(actionId: String, file: DocumentFile, recommended: Boolean, keepCopy: Boolean): String? {
        val action = actions.get(actionId) ?: return "Action not found"
        val current = workflow.selectionsFor(actionId)
        current.firstOrNull { it.uri == file.uri || it.displayName == file.name && it.sizeBytes == file.sizeBytes }?.let { existing ->
            workflow.deleteSelection(existing.id)
            invalidateApproval(action)
            return null
        }
        if (current.size >= MAX_ATTACHMENTS) return "Up to $MAX_ATTACHMENTS files per email"
        val total = current.sumOf { it.sizeBytes ?: 0L } + (file.sizeBytes ?: 0L)
        if (total > com.iamode.app.domain.mail.AttachmentSafety.MAX_TOTAL_BYTES) return "Together these files are too large for Gmail (18 MB)"
        val item = intelligence.byEmailId(action.emailId)
        val chosen = if (keepCopy && !file.uri.startsWith("file:")) documents.keepCopy(Uri.parse(file.uri), file.name) ?: file else file
        workflow.insertSelection(DocumentSelectionEntity(
            id = UUID.randomUUID().toString(), actionId = actionId, emailId = action.emailId,
            documentType = MailMapper.document(json, item?.documentRequestJson)?.type?.name,
            uri = chosen.uri, displayName = chosen.name, mimeType = chosen.mimeType, sizeBytes = chosen.sizeBytes,
            recommended = recommended, selectedAt = System.currentTimeMillis(),
        ))
        invalidateApproval(action)
        return null
    }

    /** Changing the attachments invalidates an earlier approval. */
    private suspend fun invalidateApproval(action: MailActionEntity) {
        if (action.status == MailActionStatus.APPROVED.name) {
            actions.upsert(action.copy(status = MailActionStatus.REVIEW_REQUIRED.name, approvedAt = null, approvedBy = null,
                updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun describePicked(uri: Uri) = documents.describe(uri)

    // ---------------- drafts ----------------

    /** Fetches the email from Gmail (not stored) and asks the AI for an editable draft. */
    suspend fun draftReply(actionId: String, tone: String = "professional", instructions: String? = null): Result<Unit> = runCatching {
        val action = requireNotNull(actions.get(actionId)) { "Action not found" }
        val item = requireNotNull(intelligence.byEmailId(action.emailId)) { "Email not found" }
        val account = requireNotNull(item.accountEmail) { "Gmail account missing" }
        val email = requireNotNull(
            when (com.iamode.app.domain.mail.MailIds.providerOf(action.emailId)) {
                com.iamode.app.domain.mail.MailProviderKind.GMAIL -> gmail.fetchEmail(account, action.emailId)
                com.iamode.app.domain.mail.MailProviderKind.OUTLOOK -> outlook.fetchEmail(account, action.emailId)
            },
        ) { "Couldn't load the email from your mailbox" }
        val purpose = when (MailActionType.valueOf(action.actionType)) {
            MailActionType.SEND_DOCUMENT_REPLY -> "send_document"
            MailActionType.UNSUBSCRIBE_EMAIL -> "unsubscribe"
            MailActionType.SEND_FOLLOW_UP -> "follow_up"
            else -> if (item.category == "interview_invitation") "confirm_interview" else "reply"
        }
        val res = api.mailReplyDraft(ReplyDraftRequestDto(
            emailId = action.emailId, fromAddress = email.mail.fromEmail, fromName = email.mail.fromName,
            subject = email.mail.subject, body = email.mail.body.take(12_000), threadContext = email.threadContext,
            userName = settings.current().myName.trim(), purpose = purpose, tone = tone,
            attachmentNames = workflow.selectionsFor(actionId).map { it.displayName }, instructions = instructions,
            style = if (settings.current().matchWritingStyle) style.current()?.let(style::hints) else null,
            language = settings.current().mailReplyLanguage,
        ))
        val fresh = requireNotNull(actions.get(actionId))
        actions.upsert(fresh.copy(draftSubject = res.draft.subject, draftBody = res.draft.body, updatedAt = System.currentTimeMillis(),
            status = if (fresh.status == MailActionStatus.APPROVED.name) MailActionStatus.REVIEW_REQUIRED.name else fresh.status,
            approvedAt = null, approvedBy = null))
    }

    /** The user edited the draft. Any earlier approval no longer covers it. */
    suspend fun updateDraft(actionId: String, subject: String, body: String) {
        val a = actions.get(actionId) ?: return
        if (a.draftSubject == subject && a.draftBody == body) return
        actions.upsert(a.copy(draftSubject = subject, draftBody = body, updatedAt = System.currentTimeMillis(),
            status = if (a.status == MailActionStatus.APPROVED.name) MailActionStatus.REVIEW_REQUIRED.name else a.status,
            approvedAt = null, approvedBy = null))
    }

    /** User edits to an interview's date/time/zone. Only confirmed values clear the ambiguity. */
    suspend fun confirmCalendar(emailId: String, date: String, start: String, end: String?, timezone: String, title: String,
                                location: String?, meetingUrl: String?) {
        val e = workflow.calendarFor(emailId) ?: return
        workflow.upsertCalendar(e.copy(date = date, startTime = start, endTime = end, timezone = timezone, title = title,
            location = location, meetingUrl = meetingUrl?.takeIf { it.startsWith("https://") }, ambiguous = false,
            ambiguityReason = null, updatedAt = System.currentTimeMillis()))
    }

    // ---------------- approvals ----------------

    /** Records the approval. Returns false when the action can't be approved from its current state. */
    suspend fun approve(actionId: String, actor: Actor = Actor.USER): Boolean {
        val a = actions.get(actionId) ?: return false
        val from = MailActionStatus.valueOf(a.status)
        val start = if (from == MailActionStatus.FAILED) ActionStateMachine.next(from, MailActionEvent.RETRY) ?: return false else from
        val to = ActionStateMachine.next(start, MailActionEvent.APPROVE) ?: return false
        val now = System.currentTimeMillis()
        actions.upsert(a.copy(status = to.name, approvedAt = now, approvedBy = actor.name, error = null, updatedAt = now))
        actions.insertApproval(ApprovalRecordEntity(UUID.randomUUID().toString(), actionId, actor.name, "APPROVED", now, null))
        // Only after the celebration was seen: approving never cancels a celebration that hasn't played yet.
        workflow.advance(a.emailId, CelebrationState.ACTION_APPROVED.name,
            listOf(CelebrationState.CELEBRATION_SHOWN, CelebrationState.DETAILS_VIEWED, CelebrationState.ACTION_REQUIRED).map { it.name })
        return true
    }

    suspend fun reject(actionId: String) {
        val a = actions.get(actionId) ?: return
        val to = ActionStateMachine.next(MailActionStatus.valueOf(a.status), MailActionEvent.REJECT) ?: return
        val now = System.currentTimeMillis()
        actions.upsert(a.copy(status = to.name, draftBody = null, updatedAt = now))
        actions.insertApproval(ApprovalRecordEntity(UUID.randomUUID().toString(), actionId, Actor.USER.name, "REJECTED", now, null))
        actions.insertAudit(MailAuditEntity(UUID.randomUUID().toString(), actionId, null, a.actionType, a.recipient ?: a.emailId,
            "REJECTED", null, null, null, now, emailId = a.emailId, actor = Actor.USER.name, requestedAt = a.createdAt))
    }

    /** Approvals older than 24 h without execution expire; the user reviews again. */
    suspend fun expireStaleApprovals() {
        val cutoff = System.currentTimeMillis() - MailPolicyEngine.APPROVAL_TTL_MS
        actions.stalePending(cutoff).filter { it.status == MailActionStatus.APPROVED.name }.forEach { a ->
            actions.transition(a.id, MailActionStatus.APPROVED.name, MailActionStatus.EXPIRED.name, System.currentTimeMillis())
        }
    }

    // ---------------- inbox actions (local, reversible) ----------------

    suspend fun setArchived(emailId: String, archived: Boolean): String = mailbox.setArchived(emailId, archived)
    suspend fun markRead(emailId: String): String = mailbox.markRead(emailId)
    suspend fun moveTo(emailId: String, label: String): String = mailbox.moveTo(emailId, label)
    suspend fun gmailLabels(emailId: String): List<String> = mailbox.labels(emailId)

    /** Mutes locally; mail already here from that sender is archived (in Gmail too when mirroring is on). */
    suspend fun mute(address: String) {
        val a = address.lowercase()
        workflow.mute(MutedSenderEntity(a, System.currentTimeMillis()))
        intelligence.fromSender(a).filter { !it.archived }.take(50).forEach { mailbox.setArchived(it.emailId, true) }
        intelligence.archiveFrom(a, System.currentTimeMillis())
    }

    suspend fun unmute(address: String) = workflow.unmute(address)

    sealed interface Unsubscribe {
        data class ByEmail(val address: String) : Unsubscribe
        data class ByWeb(val url: String) : Unsubscribe
    }

    /** From the List-Unsubscribe header. Nothing happens until the user confirms. */
    fun unsubscribeOptions(raw: String?): List<Unsubscribe> =
        Regex("<([^>]+)>").findAll(raw.orEmpty()).mapNotNull { m ->
            val v = m.groupValues[1].trim()
            when {
                v.startsWith("mailto:", true) -> Unsubscribe.ByEmail(v.removePrefix("mailto:").substringBefore('?').lowercase())
                v.startsWith("https://", true) -> Unsubscribe.ByWeb(v)
                else -> null
            }
        }.toList()

    /** Proposes an unsubscribe email; it still goes through review and approval. */
    suspend fun proposeUnsubscribeEmail(emailId: String, address: String): String {
        val key = IdempotencyKeys.proposal(MailActionType.UNSUBSCRIBE_EMAIL, emailId)
        actions.byIdempotencyKey(key)?.let { return it.id }
        val id = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        val now = System.currentTimeMillis()
        actions.upsert(MailActionEntity(id, emailId, MailActionType.UNSUBSCRIBE_EMAIL.name, "{}", MailActionStatus.REVIEW_REQUIRED.name,
            key, now, now, recipient = address, draftSubject = "Unsubscribe", draftBody = "Please remove me from this mailing list. Thank you."))
        return id
    }

    // ---------------- automation rules ----------------
    suspend fun setRule(type: AutomationRuleType, enabled: Boolean) =
        workflow.upsertRule(AutomationRuleEntity(type.name, enabled, System.currentTimeMillis()))

    // ---------------- celebration journey ----------------
    suspend fun markDetailsViewed(emailId: String) {
        workflow.markDetailsViewed(emailId, System.currentTimeMillis())
        workflow.advance(emailId, CelebrationState.DETAILS_VIEWED.name,
            listOf(CelebrationState.CELEBRATION_SHOWN.name))
    }

    private companion object { const val MAX_ATTACHMENTS = 5 }
}
