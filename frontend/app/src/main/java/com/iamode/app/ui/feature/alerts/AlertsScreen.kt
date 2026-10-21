package com.iamode.app.ui.feature.alerts

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                AlertOverview(total = 0, attention = 0)
                EmptyState(tr("No alerts yet"), tr("Automation activity and recovery steps will appear here when needed."))
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            AlertOverview(total = list.size, attention = list.count { AutomationAudit.present(it).needsAttention })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(displayed, key = { it.id }) { a ->
                    AlertCard(a, onOpenConversation = openConversation, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun AlertOverview(total: Int, attention: Int) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10253F)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = if (attention > 0) Color(0xFFFFB84D).copy(alpha = .18f) else Color(0xFF3E9CFF).copy(alpha = .18f))) {
                Text(if (attention > 0) "!" else "✓", color = if (attention > 0) Color(0xFFFFC867) else Color(0xFF72B9FF), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Activity center"), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (attention > 0) tr("%1\$s item(s) need your review", attention) else tr("All caught up — %1\$s recent activity items", total),
                    color = Color(0xFFBED4F2), style = MaterialTheme.typography.bodySmall,
                )
                Text(tr("Privacy-safe records only. Open a conversation to review any draft."), color = Color(0xFF829ABA), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AlertCard(alert: Alert, onOpenConversation: (String) -> Unit, modifier: Modifier) {
    val presentation = AutomationAudit.present(alert)
    val needsAttention = presentation.needsAttention
    val accent = if (needsAttention) Color(0xFFFFA847) else Color(0xFF49B6F2)
    Card(
        modifier = modifier.fillMaxWidth().clickable(enabled = alert.conversationId != null) { alert.conversationId?.let(onOpenConversation) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .16f))) {
                Text(icon(alert.kind), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(presentation.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(time(alert.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(presentation.summary, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (needsAttention) tr("Needs attention") else tr("Handled"), color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    presentation.actionLabel?.takeIf { alert.conversationId != null }?.let { label ->
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { alert.conversationId?.let(onOpenConversation) }) { Text(tr(label)) }
                    }
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
