package com.iamode.app.data.gmail

import android.util.Base64
import com.iamode.app.core.di.GmailClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.core.database.entity.GmailAccountEntity
import com.iamode.app.core.datastore.SeenMessageStore
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.ContextMessage
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.GmailMeta
import com.iamode.app.domain.model.IncomingMessage
import com.iamode.app.domain.model.SendResult
import com.iamode.app.domain.repository.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GmailRepository @Inject constructor(
    private val api: GmailApi,
    private val auth: GmailAuthManager,
    private val accounts: GmailAccountDao,
    private val seen: SeenMessageStore,
    private val notifier: Notifier,
    private val log: DiagnosticsLog,
    @GmailClient private val http: OkHttpClient,
) {
    val connectedAccounts = accounts.observeAll()

    /** Called after the user finishes the Google consent screen. */
    suspend fun addAccount(accessToken: String): String {
        val email = api.profile(bearer(accessToken)).emailAddress.lowercase()
        auth.remember(email, accessToken)
        accounts.upsert(GmailAccountEntity(email, needsReauth = false, addedAt = System.currentTimeMillis()))
        return email
    }

    suspend fun removeAccount(email: String) {
        auth.invalidate(email)
        accounts.delete(email)
    }

    /** New, human-sent inbox mail received after [sinceMillis] (when IA Mode was turned on). */
    suspend fun fetchNew(sinceMillis: Long): List<IncomingMessage> {
        val out = mutableListOf<IncomingMessage>()
        for (account in accounts.all()) {
            val token = auth.accessToken(account.email)
            if (token == null) {
                log.record("Gmail", false, "${account.email}: no access. Reconnect it in Settings")
                if (!account.needsReauth) {
                    accounts.upsert(account.copy(needsReauth = true))
                    notifier.gmailReauthNeeded(account.email)
                }
                continue
            }
            if (account.needsReauth) accounts.upsert(account.copy(needsReauth = false))
            try {
                val query = "in:inbox is:unread after:${sinceMillis / 1000} -category:promotions -category:social -from:me"
                val refs = api.list(bearer(token), query).messages
                val before = out.size
                for (ref in refs.reversed()) {
                    if (!seen.markIfNew("gmail:${ref.id}")) continue
                    val mail = GmailMessageParser.parse(api.get(bearer(token), ref.id))
                    if (mail.automated || mail.fromEmail == account.email || mail.timestamp < sinceMillis) continue
                    out += IncomingMessage(
                        channel = Channel.GMAIL, address = mail.fromEmail,
                        displayName = mail.fromName ?: mail.fromEmail, text = mail.body,
                        externalId = "gmail:${mail.gmailId}", timestamp = mail.timestamp, subject = mail.subject,
                        gmail = GmailMeta(
                            accountEmail = account.email, threadId = mail.threadId,
                            messageIdHeader = mail.messageIdHeader, references = mail.references,
                        ),
                        context = threadContext(token, mail, account.email),
                    )
                }
                log.record("Gmail", true, "${account.email}: checked, ${out.size - before} new mail to answer")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 401) auth.invalidate(account.email)
                val hint = when (e.code()) {
                    401 -> "access expired, will re-authorize"
                    403 -> "Gmail API not enabled in Google Cloud, or this account isn't a test user"
                    else -> "Gmail returned an error"
                }
                log.record("Gmail", false, "${account.email}: HTTP ${e.code()}, $hint")
            } catch (e: java.io.IOException) {
                log.record("Gmail", false, "${account.email}: network error, ${e.javaClass.simpleName}")
            }
        }
        return out
    }

    /** Earlier messages in the thread, used only as context for the AI. */
    private suspend fun threadContext(token: String, mail: ParsedMail, me: String): List<ContextMessage> =
        runCatching {
            api.thread(bearer(token), mail.threadId).messages
                .map(GmailMessageParser::parse)
                .filter { it.gmailId != mail.gmailId && it.timestamp < mail.timestamp }
                .takeLast(6)
                .map { ContextMessage(it.fromEmail == me, it.body.take(1500), it.timestamp, "gmail:${it.gmailId}") }
        }.getOrDefault(emptyList())

    suspend fun sendReply(conversation: Conversation, text: String): SendResult {
        val account = conversation.gmailAccount ?: return SendResult.Failed("No Gmail account for this thread")
        val token = auth.accessToken(account) ?: return SendResult.Failed("Reconnect $account in Settings")
        return try {
            api.send(bearer(token), SendRequest(raw = buildMime(conversation, text), threadId = conversation.gmailThreadId))
            SendResult.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? HttpException)?.code()
            log.record("Gmail", false, "Send to thread failed: ${code?.let { "HTTP $it" } ?: e.javaClass.simpleName}")
            SendResult.Failed(if (code == 403) "Gmail send permission missing. Reconnect Gmail in Settings" else "Gmail didn't accept the message")
        }
    }

    private fun buildMime(c: Conversation, body: String): String {
        val subject = c.subject.orEmpty().let { if (it.startsWith("re:", ignoreCase = true)) it else "Re: $it" }
        val mime = buildString {
            append("To: ${c.address}\r\n")
            append("Subject: =?UTF-8?B?${b64(subject.toByteArray())}?=\r\n")
            c.gmailMessageId?.let { id ->
                append("In-Reply-To: $id\r\n")
                append("References: ${listOfNotNull(c.gmailReferences, id).joinToString(" ")}\r\n")
            }
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
        }
        return Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // =====================================================================================
    // Email intelligence: read-only fetches + approved sends. No method here decides anything.
    // =====================================================================================

    data class IntelligenceMail(val accountEmail: String, val mail: ParsedMail, val threadContext: List<String>)
    data class HistoryCheckpoint(val accountEmail: String, val historyId: String)
    data class IntelligenceFetch(val messages: List<IntelligenceMail>, val checkpoints: List<HistoryCheckpoint>)
    private data class InboxScan(val refs: List<MessageRef>, val historyId: String?)

    /**
     * Recent inbox mail (all tabs, incl. promotions) not yet understood. [isKnown] skips mail already classified,
     * so each email costs at most one AI call.
     */
    suspend fun fetchForIntelligence(limit: Int, newerThanDays: Int, isKnown: suspend (String) -> Boolean): IntelligenceFetch {
        val out = mutableListOf<IntelligenceMail>()
        val checkpoints = mutableListOf<HistoryCheckpoint>()
        for (account in accounts.all()) {
            if (out.size >= limit) break
            val token = auth.accessToken(account.email)
            if (token == null) {
                if (!account.needsReauth) {
                    accounts.upsert(account.copy(needsReauth = true))
                    notifier.gmailReauthNeeded(account.email)
                }
                continue
            }
            if (account.needsReauth) accounts.upsert(account.copy(needsReauth = false))
            try {
                val source = if (account.historyId == null) {
                    // Bootstrap reads metadata for the configured recent window. Once those messages are understood,
                    // profile.historyId becomes the durable incremental checkpoint.
                    inboxScan(token, newerThanDays)
                } else {
                    try {
                        historyScan(token, account.historyId)
                    } catch (e: HttpException) {
                        if (e.code() != 404) throw e
                        // Gmail retains history for a limited period. Fall back safely to the bounded
                        // bootstrap scan; never advance a cursor that Gmail has invalidated.
                        accounts.upsert(account.copy(needsReauth = false, historyId = null))
                        inboxScan(token, newerThanDays)
                    }
                }
                val unseen = source.refs.filterNot { isKnown(it.id) }
                val capacity = limit - out.size
                // Body + thread-context retrieval dominates mailbox latency. Keep a small
                // bounded fan-out: it is much faster than serial fetches without bursting
                // Gmail quotas or starving other accounts.
                val fetched = coroutineScope {
                    val gate = Semaphore(3)
                    unseen.take(capacity).map { ref -> async {
                        gate.withPermit {
                            try {
                                val mail = GmailMessageParser.parse(api.get(bearer(token), ref.id))
                                if (mail.fromEmail == account.email) null
                                else IntelligenceMail(account.email, mail, threadTexts(token, mail))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.record("Mail", false, "${account.email}: couldn't fetch ${ref.id.take(12)}")
                                null
                            }
                        }
                    } }.awaitAll()
                }
                out += fetched.filterNotNull()
                // Do not checkpoint a partial batch. A later worker will retry the same history
                // range and skip already-classified IDs, so transient AI/network errors cannot lose mail.
                if (unseen.size <= capacity) source.historyId?.let { checkpoints += HistoryCheckpoint(account.email, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                log.record("Mail", false, "${account.email}: HTTP ${e.code()} while reading inbox")
            } catch (e: java.io.IOException) {
                log.record("Mail", false, "${account.email}: network error while reading inbox")
            }
        }
        return IntelligenceFetch(out, checkpoints)
    }

    /** Retrieves lightweight IDs only; bodies are fetched below solely for unseen messages. */
    private suspend fun inboxScan(token: String, newerThanDays: Int): InboxScan {
        val refs = mutableListOf<MessageRef>()
        var page: String? = null
        do {
            val response = api.list(bearer(token), "in:inbox newer_than:${newerThanDays}d", maxResults = 100, pageToken = page)
            refs += response.messages
            page = response.nextPageToken
        } while (page != null)
        return InboxScan(refs.distinctBy { it.id }, api.profile(bearer(token)).historyId)
    }

    /** Reads all changed message IDs before creating a checkpoint, so pagination cannot skip mail. */
    private suspend fun historyScan(token: String, startHistoryId: String): InboxScan {
        val refs = mutableListOf<MessageRef>()
        var page: String? = null
        var checkpoint: String? = null
        do {
            val response = api.history(bearer(token), startHistoryId, maxResults = 500, pageToken = page)
            refs += response.history.flatMap { h -> h.messagesAdded.mapNotNull { it.message } }
            checkpoint = response.historyId ?: checkpoint
            page = response.nextPageToken
        } while (page != null)
        return InboxScan(refs.distinctBy { it.id }, checkpoint)
    }

    /** Commits only a checkpoint whose complete batch was classified successfully. */
    suspend fun commitHistory(checkpoint: HistoryCheckpoint) {
        val account = accounts.all().firstOrNull { it.email == checkpoint.accountEmail } ?: return
        accounts.upsert(account.copy(historyId = checkpoint.historyId, needsReauth = false))
    }

    /** The user's own sent emails (bodies only, newest first), for on-device style learning. Not stored. */
    suspend fun fetchSentBodies(limit: Int): List<String> {
        val out = mutableListOf<String>()
        for (account in accounts.all()) {
            val token = auth.accessToken(account.email) ?: continue
            runCatching {
                api.list(bearer(token), "in:sent newer_than:180d -in:chats", maxResults = limit).messages.forEach { ref ->
                    if (out.size < limit) out += GmailMessageParser.parse(api.get(bearer(token), ref.id)).body
                }
            }
            if (out.size >= limit) break
        }
        return out
    }

    /** Body fetched on demand (for drafting a reply); it isn't stored. */
    suspend fun fetchEmail(accountEmail: String, gmailId: String): IntelligenceMail? {
        val token = auth.accessToken(accountEmail) ?: return null
        return runCatching {
            val mail = GmailMessageParser.parse(api.get(bearer(token), gmailId))
            IntelligenceMail(accountEmail, mail, threadTexts(token, mail))
        }.getOrNull()
    }

    private suspend fun threadTexts(token: String, mail: ParsedMail): List<String> = runCatching {
        api.thread(bearer(token), mail.threadId).messages.map(GmailMessageParser::parse)
            .filter { it.gmailId != mail.gmailId && it.timestamp < mail.timestamp }
            .takeLast(4).map { "${it.fromName ?: it.fromEmail}: ${it.body.take(800)}" }
    }.getOrDefault(emptyList())

    data class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray)

    data class OutgoingMail(
        val accountEmail: String,
        val to: String,
        val subject: String,
        val body: String,
        val threadId: String?,
        val inReplyTo: String?,
        val references: String?,
        /** Our own Message-ID, so a retry can check whether the mail already went out. */
        val messageIdHeader: String,
        val attachments: List<OutgoingAttachment>,
    )

    sealed interface SendOutcome {
        data class Sent(val gmailId: String) : SendOutcome
        data class Failed(val reason: String) : SendOutcome
    }

    /** True if a message with this Message-ID is already in the account (sent by an earlier attempt). */
    suspend fun alreadySent(accountEmail: String, messageIdHeader: String): Boolean {
        val token = auth.accessToken(accountEmail) ?: return false
        return runCatching {
            api.list(bearer(token), "in:sent rfc822msgid:${messageIdHeader.trim('<', '>')}", maxResults = 1).messages.isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Sends through Gmail's upload endpoint (multipart/related), which accepts attachments up to Gmail's size limit.
     * Called only by MailActionExecutor after the policy engine allowed it.
     */
    suspend fun sendMail(mail: OutgoingMail): SendOutcome = withContext(Dispatchers.IO) {
        val token = auth.accessToken(mail.accountEmail)
            ?: return@withContext SendOutcome.Failed("Reconnect ${mail.accountEmail} in Settings")
        val mime = buildMimeWithAttachments(mail)
        val metadata = buildString {
            append("{")
            if (mail.threadId != null) append("\"threadId\":\"").append(mail.threadId.replace("\"", "")).append("\"")
            append("}")
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(mime.toRequestBody("message/rfc822".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send?uploadType=multipart")
            .header("Authorization", bearer(token))
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    log.record("Mail", false, "Send failed: HTTP ${r.code}")
                    return@withContext SendOutcome.Failed(when (r.code) {
                        401 -> "Gmail access expired. Reconnect in Settings"
                        403 -> "Gmail didn't allow sending. Reconnect Gmail in Settings"
                        413 -> "The attachment is too large for Gmail"
                        else -> "Gmail returned an error (${r.code})"
                    })
                }
                val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1).orEmpty()
                SendOutcome.Sent(id)
            }
        } catch (e: java.io.IOException) {
            SendOutcome.Failed("No connection. Nothing was sent")
        }
    }

    private fun buildMimeWithAttachments(m: OutgoingMail): ByteArray {
        val boundary = "ia_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val sb = StringBuilder()
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("To: ${m.to}\r\n")
        sb.append("Subject: =?UTF-8?B?${b64(m.subject.toByteArray())}?=\r\n")
        sb.append("Message-ID: ${m.messageIdHeader}\r\n")
        m.inReplyTo?.let { id ->
            sb.append("In-Reply-To: $id\r\n")
            sb.append("References: ${listOfNotNull(m.references, id).joinToString(" ")}\r\n")
        }
        val text = Base64.encodeToString(m.body.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        if (m.attachments.isEmpty()) {
            sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n").append(text)
            return sb.toString().toByteArray(Charsets.UTF_8)
        }
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
        sb.append("--$boundary\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n")
        sb.append(text).append("\r\n")
        m.attachments.forEach { a ->
            val safeName = a.name.replace(Regex("[\\r\\n\"]"), "_")
            val encodedName = java.net.URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: ${a.mimeType}; name=\"$safeName\"\r\n")
            sb.append("Content-Disposition: attachment; filename=\"$safeName\"; filename*=UTF-8''$encodedName\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
            sb.append(Base64.encodeToString(a.bytes, Base64.DEFAULT)).append("\r\n")
        }
        sb.append("--$boundary--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun bearer(token: String) = "Bearer $token"
}
