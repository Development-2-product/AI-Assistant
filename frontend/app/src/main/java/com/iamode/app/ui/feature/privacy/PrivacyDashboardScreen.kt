package com.iamode.app.ui.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.dao.AlertDao
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.dao.ContactDao
import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.dao.MailIntelligenceDao
import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.database.dao.MessageDao
import com.iamode.app.core.datastore.SeenMessageStore
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.service.worker.CommitmentReminderScheduler
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import com.iamode.app.ui.components.readableWidth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyInventory(
    val conversations: Int = 0, val messages: Int = 0, val contacts: Int = 0,
    val commitments: Int = 0, val mailItems: Int = 0, val mailActions: Int = 0, val activity: Int = 0,
)

enum class PrivacyDelete(val title: String, val detail: String) {
    CHAT("Delete chat history", "Deletes local conversations, messages and automation activity. It does not delete messages from WhatsApp, Telegram, Instagram or SMS."),
    CONTACTS("Delete contacts and linked commitments", "Deletes local contact labels, preferences and commitments linked to those contacts."),
    MAIL("Delete email intelligence metadata", "Deletes only IA Mode's local email classifications, drafts, action records, calendar extractions and document selections. It does not delete Gmail messages."),
}

@HiltViewModel
class PrivacyDashboardViewModel @Inject constructor(
    conversations: ConversationDao,
    messages: MessageDao,
    contacts: ContactDao,
    commitments: ChatCommitmentDao,
    mail: MailIntelligenceDao,
    actions: MailActionDao,
    alerts: AlertDao,
    private val conversationRepository: ConversationRepository,
    private val contactDao: ContactDao,
    private val commitmentDao: ChatCommitmentDao,
    private val mailDao: MailIntelligenceDao,
    private val actionDao: MailActionDao,
    private val workflow: MailWorkflowDao,
    private val alertDao: AlertDao,
    private val seen: SeenMessageStore,
    private val toggle: ToggleIAModeUseCase,
    private val reminders: CommitmentReminderScheduler,
    private val exporter: RedactedExport,
) : ViewModel() {
    val inventory: StateFlow<PrivacyInventory> = combine(
        conversations.observeCount(), messages.observeCount(), contacts.observeCount(), commitments.observeCount(),
        mail.observeAll().map { it.size }, actions.observeAll().map { it.size }, alerts.observeCount(),
    ) { counts ->
        PrivacyInventory(
            conversations = counts[0], messages = counts[1], contacts = counts[2], commitments = counts[3],
            mailItems = counts[4], mailActions = counts[5], activity = counts[6],
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyInventory())

    fun delete(target: PrivacyDelete) = viewModelScope.launch {
        when (target) {
            PrivacyDelete.CHAT -> {
                toggle.turnOff()
                conversationRepository.deleteAll()
                alertDao.deleteAll()
                seen.clear()
            }
            PrivacyDelete.CONTACTS -> {
                commitmentDao.all().first().filter { it.contactId != null }.forEach { reminders.cancel(it.id) }
                commitmentDao.deleteContactLinked()
                contactDao.deleteAll()
            }
            PrivacyDelete.MAIL -> {
                actionDao.deleteAllApprovals(); actionDao.deleteAllAudit(); actionDao.deleteAll()
                workflow.deleteAllCandidates(); workflow.deleteAllSelections(); workflow.deleteAllCalendars()
                workflow.deleteAllOpportunities(); workflow.deleteAllCelebrations(); mailDao.deleteAll()
            }
        }
    }
    fun export(password: CharArray, ready: (ByteArray) -> Unit, failed: (String) -> Unit) = viewModelScope.launch {
        runCatching { exporter.create(password) }.onSuccess(ready).onFailure { failed(it.message ?: "Couldn't create export") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(onBack: () -> Unit, vm: PrivacyDashboardViewModel = hiltViewModel()) {
    val inventory by vm.inventory.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PrivacyDelete?>(null) }
    var exportPassword by remember { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes; pendingBytes = null
        if (uri != null && bytes != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("output unavailable") }
            .onSuccess { message = "Encrypted export saved" }.onFailure { message = "Couldn't save export" }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Privacy dashboard") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Stored locally and encrypted", style = MaterialTheme.typography.titleMedium)
            Text("Counts describe IA Mode data on this device. Message bodies, credentials and tokens are never exported from this screen.", style = MaterialTheme.typography.bodySmall)
            PrivacyCard("Chat history", "${inventory.conversations} conversations · ${inventory.messages} messages · ${inventory.activity} activity records", PrivacyDelete.CHAT) { pending = it }
            PrivacyCard("Contacts and commitments", "${inventory.contacts} contacts · ${inventory.commitments} commitments", PrivacyDelete.CONTACTS) { pending = it }
            PrivacyCard("Email intelligence", "${inventory.mailItems} email records · ${inventory.mailActions} action records", PrivacyDelete.MAIL) { pending = it }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Redacted encrypted export", style = MaterialTheme.typography.titleSmall)
                Text("AES-GCM encrypted count-only export. It excludes messages, names, addresses, credentials, tokens and endpoints.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { showExport = true }) { Text("Create encrypted export") }
            } }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(4.dp))
            Text("Gmail account access is managed separately in Settings. Removing local metadata never deletes your provider mailbox.", style = MaterialTheme.typography.bodySmall)
        }
    }
    pending?.let { target -> AlertDialog(
        onDismissRequest = { pending = null }, title = { Text(target.title) }, text = { Text(target.detail) },
        confirmButton = { TextButton(onClick = { vm.delete(target); pending = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
    ) }
    if (showExport) AlertDialog(onDismissRequest = { showExport = false }, title = { Text("Encrypt export") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use a new password with at least 12 characters. IA Mode never stores it.")
            androidx.compose.material3.OutlinedTextField(exportPassword, { exportPassword = it }, label = { Text("Password") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = {
        vm.export(exportPassword.toCharArray(), { bytes -> pendingBytes = bytes; exportLauncher.launch("ia-mode-redacted-export.iamode") }, { message = it })
        exportPassword = ""; showExport = false
    }, enabled = exportPassword.length >= 12) { Text("Create") } }, dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } })
}

@Composable
private fun PrivacyCard(title: String, summary: String, delete: PrivacyDelete, onDelete: (PrivacyDelete) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onDelete(delete) }) { Text(delete.title) }
    } }
}
