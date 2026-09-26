package com.iamode.app.ui.feature.jobs

import com.iamode.app.core.i18n.tr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.ApplicationEventEntity
import com.iamode.app.data.jobs.JobTrackerRepository
import com.iamode.app.domain.jobs.ApplicationEventType
import com.iamode.app.domain.jobs.ApplicationStage
import com.iamode.app.ui.components.rememberHaptics
import com.iamode.app.ui.feature.mail.SenderAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ApplicationDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val tracker: JobTrackerRepository,
) : ViewModel() {
    val id: String = checkNotNull(saved["id"])
    val app = tracker.application(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events = tracker.events(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun setStage(s: ApplicationStage) = viewModelScope.launch { tracker.setStage(id, s) }
    fun save(company: String, role: String, location: String, url: String, notes: String) =
        viewModelScope.launch { tracker.updateDetails(id, company, role, location, url, notes); message.value = tr("Saved") }
    fun note(text: String) = viewModelScope.launch { tracker.addNote(id, text) }
    fun delete(then: () -> Unit) = viewModelScope.launch { tracker.delete(id); then() }
    fun followUp(open: (String) -> Unit) = viewModelScope.launch {
        tracker.proposeFollowUp(id)?.let(open) ?: run { message.value = tr("No recruiter email to reply to yet") }
    }
}

private val when_ = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private fun fmt(ms: Long) = when_.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApplicationDetailScreen(
    onBack: () -> Unit, openEmail: (String) -> Unit, openReply: (String) -> Unit, vm: ApplicationDetailViewModel = hiltViewModel(),
) {
    val a by vm.app.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var stageMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var newNote by remember { mutableStateOf("") }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(tr("Application")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, tr("Delete")) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val app = a ?: return@Scaffold
        val stage = JobTrackerRepository.stageOf(app.stage)
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SenderAvatar(app.company ?: "?", stage.accent(), 56)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.role ?: tr("Role not set"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(app.company ?: tr("Company not set"), style = MaterialTheme.typography.titleMedium)
                    app.location?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Box {
                Row(Modifier.clickable { stageMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    StageChip(stage); Spacer(Modifier.width(8.dp))
                    Text(tr("Change"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(stageMenu, { stageMenu = false }) {
                    ApplicationStage.entries.forEach { s ->
                        DropdownMenuItem(text = { Text("${s.glyph()}  ${tr(s.label)}") }, onClick = { stageMenu = false; haptics.tick(); vm.setStage(s) })
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact(tr("Applied"), app.appliedAt?.let { daysAgo(it) } ?: "—")
                Fact(tr("Last update"), daysAgo(app.lastActivityAt))
                app.atsPlatform?.let { Fact(tr("Via"), it.replaceFirstChar { c -> c.uppercase() }) }
                app.referenceId?.let { Fact(tr("Reference"), it) }
                if (app.followUpsSent > 0) Fact(tr("Follow-ups"), "${app.followUpsSent}")
            }

            if (app.nextStepNote != null || app.nextStepAt != null) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = stage.accent().copy(alpha = 0.10f))) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("Next step"), style = MaterialTheme.typography.labelLarge, color = stage.accent())
                        app.nextStepNote?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        app.nextStepAt?.let { Text(tr("By %1\$s", (fmt(it))), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!stage.terminal && app.recruiterEmail != null) Button(onClick = { haptics.tick(); vm.followUp(openReply) }) { Text(tr("✉  Follow up")) }
                app.jobUrl?.let { url -> OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text(tr("Open job")) } }
                app.lastEmailId?.let { id -> OutlinedButton(onClick = { openEmail(id) }) { Text(tr("Latest email")) } }
            }

            if (editing) EditDetails(app.company.orEmpty(), app.role.orEmpty(), app.location.orEmpty(), app.jobUrl.orEmpty(), app.notes.orEmpty(),
                onCancel = { editing = false }) { c, r, l, u, n -> editing = false; vm.save(c, r, l, u, n) }
            else {
                app.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { editing = true }) { Text(tr("Edit details")) }
            }

            Text(tr("Timeline"), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newNote, { newNote = it.take(500) }, placeholder = { Text(tr("Add a note")) }, singleLine = true,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.note(newNote); newNote = "" }, enabled = newNote.isNotBlank()) { Text(tr("Add")) }
            }
            events.forEachIndexed { i, e -> TimelineRow(e, last = i == events.lastIndex, openEmail) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Delete this application?")) },
        text = { Text(tr("Its timeline is removed from IA Mode. Your emails aren't touched.")) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text(tr("Delete")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun Fact(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

/** A vertical line with a dot per event; tapping an email event opens that email. */
@Composable
private fun TimelineRow(e: ApplicationEventEntity, last: Boolean, openEmail: (String) -> Unit) {
    val type = runCatching { ApplicationEventType.valueOf(e.type) }.getOrDefault(ApplicationEventType.NOTE)
    val color = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (e.emailId != null) Modifier.clickable { openEmail(e.emailId) } else Modifier)) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = size.width / 2
            if (!last) drawLine(line, Offset(x, 14.dp.toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(color, radius = 5.dp.toPx(), center = Offset(x, 9.dp.toPx()))
        }
        Column(Modifier.padding(start = 8.dp, bottom = 16.dp)) {
            Text(tr(type.label), style = MaterialTheme.typography.titleSmall)
            e.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(fmt(e.at) + if (e.emailId != null) tr(" · View email") else "", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EditDetails(company: String, role: String, location: String, url: String, notes: String, onCancel: () -> Unit,
                        onSave: (String, String, String, String, String) -> Unit) {
    var c by remember { mutableStateOf(company) }; var r by remember { mutableStateOf(role) }
    var l by remember { mutableStateOf(location) }; var u by remember { mutableStateOf(url) }; var n by remember { mutableStateOf(notes) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(c, { c = it.take(120) }, label = { Text(tr("Company")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(r, { r = it.take(120) }, label = { Text(tr("Role")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(l, { l = it.take(80) }, label = { Text(tr("Location")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(u, { u = it.take(500) }, label = { Text(tr("Job link")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(n, { n = it.take(2000) }, label = { Text(tr("Notes")) }, modifier = Modifier.fillMaxWidth())
        Row { TextButton(onClick = onCancel) { Text(tr("Cancel")) }; Button(onClick = { onSave(c, r, l, u, n) }) { Text(tr("Save")) } }
    }
}
