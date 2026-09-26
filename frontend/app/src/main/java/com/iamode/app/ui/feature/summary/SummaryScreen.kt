package com.iamode.app.ui.feature.summary

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.foundation.layout.Box
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.model.StartSource
import com.iamode.app.domain.usecase.BuildSessionSummaryUseCase
import com.iamode.app.ui.components.ConversationCard
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.components.time
import com.iamode.app.ui.theme.IAColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SummaryUiState {
    data object Loading : SummaryUiState
    data object Missing : SummaryUiState
    data class Ready(val summary: SessionSummary) : SummaryUiState
}

@HiltViewModel
class SummaryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    build: BuildSessionSummaryUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<SummaryUiState>(SummaryUiState.Loading)
    val state = _state.asStateFlow()

    init {
        val id: String = checkNotNull(savedState["sessionId"])
        viewModelScope.launch { _state.value = build(id)?.let { SummaryUiState.Ready(it) } ?: SummaryUiState.Missing }
    }
}

/** "While you were busy": what happened during one IA Mode session, and what still needs you. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(onBack: () -> Unit, openConversation: (Conversation) -> Unit, vm: SummaryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("While you were busy")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
        )
    }) { padding ->
        when (val s = state) {
            SummaryUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            SummaryUiState.Missing -> EmptyState(tr("Summary not found"), tr("It may have been deleted with your conversation history."),
                Modifier.padding(padding))
            is SummaryUiState.Ready -> SummaryContent(s.summary, openConversation, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SummaryContent(summary: SessionSummary, openConversation: (Conversation) -> Unit, modifier: Modifier) {
    val session = summary.session
    LazyColumn(
        modifier.fillMaxSize().readableWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.headline.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineSmall)
                val span = "${time(session.startedAt)} to ${session.endedAt?.let(::time) ?: "now"}, ${duration(summary.durationMinutes)}"
                Text(span, style = MaterialTheme.typography.bodyMedium, color = IAColors.Grey)
                if (session.startedBy == StartSource.AUTO && session.autoReason != null) {
                    Text(tr("Turned on automatically. %1\$s", (session.autoReason)), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
                }
            }
        }
        item(key = "stats") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${summary.repliesSent}", tr("replies sent"), Modifier.weight(1f))
                Stat("${summary.autoReplies}", "automatic", Modifier.weight(1f))
                Stat("${summary.missedCalls}", tr("missed calls"), Modifier.weight(1f))
                Stat("${summary.emails}", "emails", Modifier.weight(1f))
            }
        }
        section(tr("Needs you"), tr("Reply, approve or call back"), summary.needsYou, openConversation)
        section(tr("Follow up"), tr("Money or a commitment came up. IA Mode replied without agreeing to anything."),
            summary.followUps, openConversation)
        section(tr("Handled"), null, summary.handled.filter { it !in summary.followUps }, openConversation)
        if (summary.conversations.isEmpty()) {
            item(key = "empty") { EmptyState(tr("A quiet session"), tr("Nobody messaged or called while IA Mode was on.")) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, subtitle: String?, list: List<Conversation>, open: (Conversation) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "title-$title") {
        Column(Modifier.padding(top = 12.dp)) {
            Text("$title (${list.size})", style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = IAColors.Grey) }
        }
    }
    items(list, key = { "$title-${it.id}" }) { c -> ConversationCard(c, onClick = { open(c) }, modifier = Modifier.animateItem()) }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = IAColors.Grey, maxLines = 1)
        }
    }
}

private fun duration(minutes: Long) = if (minutes < 60) "$minutes min" else tr("%1\$s h %2\$s min", (minutes / 60), (minutes % 60))
