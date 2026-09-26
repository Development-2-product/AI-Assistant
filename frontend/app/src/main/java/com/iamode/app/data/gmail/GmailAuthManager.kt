package com.iamode.app.data.gmail

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gmail access through Google Identity Services authorization.
 * Each Gmail account is authorized separately; access tokens live only in memory.
 */
@Singleton
class GmailAuthManager @Inject constructor(@ApplicationContext context: Context) {

    private val client = Identity.getAuthorizationClient(context)
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun request(email: String?, scopes: List<Scope>): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()

    /** Starts the account picker / consent flow. If [AuthorizationResult.hasResolution], launch its pendingIntent. */
    suspend fun beginAuthorization(): AuthorizationResult = client.authorize(request(null, SCOPES)).await()

    /** Optional, separate consent for Google Drive document search on an already-connected account. */
    suspend fun beginDriveAuthorization(email: String): AuthorizationResult = client.authorize(request(email, DRIVE_SCOPES)).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        client.getAuthorizationResultFromIntent(data ?: throw IllegalStateException("Sign-in was cancelled"))

    fun remember(email: String, token: String, scopes: List<Scope> = SCOPES) {
        cache[key(email, scopes)] = token to System.currentTimeMillis() + TOKEN_TTL_MS
    }

    /** Silent token refresh. Returns null if the user must (re-)consent, e.g. after the scope change in v1.5. */
    suspend fun accessToken(email: String, scopes: List<Scope> = SCOPES): String? {
        cache[key(email, scopes)]?.let { (token, expiry) -> if (expiry > System.currentTimeMillis()) return token }
        val result = runCatching { client.authorize(request(email, scopes)).await() }.getOrNull() ?: return null
        if (result.hasResolution()) return null
        val token = result.accessToken ?: return null
        remember(email, token, scopes)
        return token
    }

    suspend fun driveToken(email: String): String? = accessToken(email, DRIVE_SCOPES)

    fun invalidate(email: String) {
        cache.keys.filter { it.startsWith("$email|") }.forEach { cache.remove(it) }
    }

    private fun key(email: String, scopes: List<Scope>) = "$email|" + scopes.joinToString(",") { it.scopeUri }

    companion object {
        /**
         * One scope for everything IA Mode does in Gmail: read, send, labels, archive, mark read, move.
         * gmail.modify can't permanently delete mail. Replaces gmail.readonly + gmail.send (v1.4 and earlier),
         * so existing accounts are asked to reconnect once.
         */
        val SCOPES = listOf(Scope("https://www.googleapis.com/auth/gmail.modify"))

        /** Opt-in only: search and download files from Google Drive for document requests. */
        val DRIVE_SCOPES = listOf(Scope("https://www.googleapis.com/auth/drive.readonly"))
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L
    }
}
