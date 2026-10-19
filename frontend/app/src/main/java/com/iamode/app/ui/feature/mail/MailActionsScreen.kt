package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.CalendarExtractionEntity
import com.iamode.app.core.database.entity.DocumentSelectionEntity
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.core.database.entity.MailAuditEntity
import com.iamode.app.data.mail.MailActionExecutor
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.data.mail.MailItem
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.pressScale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ActionCard(
    val action: MailActionEntity,
    val mail: MailItem?,
    val attachment: DocumentSelectionEntity?,
    val event: CalendarExtractionEntity?,
) {
    /** Quick approval only when nothing is left to decide; otherwise the user reviews first. */
    val readyToSend: Boolean get() = action.draftBody != null && (action.actionType != MailActionType.SEND_DOCUMENT_REPLY.name || attachment != null)
}

data class ActionsState(
    val chats: List<Conversation> = emptyList(), val cards: List<ActionCard> = emptyList(), val audit: List<MailAuditEntity> = emptyList(),
    val commitmentReview: Int = 0, val commitmentUpcoming: Int = 0, val commitmentOverdue: Int = 0,
)

@HiltViewModel
class MailActionsViewModel @Inject constructor(
    private val repo: MailIntelligenceRepository,
    private val executor: MailActionExecutor,
    private val conversations: ConversationRepository,
    private val commitments: ChatCommitmentDao,
) : ViewModel() {
    private val mailState = combine(repo.pendingActions, repo.mail, repo.allSelections, repo.allCalendars, repo.audit) { actions, mail, sel, cal, audit ->
        val byId = mail.associateBy { it.emailId }
        ActionsState(
            cards = actions.map { a -> ActionCard(a, byId[a.emailId], sel.lastOrNull { it.actionId == a.id }, cal.firstOrNull { it.emailId == a.emailId }) },
            audit = audit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActionsState())
    val state = combine(mailState, conversations.observeActionable(), commitments.all()) { mail, chats, list ->
        val now = System.currentTimeMillis()
        mail.copy(chats = chats, commitmentReview = list.count { it.status == "REVIEW_NEEDED" },
            commitmentUpcoming = list.count { it.status in setOf("CONFIRMED", "SNOOZED") && (it.dueAt == null || it.dueAt >= now) },
            commitmentOverdue = list.count { it.status in setOf("CONFIRMED", "SNOOZED") && it.dueAt != null && it.dueAt < now })
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActionsState())
    val message = MutableStateFlow<String?>(null)

    fun approveAndSend(id: String) = viewModelScope.launch {
        if (!repo.approve(id)) { message.value = tr("Couldn't approve this action"); return@launch }
        message.value = when (val r = executor.execute(id)) {
            is MailActionExecutor.Outcome.Done -> r.message
            is MailActionExecutor.Outcome.Blocked -> r.reason
            is MailActionExecutor.Outcome.Failed -> tr("Failed: %1\$s", (r.reason))
        }
    }

    fun dismiss(id: String) = viewModelScope.launch { repo.reject(id) }
}

private val stamp = DateTimeFormatter.ofPattern("MMM d, HH:mm")
private fun at(ms: Long?) = ms?.let { stamp.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailActionsScreen(
    onBack: () -> Unit, openConversation: (Conversation) -> Unit, openCommitments: () -> Unit, openReply: (String) -> Unit, openCalendar: (String) -> Unit, openEmail: (String) -> Unit,
    vm: MailActionsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val seen = remember { mutableSetOf<String>() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("⚡ AI Actions")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Text(tr("One review queue for chats and email. Nothing is sent, attached or added to your calendar without you."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.chats.isEmpty() && state.cards.isEmpty()) item(key = "empty") { EmptyState(tr("You're all caught up"), tr("New actions appear here when a chat or email needs something from you.")) }
            if (state.chats.isNotEmpty()) {
                item(key = "chat-title") { Text(tr("Chat decisions"), style = MaterialTheme.typography.titleMedium) }
                items(state.chats, key = { "chat-${it.id}" }) { chat -> ChatActionCard(chat, Modifier.animateItem(), { openConversation(chat) }) }
            }
            item(key = "commitments") { Card(onClick = openCommitments, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp)) { Text(tr("Commitments"), style = MaterialTheme.typography.titleMedium)
                    Text("${state.commitmentReview} need review · ${state.commitmentOverdue} overdue · ${state.commitmentUpcoming} upcoming", style = MaterialTheme.typography.bodySmall)
                }
            } }
            if (state.cards.isNotEmpty()) item(key = "mail-title") { Text(tr("Email actions"), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(state.cards, key = { _, it -> it.action.id }) { index, card ->
                com.iamode.app.ui.components.Entrance(card.action.id, index, seen) {
                ActionCardView(card, modifier = Modifier.animateItem(),
                    onReview = {
                        when (MailActionType.valueOf(card.action.actionType)) {
                            MailActionType.CREATE_CALENDAR_EVENT -> openCalendar(card.action.id)
                            MailActionType.REVIEW_EMAIL -> openEmail(card.action.emailId)
                            else -> openReply(card.action.id)
                        }
                    },
                    onQuickApprove = { vm.approveAndSend(card.action.id) },
                    onDismiss = { vm.dismiss(card.action.id) })
                }
            }
            if (state.audit.isNotEmpty()) {
                item(key = "audit-title") { Text(tr("Activity"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(state.audit, key = { "audit-${it.id}" }) { AuditRow(it) }
            }
        }
    }
}

@Composable
private fun ChatActionCard(chat: Conversation, modifier: Modifier, onReview: () -> Unit) {
    val (headline, detail, tint) = when (chat.status) {
        ConversationStatus.CRISIS -> Triple(tr("Personal attention needed"), tr("Potential crisis detected. IA Mode did not reply."), MaterialTheme.colorScheme.error)
        ConversationStatus.CALLBACK -> Triple(tr("Call back"), chat.reason ?: tr("A missed call needs your follow-up."), MaterialTheme.colorScheme.tertiary)
        else -> Triple(tr("Reply needs approval"), chat.reason ?: tr("Review the draft before anything is sent."), MaterialTheme.colorScheme.primary)
    }
    Card(onClick = onReview, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${chat.channel.label} · ${chat.displayName}", style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = onReview) { Text(tr("Review safely")) }
        }
    }
}

@Composable
private fun ActionCardView(card: ActionCard, modifier: Modifier, onReview: () -> Unit, onQuickApprove: () -> Unit, onDismiss: () -> Unit) {
    val a = card.action
    val type = MailActionType.valueOf(a.actionType)
    val (glyph, headline) = when (type) {
        MailActionType.SEND_DOCUMENT_REPLY -> "📎" to tr("Send document")
        MailActionType.CREATE_CALENDAR_EVENT -> "🎯" to (if (card.mail?.primary?.name == "INTERVIEW_INVITATION") tr("Interview detected") else tr("Event detected"))
        MailActionType.SEND_REPLY -> "↩" to tr("Reply suggested")
        MailActionType.UNSUBSCRIBE_EMAIL -> "📢" to tr("Unsubscribe")
        MailActionType.SEND_FOLLOW_UP -> "📨" to tr("Follow-up ready")
        MailActionType.REVIEW_EMAIL -> "⚠" to tr("Review carefully")
    }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(onClick = onReview, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().then(Modifier.pressScale(interaction)), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(glyph, fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (a.status == "FAILED") Text(tr("Failed"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                if (a.status == "EXECUTING") Text(tr("Working…"), style = MaterialTheme.typography.labelMedium)
            }
            card.mail?.let { m ->
                val doc = m.document
                Text(
                    when (type) {
                        MailActionType.SEND_DOCUMENT_REPLY -> "${m.sender} requested ${doc?.description ?: doc?.type?.label?.lowercase() ?: "a document"}${doc?.requestedFormat?.let { " ($it)" } ?: ""}"
                        MailActionType.CREATE_CALENDAR_EVENT -> listOfNotNull(m.opportunity?.role, m.opportunity?.company).joinToString("\n").ifBlank { m.entity.subject.orEmpty() }
                        else -> "${m.sender}: ${m.entity.summary ?: m.entity.subject.orEmpty()}"
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (m.suspiciousReasons.isNotEmpty()) Text("⚠ ${m.suspiciousReasons.first()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            card.attachment?.let { Text(tr("Attachment: %1\$s", (it.displayName)), style = MaterialTheme.typography.labelLarge) }
            if (type == MailActionType.CREATE_CALENDAR_EVENT) card.event?.let { e ->
                Text(if (e.ambiguous) tr("⚠ Date/time needs confirmation") else "${e.date} · ${e.startTime}${e.endTime?.let { "–$it" } ?: ""}",
                    style = MaterialTheme.typography.labelLarge)
            }
            a.error?.takeIf { a.status == "FAILED" }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = onReview) { Text(if (type == MailActionType.CREATE_CALENDAR_EVENT) tr("Review & add") else tr("Review")) }
                if (card.readyToSend && type != MailActionType.CREATE_CALENDAR_EVENT && type != MailActionType.REVIEW_EMAIL && a.status != "EXECUTING") {
                    Button(onClick = onQuickApprove) { Text(tr("Approve & Send")) }
                }
                Spacer(Modifier.weight(1f))
                if (a.status != "EXECUTING") OutlinedButton(onClick = onDismiss) { Text(tr("Dismiss")) }
            }
        }
    }
}

@Composable
private fun AuditRow(e: MailAuditEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(e.actionType.replace('_', ' '), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(e.status, style = MaterialTheme.typography.labelMedium,
                color = if (e.status == "SUCCESS") androidx.compose.ui.graphics.Color(0xFF12B886) else MaterialTheme.colorScheme.error)
        }
        Text(listOfNotNull(
            tr("To %1\$s", (e.target)), e.attachmentNames?.let { "📎 $it" }, e.calendarEventId?.let { "Event #$it" },
            "Approved by ${e.actor?.lowercase() ?: "you"}${at(e.approvedAt)?.let { " at $it" } ?: ""}",
            at(e.executedAt)?.let { tr("Done %1\$s", it) }, e.error,
        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
