package com.iamode.app.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.notifications.AppNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure reminder rules shared by the worker and scheduler. Keeping this outside Android APIs
 * lets the lifecycle invariants be covered by fast unit tests as well as device tests.
 */
internal object CommitmentReminderPolicy {
    private val activeStatuses = setOf("CONFIRMED", "SNOOZED")

    fun shouldDeliver(status: String, storedReminderAt: Long?, scheduledReminderAt: Long): Boolean =
        status in activeStatuses && storedReminderAt != null && storedReminderAt == scheduledReminderAt

    fun uniqueWorkName(commitmentId: String): String = "commitment_reminder_$commitmentId"
}

/** Delivers only a local notification; this worker never sends a chat message. */
@HiltWorker
class CommitmentReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val commitments: ChatCommitmentDao,
    private val notifier: AppNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_COMMITMENT_ID) ?: return Result.success()
        val scheduledAt = inputData.getLong(KEY_REMINDER_AT, -1L)
        val commitment = commitments.get(id) ?: return Result.success()
        // A replaced worker, a cancelled state, or a stale reminder must remain silent.
        if (!CommitmentReminderPolicy.shouldDeliver(commitment.status, commitment.reminderAt, scheduledAt)) return Result.success()
        notifier.commitmentReminder(id)
        commitments.markReminderSent(id, System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        const val KEY_COMMITMENT_ID = "commitment_id"
        const val KEY_REMINDER_AT = "reminder_at"
    }
}

@Singleton
class CommitmentReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule(commitmentId: String, reminderAt: Long?) {
        if (reminderAt == null) { cancel(commitmentId); return }
        val delay = (reminderAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<CommitmentReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                CommitmentReminderWorker.KEY_COMMITMENT_ID to commitmentId,
                CommitmentReminderWorker.KEY_REMINDER_AT to reminderAt,
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(commitmentId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(commitmentId: String) = WorkManager.getInstance(context).cancelUniqueWork(name(commitmentId))

    private fun name(id: String) = CommitmentReminderPolicy.uniqueWorkName(id)
}
