package com.iamode.app.service.worker

import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.usecase.SendReplyUseCase
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds each automatic reply for the undo window, then sends it.
 * The window is a few seconds and the app process is kept alive by the bound
 * notification listener, so an in-process timer is enough.
 */
@Singleton
class SendScheduler @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val sendReply: Lazy<SendReplyUseCase>, // Lazy breaks the dependency cycle
    private val conversations: ConversationRepository,
) : ReplyScheduler {

    private val jobs = ConcurrentHashMap<String, Job>()

    override fun schedule(conversationId: String, atMillis: Long) {
        jobs.remove(conversationId)?.cancel()
        jobs[conversationId] = scope.launch {
            delay((atMillis - System.currentTimeMillis()).coerceAtLeast(0))
            coroutineContext[Job]?.let { jobs.remove(conversationId, it) }
            sendReply.get().invoke(conversationId, auto = true)
        }
    }

    override fun cancel(conversationId: String) {
        jobs.remove(conversationId)?.cancel()
    }

    /** Re-arms replies that were waiting when the app process was killed. */
    fun restore() {
        scope.launch {
            conversations.queued().forEach { c -> schedule(c.id, c.sendAt ?: System.currentTimeMillis()) }
        }
    }
}
