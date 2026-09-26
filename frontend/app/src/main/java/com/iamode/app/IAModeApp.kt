package com.iamode.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.iamode.app.core.notifications.NotificationChannels
import com.iamode.app.service.worker.CleanupWorker
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.service.worker.ModeLifecycle
import com.iamode.app.service.worker.SendScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.service.auto.AutoModeEvaluator
import com.iamode.app.widget.IAModeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class IAModeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sendScheduler: SendScheduler
    @Inject lateinit var modeLifecycle: ModeLifecycle
    @Inject @ApplicationScope lateinit var scope: CoroutineScope
    @Inject lateinit var autoMode: AutoModeEvaluator
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var conversations: ConversationRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Crash reports only when Firebase is configured and the user allows it; never message content. */
    private fun applyCrashReportingSetting() {
        if (FirebaseApp.getApps(this).isEmpty()) return
        scope.launch {
            settings.settings.map { it.crashReports }.distinctUntilChanged().collect { enabled ->
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
            }
        }
    }

    /** Email intelligence runs every 15 min while enabled, plus whenever the Gmail app posts a notification. */
    private fun keepMailSyncScheduled() {
        scope.launch {
            settings.settings.map { it.mailIntelligence }.distinctUntilChanged().collect { enabled ->
                com.iamode.app.data.mail.MailSyncWorker.schedule(this@IAModeApp, enabled)
                com.iamode.app.data.jobs.JobReminderWorker.schedule(this@IAModeApp, enabled)
                if (enabled) com.iamode.app.data.mail.MailSyncWorker.syncNow(this@IAModeApp)
            }
        }
    }

    /** Updates the home-screen widget whenever the number of chats that need you changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepWidgetInSync() {
        scope.launch {
            sessions.activeSession.flatMapLatest { s ->
                if (s == null) flowOf(-1)
                else conversations.observeForSession(s.id).map { list ->
                    list.count { it.status in setOf(ConversationStatus.PENDING_APPROVAL, ConversationStatus.CRISIS, ConversationStatus.CALLBACK) }
                }
            }.distinctUntilChanged().collect { IAModeWidget.refresh(this@IAModeApp) }
        }
    }

    /** Android 10–12: apply the in-app language to the application context (13+ is handled by the system). */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.iamode.app.core.i18n.I18n.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        com.iamode.app.core.i18n.I18n.init(this)
        NotificationChannels.create(this)
        sendScheduler.restore()
        CleanupWorker.schedule(this)
        modeLifecycle.rebindListener()
        scope.launch {
            modeLifecycle.restoreIfOn()
            autoMode.refresh()
        }
        applyCrashReportingSetting()
        keepMailSyncScheduled()
        keepWidgetInSync()
    }
}
