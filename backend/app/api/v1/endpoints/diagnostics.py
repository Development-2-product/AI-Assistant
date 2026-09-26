"""Checks the app runs from its Connection check screen. They reveal configuration, never secrets."""
import time

from fastapi import APIRouter, Depends

from app.api.deps import get_llm
from app.core.auth import AuthUser, get_current_user
from app.core.config import Settings, get_settings
from app.core.rate_limit import rate_limited_user
from app.services.gemini_client import LLMClient, LLMError

router = APIRouter(tags=["diagnostics"])


@router.get("/auth/check")
async def auth_check(user: AuthUser = Depends(get_current_user), settings: Settings = Depends(get_settings)) -> dict:
    """Succeeds only if the app's login token is accepted."""
    return {"ok": True, "auth_mode": settings.auth_mode, "user": user.uid_hash}


@router.post("/diagnostics/ai")
async def ai_check(
    _: AuthUser = Depends(rate_limited_user),
    settings: Settings = Depends(get_settings),
    llm: LLMClient = Depends(get_llm),
) -> dict:
    """Sends a one-word prompt to each configured model and reports which ones answer."""
    models = [m for m in dict.fromkeys(
        [settings.gemini_model_fast, settings.gemini_model_writer, settings.gemini_model_fallback]) if m]
    results = []
    for model in models:
        start = time.perf_counter()
        try:
            await llm.generate_text(model=model, prompt="Reply with the single word: ok")
            results.append({"model": model, "ok": True, "ms": int((time.perf_counter() - start) * 1000)})
        except LLMError as exc:
            results.append({"model": model, "ok": False, "error": str(exc)[:300]})
    return {"ok": any(r["ok"] for r in results), "models": results}
