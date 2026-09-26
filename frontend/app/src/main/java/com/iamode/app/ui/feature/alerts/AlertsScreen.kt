package com.iamode.app.ui.feature.alerts

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.components.time
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel @Inject constructor(
    sessions: SessionRepository,
    private val alerts: AlertRepository,
) : ViewModel() {
    val alertList: StateFlow<List<Alert>> = sessions.activeSession.flatMapLatest { s ->
        alerts.observeSince(s?.startedAt ?: (System.currentTimeMillis() - 24 * 3_600_000L))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = viewModelScope.launch { alerts.clear() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(onBack: () -> Unit, openConversation: (String) -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    val list by vm.alertList.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Alerts")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { if (list.isNotEmpty()) TextButton(onClick = { vm.clear() }) { Text(tr("Clear")) } },
        )
    }) { padding ->
        if (list.isEmpty()) {
            Column(Modifier.padding(padding)) { EmptyState(tr("No alerts yet"), tr("Everything IA Mode does for you appears here.")) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            items(list, key = { it.id }) { a ->
                ListItem(
                    leadingContent = { Text(icon(a.kind), style = MaterialTheme.typography.titleMedium) },
                    headlineContent = { Text(a.title) },
                    supportingContent = { Text(a.body, maxLines = 3) },
                    trailingContent = { Text(time(a.createdAt), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.animateItem().clickable(enabled = a.conversationId != null) { a.conversationId?.let(openConversation) },
                )
                HorizontalDivider(Modifier.animateItem())
            }
        }
    }
}

private fun icon(k: AlertKind) = when (k) {
    AlertKind.NEW_MAIL -> "📧"
    AlertKind.APPROVAL -> "✋"
    AlertKind.SENT -> "✅"
    AlertKind.CRISIS -> "🚨"
    AlertKind.CALLBACK -> "📞"
    AlertKind.ENDED -> "🏁"
    AlertKind.FOLLOW_UP -> "💰"
    AlertKind.ERROR -> "⚠️"
}
