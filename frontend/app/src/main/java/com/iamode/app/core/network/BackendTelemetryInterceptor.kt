package com.iamode.app.core.network

import android.os.SystemClock
import com.iamode.app.core.diagnostics.DiagnosticsLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a small, privacy-safe health signal for the Connection check screen.
 *
 * It deliberately records neither URLs, headers nor request/response bodies: all of those can
 * contain account or message data. The throttling prevents a backend incident from flooding the
 * on-device diagnostics log while still leaving the user a useful explanation.
 */
@Singleton
class BackendTelemetryInterceptor @Inject constructor(
    private val diagnostics: DiagnosticsLog,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val response = chain.proceed(chain.request())
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            when {
                response.code >= 500 -> recordThrottled(
                    lastServerFailureAt,
                    "Backend",
                    false,
                    "Server temporarily unavailable (HTTP ${response.code}; ${elapsedMs} ms).",
                )
                elapsedMs >= SLOW_REQUEST_MS -> recordThrottled(
                    lastSlowRequestAt,
                    "Backend",
                    true,
                    "Slow server response (${elapsedMs} ms).",
                )
            }
            response
        } catch (error: IOException) {
            // A cancelled screen/request is expected and is not a connectivity incident.
            if (!chain.call().isCanceled()) {
                recordThrottled(
                    lastNetworkFailureAt,
                    "Backend",
                    false,
                    "Network request failed (${error.javaClass.simpleName}).",
                )
            }
            throw error
        }
    }

    private fun recordThrottled(lastRecordedAt: AtomicLong, area: String, ok: Boolean, detail: String) {
        val now = System.currentTimeMillis()
        val previous = lastRecordedAt.get()
        if (now - previous < LOG_INTERVAL_MS || !lastRecordedAt.compareAndSet(previous, now)) return
        diagnostics.record(area, ok, detail)
    }

    private companion object {
        const val SLOW_REQUEST_MS = 3_000L
        const val LOG_INTERVAL_MS = 10 * 60 * 1_000L
        val lastServerFailureAt = AtomicLong(0L)
        val lastNetworkFailureAt = AtomicLong(0L)
        val lastSlowRequestAt = AtomicLong(0L)
    }
}
