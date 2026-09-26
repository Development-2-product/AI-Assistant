from app.schemas.common import LANGUAGE_NAMES, SITUATION_LABELS, ChatMessage, UserContext


def _clean(text: str) -> str:
    # Messages are untrusted: stop them from closing the <conversation> block the prompt relies on.
    return text.replace("<conversation>", "(conversation)").replace("</conversation>", "(/conversation)").strip()


def format_transcript(messages: list[ChatMessage], contact_name: str, limit: int) -> str:
    return "<conversation>\n" + _lines(messages, contact_name, limit) + "\n</conversation>"


def _lines(messages: list[ChatMessage], contact_name: str, limit: int) -> str:
    lines = []
    for m in messages[-limit:]:
        if m.kind == "call":
            lines.append(f"[{contact_name} called, the user missed it]")
        else:
            who = "Me" if m.sender == "me" else contact_name
            lines.append(f"{who}: {_clean(m.text)}")
    return "\n".join(lines) or "(no messages)"


def format_situation(user: UserContext) -> str:
    label = SITUATION_LABELS.get(user.situation.status.value, "Available")
    return f"{label} ({user.situation.reason})" if user.situation.reason else label


def language_name(code: str) -> str:
    return LANGUAGE_NAMES.get(code, "English")
