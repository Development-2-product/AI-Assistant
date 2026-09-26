package com.iamode.app.data.jobs

import com.iamode.app.core.database.dao.JobApplicationDao
import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.entity.ApplicationEventEntity
import com.iamode.app.core.database.entity.JobApplicationEntity
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.domain.jobs.ApplicationEventType
import com.iamode.app.domain.jobs.ApplicationEvidence
import com.iamode.app.domain.jobs.ApplicationInsights
import com.iamode.app.domain.jobs.ApplicationMatcher
import com.iamode.app.domain.jobs.ApplicationSource
import com.iamode.app.domain.jobs.ApplicationStage
import com.iamode.app.domain.jobs.InsightInput
import com.iamode.app.domain.jobs.MatchResult
import com.iamode.app.domain.jobs.StageMachine
import com.iamode.app.domain.jobs.TrackedApplication
import com.iamode.app.domain.mail.MailActionStatus
import com.iamode.app.domain.mail.MailActionType
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the mail pipeline tells the tracker about one career email. */
data class CareerEmail(
    val emailId: String,
    val threadId: String,
    val fromAddress: String,
    val replyTo: String?,
    val company: String?,
    val role: String?,
    val stage: String?,
    val referenceId: String?,
    val atsPlatform: String?,
    val interviewRound: Int?,
    val deadline: String?,
    val nextStep: String?,
    val receivedAt: Long,
)

/**
 * The job-application tracker. Everything is stored on the phone (encrypted database).
 * Email evidence only moves applications forward; the user can always change a stage by hand.
 */
@Singleton
class JobTrackerRepository @Inject constructor(
    private val dao: JobApplicationDao,
    private val actions: MailActionDao,
    private val json: Json,
    private val log: DiagnosticsLog,
) {
    private val lock = Mutex() // one email at a time, so two syncs can't create duplicates
    private val setSer = SetSerializer(String.serializer())

    val applications = dao.observeAll()
    val funnel = dao.observeAll().map { list ->
        ApplicationInsights.funnel(list.map {
            InsightInput(stageOf(it.maxStage), stageOf(it.stage), it.appliedAt, it.firstResponseAt)
        })
    }
    fun application(id: String) = dao.observe(id)
    fun events(id: String) = dao.observeEvents(id)

    // ---------------------------------------------------------------- from email
    /** Returns the application id the email was linked to, or null if it isn't trackable. */
    suspend fun onCareerEmail(e: CareerEmail): String? = lock.withLock {
        dao.applicationForEmail(e.emailId)?.let { return@withLock it } // already processed
        val evidenceStage = ApplicationStage.fromApi(e.stage)
        val all = dao.all()
        val tracked = all.map(::toTracked)
        val result = ApplicationMatcher.match(
            ApplicationEvidence(e.threadId, e.fromAddress, e.company, e.role, e.referenceId, evidenceStage), tracked,
        )
        val (existing, note) = when (result) {
            is MatchResult.Existing -> all.first { it.id == result.applicationId } to null
            // Several fit and the email doesn't say which: attach to the most recently active one and say so.
            is MatchResult.Ambiguous -> all.filter { it.id in result.candidateIds }.maxBy { it.lastActivityAt } to
                "Linked to the most recent application at this company. Change it if it's a different role."
            MatchResult.New -> null to null
            MatchResult.Untrackable -> return@withLock null
        }
        val now = System.currentTimeMillis()
        val app = existing?.let { update(it, e, evidenceStage, now) } ?: create(e, evidenceStage, now)
        dao.upsert(app)
        val type = evidenceStage?.let(ApplicationEventType::forStage) ?: ApplicationEventType.NOTE
        val detail = listOfNotNull(
            e.interviewRound?.let { "Round $it" }, e.nextStep, note,
            if (existing == null) "Tracking started from an email" else null,
        ).joinToString(" · ").ifBlank { null }
        dao.insertEvent(ApplicationEventEntity(UUID.randomUUID().toString(), app.id, type.name, e.emailId, e.receivedAt, detail,
            dedupeKey = "${app.id}:${e.emailId}:${type.name}"))
        app.id
    }

    private fun create(e: CareerEmail, stage: ApplicationStage?, now: Long): JobApplicationEntity {
        val s = stage ?: ApplicationStage.SAVED
        val responded = s.order > ApplicationStage.APPLIED.order || s == ApplicationStage.REJECTED
        return JobApplicationEntity(
            id = UUID.randomUUID().toString(), company = e.company, companyKey = keyFor(e.company, e.fromAddress), role = e.role,
            location = null, source = ApplicationSource.EMAIL.name, jobUrl = null, atsPlatform = e.atsPlatform,
            referenceId = e.referenceId, stage = s.name, maxStage = higher(ApplicationStage.SAVED, s).name,
            appliedAt = if (s != ApplicationStage.SAVED) e.receivedAt else null,
            firstResponseAt = if (responded) e.receivedAt else null, lastActivityAt = e.receivedAt,
            nextStepAt = deadlineMillis(e.deadline), nextStepNote = e.nextStep, notes = null,
            recruiterEmail = recruiterOf(e), lastEmailId = e.emailId,
            threadIdsJson = json.encodeToString(setSer, setOf(e.threadId)),
            senderDomainsJson = json.encodeToString(setSer, setOfNotNull(ApplicationMatcher.domainOf(e.fromAddress))),
            followUpsSent = 0, lastFollowUpAt = null, lastReminderType = null, lastRemindedAt = null, createdAt = now, updatedAt = now,
        )
    }

    private fun update(a: JobApplicationEntity, e: CareerEmail, evidence: ApplicationStage?, now: Long): JobApplicationEntity {
        val current = stageOf(a.stage)
        val next = evidence?.let { StageMachine.fromEvidence(current, it) } ?: current
        val isResponse = evidence != null && evidence != ApplicationStage.APPLIED
        return a.copy(
            company = a.company ?: e.company, role = a.role ?: e.role,
            atsPlatform = a.atsPlatform ?: e.atsPlatform, referenceId = a.referenceId ?: e.referenceId,
            stage = next.name, maxStage = higher(stageOf(a.maxStage), next).name,
            appliedAt = a.appliedAt ?: e.receivedAt.takeIf { evidence == ApplicationStage.APPLIED },
            firstResponseAt = a.firstResponseAt ?: e.receivedAt.takeIf { isResponse && a.appliedAt != null },
            lastActivityAt = maxOf(a.lastActivityAt, e.receivedAt),
            nextStepAt = deadlineMillis(e.deadline) ?: a.nextStepAt.takeIf { next == current },
            nextStepNote = e.nextStep ?: a.nextStepNote.takeIf { next == current },
            recruiterEmail = recruiterOf(e) ?: a.recruiterEmail, lastEmailId = e.emailId,
            threadIdsJson = json.encodeToString(setSer, threads(a) + e.threadId),
            senderDomainsJson = json.encodeToString(setSer, domains(a) + setOfNotNull(ApplicationMatcher.domainOf(e.fromAddress))),
            updatedAt = now,
        )
    }

    // ---------------------------------------------------------------- manual
    suspend fun addManual(company: String?, role: String?, jobUrl: String?, source: ApplicationSource, stage: ApplicationStage, location: String?): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsert(JobApplicationEntity(
            id = id, company = company?.trim()?.ifBlank { null }, companyKey = keyFor(company, null), role = role?.trim()?.ifBlank { null },
            location = location?.trim()?.ifBlank { null }, source = source.name, jobUrl = jobUrl?.trim()?.ifBlank { null },
            atsPlatform = null, referenceId = null, stage = stage.name, maxStage = stage.name,
            appliedAt = now.takeIf { stage != ApplicationStage.SAVED }, firstResponseAt = null, lastActivityAt = now,
            nextStepAt = null, nextStepNote = null, notes = null, recruiterEmail = null, lastEmailId = null,
            threadIdsJson = "[]", senderDomainsJson = "[]", followUpsSent = 0, lastFollowUpAt = null,
            lastReminderType = null, lastRemindedAt = null, createdAt = now, updatedAt = now,
        ))
        event(id, ApplicationEventType.CREATED, if (source == ApplicationSource.SHARED_LINK) "Added from a shared job link" else "Added by you")
        return id
    }

    /** A choice the user made on screen: applied directly (can move backwards). */
    suspend fun setStage(id: String, stage: ApplicationStage) {
        val a = dao.get(id) ?: return
        if (a.stage == stage.name) return
        val now = System.currentTimeMillis()
        dao.upsert(a.copy(stage = stage.name, maxStage = higher(stageOf(a.maxStage), stage).name,
            appliedAt = a.appliedAt ?: now.takeIf { stage.order >= ApplicationStage.APPLIED.order && !stage.terminal },
            lastActivityAt = now, updatedAt = now))
        event(id, ApplicationEventType.STAGE_CHANGED, "${stageOf(a.stage).label} → ${stage.label}")
    }

    suspend fun updateDetails(id: String, company: String?, role: String?, location: String?, jobUrl: String?, notes: String?) {
        val a = dao.get(id) ?: return
        dao.upsert(a.copy(company = company?.ifBlank { null }, companyKey = keyFor(company, null).ifBlank { a.companyKey },
            role = role?.ifBlank { null }, location = location?.ifBlank { null }, jobUrl = jobUrl?.ifBlank { null },
            notes = notes?.ifBlank { null }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addNote(id: String, text: String) = event(id, ApplicationEventType.NOTE, text.trim().take(500))

    suspend fun delete(id: String) { dao.deleteEvents(id); dao.delete(id) }

    // ---------------------------------------------------------------- follow-ups
    /**
     * Proposes a follow-up email in the same thread, to the recruiter who wrote last. It goes through the normal
     * review → approve → send path (policy engine: recipient must be that email's sender or Reply-To).
     * Returns the action id to open, or null if there's no email to reply to.
     */
    suspend fun proposeFollowUp(id: String): String? {
        val a = dao.get(id) ?: return null
        val emailId = a.lastEmailId ?: return null
        val recipient = a.recruiterEmail ?: return null
        val key = "follow_up:$id:${a.followUpsSent + 1}"
        actions.byIdempotencyKey(key)?.let { return it.id }
        val actionId = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        val now = System.currentTimeMillis()
        actions.upsert(MailActionEntity(actionId, emailId, MailActionType.SEND_FOLLOW_UP.name,
            payloadJson = """{"applicationId":"$id"}""", status = MailActionStatus.REVIEW_REQUIRED.name, idempotencyKey = key,
            createdAt = now, updatedAt = now, recipient = recipient))
        return actionId
    }

    /** Called by the executor after a follow-up was actually sent. */
    suspend fun onFollowUpSent(payloadJson: String?) {
        val id = payloadJson?.let { Regex("\"applicationId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: return
        val a = dao.get(id) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(a.copy(followUpsSent = a.followUpsSent + 1, lastFollowUpAt = now, lastActivityAt = now, updatedAt = now))
        event(id, ApplicationEventType.FOLLOW_UP_SENT, null)
    }

    // ---------------------------------------------------------------- reminders (worker)
    suspend fun all() = dao.all()

    suspend fun markReminded(id: String, type: String) {
        val a = dao.get(id) ?: return
        dao.upsert(a.copy(lastReminderType = type, lastRemindedAt = System.currentTimeMillis()))
    }

    suspend fun markGhosted(id: String) {
        val a = dao.get(id) ?: return
        if (stageOf(a.stage).terminal) return
        dao.upsert(a.copy(stage = ApplicationStage.GHOSTED.name, updatedAt = System.currentTimeMillis()))
        event(id, ApplicationEventType.GHOSTED, "30 days without a reply. Any new email brings it back.")
        log.record("Jobs", true, "Marked an application as no response")
    }

    // ---------------------------------------------------------------- helpers
    private suspend fun event(id: String, type: ApplicationEventType, detail: String?) {
        val eid = UUID.randomUUID().toString()
        dao.insertEvent(ApplicationEventEntity(eid, id, type.name, null, System.currentTimeMillis(), detail, dedupeKey = eid))
    }

    fun toTracked(a: JobApplicationEntity) = TrackedApplication(
        a.id, a.company, a.role, stageOf(a.stage), a.lastActivityAt, threads(a), domains(a), a.referenceId,
    )

    private fun threads(a: JobApplicationEntity): Set<String> = runCatching { json.decodeFromString(setSer, a.threadIdsJson) }.getOrDefault(emptySet())
    private fun domains(a: JobApplicationEntity): Set<String> = runCatching { json.decodeFromString(setSer, a.senderDomainsJson) }.getOrDefault(emptySet())

    private fun keyFor(company: String?, from: String?): String =
        ApplicationMatcher.normalizeCompany(company).ifBlank { from?.let(ApplicationMatcher::companyKeyFromDomain).orEmpty() }

    /** Reply-To if given, else the sender, unless that's an automated address. */
    private fun recruiterOf(e: CareerEmail): String? = listOfNotNull(e.replyTo, e.fromAddress)
        .map { it.trim().lowercase() }
        .firstOrNull { !Regex("(^|[._-])(no-?reply|do-?not-?reply|notifications?|mailer)").containsMatchIn(it.substringBefore('@')) }

    private fun deadlineMillis(date: String?): Long? = date?.let {
        runCatching { LocalDate.parse(it).atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }

    companion object {
        fun stageOf(name: String): ApplicationStage = runCatching { ApplicationStage.valueOf(name) }.getOrDefault(ApplicationStage.SAVED)
        /** The furthest non-final stage reached (used for the funnel). */
        private fun higher(a: ApplicationStage, b: ApplicationStage): ApplicationStage =
            listOf(a, b).filter { !it.terminal }.maxByOrNull { it.order } ?: a
    }
}
