package com.iamode.app.ui.feature.commitments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.entity.ChatCommitmentEntity
import com.iamode.app.core.i18n.tr
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.service.worker.CommitmentReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel class CommitmentViewModel @Inject constructor(
    private val dao: ChatCommitmentDao,
    private val reminders: CommitmentReminderScheduler,
    private val conversations: ConversationRepository,
) : ViewModel() {
    val items: StateFlow<List<ChatCommitmentEntity>> = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Detail screens observe one encrypted Room row, not the complete tracker collection. */
    fun observeCommitment(id: String): Flow<ChatCommitmentEntity?> = dao.observe(id)
    fun confirm(x: ChatCommitmentEntity) = viewModelScope.launch {
        val reminderAt = x.reminderAt ?: x.dueAt
        dao.confirm(x.id, x.dueAt, reminderAt, System.currentTimeMillis())
        reminders.schedule(x.id, reminderAt)
    }
    fun complete(x: ChatCommitmentEntity) = viewModelScope.launch { dao.complete(x.id, System.currentTimeMillis()); reminders.cancel(x.id) }
    fun dismiss(x: ChatCommitmentEntity) = viewModelScope.launch { dao.dismiss(x.id, System.currentTimeMillis()); reminders.cancel(x.id) }
    fun delete(x: ChatCommitmentEntity) = viewModelScope.launch { reminders.cancel(x.id); dao.delete(x.id) }
    fun snooze(x: ChatCommitmentEntity) = viewModelScope.launch {
        val n = System.currentTimeMillis(); val until = n + 3_600_000
        dao.snooze(x.id, until, n); reminders.schedule(x.id, until)
    }
    fun edit(x: ChatCommitmentEntity, text: String, dueAt: Long?, reminderAt: Long?) = viewModelScope.launch {
        dao.edit(x.id, text.trim(), text.trim().lowercase(), dueAt, reminderAt, System.currentTimeMillis())
        reminders.schedule(x.id, reminderAt)
    }
    fun saveFollowUpDraft(x: ChatCommitmentEntity, text: String) = viewModelScope.launch {
        dao.updateFollowUpDraft(x.id, text.trim().ifBlank { null }, System.currentTimeMillis())
    }
    fun openFollowUpForApproval(x: ChatCommitmentEntity, text: String, onReady: (String) -> Unit) = viewModelScope.launch {
        val draft = text.trim()
        if (draft.isBlank()) return@launch
        dao.updateFollowUpDraft(x.id, draft, System.currentTimeMillis())
        val conversation = conversations.get(x.conversationId) ?: return@launch
        // This only prepares the existing editable approval UI. Sending still needs its explicit button.
        conversations.upsert(conversation.copy(
            status = ConversationStatus.PENDING_APPROVAL,
            pendingReply = draft,
            autopilot = false,
            sendAt = null,
            reason = "Follow-up draft awaiting your approval",
            updatedAt = System.currentTimeMillis(),
        ))
        onReady(conversation.id)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitmentTrackerScreen(onBack: () -> Unit, openDetail: (String) -> Unit, vm: CommitmentViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatCommitmentEntity?>(null) }
    val active = items.filter { it.status !in setOf("COMPLETED", "DISMISSED") }
    val reviewCount = active.count { it.status == "REVIEW_NEEDED" }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Commitments")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "commitment-overview") {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Column(
                        Modifier.background(Brush.linearGradient(listOf(Color(0xFF24206F), Color(0xFF0A3C55))))
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(tr("Keep every promise in view"), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(tr("%1\$s active · %2\$s need review", active.size, reviewCount), color = Color(0xFFC8DAFF), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("Detected commitments stay on this phone. Nothing is sent automatically."), color = Color(0xFFAFC8E8), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (active.isEmpty()) {
                item(key = "empty") { EmptyState(tr("No commitments yet"), tr("When IA Mode detects a future commitment, it will appear here for review.")) }
            }
            items(active, key = { it.id }) { item ->
                CommitmentCard(
                    item = item,
                    onOpen = { openDetail(item.id) },
                    onEdit = { editing = item },
                    onConfirm = { vm.confirm(item) },
                    onComplete = { vm.complete(item) },
                    onSnooze = { vm.snooze(item) },
                )
            }
        }
    }
    editing?.let { item ->
        EditCommitment(item, { editing = null }) { text, dueAt, reminderAt ->
            vm.edit(item, text, dueAt, reminderAt)
            editing = null
        }
    }
}

@Composable
private fun CommitmentCard(
    item: ChatCommitmentEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onComplete: () -> Unit,
    onSnooze: () -> Unit,
) {
    val overdue = isOverdue(item)
    val needsReview = item.status == "REVIEW_NEEDED"
    val accent = when {
        overdue -> Color(0xFFFF6578)
        needsReview -> Color(0xFFFFB84D)
        else -> Color(0xFF51B6FF)
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .17f))) {
                    Icon(
                        if (needsReview) Icons.Filled.Schedule else Icons.Filled.CheckCircle,
                        null,
                        tint = accent,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.commitmentText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(if (item.needsConfirmation) tr("Date needs confirmation") else dueLabel(item.dueAt), style = MaterialTheme.typography.bodySmall, color = accent)
                }
                TextButton(onClick = onOpen) { Text(tr("Details")) }
            }
            item.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (needsReview) onConfirm else onComplete, modifier = Modifier.weight(1f)) {
                    Text(if (needsReview) tr("Confirm") else tr("Complete"))
                }
                OutlinedButton(onClick = onEdit) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(tr("Edit")) }
            }
            TextButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) { Text(tr("Snooze for 1 hour")) }
        }
    }
}
private enum class ReminderPreset(val label: String, val minutesBefore: Long?) {
    NONE("No reminder", null), AT_DUE_TIME("At due time", 0), ONE_HOUR_BEFORE("1 hour before", 60), ONE_DAY_BEFORE("1 day before", 24 * 60);
}

private fun reminderAt(dueAt: Long?, preset: ReminderPreset): Long? =
    dueAt?.let { due -> preset.minutesBefore?.let { due - it * 60_000L } }

@Composable
private fun EditCommitment(
    x: ChatCommitmentEntity,
    onDismiss: () -> Unit,
    onSave: (String, Long?, Long?) -> Unit,
) {
    val context = LocalContext.current
    var text by remember(x.id) { mutableStateOf(x.commitmentText) }
    var dueAt by remember(x.id) { mutableStateOf(x.dueAt) }
    var preset by remember(x.id) { mutableStateOf(ReminderPreset.AT_DUE_TIME) }
    val pickDate = {
        val value = Calendar.getInstance().apply { timeInMillis = dueAt ?: System.currentTimeMillis() }
        DatePickerDialog(context, { _, year, month, day ->
            value.set(year, month, day)
            dueAt = value.timeInMillis
        }, value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show()
    }
    val pickTime = {
        val value = Calendar.getInstance().apply { timeInMillis = dueAt ?: System.currentTimeMillis() }
        TimePickerDialog(context, { _, hour, minute ->
            value.set(Calendar.HOUR_OF_DAY, hour)
            value.set(Calendar.MINUTE, minute)
            value.set(Calendar.SECOND, 0)
            value.set(Calendar.MILLISECOND, 0)
            dueAt = value.timeInMillis
        }, value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit commitment")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(tr("Commitment")) })
                Text(dueAt?.let(::dueLabel) ?: "No due date selected", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pickDate) { Text("Choose date") }
                    OutlinedButton(onClick = pickTime) { Text("Choose time") }
                    if (dueAt != null) TextButton(onClick = { dueAt = null; preset = ReminderPreset.NONE }) { Text("Clear") }
                }
                Text("Reminder", style = MaterialTheme.typography.labelLarge)
                ReminderPreset.entries.forEach { option ->
                    FilterChip(selected = preset == option, onClick = { preset = option }, label = { Text(option.label) }, enabled = dueAt != null || option == ReminderPreset.NONE)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, dueAt, reminderAt(dueAt, preset)) }, enabled = text.isNotBlank()) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitmentDetailScreen(commitmentId: String, onBack: () -> Unit, openConversation: (String) -> Unit, vm: CommitmentViewModel = hiltViewModel()) {
    val commitmentFlow = remember(commitmentId) { vm.observeCommitment(commitmentId) }
    val x by commitmentFlow.collectAsStateWithLifecycle(initialValue = null)
    val commitment = x
    var editing by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(tr("Commitment")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } }) }) { padding ->
        if (commitment == null) Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text(tr("This commitment is no longer available.")) }
        else Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(commitment.commitmentText, style = MaterialTheme.typography.headlineSmall)
            Text(statusLabel(commitment), style = MaterialTheme.typography.labelLarge)
            Text(dueLabel(commitment.dueAt), style = MaterialTheme.typography.bodyLarge)
            commitment.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { openConversation(commitment.conversationId) }) { Text(tr("Open source conversation")) }
            if (isOverdue(commitment)) Button(onClick = { drafting = true }) { Text(tr("Draft follow-up")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (commitment.status == "REVIEW_NEEDED") Button(onClick = { vm.confirm(commitment) }) { Text(tr("Confirm")) }
                else Button(onClick = { vm.complete(commitment) }) { Text(tr("Complete")) }
                OutlinedButton(onClick = { editing = true }) { Text(tr("Edit")) }
                OutlinedButton(onClick = { vm.snooze(commitment) }) { Text(tr("Snooze 1 hour")) }
            }
            Row { TextButton(onClick = { vm.dismiss(commitment); onBack() }) { Text(tr("Dismiss")) }; TextButton(onClick = { vm.delete(commitment); onBack() }) { Text(tr("Delete")) } }
        }
    }
    if (commitment != null && editing) EditCommitment(commitment, { editing = false }) { text, dueAt, reminderAt -> vm.edit(commitment, text, dueAt, reminderAt); editing = false }
    if (commitment != null && drafting) FollowUpDraft(commitment, { drafting = false }) { draft -> vm.openFollowUpForApproval(commitment, draft) { conversationId -> drafting = false; openConversation(conversationId) } }
}

@Composable private fun FollowUpDraft(x: ChatCommitmentEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(x.id) { mutableStateOf(x.followUpDraft ?: "Just following up on this. Let me know when you get a chance.") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Draft follow-up")) }, text = { Column { OutlinedTextField(text, { text = it }, label = { Text(tr("Message draft")) }); Text(tr("Continue to approval to review and explicitly send this draft. IA Mode never sends commitment follow-ups automatically."), style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Continue to approval")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } })
}

private fun isOverdue(x: ChatCommitmentEntity) = x.status in setOf("CONFIRMED", "SNOOZED") && x.dueAt?.let { it < System.currentTimeMillis() } == true
private fun statusLabel(x: ChatCommitmentEntity) = when { isOverdue(x) -> "Overdue"; x.status == "REVIEW_NEEDED" -> "Review needed"; else -> x.status.lowercase().replaceFirstChar { it.uppercase() } }
private fun dueLabel(at: Long?): String {
    at ?: return "No due date"
    val now = java.util.Calendar.getInstance(); val due = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val days = ((due.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis - (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L
    return when { days == 0L -> "Due today"; days == 1L -> "Due tomorrow"; days > 1L -> "Due in $days days"; days == -1L -> "Overdue by 1 day"; else -> "Overdue by ${-days} days" }
}
