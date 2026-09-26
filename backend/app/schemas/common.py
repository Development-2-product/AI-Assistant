from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class Channel(str, Enum):
    whatsapp = "whatsapp"
    whatsapp_business = "whatsapp_business"
    telegram = "telegram"
    instagram = "instagram"
    gmail = "gmail"
    sms = "sms"

    @property
    def label(self) -> str:
        return {
            "whatsapp": "WhatsApp", "whatsapp_business": "WhatsApp Business", "telegram": "Telegram",
            "instagram": "Instagram DM", "gmail": "Email", "sms": "SMS",
        }[self.value]


class Relationship(str, Enum):
    client = "client"
    business = "business"
    partner = "partner"
    friend = "friend"
    family = "family"
    group = "group"
    unknown = "unknown"

    @property
    def is_professional(self) -> bool:
        return self in (Relationship.client, Relationship.business)


class SituationStatus(str, Enum):
    available = "available"
    driving = "driving"
    riding = "riding"
    interview = "interview"
    meeting = "meeting"
    gaming = "gaming"
    sleeping = "sleeping"
    busy = "busy"


LanguageCode = Literal["en", "te", "hi", "ta", "kn"]
Script = Literal["native", "roman"]
ReplyStyle = Literal["professional", "casual", "friendly", "romantic", "flirty", "comeback", "supportive"]

LANGUAGE_NAMES = {"en": "English", "te": "Telugu", "hi": "Hindi", "ta": "Tamil", "kn": "Kannada"}
SITUATION_LABELS = {
    "available": "Available",
    "driving": "Driving a car",
    "riding": "Riding a bike",
    "interview": "In an interview",
    "meeting": "In a meeting",
    "gaming": "Playing a game",
    "sleeping": "Sleeping",
    "busy": "Busy",
}


class ChatMessage(BaseModel):
    sender: Literal["them", "me"]
    text: str = Field(default="", max_length=6000)
    kind: Literal["text", "call", "call_reply"] = "text"


class Contact(BaseModel):
    name: str = Field(max_length=120)
    relationship: Relationship


class Situation(BaseModel):
    status: SituationStatus = SituationStatus.available
    reason: str = Field(default="", max_length=200)


class UserContext(BaseModel):
    name: str = Field(default="", max_length=80)
    gender: Literal["male", "female"] = "male"
    situation: Situation = Situation()


class LanguageHint(BaseModel):
    lang: LanguageCode = "en"
    script: Script = "roman"
