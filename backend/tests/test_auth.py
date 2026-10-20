import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.core import auth
from app.core.config import Settings


def _settings(**kw):
    base = dict(auth_mode="firebase", firebase_project_id="ia-mode", environment="production", gemini_api_key="x", redis_url="redis://test")
    base.update(kw)
    return Settings(**base)


class _Creds:
    def __init__(self, token):
        self.credentials = token


async def test_firebase_valid_token(monkeypatch):
    monkeypatch.setattr(auth, "verify_firebase_token", lambda token, project: "uid-123")
    user = await auth.get_current_user(_Creds("good"), _settings())
    assert user.uid == "uid-123"


async def test_firebase_invalid_token(monkeypatch):
    def bad(token, project):
        raise ValueError("bad signature")

    monkeypatch.setattr(auth, "verify_firebase_token", bad)
    with pytest.raises(HTTPException) as e:
        await auth.get_current_user(_Creds("bad"), _settings())
    assert e.value.status_code == 401


async def test_enforced_app_check_requires_and_verifies_header(monkeypatch):
    monkeypatch.setattr(auth, "verify_firebase_token", lambda token, project: "uid-123")
    checked = []
    monkeypatch.setattr(auth, "verify_app_check_token", lambda token: checked.append(token))
    settings = _settings(enforce_app_check=True, firebase_service_account_json="{}")

    with pytest.raises(HTTPException) as missing:
        await auth.get_current_user(_Creds("good"), settings, None)
    assert missing.value.status_code == 401

    user = await auth.get_current_user(_Creds("good"), settings, "valid-app-check")
    assert user.uid == "uid-123"
    assert checked == ["valid-app-check"]


async def test_dev_auth_blocked_in_production():
    with pytest.raises(ValidationError, match="AUTH_MODE must be firebase"):
        _settings(auth_mode="dev", dev_api_token="t")


def test_wrong_issuer_rejected(monkeypatch):
    from google.oauth2 import id_token

    monkeypatch.setattr(id_token, "verify_firebase_token",
                        lambda *a, **k: {"iss": "https://securetoken.google.com/other", "sub": "u"})
    with pytest.raises(ValueError):
        auth.verify_firebase_token("t", "ia-mode")
