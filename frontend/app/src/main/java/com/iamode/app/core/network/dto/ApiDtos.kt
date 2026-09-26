package com.iamode.app.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(val sender: String, val text: String, val kind: String = "text")

@Serializable
data class ContactDto(val name: String, val relationship: String)

@Serializable
data class SituationDto(val status: String, val reason: String = "")

@Serializable
data class UserContextDto(val name: String, val gender: String, val situation: SituationDto)

@Serializable
data class LanguageHintDto(val lang: String, val script: String)

@Serializable
data class ProcessMessageRequest(
    val channel: String,
    val contact: ContactDto,
    val user: UserContextDto,
    val subject: String? = null,
    val messages: List<ChatMessageDto>,
    @SerialName("money_mode") val moneyMode: String,
)

@Serializable
data class AnalysisDto(
    val language: String,
    val script: String,
    val tone: String,
    val summary: String,
    @SerialName("mentions_money") val mentionsMoney: Boolean,
    @SerialName("asks_commitment") val asksCommitment: Boolean,
    val crisis: Boolean,
    @SerialName("needs_reply") val needsReply: Boolean,
    @SerialName("conversation_state") val conversationState: String,
    val style: String,
)

@Serializable
data class ProcessMessageResponse(
    val analysis: AnalysisDto,
    val reply: String,
    @SerialName("prompt_version") val promptVersion: String,
)

@Serializable
data class RestyleRequest(
    val channel: String,
    val contact: ContactDto,
    val user: UserContextDto,
    val subject: String? = null,
    val messages: List<ChatMessageDto>,
    val style: String,
)

@Serializable
data class ReplyResponse(val reply: String, @SerialName("prompt_version") val promptVersion: String)

@Serializable
data class MissedCallRequest(
    val contact: ContactDto,
    val user: UserContextDto,
    val history: List<ChatMessageDto>,
    @SerialName("missed_calls_last_10_min") val missedCallsLast10Min: Int,
    val language: LanguageHintDto,
    @SerialName("language_source") val languageSource: String,
    @SerialName("local_time") val localTime: String,
)

@Serializable
data class RecapRequest(val contact: ContactDto, val messages: List<ChatMessageDto>)

@Serializable
data class RecapResponse(val recap: String, @SerialName("prompt_version") val promptVersion: String)

@Serializable
data class HealthResponse(val status: String, @SerialName("prompt_version") val promptVersion: String? = null)

@Serializable
data class AuthCheckResponse(val ok: Boolean, @SerialName("auth_mode") val authMode: String)

@Serializable
data class ModelCheck(val model: String, val ok: Boolean, val ms: Int? = null, val error: String? = null)

@Serializable
data class AiCheckResponse(val ok: Boolean, val models: List<ModelCheck>)

@Serializable data class MailSignalsDto(
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("list_unsubscribe") val listUnsubscribe: Boolean = false,
    @SerialName("auto_submitted") val autoSubmitted: Boolean = false,
    @SerialName("precedence_bulk") val precedenceBulk: Boolean = false,
    @SerialName("gmail_category") val gmailCategory: String? = null,
    @SerialName("link_domains") val linkDomains: List<String> = emptyList(),
    @SerialName("attachment_names") val attachmentNames: List<String> = emptyList(),
    @SerialName("sender_history_total") val senderHistoryTotal: Int = 0,
    @SerialName("sender_history_bulk") val senderHistoryBulk: Int = 0,
)
@Serializable data class MailIntelligenceRequestDto(
    @SerialName("email_id") val emailId: String,
    @SerialName("from_address") val fromAddress: String,
    @SerialName("from_name") val fromName: String? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    val subject: String = "",
    val body: String,
    @SerialName("thread_context") val threadContext: List<String> = emptyList(),
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("user_timezone") val userTimezone: String? = null,
    val signals: MailSignalsDto = MailSignalsDto(),
)
@Serializable data class DocumentRequestDto(
    @SerialName("document_type") val documentType: String = "other",
    val description: String? = null,
    @SerialName("requested_format") val requestedFormat: String? = null,
    val recipient: String? = null,
    val deadline: String? = null,
    val context: String? = null,
    @SerialName("requires_attachment") val requiresAttachment: Boolean = false,
)
@Serializable data class CalendarExtractionDto(
    val title: String? = null, val date: String? = null,
    @SerialName("start_time") val startTime: String? = null, @SerialName("end_time") val endTime: String? = null,
    val timezone: String? = null, val location: String? = null, @SerialName("meeting_url") val meetingUrl: String? = null,
    val organizer: String? = null, val description: String? = null, @SerialName("date_text") val dateText: String? = null,
    val ambiguous: Boolean = false, @SerialName("ambiguity_reason") val ambiguityReason: String? = null,
)
@Serializable data class OpportunityDto(val company: String? = null, val role: String? = null, val status: String = "none")
@Serializable data class ApplicationDto(
    val stage: String = "none",
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("ats_platform") val atsPlatform: String? = null,
    @SerialName("interview_round") val interviewRound: Int? = null,
    val deadline: String? = null,
    @SerialName("next_step") val nextStep: String? = null,
)
@Serializable data class MailIntelligenceDto(
    @SerialName("primary_category") val primaryCategory: String,
    val labels: List<String> = emptyList(), val priority: String = "normal", val summary: String = "",
    @SerialName("requires_reply") val requiresReply: Boolean = false,
    @SerialName("requires_user_action") val requiresUserAction: Boolean = false,
    @SerialName("requires_attachment") val requiresAttachment: Boolean = false,
    @SerialName("contains_event") val containsEvent: Boolean = false,
    @SerialName("requires_calendar_action") val requiresCalendarAction: Boolean = false,
    @SerialName("document_request") val documentRequest: DocumentRequestDto? = null,
    val calendar: CalendarExtractionDto? = null,
    val opportunity: OpportunityDto? = null,
    val application: ApplicationDto? = null,
    val language: String? = null,
    @SerialName("suspicious_reasons") val suspiciousReasons: List<String> = emptyList(),
    @SerialName("recommended_action") val recommendedAction: String = "review",
    val celebration: String = "none",
    val confidence: String = "medium",
)
@Serializable data class MailIntelligenceResponseDto(
    val intelligence: MailIntelligenceDto, @SerialName("prompt_version") val promptVersion: String,
)
@Serializable data class StyleHintsDto(
    val greeting: String? = null,
    @SerialName("sign_off") val signOff: String? = null,
    @SerialName("average_words") val averageWords: Int = 80,
    val formality: String = "neutral",
    @SerialName("uses_emoji") val usesEmoji: Boolean = false,
    val samples: List<String> = emptyList(),
)
@Serializable data class ReplyDraftRequestDto(
    @SerialName("email_id") val emailId: String,
    @SerialName("from_address") val fromAddress: String,
    @SerialName("from_name") val fromName: String? = null,
    val subject: String = "",
    val body: String,
    @SerialName("thread_context") val threadContext: List<String> = emptyList(),
    @SerialName("user_name") val userName: String = "",
    val purpose: String = "reply",
    val tone: String = "professional",
    @SerialName("attachment_names") val attachmentNames: List<String> = emptyList(),
    val instructions: String? = null,
    val style: StyleHintsDto? = null,
    val language: String = "auto",
)
@Serializable data class ReplyDraftDto(val subject: String, val body: String)
@Serializable data class ReplyDraftResponseDto(val draft: ReplyDraftDto, @SerialName("prompt_version") val promptVersion: String)

