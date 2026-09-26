package com.iamode.app.domain.mail

/** The phone's view of one classified email (no body text). */
data class MailClassification(
    val emailId: String,
    val primary: MailCategory,
    val labels: Set<MailCategory>,
    val priority: MailPriority,
    val requiresReply: Boolean,
    val requiresAttachment: Boolean,
    val document: DocumentRequest?,
    val calendar: CalendarProposal?,
    val opportunityStatus: OpportunityStatus?,
    val suspiciousReasons: List<String>,
    val celebration: CelebrationType?,
    val confidence: String?,
)

data class PlannedAction(val type: MailActionType, val idempotencyKey: String)

/** Turns an understood email into the proposals shown in AI Actions. Proposals are inert until approved. */
object MailActionPlanner {
    private val quiet = setOf(MailCategory.PROMOTION, MailCategory.NOTIFICATION, MailCategory.NO_REPLY)

    fun plan(c: MailClassification): List<PlannedAction> {
        if (c.primary in quiet) return emptyList()
        val out = mutableListOf<MailActionType>()
        if (c.suspiciousReasons.isNotEmpty()) {
            // Never propose sending files or replies to a suspicious sender; just ask the user to look.
            return listOf(PlannedAction(MailActionType.REVIEW_EMAIL, IdempotencyKeys.proposal(MailActionType.REVIEW_EMAIL, c.emailId)))
        }
        if (c.document?.requiresAttachment == true || c.requiresAttachment) out += MailActionType.SEND_DOCUMENT_REPLY
        if (c.calendar != null && (c.primary == MailCategory.INTERVIEW_INVITATION || c.primary == MailCategory.CALENDAR ||
                MailCategory.CALENDAR in c.labels || MailCategory.INTERVIEW_INVITATION in c.labels)) {
            out += MailActionType.CREATE_CALENDAR_EVENT
        }
        if (c.requiresReply && MailActionType.SEND_DOCUMENT_REPLY !in out) out += MailActionType.SEND_REPLY
        return out.map { PlannedAction(it, IdempotencyKeys.proposal(it, c.emailId)) }
    }

    /** The celebration this email earns, if any. Suspicious mail never celebrates. */
    fun celebration(c: MailClassification): CelebrationType? =
        if (c.suspiciousReasons.isNotEmpty() || c.primary in quiet) null else c.celebration
}
