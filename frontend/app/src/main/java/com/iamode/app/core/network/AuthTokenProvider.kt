package com.iamode.app.core.network

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.iamode.app.BuildConfig
import com.iamode.app.core.diagnostics.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthMethod(val label: String) { FIREBASE("Firebase sign-in"), DEV_TOKEN("Dev token"), NONE("None") }

/**
 * Production: Firebase anonymous sign-in, backend verifies the ID token.
 * Development (no google-services.json): the dev token from [ServerConfig].
 */
@Singleton
class AuthTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val server: ServerConfig,
    private val log: DiagnosticsLog,
) {
    private val lastAppCheckFailureAt = AtomicLong(0L)
    val method: AuthMethod
        get() = when {
            BuildConfig.USE_FIREBASE_AUTH && FirebaseApp.getApps(context).isNotEmpty() -> AuthMethod.FIREBASE
            server.devToken.isNotBlank() -> AuthMethod.DEV_TOKEN
            else -> AuthMethod.NONE
        }

    suspend fun token(): String? = when (method) {
        AuthMethod.DEV_TOKEN -> server.devToken
        AuthMethod.NONE -> null
        AuthMethod.FIREBASE -> try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: auth.signInAnonymously().await().user
            user?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            // Most common cause: Anonymous sign-in isn't enabled in the Firebase console.
            log.record("Sign-in", false, "Firebase sign-in failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
            null
        }
    }

    /**
     * A release request carries a short-lived App Check token in addition to its
     * Firebase user token. The backend can enforce it after Play Integrity has
     * been registered in Firebase; failed attestation never falls back to a dev token.
     */
    suspend fun appCheckToken(): String? {
        if (!BuildConfig.USE_FIREBASE_APP_CHECK || FirebaseApp.getApps(context).isEmpty()) return null
        return runCatching { FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token }
            .onFailure { error -> recordAppCheckFailure(error) }
            .getOrNull()
    }

    /** An unavailable optional attestation must not fill the user's diagnostics on every API call. */
    private fun recordAppCheckFailure(error: Throwable) {
        val now = System.currentTimeMillis()
        val previous = lastAppCheckFailureAt.get()
        if (now - previous < APP_CHECK_FAILURE_LOG_INTERVAL_MS ||
            !lastAppCheckFailureAt.compareAndSet(previous, now)
        ) return
        log.record("App Check", false, "Token unavailable: ${error.javaClass.simpleName}")
    }

    private companion object {
        const val APP_CHECK_FAILURE_LOG_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

class AuthInterceptor @Inject constructor(private val tokens: AuthTokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // OkHttp runs interceptors on its own background threads, so blocking here is safe.
        val (token, appCheckToken) = runCatching {
            runBlocking { tokens.token() to tokens.appCheckToken() }
        }.getOrDefault(null to null)
        val request = chain.request().newBuilder().apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (appCheckToken != null) header("X-Firebase-AppCheck", appCheckToken)
        }.build()
        return chain.proceed(request)
    }
}
