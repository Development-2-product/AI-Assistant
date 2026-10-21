package com.iamode.app.data.mail

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.domain.mail.Actor
import com.iamode.app.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Understands new inbox mail in the background, independent of IA Mode being on. Runs when the Gmail app
 * shows a notification (near-instant: the Gmail app gets push), when the app opens, and every 15 minutes.
 * A newly connected mailbox gets one bounded 40-message catch-up; normal runs stay capped at 15.
 * The only thing it may execute is an opted-in automation rule (complete, high-confidence interview events).
 */
@HiltWorker
class MailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MailIntelligenceRepository,
    private val executor: MailActionExecutor,
    private val settings: SettingsRepository,
    private val accounts: GmailAccountDao,
    private val style: WritingStyleRepository,
    private val outlookAccounts: com.iamode.app.core.database.dao.OutlookAccountDao,
    private val log: DiagnosticsLog,
    private val syncStatus: MailSyncStatusStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current().mailIntelligence || (accounts.all().isEmpty() && outlookAccounts.all().isEmpty())) return Result.success()
        syncStatus.markStarted()
        repository.expireStaleApprovals()
        val requestedLimit = inputData.getInt(KEY_LIMIT, DEFAULT_LIMIT).coerceIn(1, MAX_CATCH_UP_LIMIT)
        // Never hold one WorkManager run hostage to a large mailbox catch-up. Ten messages is
        // enough to provide visible progress quickly; the remainder is appended as follow-up work.
        val batchLimit = requestedLimit.coerceAtMost(FOREGROUND_BATCH_LIMIT)
        val result = try {
            repository.sync(limit = batchLimit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep retries observable without retaining provider responses or mail content.
            log.record("Mail", false, "Mailbox sync will retry: ${e.javaClass.simpleName}")
            return Result.retry()
        }
        result.automaticActionIds.forEach { id ->
            if (repository.approve(id, Actor.AUTOMATION)) executor.execute(id)
        }
        if (result.retryableFailure) {
            // WorkManager applies the configured exponential backoff. Do not enqueue the next
            // 10-message page while Gemini is unavailable: that would turn one outage into 40 failures.
            log.record("Mail", false, "AI is temporarily unavailable. Mail understanding will retry automatically.")
            return Result.retry()
        }
        if (requestedLimit > batchLimit && result.fetchedCount >= batchLimit) {
            syncNow(applicationContext, requestedLimit - batchLimit)
        }
        // Weekly, learn the user's writing style from their own sent mail (on the phone).
        if (settings.current().matchWritingStyle && style.isStale()) runCatching { style.refresh() }
        // A successful scan can legitimately find zero new emails. It is still the most recent
        // completed mailbox check and should be shown as such to the user.
        syncStatus.markSucceeded()
        return Result.success()
    }

    companion object {
        const val NOW = "mail-intelligence-now"
        private const val PERIODIC = "mail-intelligence-periodic"
        private const val KEY_LIMIT = "limit"
        private const val DEFAULT_LIMIT = 15
        private const val MAX_CATCH_UP_LIMIT = 40
        private const val FOREGROUND_BATCH_LIMIT = 10
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Queues a bounded sync. Use 40 only after a user explicitly connects a mailbox. */
        fun syncNow(context: Context, limit: Int = DEFAULT_LIMIT) {
            val safeLimit = limit.coerceIn(1, MAX_CATCH_UP_LIMIT)
            WorkManager.getInstance(context).enqueueUniqueWork(
                // A Gmail notification during an active run must trigger one follow-up scan;
                // KEEP silently drops it and can leave a new message waiting for the 15-minute job.
                NOW, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MailSyncWorker>()
                    .setInputData(Data.Builder().putInt(KEY_LIMIT, safeLimit).build())
                    .setConstraints(network)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build(),
            )
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(PERIODIC); return }
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, // picks up the new 15-minute interval
                PeriodicWorkRequestBuilder<MailSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
            )
        }
    }
}
