package com.iamode.app.data.mail

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.domain.mail.Actor
import com.iamode.app.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Understands new inbox mail in the background, independent of IA Mode being on. Runs when the Gmail app
 * shows a notification (near-instant: the Gmail app gets push), when the app opens, and every 15 minutes. Capped per run to protect AI quota.
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current().mailIntelligence || (accounts.all().isEmpty() && outlookAccounts.all().isEmpty())) return Result.success()
        repository.expireStaleApprovals()
        val automatic = runCatching { repository.sync(limit = 15) }.getOrElse { return Result.retry() }
        automatic.forEach { id ->
            if (repository.approve(id, Actor.AUTOMATION)) executor.execute(id)
        }
        // Weekly, learn the user's writing style from their own sent mail (on the phone).
        if (settings.current().matchWritingStyle && style.isStale()) runCatching { style.refresh() }
        return Result.success()
    }

    companion object {
        const val NOW = "mail-intelligence-now"
        private const val PERIODIC = "mail-intelligence-periodic"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MailSyncWorker>().setConstraints(network)
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
