package com.iamode.app.domain.mail

import java.time.ZoneId

enum class Actor { USER, AUTOMATION }

data class AttachmentInfo(val name: String, val mimeType: String?, val sizeBytes: Long?, val userConfirmed: Boolean)

/** Everything the policy needs to judge one action, gathered by the executor right before it runs. */
data class ActionCheck(
    val type: MailActionType,
    val status: MailActionStatus,
    val approvedAt: Long?,
    val approvedBy: Actor?,
    val recipient: String?,
    val allowedRecipients: Set<String>,
    val draftBody: String?,
    val attachments: List<AttachmentInfo>,
    val calendar: CalendarProposal?,
)

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Denied(val reason: String) : PolicyDecision
}

/**
 * The gate between AI proposals and the outside world. The AI never reaches it directly: application
 * code builds an [ActionCheck] from stored data and calls [canExecute] immediately before acting.
 */
object MailPolicyEngine {
    const val APPROVAL_TTL_MS = 24 * 60 * 60 * 1000L

    fun canExecute(a: ActionCheck, now: Long, deviceZone: ZoneId): PolicyDecision {
        if (!a.type.external) return PolicyDecision.Denied("This action has nothing to send or create")
        if (a.status != MailActionStatus.APPROVED) return PolicyDecision.Denied("Not approved")
        val approvedAt = a.approvedAt ?: return PolicyDecision.Denied("Not approved")
        if (now - approvedAt > APPROVAL_TTL_MS) return PolicyDecision.Denied("Approval expired. Review it again")
        if (a.approvedBy == Actor.AUTOMATION && a.type != MailActionType.CREATE_CALENDAR_EVENT) {
            return PolicyDecision.Denied("Only calendar events can be automated")
        }

        return when (a.type) {
            MailActionType.SEND_DOCUMENT_REPLY, MailActionType.SEND_REPLY, MailActionType.UNSUBSCRIBE_EMAIL,
            MailActionType.SEND_FOLLOW_UP -> {
                val to = a.recipient?.trim()?.lowercase()
                    ?: return PolicyDecision.Denied("No recipient")
                // The AI can't redirect mail: replies only go to the sender or their Reply-To.
                if (to !in a.allowedRecipients.map { it.trim().lowercase() }) {
                    return PolicyDecision.Denied("Recipient isn't the sender of this email")
                }
                if (a.draftBody.isNullOrBlank()) return PolicyDecision.Denied("The reply is empty")
                if (a.type == MailActionType.SEND_DOCUMENT_REPLY && a.attachments.isEmpty()) {
                    return PolicyDecision.Denied("Choose the document to attach")
                }
                if (a.attachments.any { !it.userConfirmed }) return PolicyDecision.Denied("Every attachment must be confirmed by you")
                AttachmentSafety.check(a.attachments)?.let { return PolicyDecision.Denied(it) }
                PolicyDecision.Allowed
            }
            MailActionType.CREATE_CALENDAR_EVENT -> {
                val cal = a.calendar ?: return PolicyDecision.Denied("No event details")
                if (cal.resolve(deviceZone) == null) PolicyDecision.Denied("Confirm the date and time first")
                else PolicyDecision.Allowed
            }
            MailActionType.REVIEW_EMAIL -> PolicyDecision.Denied("Nothing to execute")
        }
    }

    /** Automation is opt-in per rule, and only ever for complete, high-confidence interview events. */
    fun shouldAutoExecute(
        type: MailActionType, category: MailCategory, confidence: String?, calendar: CalendarProposal?,
        enabledRules: Set<AutomationRuleType>, deviceZone: ZoneId, suspicious: Boolean,
    ): Boolean = type == MailActionType.CREATE_CALENDAR_EVENT &&
        AutomationRuleType.AUTO_ADD_INTERVIEWS in enabledRules &&
        category == MailCategory.INTERVIEW_INVITATION &&
        confidence == "high" && !suspicious &&
        calendar != null && calendar.zone != null && calendar.resolve(deviceZone) != null
}

object AttachmentSafety {
    /** Gmail's limit is 25 MB after encoding (about +37 %), so the raw total must stay under ~18 MB. */
    const val MAX_TOTAL_BYTES = 18L * 1024 * 1024
    private val blocked = setOf(
        "exe", "scr", "bat", "cmd", "com", "js", "vbs", "jar", "apk", "msi", "iso", "lnk", "ps1", "hta", "dll", "sh",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Null when safe, otherwise the reason. */
    fun check(files: List<AttachmentInfo>): String? {
        files.firstOrNull { extension(it.name) in blocked }?.let { return "${it.name} is a program file and can't be attached" }
        val total = files.sumOf { it.sizeBytes ?: 0L }
        if (total > MAX_TOTAL_BYTES) return "Attachments are too large for Gmail (max 18 MB in total)"
        if (files.any { (it.sizeBytes ?: 0L) == 0L && it.sizeBytes != null }) return "One of the files is empty"
        return null
    }

    /** True when the file is in the format the sender asked for (or no format was requested). */
    fun matchesFormat(requested: String?, name: String, mimeType: String?): Boolean {
        val want = requested?.trim()?.lowercase()?.removePrefix(".") ?: return true
        val ext = extension(name)
        return when (want) {
            "pdf" -> ext == "pdf" || mimeType == "application/pdf"
            "word", "doc", "docx" -> ext == "doc" || ext == "docx"
            "image", "jpg", "jpeg", "png" -> ext in setOf("jpg", "jpeg", "png") || mimeType?.startsWith("image/") == true
            else -> ext == want
        }
    }
}
