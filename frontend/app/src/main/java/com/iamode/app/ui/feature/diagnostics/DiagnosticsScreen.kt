package com.iamode.app.ui.feature.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.diagnostics.*
import com.iamode.app.core.i18n.tr
import com.iamode.app.data.mail.MailSyncWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.permissions.AppPermission
import com.iamode.app.core.permissions.AppPermissions
import com.iamode.app.ui.components.SectionTitle
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.components.time
import com.iamode.app.ui.theme.IAColors
import com.iamode.app.ui.theme.Motion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(val running: Boolean = false, val results: List<CheckResult> = emptyList(),
    val recent: List<DiagnosticsLog.Entry> = emptyList(), val message: String? = null)

/** Local-only health snapshot. Counts and worker state contain no messages, contacts or credentials. */
data class ReliabilityHealth(
    val reviewNeeded: Int = 0, val queuedReplies: Int = 0, val commitmentReview: Int = 0,
    val activeCommitments: Int = 0, val gmailSyncing: Boolean = false,
    val notificationAccess: Boolean = false, val batteryUnrestricted: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val runner: DiagnosticsRunner, private val log: DiagnosticsLog,
    conversations: ConversationDao, commitments: ChatCommitmentDao,
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState(recent = log.entries()))
    val state = _state.asStateFlow()
    val health = combine(
        conversations.observeActionable(), conversations.observeQueued(), commitments.reviewNeeded(), commitments.active(),
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MailSyncWorker.NOW)
            .map { list -> list.any { it.state == WorkInfo.State.RUNNING } },
    ) { actionable, queued, commitmentReview, activeCommitments, gmailSyncing ->
        ReliabilityHealth(
            reviewNeeded = actionable.count { it.status == "PENDING_APPROVAL" }, queuedReplies = queued.size,
            commitmentReview = commitmentReview.size, activeCommitments = activeCommitments.size,
            gmailSyncing = gmailSyncing,
            notificationAccess = AppPermissions.isGranted(context, AppPermission.NOTIFICATION_ACCESS),
            batteryUnrestricted = AppPermissions.isGranted(context, AppPermission.BATTERY),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReliabilityHealth())
    init { runChecks() }
    fun runChecks() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, results = emptyList()) }
            runner.run { partial -> _state.update { it.copy(results = partial) } }
            _state.update { it.copy(running = false, recent = log.entries()) }
        }
    }
    fun report() = runner.report(_state.value.results)
    fun syncGmailNow() { MailSyncWorker.syncNow(context); _state.update { it.copy(message = tr("Checking Gmail now. Results appear under Recent activity.")) } }
    fun clearLog() { log.clear(); _state.update { it.copy(recent = emptyList()) } }
    fun openSystemSetting(permission: AppPermission) {
        val intent = AppPermissions.settingsIntent(context, permission)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        runCatching { context.startActivity(intent) }.onFailure { showMessage(tr("Couldn't open Android settings")) }
    }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, vm: DiagnosticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text(tr("Connection check")) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "intro") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Tests each step between a message arriving and a reply going out, and says what to fix."))
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::runChecks, enabled = !state.running) { Text(if (state.running) tr("Checkingâ€¦") else tr("Run again")) }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.report())); vm.showMessage(tr("Report copied. It has no messages, tokens or keys, so it's safe to share.")) }, enabled = !state.running && state.results.isNotEmpty()) { Text(tr("Copy report")) }
                }
            } }
            item(key = "reliability") { ReliabilityCard(health, vm::openSystemSetting, Modifier.animateItem()) }
            items(state.results, key = { it.title }) { ResultCard(it, Modifier.animateItem()) }
            item(key = "server") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(tr("Server")); Text("The endpoint is configured when this APK is built. Update frontend/local.properties and rebuild to change it.", style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
            } }
            item(key = "gmail") { Column { SectionTitle(tr("Gmail")); OutlinedButton(onClick = vm::syncGmailNow) { Text(tr("Check Gmail now")) } } }
            item(key = "recent-title") { Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("Recent activity")); Spacer(Modifier.weight(1f)); if (state.recent.isNotEmpty()) TextButton(onClick = vm::clearLog) { Text(tr("Clear")) }
            } }
            if (state.recent.isEmpty()) item(key = "recent-empty") { Text(tr("Nothing recorded yet."), color = IAColors.Grey) }
            // Legacy stored logs can share timestamp and area. Positional keys are safe for this short read-only feed.
            items(state.recent.take(25), key = { index -> "log-$index" }) { e -> Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusIcon(if (e.ok) CheckStatus.OK else CheckStatus.FAIL, Modifier.size(16.dp)); Column {
                    Text("${e.area}, ${time(e.time)}", style = MaterialTheme.typography.labelSmall, color = IAColors.Grey); Text(e.detail)
                }
            } }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ReliabilityCard(health: ReliabilityHealth, openSetting: (AppPermission) -> Unit, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(tr("Reliability status"), style = MaterialTheme.typography.titleMedium)
            Text("${health.reviewNeeded} reply reviews · ${health.queuedReplies} queued replies · ${health.commitmentReview} commitment reviews", style = MaterialTheme.typography.bodyMedium)
            Text("${health.activeCommitments} active commitments · Gmail sync ${if (health.gmailSyncing) "running" else "idle"}", style = MaterialTheme.typography.bodyMedium)
            if (!health.notificationAccess || !health.batteryUnrestricted) {
                Text(tr("Recovery actions"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!health.notificationAccess) OutlinedButton(onClick = { openSetting(AppPermission.NOTIFICATION_ACCESS) }) { Text(tr("Notification access")) }
                    if (!health.batteryUnrestricted) OutlinedButton(onClick = { openSetting(AppPermission.BATTERY) }) { Text(tr("Unrestricted battery")) }
                }
            }
            Text(tr("Recovery actions never send a message automatically."), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
        }
    }
}

@Composable private fun ResultCard(r: CheckResult, modifier: Modifier) {
    val tint = statusColor(r.status)
    Card(modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.08f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatusIcon(r.status, Modifier.size(22.dp)); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium); Text(r.detail); r.fix?.let { Text(tr("Fix: %1\$s", it), fontWeight = FontWeight.Medium, color = tint) }
        } }
    }
}
@Composable private fun StatusIcon(status: CheckStatus, modifier: Modifier) {
    val (icon, label) = when (status) { CheckStatus.OK -> Icons.Filled.CheckCircle to tr("Passed"); CheckStatus.WARN -> Icons.Filled.Warning to tr("Warning"); CheckStatus.FAIL -> Icons.Filled.Error to tr("Failed"); CheckStatus.SKIPPED -> Icons.Filled.RemoveCircleOutline to tr("Skipped") }
    Icon(icon, label, modifier, tint = statusColor(status))
}
private fun statusColor(s: CheckStatus) = when (s) { CheckStatus.OK -> IAColors.Green; CheckStatus.WARN -> IAColors.Amber; CheckStatus.FAIL -> IAColors.Red; CheckStatus.SKIPPED -> IAColors.Grey }
