from fastapi import APIRouter, Depends

from app.api.deps import get_assistant
from app.core.auth import AuthUser
from app.core.config import Settings, get_settings
from app.core.rate_limit import rate_limited_user
from app.schemas.calls import RecapRequest, RecapResponse
from app.services.assistant import AssistantService

router = APIRouter(prefix="/conversations", tags=["conversations"])


@router.post("/recap", response_model=RecapResponse)
async def recap(
    req: RecapRequest,
    _: AuthUser = Depends(rate_limited_user),
    assistant: AssistantService = Depends(get_assistant),
    settings: Settings = Depends(get_settings),
) -> RecapResponse:
    return RecapResponse(recap=await assistant.recap(req), prompt_version=settings.prompt_version)
