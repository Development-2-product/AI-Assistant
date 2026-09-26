from functools import lru_cache

from fastapi import Depends

from app.core.config import Settings, get_settings
from app.services.assistant import AssistantService
from app.services.gemini_client import GeminiClient, LLMClient


@lru_cache
def _llm_singleton() -> LLMClient:
    return GeminiClient(get_settings())


def get_llm() -> LLMClient:
    return _llm_singleton()


def get_assistant(llm: LLMClient = Depends(get_llm), settings: Settings = Depends(get_settings)) -> AssistantService:
    return AssistantService(llm, settings)
