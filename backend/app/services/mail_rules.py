"""Deterministic rules applied on top of the AI's mail understanding.

The model proposes; these rules make the result safe and consistent. They never add
information the email doesn't contain, and they can only make decisions *more* cautious.
"""
import re
from datetime import date
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.schemas.mail import ApplicationInfo, CalendarExtraction, MailIntelligence, MailIntelligenceRequest, Opportunity

AUTOMATED_SENDER = re.compile(r"(^|[._-])(no-?reply|do-?not-?reply|donotreply|notifications?|mailer-daemon|bounce)[._@-]", re.I)
EXPLICIT_REPLY_REQUEST = re.compile(
    r"\b(please (reply|respond|confirm|let us know|get back)|reply to (this|confirm)|kindly (reply|confirm|revert|share|send)"
    r"|confirm your (availability|attendance|participation|interest)|let us know (if|whether|your)|revert back"
    r"|awaiting your (reply|response|confirmation))\b"
    # Hindi / Telugu (native script and common romanized forms)
    r"|कृपया (जवाब|उत्तर|पुष्टि)|जवाब दें|पुष्टि करें|बताएं|"
    r"దయచేసి (సమాధానం|ధృవీకరించండి|తెలియజేయండి)|తెలియజేయండి|సమాధానం ఇవ్వండి|"
    r"\b(jawab (dein|do)|confirm karein|batayein|reply cheyyandi|teliyajeyandi)\b", re.I)
CREDENTIAL_REQUEST = re.compile(
    r"\b(verify your (account|identity)|confirm your password|enter your (password|pin|otp)|share (your )?(otp|password|pin|cvv)"
    r"|bank (account )?details|update your (kyc|billing|payment)|account (will be )?(suspended|locked|closed))\b"
    r"|ओटीपी|पासवर्ड (बताएं|भेजें|दर्ज)|केवाईसी (अपडेट|पूरा)|खाता (बंद|ब्लॉक)|"
    r"ఓటీపీ|పాస్‌?వర్డ్|కేవైసీ|ఖాతా (బ్లాక్|నిలిపివేయ)|"
    r"\b(otp (batao|bhejo|share karo|cheppandi)|kyc update (karo|cheyyandi))\b", re.I)
URGENCY = re.compile(r"\b(immediately|within 24 hours|urgent(ly)?|final notice|act now|last warning)\b|तुरंत|तत्काल|వెంటనే|అత్యవసరం", re.I)
RELATIVE_DATE = re.compile(
    r"\b(today|tonight|tomorrow|day after|next|this (mon|tue|wed|thu|fri|sat|sun|week|coming)|coming (mon|tue|wed|thu|fri|sat|sun)"
    r"|in \d+ days?|asap|soon|early next|later this)\w*"
    # Hindi: aaj, kal, parson, agle/is (hafte|somvar…); Telugu: ఈరోజు, రేపు, ఎల్లుండి, వచ్చే…, ఈ వారం; Tamil: இன்று, நாளை, அடுத்த
    r"|आज|कल|परसों|अगले|अगली|इस (हफ्ते|सप्ताह|सोमवार|मंगलवार|बुधवार|गुरुवार|शुक्रवार|शनिवार|रविवार)"
    r"|ఈరోజు|ఈ రోజు|రేపు|ఎల్లుండి|వచ్చే|ఈ వారం|இன்று|நாளை|அடுத்த"
    r"|\b(aaj|kal|parson|agle|agli|repu|ellundi|vachhe|vache|naalai)\b", re.I)
DANGEROUS_EXT = (".exe", ".scr", ".bat", ".cmd", ".js", ".vbs", ".jar", ".apk", ".msi", ".iso", ".lnk", ".ps1", ".hta")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
FREE_MAIL = {"gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "live.com", "icloud.com", "proton.me", "rediffmail.com"}

# Job platforms (applicant tracking systems and job boards) by sender domain
ATS_DOMAINS = {
    "greenhouse.io": "greenhouse", "greenhouse-mail.io": "greenhouse", "lever.co": "lever", "myworkday.com": "workday",
    "myworkdayjobs.com": "workday", "workday.com": "workday", "icims.com": "icims", "smartrecruiters.com": "smartrecruiters",
    "ashbyhq.com": "ashby", "jobvite.com": "jobvite", "taleo.net": "taleo", "successfactors.com": "successfactors",
    "naukri.com": "naukri", "linkedin.com": "linkedin", "indeed.com": "indeed", "instahyre.com": "instahyre",
    "foundit.in": "foundit", "internshala.com": "internshala", "wellfound.com": "wellfound", "hirist.tech": "hirist",
    "iimjobs.com": "iimjobs", "cutshort.io": "cutshort", "unstop.com": "unstop", "hackerrank.com": "hackerrank",
    "hackerearth.com": "hackerearth", "codility.com": "codility", "mettl.com": "mettl",
}
STAGE_FOR_CATEGORY = {
    "application": "applied", "interview_invitation": "interview", "job_selection": "selected", "job_offer": "offer",
}

CELEBRATION_FOR = {
    "job_selection": "selection", "job_offer": "offer", "interview_invitation": "interview",
}


def _domain(address: str | None) -> str | None:
    if not address or "@" not in address:
        return None
    return address.rsplit("@", 1)[1].strip(" >").lower()


def _registrable(domain: str) -> str:
    parts = domain.split(".")
    if len(parts) >= 3 and parts[-2] in {"co", "com", "org", "net", "ac", "gov"} and len(parts[-1]) == 2:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def is_automated_sender(req: MailIntelligenceRequest) -> bool:
    s = req.signals
    return bool(AUTOMATED_SENDER.search(req.from_address)) or s.auto_submitted or s.precedence_bulk


def suspicious_signals(req: MailIntelligenceRequest) -> list[str]:
    """Concrete, explainable reasons only. Never 'looks suspicious'."""
    reasons: list[str] = []
    text = f"{req.subject}\n{req.body}"
    sender = _domain(req.from_address)
    reply_to = _domain(req.signals.reply_to or req.reply_to)
    if sender and reply_to and _registrable(sender) != _registrable(reply_to):
        reasons.append(f"Replies go to a different domain ({reply_to}) than the sender ({sender})")
    if CREDENTIAL_REQUEST.search(text):
        reasons.append("Asks for passwords, codes or account details" + (" urgently" if URGENCY.search(text) else ""))
    for name in req.signals.attachment_names:
        if name.lower().endswith(DANGEROUS_EXT):
            reasons.append(f"Has an executable-type attachment ({name})")
            break
    if sender and sender not in FREE_MAIL and req.signals.link_domains:
        org = _registrable(sender)
        foreign = [d for d in req.signals.link_domains if _registrable(d.lower()) != org]
        # Many legit mails link elsewhere; only flag when a credential request goes to another site.
        if foreign and CREDENTIAL_REQUEST.search(text):
            reasons.append(f"Login links point to {foreign[0]}, not {org}")
    return reasons[:5]


def _validate_calendar(cal: CalendarExtraction, received: date | None) -> CalendarExtraction:
    c = cal.model_copy()
    reasons: list[str] = []
    if c.date and not DATE_RE.match(c.date):
        c.date = None
    if c.start_time and not TIME_RE.match(c.start_time):
        c.start_time = None
    if c.end_time and not TIME_RE.match(c.end_time):
        c.end_time = None
    if c.date and received:
        try:
            if date.fromisoformat(c.date) < received:
                reasons.append("The date is before the email was sent")
        except ValueError:
            c.date = None
    if c.timezone:
        try:
            ZoneInfo(c.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            c.timezone = None
    if c.date_text and RELATIVE_DATE.search(c.date_text):
        reasons.append(f'The email says "{c.date_text}", which needs an exact date')
        c.date = None  # a resolved "next Friday" would be the model's guess; the user picks the date
    if not c.date:
        reasons.append("No exact date in the email")
    elif not c.start_time:
        reasons.append("No start time in the email")
    if c.start_time and c.end_time and c.end_time <= c.start_time:
        c.end_time = None
    if c.meeting_url and not c.meeting_url.lower().startswith("https://"):
        c.meeting_url = None
    if reasons:
        c.ambiguous = True
        c.ambiguity_reason = reasons[0]
    return c


def apply(req: MailIntelligenceRequest, ai: MailIntelligence) -> MailIntelligence:
    m = ai.model_copy(deep=True)
    text = f"{req.subject}\n{req.body}"
    automated = is_automated_sender(req)
    explicit_request = bool(EXPLICIT_REPLY_REQUEST.search(text))

    # ---- no-reply / notification: several signals, never the sender name alone ----
    if automated and not explicit_request:
        m.requires_reply = False
        if m.primary_category == "reply_needed":
            m.primary_category = "notification"
    if automated and explicit_request and req.signals.reply_to and not AUTOMATED_SENDER.search(req.signals.reply_to):
        m.requires_reply = True  # e.g. "Please reply to confirm" with a human Reply-To

    # ---- promotions ----
    marketing = req.signals.gmail_category == "CATEGORY_PROMOTIONS" or (req.signals.list_unsubscribe and not explicit_request)
    s = req.signals
    bulk_sender = s.sender_history_total >= 3 and s.sender_history_bulk * 4 >= s.sender_history_total * 3
    career = m.primary_category in {"job_selection", "job_offer", "interview_invitation", "application", "recruitment", "document_request"}
    if (marketing or (bulk_sender and not explicit_request)) and m.primary_category in {"general", "important", "congratulations"} and not career:
        m.primary_category = "promotion"
    if m.primary_category == "promotion":
        m.requires_reply = False
        m.priority = "low"
        m.recommended_action = "unsubscribe" if req.signals.list_unsubscribe else "archive"

    # ---- suspicious: concrete reasons only ----
    concrete = suspicious_signals(req)
    if concrete:
        m.suspicious_reasons = concrete
        if "suspicious" not in m.labels and m.primary_category != "suspicious":
            m.labels.append("suspicious")
    elif m.primary_category == "suspicious" and not m.suspicious_reasons:
        m.primary_category = "general"  # the model gave no reason: don't accuse the sender
    m.suspicious_reasons = [r[:200] for r in m.suspicious_reasons][:5]

    # ---- documents ----
    if m.document_request:
        m.document_request.recipient = m.document_request.recipient or req.signals.reply_to or req.from_address
        if m.document_request.requires_attachment:
            m.requires_attachment = True
            m.requires_reply = True
            m.requires_user_action = True
            m.recommended_action = "select_document"

    # ---- calendar: never invent dates ----
    received = None
    if req.received_at:
        try:
            received = date.fromisoformat(req.received_at[:10])
        except ValueError:
            received = None
    if m.calendar:
        m.calendar = _validate_calendar(m.calendar, received)
        m.contains_event = bool(m.calendar.date or m.calendar.date_text)
        m.requires_calendar_action = m.contains_event
        if m.contains_event and m.recommended_action in {"review", "reply", "none"} and not m.requires_attachment:
            m.recommended_action = "review" if m.calendar.ambiguous else "add_calendar"

    # ---- opportunity consistency ----
    status_for = {"job_selection": "selected", "job_offer": "offer", "interview_invitation": "interview", "application": "applied"}
    if m.primary_category in status_for:
        m.opportunity = m.opportunity or Opportunity()
        if m.opportunity.status == "none":
            m.opportunity.status = status_for[m.primary_category]
    if m.primary_category == "interview_invitation":
        m.priority = "high"

    # ---- job application (tracker) ----
    sender_domain = _domain(req.from_address)
    ats = next((name for d, name in ATS_DOMAINS.items() if sender_domain and (sender_domain == d or sender_domain.endswith("." + d))), None)
    career_mail = m.primary_category in STAGE_FOR_CATEGORY or m.primary_category in {"recruitment", "opportunity"} or \
        (m.opportunity is not None and m.opportunity.status != "none")
    if career_mail or m.application:
        app = m.application or ApplicationInfo()
        if app.stage == "none":
            app.stage = STAGE_FOR_CATEGORY.get(m.primary_category, "none")
        if m.opportunity and m.opportunity.status == "rejected":
            app.stage = "rejected"
        if app.stage == "rejected" and m.opportunity:
            m.opportunity.status = "rejected"
        app.ats_platform = ats or app.ats_platform
        if app.deadline:
            try:
                d = date.fromisoformat(app.deadline)
                if received and d < received:
                    app.deadline = None
            except ValueError:
                app.deadline = None
        m.application = app if (app.stage != "none" or app.reference_id or ats) else None

    # ---- celebration: clear, positive, trustworthy only ----
    wanted = CELEBRATION_FOR.get(m.primary_category)
    if m.primary_category == "application" and m.opportunity and m.opportunity.status == "applied" and m.celebration == "application":
        wanted = "application"
    if m.primary_category == "congratulations" and m.celebration == "congratulations":
        wanted = "congratulations"
    if m.application and m.application.stage == "rejected":
        wanted = None  # never celebrate a rejection
    if m.suspicious_reasons or m.primary_category == "promotion" or m.confidence == "low" or (automated and wanted == "congratulations"):
        wanted = None
    m.celebration = wanted or "none"

    # ---- labels: unique, never duplicate the primary ----
    m.labels = [l for l in dict.fromkeys(m.labels) if l != m.primary_category][:6]
    m.requires_user_action = m.requires_user_action or m.requires_attachment or m.requires_calendar_action or bool(m.suspicious_reasons)
    return m

