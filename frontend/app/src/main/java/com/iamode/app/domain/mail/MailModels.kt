package com.iamode.app.domain.mail

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One primary category per email; the API uses the lowercase names. */
enum class MailCategory(val label: String) {
    IMPORTANT("Important"), REPLY_NEEDED("Reply needed"), DOCUMENT_REQUEST("Document request"),
    INTERVIEW_INVITATION("Interview"), OPPORTUNITY("Opportunity"), JOB_SELECTION("Selected"), JOB_OFFER("Job offer"),
    APPLICATION("Application"), RECRUITMENT("Recruitment"), CALENDAR("Calendar"), CONGRATULATIONS("Congratulations"),
    PROMOTION("Promotion"), NOTIFICATION("Notification"), NO_REPLY("No reply"), SUSPICIOUS("Suspicious"),
    ARCHIVE("Archive"), GENERAL("General");

    val apiValue: String get() = name.lowercase()

    companion object {
        fun fromApi(v: String?): MailCategory = entries.firstOrNull { it.apiValue == v?.lowercase() } ?: GENERAL
    }
}

/** The tabs of the Mail screen. An email appears in every tab its category or labels map to. */
enum class MailTab(val label: String) {
    INBOX("Inbox"), IMPORTANT("Important"), REPLY_NEEDED("Reply Needed"), DOCUMENTS("Documents"),
    INTERVIEWS("Interviews"), OPPORTUNITIES("Opportunities"), CALENDAR("Calendar"), PROMOTIONS("Promotions"),
    NOTIFICATIONS("Notifications"), NO_REPLY("No Reply"), SUSPICIOUS("Suspicious"), ARCHIVE("Archive");
}

enum class MailPriority { LOW, NORMAL, HIGH }

object MailTabs {
    private val career = setOf(
        MailCategory.OPPORTUNITY, MailCategory.JOB_SELECTION, MailCategory.JOB_OFFER, MailCategory.APPLICATION,
        MailCategory.RECRUITMENT, MailCategory.INTERVIEW_INVITATION,
    )
    private val quiet = setOf(MailCategory.PROMOTION, MailCategory.NOTIFICATION, MailCategory.NO_REPLY)

    /**
     * Archived mail lives only in Archive. The Inbox hides promotions, notifications and no-reply mail
     * so the important things aren't buried.
     */
    fun tabsFor(
        primary: MailCategory, labels: Set<MailCategory>, priority: MailPriority,
        requiresReply: Boolean, archived: Boolean, containsEvent: Boolean,
    ): Set<MailTab> {
        if (archived) return setOf(MailTab.ARCHIVE)
        val all = labels + primary
        return buildSet {
            if (primary !in quiet) add(MailTab.INBOX)
            if (priority == MailPriority.HIGH || MailCategory.IMPORTANT in all) add(MailTab.IMPORTANT)
            if (requiresReply || MailCategory.REPLY_NEEDED in all) add(MailTab.REPLY_NEEDED)
            if (MailCategory.DOCUMENT_REQUEST in all) add(MailTab.DOCUMENTS)
            if (MailCategory.INTERVIEW_INVITATION in all) add(MailTab.INTERVIEWS)
            if (all.any { it in career }) add(MailTab.OPPORTUNITIES)
            if (containsEvent || MailCategory.CALENDAR in all) add(MailTab.CALENDAR)
            if (MailCategory.PROMOTION in all) add(MailTab.PROMOTIONS)
            if (MailCategory.NOTIFICATION in all) add(MailTab.NOTIFICATIONS)
            if (MailCategory.NO_REPLY in all) add(MailTab.NO_REPLY)
            if (MailCategory.SUSPICIOUS in all) add(MailTab.SUSPICIOUS)
        }
    }
}

enum class DocumentType(val label: String, val keywords: List<String>) {
    RESUME("Resume", listOf("resume", "cv", "curriculum", "vitae", "biodata", "profile")),
    WORK_EXPERIENCE("Work experience", listOf("experience", "work", "updates", "employment", "career", "summary", "resume", "cv")),
    ID_PROOF("ID proof", listOf("aadhaar", "aadhar", "pan", "passport", "voter", "id", "identity", "license", "licence")),
    ADDRESS_PROOF("Address proof", listOf("address", "utility", "electricity", "bill", "aadhaar", "rental", "rent")),
    CERTIFICATE("Certificate", listOf("certificate", "certification", "cert", "course", "award")),
    TRANSCRIPT("Transcript", listOf("transcript", "marksheet", "marks", "grade", "grades", "semester", "degree", "provisional")),
    PAYSLIP("Payslip", listOf("payslip", "salary", "slip", "pay", "ctc", "form16")),
    OFFER_LETTER("Offer letter", listOf("offer", "letter", "appointment")),
    RELIEVING_LETTER("Relieving letter", listOf("relieving", "experience", "letter", "release")),
    BANK_STATEMENT("Bank statement", listOf("bank", "statement", "passbook")),
    PHOTO("Photo", listOf("photo", "photograph", "passport", "picture", "pic")),
    PORTFOLIO("Portfolio", listOf("portfolio", "work", "samples", "projects")),
    OTHER("Document", emptyList());

    companion object {
        fun fromApi(v: String?): DocumentType = entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: OTHER
    }
}

data class DocumentRequest(
    val type: DocumentType,
    val description: String?,
    val requestedFormat: String?,
    val recipient: String?,
    val deadline: String?,
    val requiresAttachment: Boolean,
)

/** What the email says about an event. Only [resolve] turns it into a real time, and only when nothing is missing. */
data class CalendarProposal(
    val title: String?,
    val date: LocalDate?,
    val start: LocalTime?,
    val end: LocalTime?,
    val zone: ZoneId?,
    val location: String?,
    val meetingUrl: String?,
    val organizer: String?,
    val description: String?,
    val dateText: String?,
    val ambiguous: Boolean,
    val ambiguityReason: String?,
) {
    data class Resolved(val start: ZonedDateTime, val end: ZonedDateTime, val timezoneAssumed: Boolean)

    /** Null when the event can't be created without the user confirming the date/time. */
    fun resolve(deviceZone: ZoneId, defaultDurationMinutes: Long = 60): Resolved? {
        if (ambiguous || date == null || start == null) return null
        val zone = zone ?: deviceZone
        val s = ZonedDateTime.of(LocalDateTime.of(date, start), zone)
        val e = if (end != null && end.isAfter(start)) ZonedDateTime.of(LocalDateTime.of(date, end), zone)
        else s.plusMinutes(defaultDurationMinutes)
        return Resolved(s, e, timezoneAssumed = this.zone == null)
    }

    /** Stable identity of this event, used to never create it twice. */
    fun eventHash(emailId: String): String =
        listOf(emailId, title.orEmpty().trim().lowercase(), date, start, end, zone?.id).joinToString("|").hashCode()
            .toUInt().toString(16)

    companion object {
        fun parse(
            title: String?, date: String?, start: String?, end: String?, timezone: String?, location: String?,
            meetingUrl: String?, organizer: String?, description: String?, dateText: String?, ambiguous: Boolean,
            ambiguityReason: String?,
        ): CalendarProposal {
            val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val s = start?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val e = end?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val z = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            // Defence in depth: the phone also refuses to treat missing data as known.
            val reason = ambiguityReason ?: when {
                d == null -> "No exact date in the email"
                s == null -> "No start time in the email"
                else -> null
            }
            return CalendarProposal(title, d, s, e, z, location, meetingUrl?.takeIf { it.startsWith("https://") },
                organizer, description, dateText, ambiguous || d == null || s == null, reason)
        }
    }
}

enum class OpportunityStatus(val label: String) {
    SELECTED("Selected"), INTERVIEW("Interview"), OFFER("Job offer"), APPLIED("Application"),
    RECRUITER_CONTACT("Recruiter"), REJECTED("Not selected"), NONE("Opportunity");

    companion object {
        fun fromApi(v: String?): OpportunityStatus = entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: NONE
    }
}

/** Celebration size is part of the design: the biggest blast is reserved for the biggest news. */
enum class CelebrationType(val tier: Int, val headline: String) {
    SELECTION(3, "You're Selected!"),
    OFFER(3, "Job Offer Received"),
    INTERVIEW(2, "Interview Invitation"),
    APPLICATION(1, "Application Accepted"),
    CONGRATULATIONS(2, "Congratulations!");

    companion object {
        fun fromApi(v: String?): CelebrationType? = entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
    }
}

enum class MailActionType(val label: String, val external: Boolean) {
    SEND_DOCUMENT_REPLY("Send document", true),
    SEND_REPLY("Send reply", true),
    CREATE_CALENDAR_EVENT("Add to calendar", true),
    UNSUBSCRIBE_EMAIL("Unsubscribe by email", true),
    SEND_FOLLOW_UP("Send follow-up", true),
    REVIEW_EMAIL("Review email", false),
}

enum class AutomationRuleType(val label: String, val description: String) {
    AUTO_ADD_INTERVIEWS(
        "Automatically add high-confidence interview events",
        "Only when the email gives an exact date and time. Everything else still asks you.",
    ),
}
