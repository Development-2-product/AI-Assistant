package com.iamode.app.ui.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.usecase.BuildSessionSummaryUseCase
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.components.time
import com.iamode.app.ui.theme.IAColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    sessions: SessionRepository,
    buildSummary: BuildSessionSummaryUseCase,
) : ViewModel() {
    val summaries: StateFlow<List<SessionSummary>> = sessions.observeRecentEnded()
        .map { sessions -> sessions.mapNotNull { buildSummary(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Retained local sessions. The screen never sends history to the backend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, openSummary: (String) -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Activity history") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        if (summaries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState("No completed sessions yet", "When IA Mode is turned off, that session will appear here.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).readableWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Your recent activity", style = MaterialTheme.typography.headlineSmall)
                    Text("Stored securely on this device and kept according to your privacy settings.",
                        style = MaterialTheme.typography.bodyMedium, color = IAColors.Grey)
                }
                items(summaries, key = { it.session.id }) { summary ->
                    HistoryCard(summary, onClick = { openSummary(summary.session.id) })
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(summary: SessionSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.primary)
                Text("  ${sessionDate(summary.session.endedAt ?: summary.session.startedAt)}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("View", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(summary.headline.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
            Text("${time(summary.session.startedAt)} · ${duration(summary.durationMinutes)}",
                style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
        }
    }
}

private fun sessionDate(timestamp: Long): String = java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
    .format(java.util.Date(timestamp))

private fun duration(minutes: Long): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
