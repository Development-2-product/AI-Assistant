import time

from fastapi import APIRouter, Depends, Request

from app.core.config import Settings, get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
async def health(request: Request, settings: Settings = Depends(get_settings)) -> dict:
    """Public, redacted readiness endpoint for uptime monitors and app compatibility checks."""
    return {
        "status": "ok",
        "api_version": "1",
        "release_version": settings.release_version,
        "prompt_version": settings.prompt_version,
        "uptime_seconds": int(time.monotonic() - request.app.state.started_at),
        # Booleans reveal no credential, endpoint, project or provider detail.
        "ai_configured": bool(settings.gemini_api_key),
        "auth_configured": bool(settings.firebase_project_id) if settings.auth_mode == "firebase" else bool(settings.dev_api_token),
    }
