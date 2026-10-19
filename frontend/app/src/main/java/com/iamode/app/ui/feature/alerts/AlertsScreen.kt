package com.iamode.app.ui.feature.alerts

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Alert
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.policy.AutomationAudit
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
    var filter by remember { mutableStateOf(AuditFilter.ATTENTION) }
    val displayed = list.filter { filter.matches(AutomationAudit.present(it).needsAttention) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Alerts")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { if (list.isNotEmpty()) TextButton(onClick = { vm.clear() }) { Text(tr("Clear")) } },
        )
    }) { padding ->
        if (list.isEmpty()) {
            Column(Modifier.padding(padding)) { EmptyState(tr("No alerts yet"), tr("Automation activity will appear here.")) }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            Text(
                tr("Privacy-safe activity and recovery. Open a conversation to review its actual draft or message."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuditFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(tr(option.label)) })
                }
            }
            if (displayed.isEmpty()) {
                EmptyState(tr("Nothing here"), tr("No automation activity matches this filter."))
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(displayed, key = { it.id }) { a ->
                    val item = AutomationAudit.present(a)
                    ListItem(
                        leadingContent = { Text(icon(a.kind), style = MaterialTheme.typography.titleMedium) },
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text(item.summary, maxLines = 2) },
                        trailingContent = {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(time(a.createdAt), style = MaterialTheme.typography.labelSmall)
                                item.actionLabel?.takeIf { a.conversationId != null }?.let { label ->
                                    TextButton(onClick = { a.conversationId?.let(openConversation) }) { Text(tr(label)) }
                                }
                            }
                        },
                        modifier = Modifier.animateItem().clickable(enabled = a.conversationId != null) { a.conversationId?.let(openConversation) },
                    )
                    HorizontalDivider(Modifier.animateItem())
                }
            }
        }
    }
}

private enum class AuditFilter(val label: String) {
    ATTENTION("Needs attention"), ALL("All activity"), COMPLETED("Completed");
    fun matches(needsAttention: Boolean) = when (this) {
        ATTENTION -> needsAttention
        ALL -> true
        COMPLETED -> !needsAttention
    }
}

private fun icon(k: AlertKind) = when (k) {
    AlertKind.AUTOMATION -> "AUTO"
    AlertKind.POLICY -> "SAFE"
    AlertKind.NEW_MAIL -> "📧"
    AlertKind.APPROVAL -> "✋"
    AlertKind.SENT -> "✅"
    AlertKind.CRISIS -> "🚨"
    AlertKind.CALLBACK -> "📞"
    AlertKind.ENDED -> "🏁"
    AlertKind.FOLLOW_UP -> "💰"
    AlertKind.ERROR -> "⚠️"
}
