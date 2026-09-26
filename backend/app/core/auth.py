"""Request authentication.

production: the Android app signs in anonymously with Firebase and sends its ID token.
            Tokens are verified against Google's public keys, so no service-account
            credentials are needed (works on Render and other supported platforms).
development: a static DEV_API_TOKEN is accepted so the app can be tested without Firebase.
"""
import hashlib
import hmac
import logging
from dataclasses import dataclass
from functools import lru_cache

from fastapi import Depends, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import Settings, get_settings

logger = logging.getLogger(__name__)
_bearer = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class AuthUser:
    uid: str

    @property
    def uid_hash(self) -> str:
        # Used in logs so raw user ids are never written
        return hashlib.sha256(self.uid.encode()).hexdigest()[:12]


@lru_cache
def _google_request():
    """HTTP transport that caches Google's signing certificates (they rotate daily)."""
    import cachecontrol
    import requests
    from google.auth.transport import requests as g_requests

    return g_requests.Request(session=cachecontrol.CacheControl(requests.Session()))


def verify_firebase_token(token: str, project_id: str) -> str:
    """Returns the Firebase uid, or raises ValueError."""
    from google.oauth2 import id_token

    claims = id_token.verify_firebase_token(token, _google_request(), audience=project_id)
    if not claims or claims.get("iss") != f"https://securetoken.google.com/{project_id}":
        raise ValueError("wrong issuer")
    uid = claims.get("sub")
    if not uid:
        raise ValueError("missing subject")
    return uid


async def get_current_user(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> AuthUser:
    if creds is None or not creds.credentials:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED,
                            "Missing login token: the app sent no token (Firebase sign-in failed or no dev token set)")
    token = creds.credentials

    if settings.auth_mode == "dev":
        if settings.environment == "production":
            raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR,
                                "Server misconfigured: AUTH_MODE=dev is not allowed when ENVIRONMENT=production")
        if not hmac.compare_digest(token, settings.dev_api_token):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED,
                                "Dev token rejected: the app's token doesn't match DEV_API_TOKEN on the server")
        return AuthUser(uid="dev-user")

    if not settings.firebase_project_id:
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "FIREBASE_PROJECT_ID is not set")
    try:
        uid = await run_in_threadpool(verify_firebase_token, token, settings.firebase_project_id)
        return AuthUser(uid=uid)
    except Exception:  # noqa: BLE001 - any verification failure is a 401
        logger.warning("Firebase token verification failed")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED,
                            "Firebase token rejected: the app isn't signed in to Firebase project "
                            f"'{settings.firebase_project_id}' (or it sent a dev token while AUTH_MODE=firebase)")
