import os

# Tests must never read the developer's real backend/.env (it holds a real Gemini key and
# local model choices that change test behaviour). Everything they need is set here.
os.environ.update({
    "ENVIRONMENT": "development",
    "AUTH_MODE": "dev",
    "DEV_API_TOKEN": "test-token",
    "GEMINI_API_KEY": "unused-in-tests",
    "GEMINI_MODEL_FAST": "gemini-3.5-flash-lite",
    "GEMINI_MODEL_WRITER": "gemini-3.5-flash",
    "GEMINI_MODEL_FALLBACK": "",
    "FIREBASE_PROJECT_ID": "",
    "REDIS_URL": "",
})
from app.core import config as _config  # noqa: E402

_config.Settings.model_config["env_file"] = None
_config.get_settings.cache_clear()

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_llm
from app.main import app
from app.schemas.messages import Analysis


class FakeLLM:
    """Deterministic stand-in for Gemini."""

    def __init__(self) -> None:
        self.analysis = Analysis(
            language="te", script="roman", tone="casual", summary="Friend asks what the user is doing",
            mentions_money=False, asks_commitment=False, crisis=False, needs_reply=True,
            conversation_state="ongoing", style="casual",
        )
        self.text = "Emi ledu ra, office lo unna. Evening match chuddam 🏏"
        self.prompts: list[str] = []
        # Per-schema answers for features other than chat analysis (e.g. mail intelligence).
        self.json_by_schema: dict = {}

    async def generate_json(self, *, model, prompt, schema):
        self.prompts.append(prompt)
        if schema in self.json_by_schema:
            return self.json_by_schema[schema].model_copy(deep=True)
        return self.analysis.model_copy()

    async def generate_text(self, *, model, prompt):
        self.prompts.append(prompt)
        return self.text


@pytest.fixture
def fake_llm():
    return FakeLLM()


@pytest.fixture
def client(fake_llm):
    app.dependency_overrides[get_llm] = lambda: fake_llm
    yield TestClient(app)
    app.dependency_overrides.clear()


AUTH = {"Authorization": "Bearer test-token"}
