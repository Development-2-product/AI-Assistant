package com.iamode.app.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iamode.app.core.database.dao.AlertDao
import com.iamode.app.core.database.dao.MessageDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Privacy: message text and alerts are kept on the phone for 30 days, then deleted. */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messages: MessageDao,
    private val alerts: AlertDao,
    private val mail: com.iamode.app.core.database.dao.MailIntelligenceDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        messages.deleteOlderThan(cutoff)
        alerts.deleteOlderThan(cutoff)
        // Email understanding keeps metadata only; drop it after 90 days.
        mail.deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90))
        return Result.success()
    }

    companion object {
        private const val RETENTION_DAYS = 30L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "cleanup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
