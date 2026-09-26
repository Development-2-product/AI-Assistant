package com.iamode.app.data.jobs

import com.iamode.app.core.i18n.tr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iamode.app.core.notifications.AppNotifier
import com.iamode.app.domain.jobs.FollowUpPlanner
import com.iamode.app.domain.jobs.PlannerInput
import com.iamode.app.domain.jobs.ReminderType
import com.iamode.app.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Once a day: follow-up nudges, deadlines and offer reminders for tracked applications.
 * It never sends anything. A follow-up nudge opens a drafted email the user reviews and approves.
 * The same nudge isn't repeated within 3 days.
 */
@HiltWorker
class JobReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tracker: JobTrackerRepository,
    private val notifier: AppNotifier,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current().jobReminders) return Result.success()
        val now = System.currentTimeMillis()
        val apps = tracker.all()
        val byId = apps.associateBy { it.id }
        val reminders = FollowUpPlanner.plan(apps.map {
            PlannerInput(it.id, JobTrackerRepository.stageOf(it.stage), it.lastActivityAt, it.nextStepAt, it.lastFollowUpAt, it.followUpsSent)
        }, now)
        for (r in reminders) {
            val a = byId[r.applicationId] ?: continue
            if (a.lastReminderType == r.type.name && a.lastRemindedAt != null && now - a.lastRemindedAt < 3 * FollowUpPlanner.DAY) continue
            val what = listOfNotNull(a.role, a.company).joinToString(" · ").ifBlank { tr("Your application") }
            when (r.type) {
                ReminderType.MARK_GHOSTED -> {
                    tracker.markGhosted(a.id)
                    notifier.jobReminder(a.id, "No reply from ${a.company ?: "this company"}", tr("%1\$s was marked as no response. Tap to change it.", what))
                }
                else -> notifier.jobReminder(a.id, tr(r.type.title), what)
            }
            tracker.markReminded(a.id, r.type.name)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "job-reminders-daily"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<JobReminderWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
