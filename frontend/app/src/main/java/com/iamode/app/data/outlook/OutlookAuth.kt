package com.iamode.app.data.outlook

import android.app.Activity
import android.content.Context
import com.iamode.app.BuildConfig
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft sign-in for Outlook.com and Microsoft 365 accounts (MSAL, multiple accounts).
 * Refresh tokens live in MSAL's own encrypted cache; IA Mode keeps access tokens in memory only.
 * Configured from local.properties (see docs/OUTLOOK_SETUP.md); without it, [configured] is false.
 */
@Singleton
class OutlookAuth @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface SignIn {
        data class Success(val email: String, val homeAccountId: String) : SignIn
        data object Cancelled : SignIn
        data class Failed(val reason: String) : SignIn
    }

    val configured: Boolean get() = BuildConfig.MSAL_CLIENT_ID.isNotBlank() && BuildConfig.MSAL_SIGNATURE_HASH.isNotBlank()

    private val createLock = Mutex()
    @Volatile private var app: IMultipleAccountPublicClientApplication? = null
    private val tokens = ConcurrentHashMap<String, Pair<String, Long>>()

    private suspend fun client(): IMultipleAccountPublicClientApplication = createLock.withLock {
        app ?: withContext(Dispatchers.IO) {
            val config = File(context.filesDir, "msal_config.json").apply { writeText(configJson()) }
            suspendCancellableCoroutine { cont ->
                PublicClientApplication.createMultipleAccountPublicClientApplication(context, config,
                    object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                        override fun onCreated(application: IMultipleAccountPublicClientApplication) { cont.resume(application) }
                        override fun onError(exception: MsalException) { cont.resumeWithException(exception) }
                    })
            }
        }.also { app = it }
    }

    /** Interactive sign-in (account picker + consent). Must be started from an Activity. */
    suspend fun signIn(activity: Activity): SignIn {
        if (!configured) return SignIn.Failed("Outlook isn't set up in this build (see docs/OUTLOOK_SETUP.md)")
        val c = runCatching { client() }.getOrElse { return SignIn.Failed(it.message ?: "Microsoft sign-in couldn't start") }
        return suspendCancellableCoroutine { cont ->
            c.acquireToken(
                AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(SCOPES)
                    .withPrompt(Prompt.SELECT_ACCOUNT)
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(result: IAuthenticationResult) {
                            val email = result.account.username.lowercase()
                            remember(email, result)
                            cont.resume(SignIn.Success(email, result.account.id))
                        }
                        override fun onError(exception: MsalException) = cont.resume(SignIn.Failed(friendly(exception)))
                        override fun onCancel() = cont.resume(SignIn.Cancelled)
                    })
                    .build(),
            )
        }
    }

    /** Silent refresh. Null means the user must reconnect (password change, revoked consent, admin policy). */
    suspend fun token(email: String): String? {
        tokens[email]?.let { (t, exp) -> if (exp > System.currentTimeMillis() + 60_000) return t }
        if (!configured) return null
        return withContext(Dispatchers.IO) {
            try {
                val c = client()
                val account = accountFor(c, email) ?: return@withContext null
                val result = c.acquireTokenSilent(
                    AcquireTokenSilentParameters.Builder().forAccount(account).fromAuthority(account.authority).withScopes(SCOPES).build(),
                )
                remember(email, result)
                result.accessToken
            } catch (e: MsalUiRequiredException) {
                null
            } catch (e: MsalException) {
                null
            }
        }
    }

    suspend fun signOut(email: String) = withContext(Dispatchers.IO) {
        tokens.remove(email)
        runCatching { val c = client(); accountFor(c, email)?.let { c.removeAccount(it) } }
        Unit
    }

    fun invalidate(email: String) { tokens.remove(email) }

    private fun accountFor(c: IMultipleAccountPublicClientApplication, email: String): IAccount? =
        c.accounts.firstOrNull { it.username.equals(email, ignoreCase = true) }

    private fun remember(email: String, r: IAuthenticationResult) {
        tokens[email] = r.accessToken to (r.expiresOn?.time ?: (System.currentTimeMillis() + 50 * 60_000))
    }

    private fun friendly(e: MsalException): String = when {
        e.errorCode.contains("access_denied", true) -> "Access wasn't granted"
        e.message?.contains("AADSTS65001") == true || e.message?.contains("admin", true) == true ->
            "Your organization's admin must approve IA Mode before it can read this mailbox"
        e.errorCode.contains("device_network_not_available", true) || e.errorCode.contains("io_error", true) -> "No connection"
        else -> "Microsoft sign-in failed (${e.errorCode})"
    }

    /** MSAL config: work/school + personal Microsoft accounts, redirect bound to this build's signature. */
    private fun configJson(): String {
        val redirect = "msauth://${context.packageName}/" + URLEncoder.encode(BuildConfig.MSAL_SIGNATURE_HASH, "UTF-8")
        return """
            {
              "client_id": "${BuildConfig.MSAL_CLIENT_ID}",
              "authorization_user_agent": "DEFAULT",
              "redirect_uri": "$redirect",
              "account_mode": "MULTIPLE",
              "broker_redirect_uri_registered": false,
              "authorities": [
                { "type": "AAD", "audience": { "type": "AzureADandPersonalMicrosoftAccount", "tenant_id": "common" }, "default": true }
              ]
            }
        """.trimIndent()
    }

    companion object {
        /** Delegated Microsoft Graph permissions. MSAL adds openid/profile/offline_access itself. */
        val SCOPES = listOf(
            "https://graph.microsoft.com/User.Read",
            "https://graph.microsoft.com/Mail.ReadWrite",
            "https://graph.microsoft.com/Mail.Send",
        )
    }
}
