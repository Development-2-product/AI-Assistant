from typing import Literal

from pydantic import BaseModel, Field

from app.schemas.common import (
    Channel,
    ChatMessage,
    Contact,
    LanguageCode,
    ReplyStyle,
    Script,
    UserContext,
)


class ProcessMessageRequest(BaseModel):
    channel: Channel
    contact: Contact
    user: UserContext = UserContext()
    subject: str | None = Field(default=None, max_length=300)
    messages: list[ChatMessage] = Field(min_length=1, max_length=40)
    money_mode: Literal["safe_reply", "ask"] = "safe_reply"


class Analysis(BaseModel):
    """What the fast model returns. Also used as the Gemini response schema."""

    language: LanguageCode
    script: Script
    tone: Literal["formal", "business", "casual", "friendly", "romantic", "flirty", "scolding", "sad", "urgent", "neutral"]
    summary: str
    mentions_money: bool
    asks_commitment: bool
    crisis: bool
    needs_reply: bool
    conversation_state: Literal["ongoing", "wrapping_up", "ended"]
    style: ReplyStyle


class ProcessMessageResponse(BaseModel):
    analysis: Analysis
    reply: str
    prompt_version: str


class RestyleRequest(BaseModel):
    channel: Channel
    contact: Contact
    user: UserContext = UserContext()
    subject: str | None = None
    messages: list[ChatMessage] = Field(min_length=1, max_length=40)
    style: ReplyStyle


class ReplyResponse(BaseModel):
    reply: str
    prompt_version: str
