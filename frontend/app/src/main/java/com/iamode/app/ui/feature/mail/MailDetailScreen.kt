package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.data.mail.CelebrationCoordinator
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.ui.components.sharedTransition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MailDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: MailIntelligenceRepository,
    private val celebrations: CelebrationCoordinator,
) : ViewModel() {
    val emailId: String = checkNotNull(saved["emailId"])
    val item = repo.mailItem(emailId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val actions = repo.actionsFor(emailId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calendar = repo.calendar(emailId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasCelebration = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repo.markDetailsViewed(emailId)
            hasCelebration.value = celebrations.forEmail(emailId) != null
        }
    }

    fun replay() = viewModelScope.launch { celebrations.replay(emailId) }
    val labels = MutableStateFlow<List<String>>(emptyList())

    fun archive(archived: Boolean) = viewModelScope.launch { message.value = repo.setArchived(emailId, archived) }
    fun markRead() = viewModelScope.launch { message.value = repo.markRead(emailId) }
    fun loadLabels() = viewModelScope.launch { labels.value = repo.gmailLabels(emailId) }
    fun moveTo(label: String) = viewModelScope.launch { message.value = repo.moveTo(emailId, label) }
    fun mute(address: String) = viewModelScope.launch { repo.mute(address); message.value = tr("Muted. Future mail from %1\$s goes to Archive", address) }
    fun unsubscribeOptions() = repo.unsubscribeOptions(item.value?.entity?.listUnsubscribe)
    fun proposeUnsubscribe(address: String, then: (String) -> Unit) = viewModelScope.launch { then(repo.proposeUnsubscribeEmail(emailId, address)) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MailDetailScreen(
    onBack: () -> Unit, openReply: (String) -> Unit, openCalendar: (String) -> Unit, vm: MailDetailViewModel = hiltViewModel(),
) {
    val item by vm.item.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    val hasCelebration by vm.hasCelebration.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var unsubscribeDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    val labels by vm.labels.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("Email")) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
        }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val it = item ?: return@Scaffold
        val look = it.primary.look()
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.sharedTransition("mail-avatar-${vm.emailId}")) {
                    SenderAvatar(it.sender, look.accent, 52)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.sender, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(it.entity.fromAddress.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(friendlyTime(it.entity.receivedAt), style = MaterialTheme.typography.labelMedium)
            }
            Text(it.entity.subject.orEmpty().ifBlank { tr("(no subject)") }, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.sharedTransition("mail-subject-${vm.emailId}"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryChip(it.primary)
                it.labels.filter { l -> l != it.primary }.forEach { l -> CategoryChip(l) }
            }
            if (it.suspiciousReasons.isNotEmpty()) SuspiciousBanner(it.suspiciousReasons)

            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = look.accent.copy(alpha = 0.08f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("✦ AI summary"), style = MaterialTheme.typography.labelLarge, color = look.accent)
                    Text(it.entity.summary.orEmpty().ifBlank { tr("No summary") }, style = MaterialTheme.typography.bodyLarge)
                    it.opportunity?.let { o ->
                        listOfNotNull(o.role, o.company).takeIf { l -> l.isNotEmpty() }?.let { l -> Text(l.joinToString(" · "), fontWeight = FontWeight.Medium) }
                    }
                    it.document?.let { d ->
                        Text("📎 Requested: ${d.description ?: d.type.label}${d.requestedFormat?.let { f -> " ($f)" } ?: ""}")
                        d.deadline?.let { dl -> Text(tr("Deadline: %1\$s", dl), style = MaterialTheme.typography.bodySmall) }
                    }
                    calendar?.let { c ->
                        Text(if (c.ambiguous) "📅 ${c.dateText ?: "Date to confirm"} · needs confirmation"
                        else "📅 ${c.date} · ${c.startTime}${c.endTime?.let { e -> "–$e" } ?: ""} ${c.timezone ?: ""}")
                    }
                }
            }

            if (actions.isNotEmpty()) {
                Text(tr("Suggested actions"), style = MaterialTheme.typography.titleMedium)
                actions.forEach { a ->
                    FilledTonalButton(
                        onClick = {
                            when (MailActionType.valueOf(a.actionType)) {
                                MailActionType.CREATE_CALENDAR_EVENT -> openCalendar(a.id)
                                MailActionType.REVIEW_EMAIL -> Unit
                                else -> openReply(a.id)
                            }
                        },
                        enabled = a.actionType != MailActionType.REVIEW_EMAIL.name,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(actionLabel(a)) }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val outlookMail = com.iamode.app.domain.mail.MailIds.providerOf(it.emailId) == com.iamode.app.domain.mail.MailProviderKind.OUTLOOK
                OutlinedButton(onClick = {
                    if (outlookMail) {
                        // The Outlook app if installed, otherwise Outlook on the web.
                        val app = context.packageManager.getLaunchIntentForPackage("com.microsoft.office.outlook")
                        runCatching { context.startActivity(app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://outlook.office.com/mail/"))) }
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/#all/${it.entity.threadId}")))
                    }
                }) { Text(if (outlookMail) tr("Open in Outlook") else tr("Open in Gmail")) }
                OutlinedButton(onClick = { vm.archive(!it.entity.archived) }) { Text(if (it.entity.archived) tr("Move to Inbox") else tr("Archive")) }
                OutlinedButton(onClick = vm::markRead) { Text(tr("Mark as read")) }
                OutlinedButton(onClick = { vm.loadLabels(); moveDialog = true }) { Text(tr("Move to…")) }
                if (it.primary == MailCategory.PROMOTION || it.entity.listUnsubscribe != null) {
                    it.entity.fromAddress?.let { from -> OutlinedButton(onClick = { vm.mute(from) }) { Text(tr("Mute sender")) } }
                    if (vm.unsubscribeOptions().isNotEmpty()) OutlinedButton(onClick = { unsubscribeDialog = true }) { Text(tr("Unsubscribe")) }
                }
                if (hasCelebration) OutlinedButton(onClick = vm::replay) { Text(tr("🎉 Replay celebration")) }
            }
        }
    }

    if (moveDialog) {
        var newLabel by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text(tr("Move to label")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    labels.forEach { l ->
                        TextButton(onClick = { moveDialog = false; vm.moveTo(l) }, modifier = Modifier.fillMaxWidth()) {
                            Text(l, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(newLabel, { newLabel = it.take(100) }, singleLine = true,
                        label = { Text(tr("New label")) }, modifier = Modifier.fillMaxWidth())
                    Text(tr("The email leaves your inbox and gets this label in Gmail. You can move it back any time."),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { moveDialog = false; vm.moveTo(newLabel.trim()) }, enabled = newLabel.isNotBlank()) { Text(tr("Create & move")) } },
            dismissButton = { TextButton(onClick = { moveDialog = false }) { Text(tr("Cancel")) } },
        )
    }

    if (unsubscribeDialog) {
        val options = vm.unsubscribeOptions()
        AlertDialog(
            onDismissRequest = { unsubscribeDialog = false },
            title = { Text(tr("Unsubscribe?")) },
            text = { Text(tr("Nothing happens until you confirm. By email, you'll review the message before it's sent. On the web, the sender's page opens in your browser.")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    options.forEach { o ->
                        when (o) {
                            is MailIntelligenceRepository.Unsubscribe.ByEmail -> TextButton(onClick = {
                                unsubscribeDialog = false; vm.proposeUnsubscribe(o.address, openReply)
                            }) { Text(tr("Review unsubscribe email")) }
                            is MailIntelligenceRepository.Unsubscribe.ByWeb -> TextButton(onClick = {
                                unsubscribeDialog = false; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.url)))
                            }) { Text(tr("Open unsubscribe page")) }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { unsubscribeDialog = false }) { Text(tr("Cancel")) } },
        )
    }
}
