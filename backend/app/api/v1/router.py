from fastapi import APIRouter

from app.api.v1.endpoints import calls, conversations, diagnostics, health, mail, messages

api_router = APIRouter(prefix="/v1")
api_router.include_router(health.router)
api_router.include_router(messages.router)
api_router.include_router(mail.router)
api_router.include_router(calls.router)
api_router.include_router(conversations.router)
api_router.include_router(diagnostics.router)
