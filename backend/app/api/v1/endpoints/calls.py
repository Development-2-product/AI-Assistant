from fastapi import APIRouter, Depends

from app.api.deps import get_assistant
from app.core.auth import AuthUser
from app.core.config import Settings, get_settings
from app.core.rate_limit import rate_limited_user
from app.schemas.calls import MissedCallRequest
from app.schemas.messages import ReplyResponse
from app.services.assistant import AssistantService

router = APIRouter(prefix="/calls", tags=["calls"])


@router.post("/missed-reply", response_model=ReplyResponse)
async def missed_call_reply(
    req: MissedCallRequest,
    _: AuthUser = Depends(rate_limited_user),
    assistant: AssistantService = Depends(get_assistant),
    settings: Settings = Depends(get_settings),
) -> ReplyResponse:
    return ReplyResponse(reply=await assistant.missed_call_reply(req), prompt_version=settings.prompt_version)
