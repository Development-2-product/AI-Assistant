package com.iamode.app.domain.model

enum class Channel(val label: String) {
    WHATSAPP("WhatsApp"), WHATSAPP_BUSINESS("WA Business"), TELEGRAM("Telegram"), INSTAGRAM("Instagram"),
    GMAIL("Gmail"), SMS("SMS");

    /** Chat apps read from notifications and answered through the notification's Reply button. */
    val isChatApp: Boolean get() = this in CHAT_APPS

    companion object {
        val CHAT_APPS = setOf(WHATSAPP, WHATSAPP_BUSINESS, TELEGRAM, INSTAGRAM)
    }
}

enum class Relationship(val label: String) {
    CLIENT("Client"), BUSINESS("Business"), PARTNER("Partner"), FRIEND("Friend"), FAMILY("Family"),
    GROUP("Group"), UNKNOWN("Unknown");

    val isProfessional: Boolean get() = this == CLIENT || this == BUSINESS
    val apiValue: String get() = name.lowercase()

    companion object {
        /** Relationships the user can assign to a contact. */
        val assignable = listOf(CLIENT, BUSINESS, PARTNER, FRIEND, FAMILY)
    }
}

enum class ReplyMode { AUTO, APPROVE }

enum class ConversationStatus {
    ANALYZING, PENDING_APPROVAL, QUEUED, WAITING, CALLBACK, CRISIS, ENDED, SKIPPED;

    val isOpen: Boolean get() = this != ENDED && this != SKIPPED
}

enum class Script(val label: String) { ROMAN("English letters"), NATIVE("Native script") }

enum class LanguageCode(val label: String) {
    EN("English"), TE("Telugu"), HI("Hindi"), TA("Tamil"), KN("Kannada");

    val apiValue: String get() = name.lowercase()

    companion object {
        fun fromApi(value: String?): LanguageCode = entries.firstOrNull { it.apiValue == value } ?: EN
    }
}

data class Language(val code: LanguageCode, val script: Script) {
    val label: String
        get() = if (code == LanguageCode.EN) "English" else "${code.label} · ${if (script == Script.NATIVE) "native script" else "English letters"}"
}

enum class ReplyStyle(val label: String) {
    PROFESSIONAL("Professional"), CASUAL("Casual"), FRIENDLY("Friendly"), ROMANTIC("Romantic"),
    FLIRTY("Flirty"), COMEBACK("Playful comeback"), SUPPORTIVE("Supportive");

    val apiValue: String get() = name.lowercase()

    companion object {
        fun fromApi(value: String?): ReplyStyle = entries.firstOrNull { it.apiValue == value } ?: FRIENDLY
    }
}

enum class ConversationState { ONGOING, WRAPPING_UP, ENDED }

enum class MoneyMode(val label: String) {
    SAFE_REPLY("Reply without agreeing, alert me"), ASK("Ask me first")
}

enum class Gender(val label: String) { MALE("Male"), FEMALE("Female") }

enum class VehicleType(val label: String) { BIKE("Bike"), CAR("Car") }

enum class SituationStatus(val emoji: String, val label: String) {
    AVAILABLE("🟢", "Available"),
    DRIVING("🚗", "Driving"),
    RIDING("🏍️", "Riding a bike"),
    INTERVIEW("💼", "In an interview"),
    MEETING("📅", "In a meeting"),
    GAMING("🎮", "Gaming"),
    SLEEPING("😴", "Sleeping"),
    BUSY("⏳", "Busy");

    val apiValue: String get() = name.lowercase()
}

enum class MessageKind { TEXT, CALL, CALL_REPLY }

enum class StartSource { MANUAL, AUTO }

enum class AlertKind { NEW_MAIL, APPROVAL, SENT, CRISIS, CALLBACK, ENDED, FOLLOW_UP, ERROR }
