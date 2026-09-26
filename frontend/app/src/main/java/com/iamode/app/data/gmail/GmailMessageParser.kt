package com.iamode.app.data.gmail

import android.util.Base64

data class ParsedMail(
    val gmailId: String,
    val threadId: String,
    val fromName: String?,
    val fromEmail: String,
    val subject: String,
    val body: String,
    val messageIdHeader: String?,
    val references: String?,
    val timestamp: Long,
    val automated: Boolean,
    // Signals for email intelligence (headers and structure, never used to act on their own)
    val replyTo: String? = null,
    val listUnsubscribe: String? = null,
    val autoSubmitted: Boolean = false,
    val precedenceBulk: Boolean = false,
    val gmailCategory: String? = null,
    val linkDomains: List<String> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    val labelIds: List<String> = emptyList(),
)

object GmailMessageParser {

    private val FROM = Regex("""^\s*"?([^"<]*?)"?\s*<([^>]+)>\s*$""")

    fun parse(m: GmailMessage): ParsedMail {
        val headers = m.payload?.headers.orEmpty().associate { it.name.lowercase() to it.value }
        val from = headers["from"].orEmpty()
        val match = FROM.find(from)
        val email = (match?.groupValues?.get(2) ?: from).trim().lowercase()
        val name = match?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val automated = headers.containsKey("list-unsubscribe") ||
            headers["auto-submitted"]?.lowercase()?.let { it != "no" } == true ||
            headers["precedence"]?.lowercase() in setOf("bulk", "list", "junk") ||
            Regex("no-?reply|mailer-daemon|notifications?@").containsMatchIn(email) ||
            m.labelIds.any { it in setOf("CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_FORUMS", "SPAM") }
        val rawBody = bodyText(m.payload) ?: m.snippet
        val html = htmlPart(m.payload)
        return ParsedMail(
            gmailId = m.id, threadId = m.threadId, fromName = name, fromEmail = email,
            subject = headers["subject"].orEmpty(),
            body = stripQuoted(rawBody),
            messageIdHeader = headers["message-id"], references = headers["references"],
            timestamp = m.internalDate?.toLongOrNull() ?: System.currentTimeMillis(),
            automated = automated,
            replyTo = headers["reply-to"]?.let { FROM.find(it)?.groupValues?.get(2) ?: it }?.trim()?.lowercase(),
            listUnsubscribe = headers["list-unsubscribe"],
            autoSubmitted = headers["auto-submitted"]?.lowercase()?.let { it != "no" } == true,
            precedenceBulk = headers["precedence"]?.lowercase() in setOf("bulk", "list", "junk"),
            gmailCategory = m.labelIds.firstOrNull { it.startsWith("CATEGORY_") },
            linkDomains = linkDomains(rawBody + "\n" + html.orEmpty()),
            attachmentNames = attachments(m.payload),
            labelIds = m.labelIds,
        )
    }

    private fun bodyText(part: MessagePart?): String? {
        if (part == null) return null
        if (part.mimeType == "text/plain") part.body?.data?.let { return decode(it) }
        part.parts.forEach { child -> bodyText(child)?.let { return it } }
        if (part.mimeType == "text/html") part.body?.data?.let { return htmlToText(decode(it)) }
        return null
    }

    private val LINK = Regex("""https?://([a-z0-9.-]+\.[a-z]{2,})""", RegexOption.IGNORE_CASE)

    private fun linkDomains(text: String): List<String> =
        LINK.findAll(text).map { it.groupValues[1].lowercase().removePrefix("www.") }.distinct().take(30).toList()

    private fun htmlPart(part: MessagePart?): String? {
        if (part == null) return null
        if (part.mimeType == "text/html") part.body?.data?.let { return decode(it) }
        part.parts.forEach { child -> htmlPart(child)?.let { return it } }
        return null
    }

    /** Attachment file names only (never downloaded). */
    private fun attachments(part: MessagePart?): List<String> {
        if (part == null) return emptyList()
        val own = part.headers.firstOrNull { it.name.equals("Content-Disposition", true) }?.value
            ?.let { Regex("""filename\*?=("?)([^";]+)\1""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(2) }
        return listOfNotNull(own) + part.parts.flatMap { attachments(it) }
    }

    private fun decode(data: String) = String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)

    private fun htmlToText(html: String) = html
        .replace(Regex("(?is)<(script|style).*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    /** Removes the quoted earlier email ("On Mon, X wrote:" and "> ..." lines). */
    /** Shared with the Outlook provider: drops quoted replies and signatures. */
    fun stripQuoted(text: String): String {
        val lines = text.lines()
        val cut = lines.indexOfFirst { Regex("^On .+wrote:\\s*$").matches(it.trim()) || it.trim() == "-----Original Message-----" }
        return (if (cut > 0) lines.take(cut) else lines)
            .filterNot { it.trimStart().startsWith(">") }
            .joinToString("\n").trim().take(4000)
    }
}
