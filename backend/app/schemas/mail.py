"""Typed, non-executing AI proposals for Gmail messages.

The server only understands, extracts and drafts. Android owns file access, calendar creation
and Gmail sending, and only after the user explicitly approves (see the app's MailPolicyEngine).
"""
from typing import Literal

from pydantic import BaseModel, Field

MailCategory = Literal[
    "important", "reply_needed", "document_request", "interview_invitation", "opportunity",
    "job_selection", "job_offer", "application", "recruitment", "calendar", "congratulations",
    "promotion", "notification", "no_reply", "suspicious", "archive", "general",
]
DocumentType = Literal[
    "resume", "work_experience", "id_proof", "address_proof", "certificate", "transcript", "payslip",
    "offer_letter", "relieving_letter", "bank_statement", "photo", "portfolio", "other",
]
OpportunityStatus = Literal["selected", "interview", "offer", "applied", "recruiter_contact", "rejected", "none"]
Celebration = Literal["none", "selection", "interview", "offer", "application", "congratulations"]
RecommendedAction = Literal["review", "reply", "select_document", "add_calendar", "archive", "unsubscribe", "none"]


class MailSignals(BaseModel):
    """Header and structure signals extracted on the phone. Used for no-reply, promotion and
    suspicious decisions so they never rest on the sender name alone."""

    reply_to: str | None = Field(default=None, max_length=320)
    list_unsubscribe: bool = False
    auto_submitted: bool = False
    precedence_bulk: bool = False
    gmail_category: str | None = Field(default=None, max_length=40)  # CATEGORY_PROMOTIONS etc.
    link_domains: list[str] = Field(default_factory=list, max_length=30)
    attachment_names: list[str] = Field(default_factory=list, max_length=20)
    # What this sender usually sends, from mail the phone already understood (counts only)
    sender_history_total: int = Field(default=0, ge=0, le=100000)
    sender_history_bulk: int = Field(default=0, ge=0, le=100000)


class MailIntelligenceRequest(BaseModel):
    email_id: str = Field(max_length=300)
    from_address: str = Field(max_length=320)
    from_name: str | None = Field(default=None, max_length=200)
    reply_to: str | None = Field(default=None, max_length=320)
    subject: str = Field(default="", max_length=300)
    body: str = Field(max_length=12000)
    thread_context: list[str] = Field(default_factory=list, max_length=6)
    received_at: str | None = Field(default=None, max_length=40)  # ISO date, lets "September 30" get a year
    user_timezone: str | None = Field(default=None, max_length=60)
    signals: MailSignals = MailSignals()


class DocumentRequest(BaseModel):
    document_type: DocumentType = "other"
    description: str | None = Field(default=None, max_length=200)  # as the sender phrased it
    requested_format: str | None = Field(default=None, max_length=30)
    recipient: str | None = Field(default=None, max_length=320)
    deadline: str | None = Field(default=None, max_length=120)
    context: str | None = Field(default=None, max_length=300)
    requires_attachment: bool = False


class CalendarExtraction(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    date: str | None = Field(default=None, max_length=10)        # YYYY-MM-DD, only if stated
    start_time: str | None = Field(default=None, max_length=5)   # HH:MM 24h
    end_time: str | None = Field(default=None, max_length=5)
    timezone: str | None = Field(default=None, max_length=60)    # IANA, only if stated or implied by a named zone
    location: str | None = Field(default=None, max_length=300)
    meeting_url: str | None = Field(default=None, max_length=1000)
    organizer: str | None = Field(default=None, max_length=200)
    description: str | None = Field(default=None, max_length=500)
    date_text: str | None = Field(default=None, max_length=160)  # the wording in the email
    ambiguous: bool = False
    ambiguity_reason: str | None = Field(default=None, max_length=200)


class Opportunity(BaseModel):
    company: str | None = Field(default=None, max_length=160)
    role: str | None = Field(default=None, max_length=160)
    status: OpportunityStatus = "none"


ApplicationStage = Literal["applied", "assessment", "interview", "selected", "offer", "rejected", "none"]


class ApplicationInfo(BaseModel):
    """Job-application details for the tracker. Only what the email states."""

    stage: ApplicationStage = "none"
    reference_id: str | None = Field(default=None, max_length=80)     # requisition / application number
    ats_platform: str | None = Field(default=None, max_length=40)     # workday, greenhouse, naukri… (set by rules)
    interview_round: int | None = Field(default=None, ge=1, le=10)
    deadline: str | None = Field(default=None, max_length=10)         # YYYY-MM-DD, only if stated
    next_step: str | None = Field(default=None, max_length=200)


class MailIntelligence(BaseModel):
    primary_category: MailCategory = "general"
    labels: list[MailCategory] = Field(default_factory=list, max_length=6)
    priority: Literal["low", "normal", "high"] = "normal"
    summary: str = Field(default="", max_length=400)
    requires_reply: bool = False
    requires_user_action: bool = False
    requires_attachment: bool = False
    contains_event: bool = False
    requires_calendar_action: bool = False
    document_request: DocumentRequest | None = None
    calendar: CalendarExtraction | None = None
    opportunity: Opportunity | None = None
    application: ApplicationInfo | None = None
    language: str | None = Field(default=None, max_length=10)  # ISO code of the email's language, e.g. en, te, hi
    suspicious_reasons: list[str] = Field(default_factory=list, max_length=5)
    recommended_action: RecommendedAction = "review"
    celebration: Celebration = "none"
    confidence: Literal["low", "medium", "high"] = "medium"


class MailIntelligenceResponse(BaseModel):
    intelligence: MailIntelligence
    prompt_version: str


class StyleHints(BaseModel):
    """The user's own writing style, learned on the phone from their sent mail. Samples are masked excerpts."""

    greeting: str | None = Field(default=None, max_length=40)
    sign_off: str | None = Field(default=None, max_length=80)
    average_words: int = Field(default=80, ge=10, le=400)
    formality: Literal["casual", "neutral", "formal"] = "neutral"
    uses_emoji: bool = False
    samples: list[str] = Field(default_factory=list, max_length=3)


class ReplyDraftRequest(BaseModel):
    email_id: str = Field(max_length=300)
    from_address: str = Field(max_length=320)
    from_name: str | None = Field(default=None, max_length=200)
    subject: str = Field(default="", max_length=300)
    body: str = Field(max_length=12000)
    thread_context: list[str] = Field(default_factory=list, max_length=6)
    user_name: str = Field(default="", max_length=80)
    purpose: Literal["reply", "send_document", "confirm_interview", "unsubscribe", "follow_up"] = "reply"
    # Reply language: "auto" follows the email's language and script; otherwise an ISO code (en, te, hi, ta, kn, ml…)
    language: str = Field(default="auto", max_length=10)
    tone: Literal["professional", "friendly", "formal", "brief"] = "professional"
    attachment_names: list[str] = Field(default_factory=list, max_length=10)
    instructions: str | None = Field(default=None, max_length=300)  # typed by the user, e.g. "say I'll join"
    style: StyleHints | None = None


class ReplyDraft(BaseModel):
    subject: str = Field(max_length=300)
    body: str = Field(max_length=4000)


class ReplyDraftResponse(BaseModel):
    draft: ReplyDraft
    prompt_version: str
