from fastapi import APIRouter, Depends

from app.api.deps import get_assistant
from app.core.auth import AuthUser
from app.core.config import Settings, get_settings
from app.core.rate_limit import rate_limited_user
from app.schemas.messages import ProcessMessageRequest, ProcessMessageResponse, ReplyResponse, RestyleRequest
from app.services.assistant import AssistantService

router = APIRouter(prefix="/messages", tags=["messages"])


@router.post("/process", response_model=ProcessMessageResponse)
async def process_message(
    req: ProcessMessageRequest,
    _: AuthUser = Depends(rate_limited_user),
    assistant: AssistantService = Depends(get_assistant),
    settings: Settings = Depends(get_settings),
) -> ProcessMessageResponse:
    """Analyze the latest incoming message and, if a reply is appropriate, write it.
    The app's own policy engine decides whether to send it."""
    analysis, reply = await assistant.process_message(req)
    return ProcessMessageResponse(analysis=analysis, reply=reply, prompt_version=settings.prompt_version)


@router.post("/restyle", response_model=ReplyResponse)
async def restyle(
    req: RestyleRequest,
    _: AuthUser = Depends(rate_limited_user),
    assistant: AssistantService = Depends(get_assistant),
    settings: Settings = Depends(get_settings),
) -> ReplyResponse:
    return ReplyResponse(reply=await assistant.restyle(req), prompt_version=settings.prompt_version)
