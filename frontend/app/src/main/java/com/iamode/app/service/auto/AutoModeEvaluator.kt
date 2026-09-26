package com.iamode.app.service.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iamode.app.core.datastore.PreferenceKeys
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.model.AutoModeSettings
import com.iamode.app.domain.model.AutoTrigger
import com.iamode.app.domain.model.VehicleType
import com.iamode.app.domain.policy.AutoModePolicy
import com.iamode.app.domain.repository.AutoModeStateStore
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import com.iamode.app.service.situation.ActivityRecognitionController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns IA Mode on and off by itself: while driving, during busy calendar events, and on weekly
 * schedules. It runs when the phone reports a new activity, at the next calendar/schedule boundary
 * (alarm), after reboot, and every 15 minutes as a backup.
 */
@Singleton
class AutoModeEvaluator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val sessions: SessionRepository,
    private val store: DataStore<Preferences>,
    private val calendar: CalendarReader,
    private val autoState: AutoModeStateStore,
    private val toggle: ToggleIAModeUseCase,
    private val activity: ActivityRecognitionController,
) {
    private val mutex = Mutex()

    /** Call after settings change or at app start: sets up what auto mode needs, then evaluates. */
    suspend fun refresh() {
        val auto = settings.current().autoMode
        if (auto.whenDriving || sessions.current() != null) activity.start() else activity.stop()
        AutoModeWorker.ensure(context, auto.anyEnabled)
        evaluate()
    }

    suspend fun evaluate() = mutex.withLock {
        val s = settings.current()
        val auto = s.autoMode
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        val triggers = if (auto.anyEnabled) activeTriggers(auto, s.vehicleType, nowMs, now) else emptyList()

        val result = AutoModePolicy.decide(triggers, sessions.current(), autoState.suppressedKey())
        if (result.clearSuppression) autoState.setSuppressedKey(null)
        when (val action = result.action) {
            is AutoModePolicy.Action.TurnOn -> toggle.turnOn(action.trigger)
            is AutoModePolicy.Action.SwitchTrigger -> toggle.switchTrigger(action.trigger)
            AutoModePolicy.Action.TurnOff -> toggle.turnOff(manual = false)
            AutoModePolicy.Action.None -> Unit
        }
        scheduleNextCheck(auto, nowMs, now)
    }

    private suspend fun activeTriggers(auto: AutoModeSettings, vehicle: VehicleType, nowMs: Long, now: LocalDateTime) = buildList {
        if (auto.whenDriving) {
            val prefs = store.data.first()
            val motion = prefs[PreferenceKeys.MOTION]
            val fresh = nowMs - (prefs[PreferenceKeys.MOTION_AT] ?: 0L) < TimeUnit.HOURS.toMillis(6)
            if (fresh && (motion == "VEHICLE" || motion == "BICYCLE")) {
                val riding = motion == "BICYCLE" || vehicle == VehicleType.BIKE
                add(AutoTrigger("driving", if (riding) "You started riding" else "You started driving"))
            }
        }
        if (auto.duringMeetings) {
            calendar.current(nowMs)?.let { add(AutoTrigger("meeting:${it.id}:${it.begin}", "Calendar: ${it.title}")) }
        }
        auto.schedules.forEachIndexed { i, schedule ->
            schedule.activeWindowStart(now)?.let { add(AutoTrigger("schedule:$i:$it", "Schedule: ${schedule.label}")) }
        }
    }

    private fun scheduleNextCheck(auto: AutoModeSettings, nowMs: Long, now: LocalDateTime) {
        val zone = ZoneId.systemDefault()
        val candidates = auto.schedules.mapNotNull { it.nextBoundary(now)?.atZone(zone)?.toInstant()?.toEpochMilli() } +
            listOfNotNull(if (auto.duringMeetings) calendar.nextBoundary(nowMs) else null)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 11, Intent(context, AutoModeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = candidates.minOrNull()
        // Inexact but Doze-friendly (no exact-alarm permission needed); fires within a few minutes.
        if (next == null) alarms.cancel(pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 5_000, pi)
    }
}

@AndroidEntryPoint
class AutoModeReceiver : BroadcastReceiver() {
    @Inject lateinit var evaluator: AutoModeEvaluator
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch { try { evaluator.evaluate() } finally { pending.finish() } }
    }
}

/** 15-minute backup check, e.g. for calendar events added after the last alarm was set. */
@HiltWorker
class AutoModeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val evaluator: AutoModeEvaluator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        evaluator.evaluate()
        return Result.success()
    }

    companion object {
        private const val NAME = "auto-mode-check"

        fun ensure(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<AutoModeWorker>(15, TimeUnit.MINUTES).build())
            } else {
                wm.cancelUniqueWork(NAME)
            }
        }
    }
}
