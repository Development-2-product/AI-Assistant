package com.iamode.app.data.mail

import com.iamode.app.core.i18n.tr

import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.dao.MailIntelligenceDao
import com.iamode.app.core.database.entity.MailAuditEntity
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.data.gmail.GmailMailbox
import com.iamode.app.data.outlook.OutlookRepository
import com.iamode.app.domain.mail.Actor
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.domain.mail.MailIds
import com.iamode.app.domain.mail.MailProviderKind
import com.iamode.app.domain.repository.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archive / mark read / move / label, in IA Mode and (when "Mirror to Gmail/Outlook" is on) in the mailbox itself.
 * Routes to Gmail or Outlook by the message ID. Reversible, user-initiated (or the opt-in labels rule), always audited.
 */
@Singleton
class MailboxActions @Inject constructor(
    private val gmail: GmailMailbox,
    private val outlook: OutlookRepository,
    private val intelligence: MailIntelligenceDao,
    private val actions: MailActionDao,
    private val settings: SettingsRepository,
    private val log: DiagnosticsLog,
) {
    private sealed interface Result { data object Done : Result; data class Failed(val reason: String) : Result }

    private fun GmailMailbox.Outcome.unified(): Result = if (this is GmailMailbox.Outcome.Failed) Result.Failed(reason) else Result.Done
    private fun OutlookRepository.Outcome.unified(): Result = if (this is OutlookRepository.Outcome.Failed) Result.Failed(reason) else Result.Done
    private fun providerName(emailId: String) = MailIds.providerOf(emailId).label

    suspend fun setArchived(emailId: String, archived: Boolean, actor: Actor = Actor.USER): String {
        intelligence.setArchived(emailId, archived, System.currentTimeMillis())
        if (!settings.current().gmailMirror) return if (archived) tr("Archived in IA Mode") else tr("Moved back to Inbox")
        val account = intelligence.byEmailId(emailId)?.accountEmail ?: return tr("Archived in IA Mode")
        val r = when (MailIds.providerOf(emailId)) {
            MailProviderKind.GMAIL -> (if (archived) gmail.archive(account, emailId) else gmail.moveToInbox(account, emailId)).unified()
            MailProviderKind.OUTLOOK -> (if (archived) outlook.archive(account, emailId) else outlook.moveToInbox(account, emailId)).unified()
        }
        audit(if (archived) "MAILBOX_ARCHIVE" else "MAILBOX_MOVE_TO_INBOX", emailId, account, actor, r)
        val where = providerName(emailId)
        return when (r) {
            Result.Done -> if (archived) tr("Archived in IA Mode and %1\$s", where) else tr("Moved back to Inbox in %1\$s", where)
            is Result.Failed -> tr("Archived in IA Mode only. %1\$s", (r.reason))
        }
    }

    suspend fun markRead(emailId: String): String {
        val account = intelligence.byEmailId(emailId)?.accountEmail ?: return tr("Email not found")
        val r = when (MailIds.providerOf(emailId)) {
            MailProviderKind.GMAIL -> gmail.markRead(account, emailId).unified()
            MailProviderKind.OUTLOOK -> outlook.markRead(account, emailId).unified()
        }
        audit("MAILBOX_MARK_READ", emailId, account, Actor.USER, r)
        return if (r is Result.Failed) r.reason else tr("Marked as read in %1\$s", (providerName(emailId)))
    }

    /** Gmail: label + remove from inbox. Outlook: move to a folder (created if missing). */
    suspend fun moveTo(emailId: String, label: String): String {
        val account = intelligence.byEmailId(emailId)?.accountEmail ?: return tr("Email not found")
        val r = when (MailIds.providerOf(emailId)) {
            MailProviderKind.GMAIL -> gmail.moveToLabel(account, emailId, label).unified()
            MailProviderKind.OUTLOOK -> outlook.moveToFolder(account, emailId, label).unified()
        }
        audit("MAILBOX_MOVE", emailId, account, Actor.USER, r, detail = label)
        if (r == Result.Done) intelligence.setArchived(emailId, true, System.currentTimeMillis())
        return if (r is Result.Failed) r.reason else "Moved to \"$label\" in ${providerName(emailId)}"
    }

    /** Gmail labels or Outlook folders, for the "Move to" picker. */
    suspend fun labels(emailId: String): List<String> {
        val account = intelligence.byEmailId(emailId)?.accountEmail ?: return emptyList()
        return when (MailIds.providerOf(emailId)) {
            MailProviderKind.GMAIL -> gmail.userLabels(account)
            MailProviderKind.OUTLOOK -> outlook.folders(account)
        }
    }

    /** Opt-in rule: tag understood mail with "IA Mode/…" (Gmail label, Outlook category). Never moves or archives. */
    suspend fun autoLabel(emailId: String, account: String, category: MailCategory) {
        if (!settings.current().gmailLabels) return
        val label = labelFor(category) ?: return
        val r = when (MailIds.providerOf(emailId)) {
            MailProviderKind.GMAIL -> gmail.addLabel(account, emailId, label).unified()
            MailProviderKind.OUTLOOK -> outlook.addCategory(account, emailId, label).unified()
        }
        audit("MAILBOX_LABEL", emailId, account, Actor.AUTOMATION, r, detail = label)
    }

    private fun labelFor(c: MailCategory): String? = when (c) {
        MailCategory.DOCUMENT_REQUEST -> "IA Mode/Documents"
        MailCategory.INTERVIEW_INVITATION -> "IA Mode/Interviews"
        MailCategory.JOB_SELECTION, MailCategory.JOB_OFFER, MailCategory.APPLICATION,
        MailCategory.RECRUITMENT, MailCategory.OPPORTUNITY -> "IA Mode/Opportunities"
        MailCategory.REPLY_NEEDED -> "IA Mode/Reply needed"
        MailCategory.CALENDAR -> "IA Mode/Calendar"
        MailCategory.PROMOTION -> "IA Mode/Promotions"
        MailCategory.SUSPICIOUS -> "IA Mode/Suspicious"
        else -> null
    }

    private suspend fun audit(type: String, emailId: String, account: String, actor: Actor, r: Result, detail: String? = null) {
        val now = System.currentTimeMillis()
        val failed = r as? Result.Failed
        actions.insertAudit(MailAuditEntity(
            id = UUID.randomUUID().toString(), actionId = "mailbox:$type:$emailId", userId = null, actionType = type,
            target = detail ?: account.replace(Regex("^(.)[^@]*@"), "$1***@"), status = if (failed == null) "SUCCESS" else "FAILED",
            requestMetadataJson = null, resultMetadataJson = null, error = failed?.reason, createdAt = now, emailId = emailId,
            actor = actor.name, requestedAt = now, approvedAt = if (actor == Actor.USER) now else null,
            executedAt = if (failed == null) now else null,
        ))
        if (failed != null) log.record("Mail", false, "$type failed: ${failed.reason}")
    }
}
