"""Loads versioned prompt files and fills $placeholders (string.Template, so JSON braces are safe)."""
from functools import lru_cache
from pathlib import Path
from string import Template

PROMPT_DIR = Path(__file__).resolve().parent.parent / "prompts"


@lru_cache
def _load(name: str) -> Template:
    return Template((PROMPT_DIR / f"{name}.md").read_text(encoding="utf-8"))


def render(name: str, **values: object) -> str:
    return _load(name).safe_substitute({k: "" if v is None else str(v) for k, v in values.items()}).strip()
