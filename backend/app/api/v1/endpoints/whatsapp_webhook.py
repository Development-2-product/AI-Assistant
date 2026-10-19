"""Verified inbound delivery webhooks only; never a message-sending endpoint."""
import logging

from fastapi import APIRouter, Header, HTTPException, Request, Response, status

from app.core.config import get_settings
from app.core.logging import log_event
from app.services.whatsapp_cloud import WhatsAppCloudClient

router = APIRouter(prefix="/webhooks/whatsapp", tags=["whatsapp-webhook"])
logger = logging.getLogger(__name__)


@router.get("")
async def verify(mode: str | None = None, challenge: str | None = None, verify_token: str | None = None) -> Response:
    settings = get_settings()
    if not settings.whatsapp_cloud_enabled:
        raise HTTPException(status.HTTP_404_NOT_FOUND)
    if mode != "subscribe" or not challenge or not verify_token or verify_token != settings.whatsapp_webhook_verify_token:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Webhook verification rejected")
    return Response(content=challenge, media_type="text/plain")


@router.post("")
async def receive(request: Request, x_hub_signature_256: str | None = Header(default=None)) -> Response:
    settings = get_settings()
    if not settings.whatsapp_cloud_enabled:
        raise HTTPException(status.HTTP_404_NOT_FOUND)
    body = await request.body()
    if not WhatsAppCloudClient(settings).verify_signature(body, x_hub_signature_256):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid webhook signature")
    # Do not log body: webhook data can contain private chat content and phone numbers.
    log_event(logger, "whatsapp_webhook_verified", bytes=len(body))
    return Response(status_code=status.HTTP_200_OK)
