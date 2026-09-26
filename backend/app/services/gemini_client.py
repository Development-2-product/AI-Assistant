"""Thin async wrapper around the Google Gen AI SDK. Swappable in tests."""
import asyncio
import logging
import time
from typing import Protocol, TypeVar

from pydantic import BaseModel

from app.core.config import Settings
from app.core.logging import log_event

logger = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)


class LLMError(RuntimeError):
    pass


def _describe(exc: Exception | None) -> str:
    """Return a diagnostic that cannot contain a provider message, prompt, or credential."""
    if exc is None:
        return "unknown error"
    code = getattr(exc, "code", None) or getattr(exc, "status_code", None)
    if code:
        return f"Gemini request failed with HTTP {code}"
    return f"Gemini request failed ({type(exc).__name__})"


def _is_transient(exc: Exception) -> bool:
    """Overload, rate limit, timeout or network errors are worth retrying; bad requests are not."""
    if isinstance(exc, (asyncio.TimeoutError, ConnectionError)):
        return True
    code = getattr(exc, "code", None) or getattr(exc, "status_code", None)
    return code is None or code in (408, 429, 500, 502, 503, 504)


class LLMClient(Protocol):
    async def generate_json(self, *, model: str, prompt: str, schema: type[T]) -> T: ...
    async def generate_text(self, *, model: str, prompt: str) -> str: ...


class GeminiClient:
    def __init__(self, settings: Settings) -> None:
        from google import genai

        if not settings.gemini_api_key:
            raise LLMError("GEMINI_API_KEY is not set on the server")
        self._client = genai.Client(api_key=settings.gemini_api_key)
        self._timeout = settings.gemini_timeout_seconds

    async def _call(self, model: str, prompt: str, config) -> object:
        start = time.perf_counter()
        last_exc: Exception | None = None
        for attempt in range(2):  # one retry on transient failures, then the caller tries a fallback model
            try:
                resp = await asyncio.wait_for(
                    self._client.aio.models.generate_content(model=model, contents=prompt, config=config),
                    timeout=self._timeout,
                )
                log_event(logger, "gemini_call", model=model, attempt=attempt,
                          ms=int((time.perf_counter() - start) * 1000))
                return resp
            except Exception as exc:  # noqa: BLE001
                last_exc = exc
                if not _is_transient(exc):
                    break
                await asyncio.sleep(0.8 * (attempt + 1))
        reason = _describe(last_exc)
        log_event(logger, "gemini_error", model=model, error=reason)
        raise LLMError(f"{model}: {reason}") from last_exc

    async def generate_json(self, *, model: str, prompt: str, schema: type[T]) -> T:
        from google.genai import types

        config = types.GenerateContentConfig(response_mime_type="application/json", response_schema=schema)
        resp = await self._call(model, prompt, config)
        parsed = getattr(resp, "parsed", None)
        if isinstance(parsed, schema):
            return parsed
        text = getattr(resp, "text", "") or ""
        try:
            return schema.model_validate_json(text)
        except Exception as exc:  # noqa: BLE001
            raise LLMError("Gemini returned invalid JSON") from exc

    async def generate_text(self, *, model: str, prompt: str) -> str:
        resp = await self._call(model, prompt, None)
        text = (getattr(resp, "text", "") or "").strip()
        if not text:
            raise LLMError("Gemini returned an empty response")
        return text
