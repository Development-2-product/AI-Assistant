package com.iamode.app.service.worker

import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.usecase.SendReplyUseCase
import com.iamode.app.domain.model.ConversationStatus
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
 * The short undo window is handled in-process. If the process is recreated,
 * [restore] only re-arms future sends and converts expired ones to approval.
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

    /**
     * Re-arm only future replies after a process restart. A reply whose undo window
     * elapsed while IA Mode was not running must never be sent without a fresh user
     * decision: the notification RemoteInput may also have expired.
     */
    fun restore() {
        scope.launch {
            val now = System.currentTimeMillis()
            conversations.queued().forEach { c ->
                val sendAt = c.sendAt
                if (sendAt != null && sendAt > now) {
                    schedule(c.id, sendAt)
                } else {
                    conversations.upsert(c.copy(
                        status = ConversationStatus.PENDING_APPROVAL,
                        autopilot = false,
                        sendAt = null,
                        reason = "Automatic send paused after IA Mode restarted; review before sending",
                        updatedAt = now,
                    ))
                }
            }
        }
    }
}
