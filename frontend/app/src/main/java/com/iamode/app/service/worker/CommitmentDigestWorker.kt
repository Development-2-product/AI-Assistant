package com.iamode.app.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.notifications.AppNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** One privacy-safe daily summary. It only opens the tracker and never sends a message. */
@HiltWorker
class CommitmentDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val commitments: ChatCommitmentDao,
    private val conversations: ConversationDao,
    private val mailActions: MailActionDao,
    private val notifier: AppNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val end = start + TimeUnit.DAYS.toMillis(1)
        val rows = commitments.all().first()
        val review = rows.count { it.status == "REVIEW_NEEDED" }
        val dueToday = rows.count { it.status in setOf("CONFIRMED", "SNOOZED") && it.dueAt?.let { due -> due >= start && due < end } == true }
        val overdue = rows.count { it.status in setOf("CONFIRMED", "SNOOZED") && it.dueAt?.let { due -> due < start } == true }
        val chatReviews = conversations.observeActionable().first().count { it.status == "PENDING_APPROVAL" }
        val mailReviews = mailActions.observePending().first().count { it.status in setOf("PROPOSED", "REVIEW_REQUIRED", "APPROVED") }
        notifier.dailyBriefing(chatReviews, mailReviews, review, dueToday, overdue)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "commitment-digest", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CommitmentDigestWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
