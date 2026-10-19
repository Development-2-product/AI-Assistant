package com.iamode.app.service.worker

import com.iamode.app.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debounces rapid notification updates from one conversation. The latest message resets the
 * quiet-period timer, so the AI receives one coherent thought instead of replying mid-burst.
 */
@Singleton
class MessageBurstCoordinator @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var quietPeriodMillis: Long = QUIET_PERIOD_MS

    /** Test-only timing override; production always uses the injected primary constructor. */
    constructor(scope: CoroutineScope, quietPeriodMillis: Long) : this(scope) {
        this.quietPeriodMillis = quietPeriodMillis
    }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val firstArrival = ConcurrentHashMap<String, Long>()

    fun schedule(conversationId: String, afterQuietMillis: Long = quietPeriodMillis, action: suspend () -> Unit) {
        val now = System.currentTimeMillis()
        val first = firstArrival.putIfAbsent(conversationId, now) ?: now
        // Wait briefly for a split thought, but never make a lively chat wait forever.
        val delayMillis = minOf(afterQuietMillis, (MAX_WAIT_MS - (now - first)).coerceAtLeast(0L))
        jobs.remove(conversationId)?.cancel()
        jobs[conversationId] = scope.launch {
            try {
                delay(delayMillis)
                action()
            } finally {
                coroutineContext[Job]?.let { job ->
                    if (jobs.remove(conversationId, job)) firstArrival.remove(conversationId, first)
                }
            }
        }
    }

    fun cancel(conversationId: String) {
        jobs.remove(conversationId)?.cancel()
        firstArrival.remove(conversationId)
    }

    companion object {
        /** Fast enough for chat, while still joining a message split across notifications. */
        const val QUIET_PERIOD_MS = 2_000L
        const val MAX_WAIT_MS = 5_000L
    }
}
