package com.iamode.app.ui.feature.settings

import com.iamode.app.core.i18n.tr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.data.mail.MailSyncWorker
import com.iamode.app.data.outlook.OutlookAuth
import com.iamode.app.data.outlook.OutlookRepository
import com.iamode.app.ui.components.SectionTitle
import com.iamode.app.ui.components.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OutlookViewModel @Inject constructor(
    private val auth: OutlookAuth,
    private val repo: OutlookRepository,
) : ViewModel() {
    val configured = auth.configured
    val accounts = repo.connectedAccounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun connect(activity: Activity) = viewModelScope.launch {
        busy.value = true
        message.value = when (val r = auth.signIn(activity)) {
            is OutlookAuth.SignIn.Success -> {
                repo.addAccount(r.email, r.homeAccountId)
                MailSyncWorker.syncNow(activity.applicationContext)
                tr("Connected %1\$s", (r.email))
            }
            OutlookAuth.SignIn.Cancelled -> null
            is OutlookAuth.SignIn.Failed -> r.reason
        }
        busy.value = false
    }

    fun remove(email: String) = viewModelScope.launch { repo.removeAccount(email); message.value = tr("Removed %1\$s", email) }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun OutlookSection(vm: OutlookViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val activity = LocalContext.current.activity()
    Column {
        SectionTitle(tr("Outlook accounts"))
        if (!vm.configured) {
            Text(tr("Outlook isn't set up in this build yet. Add your Microsoft app details to local.properties (docs/OUTLOOK_SETUP.md)."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        accounts.forEach { a ->
            SettingRow(a.email, if (a.needsReauth) tr("Needs reconnecting") else tr("Connected")) {
                if (a.needsReauth && activity != null) TextButton(onClick = { vm.connect(activity) }) { Text(tr("Reconnect")) }
                TextButton(onClick = { vm.remove(a.email) }) { Text(tr("Remove")) }
            }
        }
        OutlinedButton(onClick = { activity?.let(vm::connect) }, enabled = !busy && activity != null) {
            Text(if (busy) tr("Connecting…") else tr("Connect Outlook / Microsoft 365"))
        }
        Text(tr("Works with Outlook.com, Hotmail and work or school accounts. Some organizations require an admin to approve IA Mode first."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}
