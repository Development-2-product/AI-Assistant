from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


BACKEND_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BACKEND_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    environment: Literal["development", "staging", "production"] = "development"
    log_level: str = "INFO"
    # Development always has local Swagger. Production documentation is opt-in
    # for a short diagnostic window and must be disabled again after use.
    enable_api_docs: bool = False
    # Human-readable deploy revision only; never put secrets or URLs in this value.
    release_version: str = "unknown"

    gemini_api_key: str = ""
    gemini_model_fast: str = "gemini-3.5-flash-lite"
    gemini_model_writer: str = "gemini-3.5-flash"
    # Tried when the primary model is overloaded or rate-limited. Empty = use the other configured model.
    gemini_model_fallback: str = ""
    # Keep a failed provider from delaying a real-time chat reply for too long.
    gemini_timeout_seconds: float = 12.0

    auth_mode: Literal["firebase", "dev"] = "firebase"
    dev_api_token: str = ""
    firebase_project_id: str = ""
    # App Check is staged: deploy client support and register Play Integrity first,
    # then enable this to reject requests that do not originate from a valid app.
    enforce_app_check: bool = False
    firebase_service_account_json: str = ""

    # Comma-separated browser origins. Android clients do not need CORS, so the
    # safe default is to allow no browser origin until one is explicitly set.
    cors_allowed_origins: str = ""

    # AI endpoints are intentionally expensive. Keep a per-account and a second
    # network-level limit so freshly-created anonymous accounts cannot be used to
    # turn the API into an unrestricted model proxy.
    rate_limit_per_minute: int = Field(default=60, ge=1, le=10_000)
    rate_limit_per_ip_per_minute: int = Field(default=240, ge=1, le=50_000)
    max_request_body_bytes: int = Field(default=131_072, ge=4_096, le=2_097_152)
    redis_url: str = ""

    # WhatsApp Cloud is deliberately off until a verified Meta business integration is configured.
    # These values are backend-only secrets; Android builds must never receive them.
    whatsapp_cloud_enabled: bool = False
    whatsapp_graph_version: str = ""
    whatsapp_phone_number_id: str = ""
    whatsapp_business_account_id: str = ""
    whatsapp_system_user_token: str = ""
    whatsapp_app_secret: str = ""
    whatsapp_webhook_verify_token: str = ""

    prompt_version: str = "2026-09-v4"
    max_messages_in_context: int = 14

    @property
    def cors_origins(self) -> tuple[str, ...]:
        return tuple(origin.strip() for origin in self.cors_allowed_origins.split(",") if origin.strip())

    @model_validator(mode="after")
    def validate_deployment_security(self) -> "Settings":
        if self.environment == "production":
            if self.auth_mode != "firebase":
                raise ValueError("AUTH_MODE must be firebase when ENVIRONMENT=production")
            if not self.firebase_project_id:
                raise ValueError("FIREBASE_PROJECT_ID is required when ENVIRONMENT=production")
            if not self.gemini_api_key:
                raise ValueError("GEMINI_API_KEY is required when ENVIRONMENT=production")
            if not self.redis_url:
                raise ValueError("REDIS_URL is required when ENVIRONMENT=production so rate limits are shared")
            if self.enforce_app_check and not self.firebase_service_account_json:
                raise ValueError("FIREBASE_SERVICE_ACCOUNT_JSON is required when ENFORCE_APP_CHECK=true")
        if self.auth_mode == "dev" and not self.dev_api_token:
            raise ValueError("DEV_API_TOKEN is required when AUTH_MODE=dev")
        if "*" in self.cors_origins:
            raise ValueError("CORS_ALLOWED_ORIGINS cannot contain '*' for an authenticated API")
        if self.whatsapp_cloud_enabled:
            required = (
                self.whatsapp_graph_version, self.whatsapp_phone_number_id,
                self.whatsapp_business_account_id, self.whatsapp_system_user_token,
                self.whatsapp_app_secret, self.whatsapp_webhook_verify_token,
            )
            if not all(required):
                raise ValueError("WhatsApp Cloud is enabled but required backend-only Meta configuration is missing")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
