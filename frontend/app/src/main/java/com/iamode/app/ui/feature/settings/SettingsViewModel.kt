package com.iamode.app.ui.feature.settings

import com.iamode.app.core.i18n.tr

import android.app.PendingIntent
import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.GmailAccountEntity
import com.iamode.app.core.datastore.SeenMessageStore
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val gmail: GmailRepository,
    private val gmailAuth: GmailAuthManager,
    private val conversations: ConversationRepository,
    private val alerts: AlertRepository,
    private val seen: SeenMessageStore,
    private val toggle: ToggleIAModeUseCase,
    private val modeLifecycle: ModeLifecycle,
    private val autoMode: AutoModeEvaluator,
) : ViewModel() {

    val state: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val gmailAccounts: StateFlow<List<GmailAccountEntity>> =
        gmail.connectedAccounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun update(f: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settings.update(f) }
    }

    /** Starts Google's consent flow. [launch] is called when the user must pick an account / approve scopes. */
    fun connectGmail(launch: (PendingIntent) -> Unit) = viewModelScope.launch {
        try {
            val result = gmailAuth.beginAuthorization()
            val pending = result.pendingIntent
            if (result.hasResolution() && pending != null) launch(pending)
            else result.accessToken?.let { addAccount(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = tr("Couldn't start Google sign-in")
        }
    }

    fun onGmailAuthResult(resultCode: Int, data: Intent?) = viewModelScope.launch {
        try {
            if (data == null) {
                _message.value = if (resultCode == Activity.RESULT_CANCELED) {
                    tr("Gmail connection was cancelled")
                } else {
                    tr("Google returned no Gmail authorization result")
                }
                return@launch
            }
            val token = gmailAuth.resultFromIntent(data).accessToken
            if (token != null) addAccount(token) else _message.value =
                tr("Google did not issue Gmail access. Check the Android OAuth client, Gmail API and test user.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = tr("Gmail authorization failed. Check the Android OAuth client, Gmail API and test user.")
        }
    }

    private suspend fun addAccount(token: String) {
        val email = gmail.addAccount(token)
        _message.value = tr("Connected %1\$s", email)
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

    fun removeGmail(email: String) = viewModelScope.launch { gmail.removeAccount(email) }

    fun deleteAllData() = viewModelScope.launch {
        toggle.turnOff()
        conversations.deleteAll()
        alerts.clear()
        seen.clear()
        _message.value = tr("All conversations and alerts deleted")
    }

    fun messageShown() { _message.value = null }
}
