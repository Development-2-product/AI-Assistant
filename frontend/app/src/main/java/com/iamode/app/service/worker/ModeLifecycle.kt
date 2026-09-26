package com.iamode.app.service.worker

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import com.iamode.app.core.notifications.AppNotifier
import com.iamode.app.domain.repository.ModeLifecycleHooks
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.service.notification.IANotificationListener
import com.iamode.app.service.situation.ActivityRecognitionController
import com.iamode.app.service.tile.IAModeTileService
import com.iamode.app.widget.IAModeWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModeLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activity: ActivityRecognitionController,
    private val notifier: AppNotifier,
    private val settings: SettingsRepository,
    private val sessions: SessionRepository,
) : ModeLifecycleHooks {

    override suspend fun onModeStarted() {
        rebindListener()
        activity.start()
        com.iamode.app.data.mail.MailSyncWorker.schedule(context, settings.current().mailIntelligence)
        com.iamode.app.data.mail.MailSyncWorker.syncNow(context)
        val session = sessions.current()
        if (session != null && settings.current().showStatusNotification) notifier.showModeOn(session.startedAt, session.autoReason)
        else notifier.hideModeOn()
        IAModeTileService.requestUpdate(context)
        IAModeWidget.refresh(context)
    }

    override suspend fun onModeStopped() {
        // Keep listening for driving if IA Mode should switch itself on when you start driving.
        if (!settings.current().autoMode.whenDriving) activity.stop()
        // Mail intelligence is independent of IA Mode; IAModeApp keeps its schedule in sync with settings.
        notifier.hideModeOn()
        IAModeTileService.requestUpdate(context)
        IAModeWidget.refresh(context)
    }

    /** Re-applies everything after a reboot or app update, if IA Mode was on. */
    suspend fun restoreIfOn() {
        if (sessions.current() != null) onModeStarted() else notifier.hideModeOn()
    }

    /** Asks Android to reconnect the notification listener (some phones drop it after updates). */
    fun rebindListener() {
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, IANotificationListener::class.java))
        }
    }
}
