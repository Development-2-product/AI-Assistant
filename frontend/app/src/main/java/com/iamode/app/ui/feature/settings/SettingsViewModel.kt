package com.iamode.app.ui.feature.settings

import com.iamode.app.core.i18n.tr

import android.app.PendingIntent
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.GmailAccountEntity
import com.iamode.app.core.datastore.SeenMessageStore
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.data.gmail.GmailAuthManager
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.domain.model.AutoModeSettings
import com.iamode.app.domain.model.AutoSchedule
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import com.iamode.app.service.auto.AutoModeEvaluator
import com.iamode.app.service.worker.ModeLifecycle
import com.iamode.app.service.worker.CommitmentReminderScheduler
import com.iamode.app.data.mail.MailSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val gmail: GmailRepository,
    private val gmailAuth: GmailAuthManager,
    private val conversations: ConversationRepository,
    private val alerts: AlertRepository,
    private val seen: SeenMessageStore,
    private val toggle: ToggleIAModeUseCase,
    private val modeLifecycle: ModeLifecycle,
    private val autoMode: AutoModeEvaluator,
    private val commitments: ChatCommitmentDao,
    private val commitmentReminders: CommitmentReminderScheduler,
) : ViewModel() {

    val state: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val gmailAccounts: StateFlow<List<GmailAccountEntity>> =
        gmail.connectedAccounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _gmailConnectionInProgress = MutableStateFlow(false)
    val gmailConnectionInProgress = _gmailConnectionInProgress.asStateFlow()

    fun update(f: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settings.update(f) }
    }

    /** Starts Google Identity Services' native account selector, followed by Gmail consent when needed. */
    fun connectGmail(launch: (PendingIntent) -> Unit) {
        if (_gmailConnectionInProgress.value) return
        viewModelScope.launch {
            _gmailConnectionInProgress.value = true
            try {
                val awaitingResult = finishAuthorization(gmailAuth.beginAuthorizationWithAccountSelection(), launch)
                if (!awaitingResult) _gmailConnectionInProgress.value = false
            } catch (e: CancellationException) {
                _gmailConnectionInProgress.value = false
                throw e
            } catch (e: Exception) {
                _gmailConnectionInProgress.value = false
                _message.value = tr("Google account selection was cancelled or unavailable")
            }
        }
    }

    fun onGmailAuthResult(resultCode: Int, data: Intent?, launch: (PendingIntent) -> Unit) = viewModelScope.launch {
        try {
            if (data == null) {
                _message.value = if (resultCode == Activity.RESULT_CANCELED) {
                    tr("Gmail connection was cancelled")
                } else {
                    tr("Google returned no Gmail authorization result")
                }
                _gmailConnectionInProgress.value = false
                return@launch
            }
            val awaitingResult = finishAuthorization(gmailAuth.resultFromIntent(data), launch)
            if (!awaitingResult) _gmailConnectionInProgress.value = false
        } catch (e: CancellationException) {
            _gmailConnectionInProgress.value = false
            throw e
        } catch (e: Exception) {
            _gmailConnectionInProgress.value = false
            _message.value = tr("Gmail authorization failed. Check the Android OAuth client, Gmail API and test user.")
        }
    }

    private suspend fun finishAuthorization(
        result: com.google.android.gms.auth.api.identity.AuthorizationResult,
        launch: (PendingIntent) -> Unit,
    ): Boolean {
        val pending = result.pendingIntent
        when {
            result.hasResolution() && pending != null -> {
                launch(pending)
                return true
            }
            result.accessToken != null -> {
                addAccount(requireNotNull(result.accessToken))
                return false
            }
            else -> {
                _message.value = tr("Google did not issue Gmail access. Check the Android OAuth client, Gmail API and test user.")
                return false
            }
        }
    }

    private suspend fun addAccount(token: String) {
        val email = gmail.addAccount(token)
        // The first scan is intentionally larger than ordinary background refreshes, but it
        // remains bounded and is queued through WorkManager rather than blocking the consent UI.
        MailSyncWorker.syncNow(context, limit = 40)
        _message.value = tr("Connected %1\$s. Checking up to 40 recent emails now.", email)
    }

    fun setStatusNotification(show: Boolean) = viewModelScope.launch {
        settings.update { it.copy(showStatusNotification = show) }
        modeLifecycle.restoreIfOn() // shows or hides it right away
    }

    fun setAppEnabled(channel: Channel, enabled: Boolean) =
        update { it.copy(enabledApps = if (enabled) it.enabledApps + channel else it.enabledApps - channel) }

    /** Auto on/off changes take effect immediately (alarms, activity updates, and a fresh check). */
    private fun updateAuto(f: (AutoModeSettings) -> AutoModeSettings) = viewModelScope.launch {
        settings.update { it.copy(autoMode = f(it.autoMode)) }
        autoMode.refresh()
    }

    fun setAutoDriving(on: Boolean) = updateAuto { it.copy(whenDriving = on) }
    fun setAutoMeetings(on: Boolean) = updateAuto { it.copy(duringMeetings = on) }
    fun addSchedule(schedule: AutoSchedule) = updateAuto { it.copy(schedules = it.schedules + schedule) }
    fun removeSchedule(index: Int) = updateAuto { a -> a.copy(schedules = a.schedules.filterIndexed { i, _ -> i != index }) }

    fun removeGmail(email: String, deleteLocalData: Boolean = false) = viewModelScope.launch {
        val revoked = gmail.removeAccount(email, deleteLocalData)
        _message.value = if (revoked) tr("Disconnected %1\$s. Choose an account again to reconnect.", email)
        else tr("Disconnected %1\$s locally. Remove IA Mode from Google Account access when online.", email)
    }

    fun deleteAllData() = viewModelScope.launch {
        toggle.turnOff()
        commitments.all().first().forEach { commitmentReminders.cancel(it.id) }
        commitments.deleteAll()
        conversations.deleteAll()
        alerts.clear()
        seen.clear()
        _message.value = tr("All conversations, commitments and alerts deleted")
    }

    fun messageShown() { _message.value = null }
}
