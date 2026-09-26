package com.iamode.app.core.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.iamode.app.BuildConfig
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.core.network.AuthMethod
import com.iamode.app.core.network.AuthTokenProvider
import com.iamode.app.core.network.IAModeApi
import com.iamode.app.core.network.ServerConfig
import com.iamode.app.core.permissions.AppPermission
import com.iamode.app.core.permissions.AppPermissions
import com.iamode.app.data.gmail.GmailApi
import com.iamode.app.data.gmail.GmailAuthManager
import com.iamode.app.domain.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

enum class CheckStatus { OK, WARN, FAIL, SKIPPED }

data class CheckResult(val title: String, val status: CheckStatus, val detail: String, val fix: String? = null)

/**
 * Tests every link between a message arriving and a reply going out, in order, and says exactly
 * what to fix. The copyable report contains no messages, tokens or keys.
 */
@Singleton
class DiagnosticsRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: IAModeApi,
    private val server: ServerConfig,
    private val auth: AuthTokenProvider,
    private val sessions: SessionRepository,
    private val gmailAccounts: GmailAccountDao,
    private val gmailAuth: GmailAuthManager,
    private val gmailApi: GmailApi,
    private val log: DiagnosticsLog,
) {
    suspend fun run(onProgress: (List<CheckResult>) -> Unit): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        fun add(r: CheckResult) { results += r; onProgress(results.toList()) }

        add(buildCheck())
        add(permissionsCheck())
        val online = internetCheck().also(::add).status == CheckStatus.OK
        val serverOk = online && serverCheck().also(::add).status == CheckStatus.OK
        if (!online) add(CheckResult("Server", CheckStatus.SKIPPED, "Skipped because the phone is offline"))
        val signedIn = serverOk && signInCheck().also(::add).status == CheckStatus.OK
        if (serverOk && signedIn) add(aiCheck()) else add(CheckResult("AI (Gemini)", CheckStatus.SKIPPED, "Needs the server and sign-in to work first"))
        gmailChecks(online).forEach(::add)
        add(recentProblems())
        log.record("Check", results.none { it.status == CheckStatus.FAIL }, "Connection check: " +
            results.joinToString { "${it.title}=${it.status}" })
        return results
    }

    fun report(results: List<CheckResult>): String = buildString {
        appendLine("IA Mode connection report, ${SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date())}")
        results.forEach { r ->
            append("[${r.status}] ${r.title}: ${r.detail}")
            r.fix?.let { append("\n    Fix: $it") }
            appendLine()
        }
        appendLine("Recent activity:")
        log.entries().take(15).forEach { e ->
            appendLine("  ${SimpleDateFormat("d MMM h:mm a", Locale.ENGLISH).format(Date(e.time))} ${if (e.ok) "ok  " else "FAIL"} ${e.area}: ${e.detail}")
        }
    }.let(::maskEmails)

    // ---------------- individual checks ----------------

    private fun buildCheck(): CheckResult {
        val detail = "v${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}, package ${context.packageName}, " +
            "endpoint: build-configured, login: ${auth.method.label}"
        return if (auth.method == AuthMethod.NONE) {
            CheckResult("App setup", CheckStatus.FAIL, detail,
                "This build has no way to sign in. Configure frontend/local.properties and rebuild the APK.")
        } else CheckResult("App setup", CheckStatus.OK, detail)
    }

    private suspend fun permissionsCheck(): CheckResult {
        val missing = AppPermissions.missingRequired(context)
        val mode = if (sessions.current() != null) "IA Mode is on" else "IA Mode is off (turn it on to get replies)"
        return when {
            AppPermission.NOTIFICATION_ACCESS in missing -> CheckResult("Permissions", CheckStatus.FAIL,
                "$mode. Notification access is off, so no chat messages can be read.",
                "Settings › Privacy › Permissions › Notification access. On Android 13+, allow restricted settings first.")
            missing.isNotEmpty() -> CheckResult("Permissions", CheckStatus.WARN,
                "$mode. Missing: ${missing.joinToString { it.title }}", "Grant them in Settings › Privacy › Permissions.")
            !AppPermissions.isGranted(context, AppPermission.BATTERY) -> CheckResult("Permissions", CheckStatus.WARN,
                "$mode. Battery is optimized, so Android may pause IA Mode in the background.",
                "Allow Unrestricted battery in Permissions" + if (AppPermissions.needsAutostartHint()) ", and turn on Autostart for IA Mode." else ".")
            else -> CheckResult("Permissions", CheckStatus.OK, "$mode. Everything needed is granted.")
        }
    }

    private fun internetCheck(): CheckResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val via = when {
            caps == null -> "no network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "network"
        }
        return if (online) CheckResult("Internet", CheckStatus.OK, "Connected via $via")
        else CheckResult("Internet", CheckStatus.FAIL, "The phone has no working internet ($via)", "Turn on Wi-Fi or mobile data.")
    }

    private suspend fun serverCheck(): CheckResult {
        val host = server.baseUrl.host
        val start = System.currentTimeMillis()
        return try {
            val h = api.health()
            CheckResult("Server", CheckStatus.OK, "Reached the build-configured server in ${System.currentTimeMillis() - start} ms (prompts ${h.promptVersion ?: "old version"})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val local = isPrivateHost(host)
            val (detail, fix) = when {
                e is UnknownHostException -> "The build-configured server address was not found" to
                    "Check frontend/local.properties and rebuild the APK."
                e is UnknownServiceException || e.message.orEmpty().contains("CLEARTEXT", true) ->
                    "This build only allows HTTPS, but its configured endpoint is HTTP" to "Use an HTTPS release endpoint and rebuild."
                e is SSLException -> "HTTPS error talking to the configured server" to "Check the configured HTTPS domain and certificate, then rebuild."
                e is HttpException && e.code() == 404 -> "The configured endpoint is not an IA Mode server" to
                    "Set the backend base URL (without /v1) in frontend/local.properties and rebuild."
                (e is ConnectException || e is SocketTimeoutException) && local ->
                    "Couldn't reach the configured local server" to "Phone and PC must be on the same Wi-Fi; configure BIND_ADDRESS=0.0.0.0 and HOST_PORT=8000 in backend/.env, then rebuild."
                e is ConnectException || e is SocketTimeoutException ->
                    "The configured server didn't answer" to "A free server can take up to 50 s to wake up; if it persists, check the deployment."
                else -> "Couldn't connect to the build-configured server (${e.javaClass.simpleName})" to
                    "Check frontend/local.properties and the server deployment, then rebuild if the endpoint changed."
            }
            CheckResult("Server", CheckStatus.FAIL, detail, fix)
        }
    }

    private suspend fun signInCheck(): CheckResult = try {
        val r = api.authCheck()
        CheckResult("Sign-in", CheckStatus.OK, "${auth.method.label} accepted (server uses AUTH_MODE=${r.authMode})")
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val detail = errorDetail(e)
        val fix = when {
            e.code() == 404 -> "Your server runs an older version. Update the backend (pull the new code, redeploy on Render)."
            detail.contains("Dev token rejected") -> "Make iamode.devToken match DEV_API_TOKEN in backend/.env, then rebuild the debug APK."
            detail.contains("AUTH_MODE=dev is not allowed") -> "On the server set ENVIRONMENT=staging (keeps AUTH_MODE=dev), or switch to Firebase."
            auth.method == AuthMethod.DEV_TOKEN -> "The server expects Firebase (AUTH_MODE=firebase), but this app has no google-services.json. " +
                "Either add google-services.json and rebuild, or use a debug backend with AUTH_MODE=dev and matching build-time iamode.devToken."
            auth.method == AuthMethod.FIREBASE -> "In the Firebase console enable Authentication › Anonymous, and make sure " +
                "FIREBASE_PROJECT_ID on the server is your Firebase project ID."
            else -> "Configure Firebase or the debug token in frontend/local.properties and rebuild."
        }
        CheckResult("Sign-in", CheckStatus.FAIL, "HTTP ${e.code()}: $detail", fix)
    } catch (e: Exception) {
        CheckResult("Sign-in", CheckStatus.FAIL, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
    }

    private suspend fun aiCheck(): CheckResult = try {
        val r = api.aiCheck()
        val failed = r.models.filterNot { it.ok }
        val summary = r.models.joinToString { m -> if (m.ok) "${m.model} ok (${m.ms} ms)" else "${m.model} failed" }
        val errors = failed.joinToString(" | ") { it.error.orEmpty() }
        val fix = when {
            failed.isEmpty() -> null
            errors.contains("not set", true) || errors.contains("API key not valid", true) || errors.contains("API_KEY_INVALID") ->
                "Put a valid GEMINI_API_KEY on the server (Render › Environment), then redeploy."
            errors.contains("429") || errors.contains("RESOURCE_EXHAUSTED") ->
                "Free-tier quota used up. Wait a few minutes, use gemini-3.5-flash-lite for both models, or enable billing."
            errors.contains("404") || errors.contains("NOT_FOUND") ->
                "A model name isn't available for your key. Change GEMINI_MODEL_FAST / _WRITER on the server to one listed in AI Studio."
            errors.contains("503") || errors.contains("UNAVAILABLE") ->
                "Google says the model is busy. Set GEMINI_MODEL_FALLBACK=gemini-3.5-flash-lite on the server."
            else -> "See the error text; it comes straight from Google."
        }
        val status = when {
            failed.isEmpty() -> CheckStatus.OK
            r.ok -> CheckStatus.WARN
            else -> CheckStatus.FAIL
        }
        CheckResult("AI (Gemini)", status, summary + if (errors.isNotBlank()) ". $errors" else "", fix)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() == 404) CheckResult("AI (Gemini)", CheckStatus.WARN, "The server is too old to run this check",
            "Update and redeploy the backend.")
        else CheckResult("AI (Gemini)", CheckStatus.FAIL, "HTTP ${e.code()}: ${errorDetail(e)}")
    } catch (e: Exception) {
        CheckResult("AI (Gemini)", CheckStatus.FAIL, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}",
            "The AI check can take up to a minute on a sleeping free server. Run it again.")
    }

    private suspend fun gmailChecks(online: Boolean): List<CheckResult> {
        val accounts = gmailAccounts.all()
        if (accounts.isEmpty()) return listOf(CheckResult("Gmail", CheckStatus.SKIPPED, "No Gmail account connected",
            "Settings › Gmail accounts › Connect a Gmail account."))
        if (!online) return listOf(CheckResult("Gmail", CheckStatus.SKIPPED, "Skipped because the phone is offline"))
        return accounts.map { a ->
            val token = gmailAuth.accessToken(a.email)
            if (token == null) {
                CheckResult("Gmail ${a.email}", CheckStatus.FAIL, "No access to this account",
                    "Settings › Gmail › Reconnect. If it fails again: in Google Cloud › Credentials the Android OAuth client " +
                        "must use package ${context.packageName} and this build's SHA-1 (debug and release builds differ), " +
                        "the Gmail API must be enabled, and the account must be a test user.")
            } else try {
                gmailApi.profile("Bearer $token")
                CheckResult("Gmail ${a.email}", CheckStatus.OK, "Connected. New mail is handled by the dedicated email-intelligence workflow.")
            } catch (e: HttpException) {
                CheckResult("Gmail ${a.email}", CheckStatus.FAIL, "HTTP ${e.code()} from Gmail",
                    if (e.code() == 403) "Enable the Gmail API in Google Cloud (APIs & Services › Library), then reconnect." else "Reconnect the account.")
            } catch (e: Exception) {
                CheckResult("Gmail ${a.email}", CheckStatus.FAIL, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(100)}")
            }
        }
    }

    private fun recentProblems(): CheckResult {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val problems = log.entries().filter { !it.ok && it.time > since && it.area != "Check" }
        return if (problems.isEmpty()) CheckResult("Last 24 hours", CheckStatus.OK, "No problems recorded")
        else CheckResult("Last 24 hours", CheckStatus.WARN,
            "${problems.size} problem(s). Latest: " + problems.take(3).joinToString(" | ") { "${it.area}: ${it.detail}" })
    }

    // ---------------- helpers ----------------

    private fun errorDetail(e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        return Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: body.take(160).ifBlank { e.message() }
    }

    private fun isPrivateHost(host: String): Boolean =
        host == "localhost" || host == "10.0.2.2" || host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)

    /** k***@gmail.com: the report can be shared without exposing addresses. */
    private fun maskEmails(text: String) = text.replace(Regex("([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+)")) { m ->
        "${m.groupValues[1]}***@${m.groupValues[2]}"
    }
}
