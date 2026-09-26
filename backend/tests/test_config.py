import pytest
from pydantic import ValidationError

from app.core.config import Settings


def test_production_requires_firebase_auth():
    with pytest.raises(ValidationError, match="AUTH_MODE must be firebase"):
        Settings(environment="production", auth_mode="dev", dev_api_token="test-token")


def test_production_requires_firebase_project_id():
    with pytest.raises(ValidationError, match="FIREBASE_PROJECT_ID"):
        Settings(environment="production", auth_mode="firebase", firebase_project_id="")


def test_production_requires_gemini_key():
    with pytest.raises(ValidationError, match="GEMINI_API_KEY"):
        Settings(environment="production", auth_mode="firebase", firebase_project_id="ia-mode", gemini_api_key="")


def test_dev_auth_requires_explicit_token():
    with pytest.raises(ValidationError, match="DEV_API_TOKEN"):
        Settings(environment="development", auth_mode="dev", dev_api_token="")


def test_cors_wildcard_is_rejected():
    with pytest.raises(ValidationError, match="CORS_ALLOWED_ORIGINS"):
        Settings(cors_allowed_origins="*")
