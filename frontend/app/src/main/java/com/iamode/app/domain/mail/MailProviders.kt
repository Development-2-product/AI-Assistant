package com.iamode.app.domain.mail

enum class MailProviderKind(val label: String) { GMAIL("Gmail"), OUTLOOK("Outlook") }

/**
 * Message and thread IDs are stored with a provider prefix so every part of the workflow (sync, drafts,
 * sending, archive, tracker) routes to the right service. Gmail IDs keep their original form, so existing
 * data from earlier versions stays valid.
 */
object MailIds {
    private const val OUTLOOK = "ol:"

    fun providerOf(id: String): MailProviderKind = if (id.startsWith(OUTLOOK)) MailProviderKind.OUTLOOK else MailProviderKind.GMAIL
    fun outlook(rawId: String): String = if (rawId.startsWith(OUTLOOK)) rawId else OUTLOOK + rawId
    fun raw(id: String): String = id.removePrefix(OUTLOOK)
}
