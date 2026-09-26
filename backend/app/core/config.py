from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import model_validator
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

    # Comma-separated browser origins. Android clients do not need CORS, so the
    # safe default is to allow no browser origin until one is explicitly set.
    cors_allowed_origins: str = ""

    rate_limit_per_minute: int = 60
    redis_url: str = ""

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
        if self.auth_mode == "dev" and not self.dev_api_token:
            raise ValueError("DEV_API_TOKEN is required when AUTH_MODE=dev")
        if "*" in self.cors_origins:
            raise ValueError("CORS_ALLOWED_ORIGINS cannot contain '*' for an authenticated API")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
