"""Loads versioned prompt files and fills $placeholders (string.Template, so JSON braces are safe)."""
from functools import lru_cache
from pathlib import Path
from string import Template

PROMPT_DIR = Path(__file__).resolve().parent.parent / "prompts"
REQUIRED_PROMPTS = (
    "_language_rules", "_security_rules", "_tone_rules", "analyze", "mail_intelligence",
    "mail_reply", "missed_call", "process", "recap", "reply",
)


def validate_prompt_assets() -> None:
    """Fail deployment startup if a source prompt was omitted from the image.

    Prompt files are executable application policy, not optional runtime content.  A
    startup failure is observable by Render and is safer than a healthy `/health`
    endpoint followed by opaque 500s for every private message or email.
    """
    missing = [name for name in REQUIRED_PROMPTS if not (PROMPT_DIR / f"{name}.md").is_file()]
    if missing:
        raise RuntimeError(f"Required prompt assets are missing: {', '.join(missing)}")


@lru_cache
def _load(name: str) -> Template:
    return Template((PROMPT_DIR / f"{name}.md").read_text(encoding="utf-8"))


def render(name: str, **values: object) -> str:
    return _load(name).safe_substitute({k: "" if v is None else str(v) for k, v in values.items()}).strip()
