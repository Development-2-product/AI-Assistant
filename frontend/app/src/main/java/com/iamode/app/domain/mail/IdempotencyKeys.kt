package com.iamode.app.domain.mail

/** Every external effect has one key; the same key can only ever happen once. */
object IdempotencyKeys {
    fun proposal(type: MailActionType, emailId: String) = "proposal:${type.name.lowercase()}:$emailId"
    fun sendEmail(emailId: String, draftId: String) = "send_email:$emailId:$draftId"
    fun calendarEvent(emailId: String, eventHash: String) = "calendar_event:$emailId:$eventHash"
    fun celebration(emailId: String, type: CelebrationType) = "celebration:$emailId:${type.name.lowercase()}"

    /** Goes into the sent email's Message-ID so a retry can check Gmail before sending again. */
    fun messageIdHeader(actionId: String) = "<ia-$actionId@iamode.app>"

    /** Goes into the calendar event's UID so a retry finds the event instead of duplicating it. */
    fun calendarUid(eventHash: String) = "ia-$eventHash@iamode.app"
}
