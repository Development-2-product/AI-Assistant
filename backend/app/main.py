import logging
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.v1.router import api_router
from app.core.config import get_settings
from app.core.http import MaxRequestBodySizeMiddleware
from app.core.logging import log_event, setup_logging
from app.services.gemini_client import LLMError
from app.services.prompts import validate_prompt_assets

settings = get_settings()
validate_prompt_assets()
setup_logging(settings.log_level)
logger = logging.getLogger("ia_mode")
docs_enabled = settings.environment != "production" or settings.enable_api_docs

app = FastAPI(
    title="IA Mode API",
    version="1.0.0",
    docs_url="/docs" if docs_enabled else None,
    redoc_url=None,
    openapi_url="/openapi.json" if docs_enabled else None,
)
app.add_middleware(MaxRequestBodySizeMiddleware, max_bytes=settings.max_request_body_bytes)
app.state.started_at = time.monotonic()


def content_security_policy(path: str) -> str:
    """Keep the API locked down while allowing FastAPI's development-only Swagger UI.

    FastAPI serves /docs only outside production and its stock page loads its
    versioned UI bundle from jsDelivr plus one inline initialisation block. This
    exception is deliberately path- and environment-scoped; API responses and
    every production response retain the deny-all policy.
    """
    if docs_enabled and path in {"/docs", "/redoc"}:
        return (
            "default-src 'self'; "
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
            "img-src 'self' data: https://fastapi.tiangolo.com; "
            "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"
        )
    return "default-src 'none'; base-uri 'none'; frame-ancestors 'none'"

if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_origins),
        allow_credentials=True,
        allow_methods=["GET", "POST"],
        allow_headers=["Authorization", "Content-Type", "X-Request-ID", "X-Firebase-AppCheck"],
    )


@app.middleware("http")
async def request_context(request: Request, call_next):
    supplied_request_id = request.headers.get("x-request-id", "")
    # Do not reflect an arbitrary unbounded header into logs or responses.
    request_id = supplied_request_id if supplied_request_id.isascii() and supplied_request_id.replace("-", "").isalnum() and len(supplied_request_id) <= 64 else uuid.uuid4().hex[:12]
    request.state.request_id = request_id
    start = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception as exc:  # Starlette's BaseHTTPMiddleware otherwise re-raises ExceptionGroups to clients/tests.
        # Do not log exception text or a stack trace: either can carry private provider/request data.
        log_event(logger, "unhandled_request_error", id=request_id, path=request.url.path, error_type=type(exc).__name__)
        response = JSONResponse(status_code=500, content={
            "detail": "Service error. Try again shortly.", "request_id": request_id,
        })
    response.headers["x-request-id"] = request_id
    # This API serves JSON to an installed app, not browser content. Keep conservative defaults
    # even when it is placed behind a reverse proxy or a future diagnostic web client.
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Content-Security-Policy"] = content_security_policy(request.url.path)
    response.headers["Permissions-Policy"] = "accelerometer=(), camera=(), geolocation=(), microphone=()"
    response.headers["Cache-Control"] = "no-store"
    if settings.environment == "production":
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    # Metadata only. Request bodies contain private messages and are never logged.
    log_event(logger, "request", id=request_id, path=request.url.path, status=response.status_code,
              ms=int((time.perf_counter() - start) * 1000))
    return response


@app.exception_handler(LLMError)
async def llm_error_handler(request: Request, exc: LLMError) -> JSONResponse:
    # 503 tells the app to fall back to asking the user instead of auto-sending
    request_id = getattr(request.state, "request_id", "unknown")
    # Do not log exc text: SDK errors may contain provider/request details.
    log_event(logger, "ai_unavailable", id=request_id, error_type=type(exc.__cause__ or exc).__name__)
    return JSONResponse(status_code=503, content={"detail": "AI temporarily unavailable. Try again shortly.", "request_id": request_id})


@app.exception_handler(Exception)
async def unexpected_error_handler(request: Request, exc: Exception) -> JSONResponse:
    """Return a safe error while retaining a correlation id for server-only logs."""
    request_id = getattr(request.state, "request_id", "unknown")
    # Never attach the exception text: request payloads can contain private mail/chat.
    log_event(logger, "unhandled_request_error", id=request_id, path=request.url.path, error_type=type(exc).__name__)
    return JSONResponse(status_code=500, content={"detail": "Service error. Try again shortly.", "request_id": request_id})


app.include_router(api_router)

# One line at startup that makes misconfiguration obvious in the Render logs.
log_event(logger, "startup", environment=settings.environment, auth_mode=settings.auth_mode,
          firebase_project=settings.firebase_project_id or None, gemini_key_set=bool(settings.gemini_api_key),
          fast_model=settings.gemini_model_fast, writer_model=settings.gemini_model_writer,
          prompt_version=settings.prompt_version)
