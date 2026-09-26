"""Core AI workflows. Gemini analyzes and writes; deterministic code enforces safety."""
import logging
import re

from app.core.config import Settings
from app.core.logging import log_event
from app.schemas.calls import MissedCallRequest, RecapRequest
from app.schemas.common import Channel, Relationship
from app.schemas.messages import Analysis, ProcessMessageRequest, RestyleRequest
from app.schemas.mail import MailIntelligence, MailIntelligenceRequest, ReplyDraft, ReplyDraftRequest
from app.services import mail_rules, safety
from app.services.gemini_client import LLMClient, LLMError
from app.services.prompts import render
from app.services.transcript import format_situation, format_transcript, language_name

logger = logging.getLogger(__name__)

ROMAN_EXAMPLES = {
    "te": "Bike meeda unna, taruvatha call chesta",
    "hi": "Bike chala raha hoon, baad mein call karta hoon",
    "ta": "Bike la poitu iruken, apram call panren",
    "kn": "Bike alli idini, aamele call maadtini",
}
NATIVE_EXAMPLES = {"te": "బైక్ మీద ఉన్నా, తర్వాత కాల్ చేస్తా"}


class AssistantService:
    def __init__(self, llm: LLMClient, settings: Settings) -> None:
        self.llm = llm
        self.s = settings

    # ---------- model fallback ----------
    def _chain(self, primary: str) -> list[str]:
        """Primary model first, then the fallback, then the other configured model (no duplicates)."""
        chain = [primary, self.s.gemini_model_fallback, self.s.gemini_model_fast, self.s.gemini_model_writer]
        return [m for m in dict.fromkeys(chain) if m]

    async def _text(self, primary: str, prompt: str) -> str:
        last: LLMError | None = None
        for model in self._chain(primary):
            try:
                return await self.llm.generate_text(model=model, prompt=prompt)
            except LLMError as exc:
                last = exc
                log_event(logger, "model_fallback", failed=model)
        raise last or LLMError("No model configured")

    async def _json(self, primary: str, prompt: str, schema):
        last: LLMError | None = None
        for model in self._chain(primary):
            try:
                return await self.llm.generate_json(model=model, prompt=prompt, schema=schema)
            except LLMError as exc:
                last = exc
                log_event(logger, "model_fallback", failed=model)
        raise last or LLMError("No model configured")

    # ---------- shared pieces ----------
    def _common(self, *, channel: Channel, name: str, relationship: Relationship, user, subject, messages) -> dict:
        return {
            "channel": channel.label,
            "contact_name": name,
            "relationship": relationship.value,
            "situation": format_situation(user),
            "subject_line": f"Email subject: {subject}" if subject else "",
            "transcript": format_transcript(messages, name, self.s.max_messages_in_context),
            "user_name": user.name or "[Your name]",
            "gender": user.gender,
        }

    def _rules(self, v: dict) -> dict:
        return {
            "language_rules": render("_language_rules", contact_name=v["contact_name"], gender=v["gender"]),
            "tone_rules": render("_tone_rules"),
            "security_rules": render("_security_rules"),
        }

    @staticmethod
    def _format_rules(channel: Channel, user_name: str) -> str:
        if channel == Channel.gmail:
            return (f"Email body of 50-120 words with a greeting and the sign-off "
                    f"'Best regards,\\n{user_name}'. Plain text.")
        return "Under 40 words. Use emojis only if they use them."

    @staticmethod
    def _group_rules(relationship: Relationship, name: str) -> str:
        if relationship != Relationship.group:
            return ""
        return (f"This is the group chat \"{name}\". Each line shows who wrote it. Someone mentioned the user: "
                "reply only to what was asked of the user, address that person by name, keep it to one short line, "
                "and never reply on behalf of anyone else in the group.")

    # ---------- incoming message ----------
    async def process_message(self, req: ProcessMessageRequest) -> tuple[Analysis, str]:
        v = self._common(channel=req.channel, name=req.contact.name, relationship=req.contact.relationship,
                         user=req.user, subject=req.subject, messages=req.messages)
        analysis = await self._json(self.s.gemini_model_fast, render("analyze", **v), Analysis)

        last_text = next((m.text for m in reversed(req.messages) if m.sender == "them"), "")
        if safety.detect_crisis(last_text):
            analysis.crisis = True

        if analysis.crisis or not analysis.needs_reply or analysis.conversation_state == "ended":
            log_event(logger, "process_no_reply", crisis=analysis.crisis, state=analysis.conversation_state)
            return analysis, ""

        reply = await self._write_reply(req, v, analysis.style, analysis.conversation_state,
                                        analysis.mentions_money or analysis.asks_commitment)

        convo_text = "\n".join(m.text for m in req.messages)
        risky = analysis.mentions_money or analysis.asks_commitment
        if req.contact.relationship.is_professional and risky and safety.violates_money_rule(reply, convo_text):
            log_event(logger, "money_rule_fallback")
            reply = safety.gendered_fallback(analysis.language, req.user.gender)
        return analysis, reply

    async def _write_reply(self, req, v: dict, style: str, state: str, risky: bool) -> str:
        state_instruction = {
            "wrapping_up": "The conversation is closing: write a brief, warm closing line with NO question.",
            "ongoing": "Keep the conversation natural. Don't add questions just to prolong it.",
        }.get(state, "")
        money_instruction = ""
        if risky and req.contact.relationship.is_professional:
            money_instruction = ("Money or a commitment came up: acknowledge it politely but DO NOT agree to any "
                                 "amount, date or terms. Say you will check and confirm shortly.")
        prompt = render("reply", **v, **self._rules(v), style=style, conversation_state=state,
                        group_rules=self._group_rules(req.contact.relationship, req.contact.name),
                        state_instruction=state_instruction, money_instruction=money_instruction,
                        format_rules=self._format_rules(req.channel, v["user_name"]))
        text = await self._text(self.s.gemini_model_writer, prompt)
        return safety.clean_reply(text, req.channel.value)

    async def restyle(self, req: RestyleRequest) -> str:
        v = self._common(channel=req.channel, name=req.contact.name, relationship=req.contact.relationship,
                         user=req.user, subject=req.subject, messages=req.messages)
        return await self._write_reply(req, v, req.style, "ongoing", False)

    # ---------- email intelligence ----------
    async def understand_mail(self, req: MailIntelligenceRequest) -> MailIntelligence:
        """Classify and extract. Deterministic rules then make the result safe (never more permissive)."""
        sig = req.signals
        signals = ", ".join(filter(None, [
            "has List-Unsubscribe" if sig.list_unsubscribe else "",
            "Auto-Submitted" if sig.auto_submitted else "",
            "bulk precedence" if sig.precedence_bulk else "",
            f"Gmail tab {sig.gmail_category}" if sig.gmail_category else "",
            f"links to {', '.join(sig.link_domains[:8])}" if sig.link_domains else "",
            f"attachments: {', '.join(sig.attachment_names[:5])}" if sig.attachment_names else "",
        ])) or "none"
        prompt = render(
            "mail_intelligence",
            from_address=req.from_address, from_name=req.from_name or "", reply_to=sig.reply_to or req.reply_to or "[none]",
            subject=_fence(req.subject), body=_fence(req.body), signals=signals,
            thread_context=_fence("\n---\n".join(req.thread_context[-6:])) or "[none]",
            received_at=req.received_at or "unknown", user_timezone=req.user_timezone or "unknown",
        )
        ai = await self._json(self.s.gemini_model_fast, prompt, MailIntelligence)
        result = mail_rules.apply(req, ai)
        log_event(logger, "mail_classified", category=result.primary_category, celebration=result.celebration,
                  action=result.recommended_action, suspicious=bool(result.suspicious_reasons))
        return result

    async def draft_mail_reply(self, req: ReplyDraftRequest) -> ReplyDraft:
        purpose = {
            "reply": "Reply helpfully to what the email asks.",
            "send_document": "Reply that the requested document is attached, briefly and politely.",
            "confirm_interview": "Confirm the user's availability for the interview as scheduled in the email.",
            "unsubscribe": "Ask to be removed from this mailing list. One or two sentences.",
            "follow_up": "Politely follow up on the user's job application or interview: restate interest in the role, "
                         "ask about the status or next steps. Short, warm and professional. Don't sound impatient.",
        }[req.purpose]
        language_instruction = (
            "Write in the same language and script as the email. Formal English emails get English replies; if the email "
            "is in Telugu, Hindi or another Indian language (native script or romanized), reply the same way."
            if req.language == "auto" else
            f"Write the reply in the language with ISO code '{req.language}', in its native script, "
            "keeping names, company names and technical terms as written."
        )
        attachment = (f"The user is attaching: {', '.join(req.attachment_names)}. Mention the attachment naturally."
                      if req.attachment_names else "No file is attached. Never say something is attached.")
        st = req.style
        name = req.user_name or "[Your name]"
        if st:
            lo, hi = max(25, int(st.average_words * 0.6)), min(220, int(st.average_words * 1.4) + 10)
            style_instruction = (
                f"Write like the user: {st.formality} tone"
                + (f', greet with "{st.greeting} <name>,"' if st.greeting else "")
                + (", an emoji is fine if natural" if st.uses_emoji else ", no emoji")
                + "."
            )
            sign_off = (st.sign_off or f"Regards,\n{name}").replace('"', "'")
            samples = "\n---\n".join(_fence(x)[:400] for x in st.samples[:3]) or "[none]"
        else:
            lo, hi, style_instruction, sign_off, samples = 40, 140, "", f"Regards,\n{name}", "[none]"
        prompt = render(
            "mail_reply", user_name=name, style_samples=re.sub(r"</?\s*style_samples\s*>", "", samples, flags=re.I),
            style_instruction=style_instruction, sign_off=sign_off, length_range=f"{lo}-{hi}",
            language_instruction=language_instruction, from_name=req.from_name or "",
            from_address=req.from_address, subject=_fence(req.subject), body=_fence(req.body),
            thread_context=_fence("\n---\n".join(req.thread_context[-6:])) or "[none]",
            purpose_instruction=purpose, tone=req.tone, attachment_instruction=attachment,
            user_instructions=f"The user adds: {req.instructions}" if req.instructions else "",
        )
        draft = await self._json(self.s.gemini_model_writer, prompt, ReplyDraft)
        body = safety.clean_reply(draft.body, "gmail")
        if not req.attachment_names:
            # A draft must never claim an attachment that isn't there.
            body = re.sub(r"(?i)\b(please find|i have|i've|i am|i'm)?\s*attached\b[^.\n]*[.\n]?", "", body).strip()
        subject = draft.subject.strip() or req.subject
        if req.subject and not subject.lower().startswith("re:"):
            subject = f"Re: {req.subject}"
        return ReplyDraft(subject=subject[:300], body=body or "Hello,\n\n[Write your reply]\n\nRegards,\n" + (req.user_name or ""))

    # ---------- missed call ----------
    async def missed_call_reply(self, req: MissedCallRequest) -> str:
        lang, script = req.language.lang, req.language.script
        if req.language_source == "conversation" and req.history:
            language_instruction = (f"Match EXACTLY the language and script of {req.contact.name}'s latest message "
                                    "in the conversation above.")
        elif lang == "en":
            language_instruction = "Write in English."
        elif script == "native":
            ex = NATIVE_EXAMPLES.get(lang)
            language_instruction = (f"Write in {language_name(lang)} using {language_name(lang)} script"
                                    + (f' (e.g. "{ex}")' if ex else "") + ".")
        else:
            language_instruction = (f"Write in {language_name(lang)} using English letters, the way people actually "
                                    f'text (e.g. "{ROMAN_EXAMPLES.get(lang, "")}"). Mix in common English words '
                                    "like call, bike, meeting naturally.")
        repeat = ("They called more than once: acknowledge it and ask if it is urgent."
                  if req.missed_calls_last_10_min > 1 else "")
        prompt = render(
            "missed_call",
            contact_name=req.contact.name,
            relationship=req.contact.relationship.value,
            situation=format_situation(req.user),
            local_time=req.local_time or "unknown",
            call_count=req.missed_calls_last_10_min,
            transcript=format_transcript(req.history, req.contact.name, 10),
            language_instruction=language_instruction,
            gender=req.user.gender,
            repeat_instruction=repeat,
            tone_rules=render("_tone_rules"),
            security_rules=render("_security_rules"),
        )
        text = await self._text(self.s.gemini_model_writer, prompt)
        return safety.clean_reply(text, "sms")

    # ---------- recap ----------
    async def recap(self, req: RecapRequest) -> str:
        prompt = render("recap", contact_name=req.contact.name, relationship=req.contact.relationship.value,
                        transcript=format_transcript(req.messages, req.contact.name, 60))
        return (await self._text(self.s.gemini_model_fast, prompt)).strip()


def _fence(text: str) -> str:
    """Untrusted mail text can't close the <email>/<thread> blocks the prompts rely on."""
    return re.sub(r"</?\s*(email|thread)\s*>", "", text or "", flags=re.I)
