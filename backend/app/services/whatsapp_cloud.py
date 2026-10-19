"""Meta WhatsApp Cloud boundary.

This module is intentionally not connected to an automatic reply path. A future approved-send
service may call it only after persistent idempotency, consent/window checks and a user approval.
"""
import hashlib
import hmac
from dataclasses import dataclass

import requests

from app.core.config import Settings


class WhatsAppCloudError(RuntimeError):
    pass


@dataclass(frozen=True)
class CloudSendResult:
    provider_message_id: str


class WhatsAppCloudClient:
    def __init__(self, settings: Settings) -> None:
        self._s = settings

    @property
    def enabled(self) -> bool:
        return self._s.whatsapp_cloud_enabled

    def verify_signature(self, body: bytes, signature: str | None) -> bool:
        if not self.enabled or not signature or not signature.startswith("sha256="):
            return False
        expected = hmac.new(self._s.whatsapp_app_secret.encode(), body, hashlib.sha256).hexdigest()
        return hmac.compare_digest(signature.removeprefix("sha256="), expected)

    def send_approved_text(self, *, recipient: str, text: str) -> CloudSendResult:
        """Low-level transport only. Callers must enforce approval, consent and idempotency first."""
        if not self.enabled:
            raise WhatsAppCloudError("WhatsApp Business Cloud is not configured")
        if not recipient or not text.strip():
            raise WhatsAppCloudError("Recipient and message are required")
        url = f"https://graph.facebook.com/{self._s.whatsapp_graph_version}/{self._s.whatsapp_phone_number_id}/messages"
        try:
            response = requests.post(url, timeout=15, headers={"Authorization": f"Bearer {self._s.whatsapp_system_user_token}"}, json={
                "messaging_product": "whatsapp", "to": recipient, "type": "text", "text": {"body": text.strip()},
            })
            response.raise_for_status()
            message_id = response.json().get("messages", [{}])[0].get("id")
        except Exception as exc:  # Provider errors must not expose request bodies or tokens.
            raise WhatsAppCloudError("Meta did not accept the approved message") from exc
        if not message_id:
            raise WhatsAppCloudError("Meta returned no message identifier")
        return CloudSendResult(provider_message_id=message_id)
