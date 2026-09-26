from fastapi import APIRouter, Depends

from app.api.deps import get_assistant
from app.core.auth import AuthUser
from app.core.config import Settings, get_settings
from app.core.rate_limit import rate_limited_user
from app.schemas.mail import MailIntelligenceRequest, MailIntelligenceResponse, ReplyDraftRequest, ReplyDraftResponse
from app.services.assistant import AssistantService

router = APIRouter(prefix="/mail", tags=["mail"])


@router.post("/intelligence", response_model=MailIntelligenceResponse)
async def understand_mail(
    req: MailIntelligenceRequest,
    _: AuthUser = Depends(rate_limited_user),
    assistant: AssistantService = Depends(get_assistant),
    settings: Settings = Depends(get_settings),
) -> MailIntelligenceResponse:
    """Classify, extract and propose. This endpoint never sends or changes mail."""
    return MailIntelligenceResponse(intelligence=await assistant.understand_mail(req), prompt_version=settings.prompt_version)


@router.post("/reply-draft", response_model=ReplyDraftResponse)
async def draft_reply(
    req: ReplyDraftRequest,
    _: AuthUser = Depends(rate_limited_user),
    assistant: AssistantService = Depends(get_assistant),
    settings: Settings = Depends(get_settings),
) -> ReplyDraftResponse:
    """An editable draft for the user to review. Sending happens only on the phone, after approval."""
    return ReplyDraftResponse(draft=await assistant.draft_mail_reply(req), prompt_version=settings.prompt_version)
