package com.iamode.app.core.network

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.iamode.app.BuildConfig
import com.iamode.app.core.diagnostics.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import okhttp3.Interceptor
import okhttp3.Response
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
}

class AuthInterceptor @Inject constructor(private val tokens: AuthTokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // OkHttp runs interceptors on its own background threads, so blocking here is safe.
        val token = runCatching { runBlocking { tokens.token() } }.getOrNull()
        val request = chain.request().newBuilder().apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        return chain.proceed(request)
    }
}
