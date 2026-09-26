"""Deterministic safety checks around model output. The model never has the final say."""
import re

CRISIS_PATTERNS = re.compile(
    r"kill myself|end it all|suicide|don'?t want to live|want to die|hurt myself|"
    r"chachipovali|chanipovali|marna chahta|marna chahti|saaganum|saayabeku",
    re.IGNORECASE,
)
# Amounts like ₹48,000 / Rs 500 / $20 / 5000 rupees
AMOUNT = re.compile(r"(₹|rs\.?|inr|\$)\s?\d[\d,]*|\d[\d,]*\s?(rupees|rs\b|/-)", re.IGNORECASE)
COMMIT_WORDS = re.compile(
    r"\b(confirmed|i confirm|agreed|deal|i will pay|i'll pay|payment (is )?done|transferred|guarantee)\b",
    re.IGNORECASE,
)

MAX_LEN = {"whatsapp": 600, "sms": 320, "gmail": 2500}
SAFE_FALLBACK = {
    "en": "Thanks for your message. I'll check and confirm shortly.",
    "te": "Message chusanu, check chesi konchem sepatlo confirm chestanu.",
    "hi": "Message mil gaya, check karke thodi der mein confirm karta hoon.",
    "ta": "Message paathen, check pannitu konja nerathula confirm panren.",
    "kn": "Message nodide, check maadi swalpa hottalli confirm maadtini.",
}


def detect_crisis(text: str) -> bool:
    return bool(CRISIS_PATTERNS.search(text or ""))


def clean_reply(text: str, channel: str) -> str:
    text = (text or "").strip()
    # Models sometimes wrap output in quotes or add a label
    text = re.sub(r"^(reply|message)\s*:\s*", "", text, flags=re.IGNORECASE)
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'“”":
        text = text[1:-1].strip()
    limit = MAX_LEN.get(channel, 600)
    if len(text) > limit:
        cut = text[:limit]
        text = cut[: max(cut.rfind(". "), cut.rfind("\n"), limit // 2)].rstrip() or cut
    return text


def violates_money_rule(reply: str, conversation_text: str) -> bool:
    """True if the reply introduces an amount not in the conversation or reads like a commitment."""
    convo_amounts = {m.group(0).replace(" ", "").lower() for m in AMOUNT.finditer(conversation_text)}
    for m in AMOUNT.finditer(reply):
        if m.group(0).replace(" ", "").lower() not in convo_amounts:
            return True
    return bool(COMMIT_WORDS.search(reply))


def gendered_fallback(lang: str, gender: str) -> str:
    text = SAFE_FALLBACK.get(lang, SAFE_FALLBACK["en"])
    if lang == "hi" and gender == "female":
        text = text.replace("karta", "karti")
    return text
