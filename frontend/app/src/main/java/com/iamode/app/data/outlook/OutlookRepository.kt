package com.iamode.app.data.outlook

import android.util.Base64
import com.iamode.app.core.database.dao.OutlookAccountDao
import com.iamode.app.core.di.GmailClient
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.core.notifications.AppNotifier
import com.iamode.app.data.gmail.GmailMessageParser
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.data.gmail.ParsedMail
import com.iamode.app.domain.mail.MailIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outlook / Microsoft 365 mail through Microsoft Graph. Mirrors what IA Mode does with Gmail, so the whole
 * workflow (understanding, documents, interviews, tracker, approvals) works for both.
 * Every request asks for immutable IDs, so a message keeps its ID after it's archived or moved.
 */
@Singleton
class OutlookRepository @Inject constructor(
    private val auth: OutlookAuth,
    private val accounts: OutlookAccountDao,
    @GmailClient private val http: OkHttpClient,
    private val notifier: AppNotifier,
    private val log: DiagnosticsLog,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private class GraphError(val code: Int, message: String) : IOException(message)

    // ------------------------------------------------------------------ low level
    private suspend fun call(
        account: String, method: String, path: String, body: JsonElement? = null, query: Map<String, String> = emptyMap(),
        textBody: Boolean = false,
    ): JsonObject? = withContext(Dispatchers.IO) {
        val token = auth.token(account) ?: throw GraphError(401, "Reconnect $account")
        val url = (if (path.startsWith("http")) path else "$GRAPH$path").toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val prefer = if (textBody) """IdType="ImmutableId", outlook.body-content-type="text"""" else """IdType="ImmutableId""""
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Prefer", prefer)
            .method(method, body?.toString()?.toRequestBody(jsonType) ?: if (method == "POST") "{}".toRequestBody(jsonType) else null)
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) auth.invalidate(account)
            if (!r.isSuccessful) throw GraphError(r.code, "Graph ${r.code}")
            val text = r.body?.string().orEmpty()
            if (text.isBlank()) null else kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty()
    private fun JsonObject.address(k: String) = obj(k)?.obj("emailAddress")

    // ------------------------------------------------------------------ reading
    /** New inbox mail from every connected Outlook account, in the same shape as Gmail's. */
    suspend fun fetchForIntelligence(limit: Int, newerThanDays: Int, isKnown: suspend (String) -> Boolean): List<GmailRepository.IntelligenceMail> {
        val out = mutableListOf<GmailRepository.IntelligenceMail>()
        for (acc in accounts.all()) {
            if (out.size >= limit) break
            try {
                val since = Instant.now().minus(newerThanDays.toLong(), ChronoUnit.DAYS).toString()
                val list = call(acc.email, "GET", "/me/mailFolders/inbox/messages", query = mapOf(
                    "\$top" to "40", "\$orderby" to "receivedDateTime desc", "\$filter" to "receivedDateTime ge $since",
                    "\$select" to "id",
                ))
                for (m in list?.arr("value").orEmpty()) {
                    if (out.size >= limit) break
                    val id = MailIds.outlook((m as JsonObject).str("id") ?: continue)
                    if (isKnown(id)) continue
                    fetchEmail(acc.email, id)?.let { if (it.mail.fromEmail != acc.email) out += it }
                }
                if (acc.needsReauth) accounts.upsert(acc.copy(needsReauth = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GraphError) {
                if (e.code == 401 || e.code == 403) {
                    if (!acc.needsReauth) { accounts.upsert(acc.copy(needsReauth = true)); notifier.outlookReauthNeeded(acc.email) }
                } else log.record("Mail", false, "${acc.email}: Outlook returned ${e.code}")
            } catch (e: IOException) {
                log.record("Mail", false, "${acc.email}: network error while reading Outlook")
            }
        }
        return out
    }

    /** One message with body (plain text), header signals and thread context. Not stored. */
    suspend fun fetchEmail(account: String, emailId: String): GmailRepository.IntelligenceMail? = runCatching {
        val raw = MailIds.raw(emailId)
        val m = call(account, "GET", "/me/messages/$raw", textBody = true, query = mapOf("\$select" to
            "id,conversationId,subject,from,replyTo,receivedDateTime,body,internetMessageId,internetMessageHeaders,hasAttachments")) ?: return null
        val parsed = toParsed(m, attachments = if (m.str("hasAttachments") == "true") attachmentNames(account, raw) else emptyList())
        GmailRepository.IntelligenceMail(account, parsed, threadTexts(account, m.str("conversationId"), raw, parsed.timestamp))
    }.getOrNull()

    private suspend fun attachmentNames(account: String, raw: String): List<String> = runCatching {
        call(account, "GET", "/me/messages/$raw/attachments", query = mapOf("\$select" to "name"))
            ?.arr("value").orEmpty().mapNotNull { (it as JsonObject).str("name") }.take(20)
    }.getOrDefault(emptyList())

    private suspend fun threadTexts(account: String, conversationId: String?, exclude: String, before: Long): List<String> = runCatching {
        conversationId ?: return emptyList()
        call(account, "GET", "/me/messages", textBody = true, query = mapOf(
            "\$filter" to "conversationId eq '${conversationId.replace("'", "''")}'", "\$select" to "id,from,body,receivedDateTime", "\$top" to "8",
        ))?.arr("value").orEmpty().map { it as JsonObject }
            .filter { it.str("id") != exclude && (it.str("receivedDateTime")?.let(Instant::parse)?.toEpochMilli() ?: 0) < before }
            .sortedBy { it.str("receivedDateTime") }.takeLast(4)
            .map { "${it.address("from")?.str("name") ?: it.address("from")?.str("address")}: ${GmailMessageParser.stripQuoted(it.obj("body")?.str("content").orEmpty()).take(800)}" }
    }.getOrDefault(emptyList())

    /** The user's own recent sent mail (bodies only) for on-device style learning. */
    suspend fun fetchSentBodies(limit: Int): List<String> {
        val out = mutableListOf<String>()
        for (acc in accounts.all()) {
            runCatching {
                call(acc.email, "GET", "/me/mailFolders/sentitems/messages", textBody = true, query = mapOf("\$top" to "$limit", "\$select" to "body"))
                    ?.arr("value").orEmpty().forEach { if (out.size < limit) out += (it as JsonObject).obj("body")?.str("content").orEmpty() }
            }
            if (out.size >= limit) break
        }
        return out
    }

    private fun toParsed(m: JsonObject, attachments: List<String>): ParsedMail {
        val headers = m.arr("internetMessageHeaders").associate { h -> (h as JsonObject).str("name").orEmpty().lowercase() to h.str("value").orEmpty() }
        val from = m.address("from")
        val fromEmail = from?.str("address").orEmpty().lowercase()
        val body = m.obj("body")?.str("content").orEmpty()
        val replyTo = m.arr("replyTo").firstOrNull()?.let { (it as JsonObject).obj("emailAddress")?.str("address")?.lowercase() }
        val autoSubmitted = headers["auto-submitted"]?.lowercase()?.let { it != "no" } == true
        val bulk = headers["precedence"]?.lowercase() in setOf("bulk", "list", "junk")
        return ParsedMail(
            gmailId = MailIds.outlook(m.str("id").orEmpty()),
            threadId = MailIds.outlook(m.str("conversationId") ?: m.str("id").orEmpty()),
            fromName = from?.str("name"), fromEmail = fromEmail, subject = m.str("subject").orEmpty(),
            body = GmailMessageParser.stripQuoted(body).take(20_000),
            messageIdHeader = m.str("internetMessageId"), references = headers["references"],
            timestamp = m.str("receivedDateTime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis(),
            automated = autoSubmitted || bulk || Regex("(^|[._-])(no-?reply|do-?not-?reply)").containsMatchIn(fromEmail.substringBefore('@')),
            replyTo = replyTo, listUnsubscribe = headers["list-unsubscribe"], autoSubmitted = autoSubmitted, precedenceBulk = bulk,
            gmailCategory = null,
            linkDomains = Regex("""https?://([a-z0-9.-]+\.[a-z]{2,})""", RegexOption.IGNORE_CASE).findAll(body)
                .map { it.groupValues[1].lowercase().removePrefix("www.") }.distinct().take(30).toList(),
            attachmentNames = attachments,
        )
    }

    // ------------------------------------------------------------------ sending
    /**
     * Replies in the same conversation. Idempotent across crashes: the reply draft's ID is stored before sending
     * ([draftId]/[saveDraftId]); on retry, if the draft is gone it was already sent, so nothing is sent twice.
     */
    suspend fun sendReply(
        mail: GmailRepository.OutgoingMail, emailId: String, draftId: String?, saveDraftId: suspend (String) -> Unit,
    ): GmailRepository.SendOutcome {
        val acc = mail.accountEmail
        return try {
            var draft = draftId
            if (draft != null) {
                val exists = try { call(acc, "GET", "/me/messages/$draft", query = mapOf("\$select" to "id,isDraft"))?.str("isDraft") == "true" }
                    catch (e: GraphError) { if (e.code == 404) false else throw e }
                if (!exists) return GmailRepository.SendOutcome.Sent(draft) // already sent by an earlier attempt
            } else {
                draft = call(acc, "POST", "/me/messages/${MailIds.raw(emailId)}/createReply")?.str("id")
                    ?: return GmailRepository.SendOutcome.Failed("Outlook didn't create the reply")
                saveDraftId(draft)
                call(acc, "PATCH", "/me/messages/$draft", buildJsonObject {
                    put("subject", mail.subject)
                    putJsonObject("body") { put("contentType", "Text"); put("content", mail.body) }
                    putJsonArray("toRecipients") { add(buildJsonObject { putJsonObject("emailAddress") { put("address", mail.to) } }) }
                })
                for (a in mail.attachments) attach(acc, draft, a)
            }
            call(acc, "POST", "/me/messages/$draft/send")
            GmailRepository.SendOutcome.Sent(draft)
        } catch (e: GraphError) {
            GmailRepository.SendOutcome.Failed(when (e.code) {
                401, 403 -> "Outlook access expired. Reconnect in Settings"
                413 -> "The attachment is too large for Outlook"
                429 -> "Outlook is busy. Try again in a minute"
                else -> "Outlook returned an error (${e.code})"
            })
        } catch (e: IOException) {
            GmailRepository.SendOutcome.Failed("No connection. Nothing was sent")
        }
    }

    /** Small files go inline; larger ones (over 3 MB) use an upload session in 3.75 MB chunks. */
    private suspend fun attach(acc: String, draft: String, a: GmailRepository.OutgoingAttachment) {
        if (a.bytes.size <= 3 * 1024 * 1024) {
            call(acc, "POST", "/me/messages/$draft/attachments", buildJsonObject {
                put("@odata.type", "#microsoft.graph.fileAttachment"); put("name", a.name); put("contentType", a.mimeType)
                put("contentBytes", Base64.encodeToString(a.bytes, Base64.NO_WRAP))
            })
            return
        }
        val session = call(acc, "POST", "/me/messages/$draft/attachments/createUploadSession", buildJsonObject {
            putJsonObject("AttachmentItem") { put("attachmentType", "file"); put("name", a.name); put("size", a.bytes.size) }
        })?.str("uploadUrl") ?: throw GraphError(500, "No upload session")
        withContext(Dispatchers.IO) {
            val chunk = 12 * 320 * 1024 // multiple of 320 KiB, as Graph requires
            var start = 0
            while (start < a.bytes.size) {
                val end = minOf(start + chunk, a.bytes.size)
                val part = a.bytes.copyOfRange(start, end)
                val req = Request.Builder().url(session)
                    .header("Content-Range", "bytes $start-${end - 1}/${a.bytes.size}")
                    .put(part.toRequestBody("application/octet-stream".toMediaType())).build()
                http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw GraphError(r.code, "Upload failed") }
                start = end
            }
        }
    }

    // ------------------------------------------------------------------ mailbox
    sealed interface Outcome { data object Done : Outcome; data class Failed(val reason: String) : Outcome }

    private suspend fun safely(block: suspend () -> Unit): Outcome = try { block(); Outcome.Done }
    catch (e: GraphError) { Outcome.Failed(when (e.code) { 401, 403 -> "Reconnect Outlook in Settings"; 404 -> "This email no longer exists"; else -> "Outlook returned ${e.code}" }) }
    catch (e: IOException) { Outcome.Failed("No connection. Nothing changed in Outlook") }

    suspend fun archive(account: String, emailId: String) = move(account, emailId, "archive")
    suspend fun moveToInbox(account: String, emailId: String) = move(account, emailId, "inbox")
    suspend fun markRead(account: String, emailId: String) = safely {
        call(account, "PATCH", "/me/messages/${MailIds.raw(emailId)}", buildJsonObject { put("isRead", true) })
    }

    private suspend fun move(account: String, emailId: String, destination: String) = safely {
        call(account, "POST", "/me/messages/${MailIds.raw(emailId)}/move", buildJsonObject { put("destinationId", destination) })
    }

    /** "Move to" in Outlook terms: a folder (created if missing). */
    suspend fun moveToFolder(account: String, emailId: String, folderName: String) = safely {
        val id = folderId(account, folderName) ?: call(account, "POST", "/me/mailFolders", buildJsonObject { put("displayName", folderName.take(200)) })?.str("id")
            ?: throw GraphError(500, "Folder not created")
        call(account, "POST", "/me/messages/${MailIds.raw(emailId)}/move", buildJsonObject { put("destinationId", id) })
    }

    /** Opt-in labels map to Outlook categories (never moves the message). */
    suspend fun addCategory(account: String, emailId: String, category: String) = safely {
        val raw = MailIds.raw(emailId)
        val current = call(account, "GET", "/me/messages/$raw", query = mapOf("\$select" to "categories"))
            ?.arr("categories").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (category !in current) call(account, "PATCH", "/me/messages/$raw", buildJsonObject {
            put("categories", buildJsonArray { (current + category).forEach { add(JsonPrimitive(it)) } })
        })
    }

    suspend fun folders(account: String): List<String> = runCatching {
        call(account, "GET", "/me/mailFolders", query = mapOf("\$top" to "100", "\$select" to "displayName"))
            ?.arr("value").orEmpty().mapNotNull { (it as JsonObject).str("displayName") }
            .filter { it.lowercase() !in setOf("inbox", "drafts", "sent items", "deleted items", "junk email", "outbox", "conversation history") }.sorted()
    }.getOrDefault(emptyList())

    private suspend fun folderId(account: String, name: String): String? =
        call(account, "GET", "/me/mailFolders", query = mapOf("\$filter" to "displayName eq '${name.replace("'", "''")}'", "\$select" to "id"))
            ?.arr("value")?.firstOrNull()?.let { (it as JsonObject).str("id") }

    // ------------------------------------------------------------------ accounts
    suspend fun addAccount(email: String, homeAccountId: String) =
        accounts.upsert(com.iamode.app.core.database.entity.OutlookAccountEntity(email, homeAccountId, false, System.currentTimeMillis()))

    suspend fun removeAccount(email: String) { auth.signOut(email); accounts.delete(email) }

    val connectedAccounts = accounts.observeAll()

    private companion object { const val GRAPH = "https://graph.microsoft.com/v1.0" }
}
