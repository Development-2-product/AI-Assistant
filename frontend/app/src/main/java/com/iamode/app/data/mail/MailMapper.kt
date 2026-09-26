package com.iamode.app.data.mail

import com.iamode.app.core.database.entity.CalendarExtractionEntity
import com.iamode.app.core.database.entity.MailIntelligenceEntity
import com.iamode.app.core.network.dto.MailIntelligenceDto
import com.iamode.app.domain.mail.CalendarProposal
import com.iamode.app.domain.mail.CelebrationType
import com.iamode.app.domain.mail.DocumentRequest
import com.iamode.app.domain.mail.DocumentType
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.domain.mail.MailClassification
import com.iamode.app.domain.mail.MailPriority
import com.iamode.app.domain.mail.MailTab
import com.iamode.app.domain.mail.MailTabs
import com.iamode.app.domain.mail.OpportunityStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stored in mail_intelligence.documentRequestJson (extracted fields only). */
@Serializable
data class StoredDocumentRequest(
    val type: String, val description: String? = null, val format: String? = null, val recipient: String? = null,
    val deadline: String? = null, val requiresAttachment: Boolean = false,
)

/** Stored in mail_intelligence.opportunityJson. */
@Serializable
data class StoredOpportunity(val company: String? = null, val role: String? = null, val status: String = "none")

/** A card on the Mail screen: metadata + AI understanding, no body. */
data class MailItem(
    val entity: MailIntelligenceEntity,
    val primary: MailCategory,
    val labels: Set<MailCategory>,
    val priority: MailPriority,
    val document: DocumentRequest?,
    val opportunity: StoredOpportunity?,
    val suspiciousReasons: List<String>,
    val tabs: Set<MailTab>,
) {
    val emailId get() = entity.emailId
    val sender get() = entity.fromName?.takeIf { it.isNotBlank() } ?: entity.fromAddress ?: "Unknown sender"
    val celebration get() = CelebrationType.fromApi(entity.celebrationType)
}

object MailMapper {
    fun priority(v: String?) = when (v?.lowercase()) { "high" -> MailPriority.HIGH; "low" -> MailPriority.LOW; else -> MailPriority.NORMAL }

    fun labels(json: Json, raw: String): Set<MailCategory> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())
            .map(MailCategory::fromApi).toSet()

    fun document(json: Json, raw: String?): DocumentRequest? = raw?.let {
        runCatching { json.decodeFromString(StoredDocumentRequest.serializer(), it) }.getOrNull()
    }?.let { DocumentRequest(DocumentType.fromApi(it.type), it.description, it.format, it.recipient, it.deadline, it.requiresAttachment) }

    fun item(json: Json, e: MailIntelligenceEntity): MailItem {
        val primary = MailCategory.fromApi(e.category)
        val labels = labels(json, e.labelsJson)
        val priority = priority(e.priority)
        val suspicious = e.suspiciousJson?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: listOfNotNull(e.suspiciousReason)
        return MailItem(
            entity = e, primary = primary, labels = labels, priority = priority, document = document(json, e.documentRequestJson),
            opportunity = e.opportunityJson?.let { runCatching { json.decodeFromString(StoredOpportunity.serializer(), it) }.getOrNull() },
            suspiciousReasons = suspicious,
            tabs = MailTabs.tabsFor(primary, labels, priority, e.requiresReply, e.archived, e.containsEvent),
        )
    }

    fun classification(emailId: String, dto: MailIntelligenceDto, calendar: CalendarProposal?): MailClassification {
        val doc = dto.documentRequest
        return MailClassification(
            emailId = emailId,
            primary = MailCategory.fromApi(dto.primaryCategory),
            labels = dto.labels.map(MailCategory::fromApi).toSet(),
            priority = priority(dto.priority),
            requiresReply = dto.requiresReply,
            requiresAttachment = dto.requiresAttachment,
            document = doc?.let { DocumentRequest(DocumentType.fromApi(it.documentType), it.description, it.requestedFormat,
                it.recipient, it.deadline, it.requiresAttachment) },
            calendar = calendar,
            opportunityStatus = dto.opportunity?.let { OpportunityStatus.fromApi(it.status) },
            suspiciousReasons = dto.suspiciousReasons,
            celebration = CelebrationType.fromApi(dto.celebration),
            confidence = dto.confidence,
        )
    }

    fun calendar(e: CalendarExtractionEntity): CalendarProposal = CalendarProposal.parse(
        e.title, e.date, e.startTime, e.endTime, e.timezone, e.location, e.meetingUrl, e.organizer, e.description,
        e.dateText, e.ambiguous, e.ambiguityReason,
    )

    fun encodeLabels(json: Json, labels: List<String>) = json.encodeToString(labels)
}
