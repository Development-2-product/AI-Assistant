import hashlib
import hmac

from app.core.config import Settings
from app.services.whatsapp_cloud import WhatsAppCloudClient


def cloud_settings() -> Settings:
    return Settings(
        auth_mode="dev", dev_api_token="test", whatsapp_cloud_enabled=True,
        whatsapp_graph_version="v1", whatsapp_phone_number_id="phone", whatsapp_business_account_id="waba",
        whatsapp_system_user_token="token", whatsapp_app_secret="secret", whatsapp_webhook_verify_token="verify",
    )


def test_webhook_signature_is_constant_time_verified():
    body = b'{"object":"whatsapp_business_account"}'
    signature = "sha256=" + hmac.new(b"secret", body, hashlib.sha256).hexdigest()
    client = WhatsAppCloudClient(cloud_settings())
    assert client.verify_signature(body, signature)
    assert not client.verify_signature(body, "sha256=invalid")


def test_cloud_is_disabled_without_configuration():
    client = WhatsAppCloudClient(Settings(auth_mode="dev", dev_api_token="test"))
    assert not client.enabled
