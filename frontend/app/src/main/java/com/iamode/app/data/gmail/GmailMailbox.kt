package com.iamode.app.data.gmail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed, reversible Gmail mailbox operations (gmail.modify). No permanent deletion exists here.
 * Every operation is naturally idempotent: adding a label twice or removing a missing one is a no-op.
 * Callers (MailboxActions) decide when to run them and write the audit entry.
 */
@Singleton
class GmailMailbox @Inject constructor(
    private val api: GmailApi,
    private val auth: GmailAuthManager,
) {
    sealed interface Outcome {
        data object Done : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val labelCache = ConcurrentHashMap<String, Map<String, String>>() // account -> name(lowercase) -> id
    private val labelLock = Mutex()

    suspend fun archive(account: String, messageId: String) = modify(account, messageId, remove = listOf("INBOX"))
    suspend fun moveToInbox(account: String, messageId: String) = modify(account, messageId, add = listOf("INBOX"))
    suspend fun markRead(account: String, messageId: String) = modify(account, messageId, remove = listOf("UNREAD"))
    suspend fun markUnread(account: String, messageId: String) = modify(account, messageId, add = listOf("UNREAD"))

    /** "Move" in Gmail terms: add the label and take the message out of the inbox. */
    suspend fun moveToLabel(account: String, messageId: String, labelName: String): Outcome {
        val id = labelId(account, labelName, create = true) ?: return Outcome.Failed("Couldn't create the label \"$labelName\"")
        return modify(account, messageId, add = listOf(id), remove = listOf("INBOX"))
    }

    /** Adds a label without moving the message (used by the opt-in "IA Mode labels" setting). */
    suspend fun addLabel(account: String, messageId: String, labelName: String): Outcome {
        val id = labelId(account, labelName, create = true) ?: return Outcome.Failed("Couldn't create the label \"$labelName\"")
        return modify(account, messageId, add = listOf(id))
    }

    /** The user's own labels, for the "Move to" picker. */
    suspend fun userLabels(account: String): List<String> {
        val token = auth.accessToken(account) ?: return emptyList()
        return runCatching { api.labels("Bearer $token").labels.filter { it.type == "user" }.map { it.name }.sorted() }
            .getOrDefault(emptyList())
    }

    private suspend fun labelId(account: String, name: String, create: Boolean): String? = labelLock.withLock {
        val clean = name.trim().take(200)
        labelCache[account]?.get(clean.lowercase())?.let { return@withLock it }
        val token = auth.accessToken(account) ?: return@withLock null
        runCatching {
            val all = api.labels("Bearer $token").labels
            labelCache[account] = all.associate { it.name.lowercase() to it.id }
            all.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.id
                ?: if (create) api.createLabel("Bearer $token", CreateLabelRequest(clean)).id.also {
                    labelCache[account] = labelCache[account].orEmpty() + (clean.lowercase() to it)
                } else null
        }.getOrNull()
    }

    private suspend fun modify(account: String, messageId: String, add: List<String> = emptyList(), remove: List<String> = emptyList()): Outcome {
        val token = auth.accessToken(account) ?: return Outcome.Failed("Reconnect $account in Settings to allow Gmail changes")
        return try {
            api.modify("Bearer $token", messageId, ModifyRequest(add, remove))
            Outcome.Done
        } catch (e: HttpException) {
            if (e.code() == 401) auth.invalidate(account)
            Outcome.Failed(when (e.code()) {
                401, 403 -> "Gmail didn't allow this change. Reconnect Gmail in Settings"
                404 -> "This email no longer exists in Gmail"
                else -> "Gmail returned an error (${e.code()})"
            })
        } catch (e: java.io.IOException) {
            Outcome.Failed("No connection. Nothing changed in Gmail")
        }
    }
}
