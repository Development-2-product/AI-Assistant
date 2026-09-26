from fastapi import APIRouter, Depends

from app.core.config import Settings, get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict:
    """Liveness check for uptime monitors. Never calls Gemini, so it costs nothing."""
    return {"status": "ok", "prompt_version": settings.prompt_version}
