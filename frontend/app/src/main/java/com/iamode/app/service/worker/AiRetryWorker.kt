package com.iamode.app.service.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.iamode.app.domain.repository.AiRetryScheduler
import com.iamode.app.domain.usecase.ProcessIncomingMessageUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the AI couldn't be reached (offline, server waking up, model busy), tries again once the
 * phone is online, with exponential backoff: ~30 s, 1 min, 2 min, 4 min, then gives up.
 * If the user replies or skips in the meantime, the retry does nothing.
 */
@HiltWorker
class AiRetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processIncoming: ProcessIncomingMessageUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_CONVERSATION) ?: return Result.success()
        if (runAttemptCount >= MAX_ATTEMPTS) return Result.success()
        return if (processIncoming.retry(id)) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_CONVERSATION = "conversation_id"
        private const val MAX_ATTEMPTS = 5
    }
}

@Singleton
class WorkManagerAiRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AiRetryScheduler {
    override fun scheduleRetry(conversationId: String) {
        val request = OneTimeWorkRequestBuilder<AiRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(AiRetryWorker.KEY_CONVERSATION to conversationId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ai-retry-$conversationId", ExistingWorkPolicy.REPLACE, request)
    }
}
