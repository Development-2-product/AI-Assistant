from tests.conftest import AUTH


def test_auth_check_ok(client):
    r = client.get("/v1/auth/check", headers=AUTH)
    assert r.status_code == 200 and r.json()["auth_mode"] == "dev"


def test_auth_check_explains_wrong_token(client):
    r = client.get("/v1/auth/check", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401 and "doesn't match" in r.json()["detail"]


def test_auth_check_explains_missing_token(client):
    r = client.get("/v1/auth/check")
    assert r.status_code == 401 and "no token" in r.json()["detail"]


def test_ai_check_reports_each_model(client, fake_llm):
    from app.services.gemini_client import LLMError

    async def flaky(*, model, prompt):
        if model == "gemini-3.5-flash-lite":
            raise LLMError(f"{model}: 429 RESOURCE_EXHAUSTED quota")
        return "ok"

    fake_llm.generate_text = flaky
    r = client.post("/v1/diagnostics/ai", headers=AUTH)
    body = r.json()
    assert r.status_code == 200 and body["ok"] is True
    failed = [m for m in body["models"] if not m["ok"]]
    assert failed and "429" in failed[0]["error"]


def test_503_does_not_echo_provider_error(client, fake_llm):
    from app.services.gemini_client import LLMError

    async def down(*, model, prompt, schema):
        raise LLMError("gemini-x: 400 API key not valid")

    fake_llm.generate_json = down
    r = client.post("/v1/messages/process", headers=AUTH, json={
        "channel": "whatsapp", "contact": {"name": "A", "relationship": "friend"},
        "messages": [{"sender": "them", "text": "hi"}]})
    assert r.status_code == 503 and "API key not valid" not in r.json()["detail"]
