from pydantic import BaseModel, Field

from app.schemas.common import ChatMessage, Contact, LanguageHint, UserContext


class MissedCallRequest(BaseModel):
    contact: Contact
    user: UserContext = UserContext()
    history: list[ChatMessage] = Field(default_factory=list, max_length=40)
    missed_calls_last_10_min: int = Field(default=1, ge=1, le=50)
    # Where the language came from: "conversation" means match the history exactly.
    language: LanguageHint = LanguageHint()
    language_source: str = Field(default="default", pattern="^(conversation|contact|default)$")
    local_time: str = Field(default="", max_length=20)


class RecapRequest(BaseModel):
    contact: Contact
    messages: list[ChatMessage] = Field(min_length=1, max_length=60)


class RecapResponse(BaseModel):
    recap: str
    prompt_version: str
