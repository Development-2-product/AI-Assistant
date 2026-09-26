package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import com.iamode.app.ui.components.pressScale
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.DocumentCandidateEntity
import com.iamode.app.data.mail.DocumentSources
import com.iamode.app.data.mail.MailActionExecutor
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.domain.mail.AttachmentSafety
import com.iamode.app.domain.mail.DocumentFile
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MatchLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReplyUi(
    val resolving: Boolean = false,
    val drafting: Boolean = false,
    val sending: Boolean = false,
    val preview: Bitmap? = null,
    val result: String? = null,
    val done: Boolean = false,
    val error: String? = null,
    /** Attachments changed after the reply was written: offer to rewrite it. */
    val attachmentsChanged: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReplyReviewViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: MailIntelligenceRepository,
    private val executor: MailActionExecutor,
    private val documents: DocumentSources,
    private val viewing: com.iamode.app.data.mail.DocumentViewing,
) : ViewModel() {
    suspend fun openForViewing(uri: String, name: String) = viewing.open(uri, name)

    val actionId: String = checkNotNull(saved["actionId"])
    val action = repo.action(actionId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val item = action.filterNotNull().flatMapLatest { repo.mailItem(it.emailId) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val candidates = repo.candidates(actionId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selection = repo.selections(actionId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sources = documents.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val ui = MutableStateFlow(ReplyUi())

    init {
        viewModelScope.launch {
            val a = repo.actionOnce(actionId) ?: return@launch
            if (a.actionType == MailActionType.SEND_DOCUMENT_REPLY.name) resolve()
            else if (a.draftBody == null) draft()
        }
    }

    fun resolve() = viewModelScope.launch {
        ui.value = ui.value.copy(resolving = true)
        runCatching { repo.resolveDocuments(actionId) }
        ui.value = ui.value.copy(resolving = false)
    }

    fun choose(c: DocumentCandidateEntity) = select(
        DocumentFile(c.uri, c.displayName, c.mimeType, c.sizeBytes, c.modifiedAt, null), c.label.startsWith(MatchLabel.RECOMMENDED.name), false,
    )

    fun picked(uri: Uri, keepCopy: Boolean) = viewModelScope.launch {
        val file = repo.describePicked(uri)
        if (file == null) { ui.value = ui.value.copy(error = tr("IA Mode can't read that file")); return@launch }
        select(file, recommended = false, keepCopy = keepCopy)
    }

    private fun select(file: DocumentFile, recommended: Boolean, keepCopy: Boolean) = viewModelScope.launch {
        AttachmentSafety.check(listOf(com.iamode.app.domain.mail.AttachmentInfo(file.name, file.mimeType, file.sizeBytes, true)))?.let {
            ui.value = ui.value.copy(error = it); return@launch
        }
        val hadDraft = repo.actionOnce(actionId)?.draftBody != null
        repo.toggleDocument(actionId, file, recommended, keepCopy)?.let { ui.value = ui.value.copy(error = it); return@launch }
        ui.value = ui.value.copy(error = null, attachmentsChanged = hadDraft)
        if (!hadDraft) draft()
    }

    fun addFolder(uri: Uri) = viewModelScope.launch { documents.addFolder(uri); resolve() }

    fun draft(tone: String = "professional", instructions: String? = null) = viewModelScope.launch {
        ui.value = ui.value.copy(drafting = true, error = null, attachmentsChanged = false)
        repo.draftReply(actionId, tone, instructions).onFailure { ui.value = ui.value.copy(error = it.message ?: tr("Couldn't draft a reply")) }
        ui.value = ui.value.copy(drafting = false)
    }

    fun approveAndSend(subject: String, body: String) = viewModelScope.launch {
        ui.value = ui.value.copy(sending = true, error = null)
        repo.updateDraft(actionId, subject, body)
        if (!repo.approve(actionId)) {
            ui.value = ui.value.copy(sending = false, error = tr("This action can't be approved any more")); return@launch
        }
        when (val r = executor.execute(actionId)) {
            is MailActionExecutor.Outcome.Done -> ui.value = ui.value.copy(sending = false, result = r.message, done = true)
            is MailActionExecutor.Outcome.Blocked -> ui.value = ui.value.copy(sending = false, error = r.reason)
            is MailActionExecutor.Outcome.Failed -> ui.value = ui.value.copy(sending = false, error = r.reason)
        }
    }

    fun cancel(then: () -> Unit) = viewModelScope.launch { repo.reject(actionId); then() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyReviewScreen(onBack: () -> Unit, vm: ReplyReviewViewModel = hiltViewModel()) {
    val action by vm.action.collectAsStateWithLifecycle()
    val item by vm.item.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val a = action
    val isDocument = a?.actionType == MailActionType.SEND_DOCUMENT_REPLY.name

    var subject by remember(a?.draftSubject) { mutableStateOf(a?.draftSubject.orEmpty()) }
    var body by remember(a?.draftBody) { mutableStateOf(a?.draftBody.orEmpty()) }
    var keepCopy by remember { mutableStateOf(true) }
    var regenerateOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.picked(it, keepCopy) } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addFolder) }
    val chosen = selection.firstOrNull()
    var viewer by remember { mutableStateOf<Pair<String, String>?>(null) } // uri to name
    val haptics = com.iamode.app.ui.components.rememberHaptics()
    val lockedUris = candidates.filter { it.label.contains("_LOCKED") }.map { it.uri }.toSet()

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (isDocument) tr("Send document") else tr("Review reply")) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
        })
    }) { padding ->
        if (a == null) return@Scaffold
        if (ui.done) { SentConfirmation(ui.result ?: tr("Sent"), selection.joinToString(", ") { it.displayName }.ifBlank { null }, onBack); return@Scaffold }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- the request ----
            item?.let { m ->
                val doc = m.document
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.10f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (isDocument) tr("📎 Document requested") else tr("↩ Reply to %1\$s", (m.sender)), fontWeight = FontWeight.SemiBold)
                        Text(if (isDocument) "${m.sender} requested ${doc?.description ?: doc?.type?.label?.lowercase() ?: "a document"}" +
                            (doc?.requestedFormat?.let { tr(" as a %1\$s", it) } ?: "") + "." else m.entity.summary.orEmpty())
                        Text(tr("To: %1\$s", (a.recipient)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- documents: recommended + other candidates + picker ----
            if (isDocument) {
                if (ui.resolving && candidates.isEmpty()) {
                    Text(tr("Looking through your documents…"), style = MaterialTheme.typography.titleSmall)
                    repeat(3) { FileRowSkeleton() }
                } else if (ui.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                val recommended = candidates.firstOrNull { it.label.startsWith(MatchLabel.RECOMMENDED.name) }
                val others = candidates.filter { it != recommended }
                val selectedUris = selection.map { it.uri }.toSet()
                if (recommended != null) {
                    Text(tr("Recommended file"), style = MaterialTheme.typography.titleSmall)
                    FileRow(recommended.displayName, recommended.sizeBytes, candidateNote(recommended, tr("Recommended")),
                        recommended.uri in selectedUris, onPreview = { viewer = recommended.uri to recommended.displayName }) { vm.choose(recommended) }
                }
                if (others.isNotEmpty()) {
                    Text(if (recommended != null) tr("Other possible files") else tr("Possible matches"), style = MaterialTheme.typography.titleSmall)
                    others.forEach { c ->
                        FileRow(c.displayName, c.sizeBytes, candidateNote(c, tr("Possible match")), c.uri in selectedUris,
                            onPreview = { viewer = c.uri to c.displayName }) { vm.choose(c) }
                    }
                }
                if (selection.size > 1) Text(tr("%1\$s files selected · %2\$s", (selection.size), (formatSize(selection.sumOf { it.sizeBytes ?: 0L }))),
                    style = MaterialTheme.typography.labelLarge)
                if (!ui.resolving && candidates.isEmpty()) {
                    Text(
                        if (sources.isEmpty()) tr("IA Mode only searches folders you allow. Add your documents folder, or choose the file yourself.")
                        else tr("No matching file in your allowed folders. Choose one yourself."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Files the user picked that weren't among the candidates (tap to remove)
                selection.filter { s -> candidates.none { it.uri == s.uri } }.forEach { s ->
                    FileRow(s.displayName, s.sizeBytes, tr("Chosen by you"), true, onPreview = { viewer = s.uri to s.displayName }) {
                        vm.choose(DocumentCandidateEntity("", "", s.uri, s.displayName, s.mimeType, s.sizeBytes, null, "POSSIBLE", 0))
                    }
                }

                selection.firstOrNull { !AttachmentSafety.matchesFormat(item?.document?.requestedFormat, it.displayName, it.mimeType) }?.let { bad ->
                    Text(tr("⚠ They asked for %1\$s. %2\$s is %3\$s.", (item?.document?.requestedFormat), (bad.displayName), (bad.displayName.substringAfterLast('.').uppercase())),
                        color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                }
                if (selection.any { it.uri in lockedUris }) {
                    Text(tr("🔒 A selected PDF is password-protected. The recipient will need the password to open it."),
                        color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*", "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*")) }) { Text(tr("Choose another file")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keepCopy = !keepCopy }) {
                    Checkbox(keepCopy, { keepCopy = it })
                    Text(tr("Keep files I choose in IA Mode so they're suggested next time"), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { folderPicker.launch(null) }) { Text(tr("+ Allow IA Mode to search a folder")) }
                if (sources.none { it.uri.startsWith("gdrive://") }) Text(tr("Tip: connect Google Drive in Settings › Email intelligence to search it too."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- editable reply ----
            if (!isDocument || chosen != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Suggested reply"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (ui.drafting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { regenerateOpen = true }, enabled = !ui.drafting) { Text(tr("Regenerate")) }
                }
                OutlinedTextField(subject, { subject = it }, label = { Text(tr("Subject")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (ui.drafting && body.isBlank()) DraftingSkeleton()
                else OutlinedTextField(body, { body = it }, label = { Text(tr("Message (you can edit)")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp))
                selection.forEach { Text("📎 ${it.displayName}", style = MaterialTheme.typography.labelLarge) }
                if (ui.attachmentsChanged) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Attachments changed. Update the reply?"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.draft() }) { Text(tr("Rewrite")) }
                    }
                }
            } else if (isDocument) {
                Text(tr("Choose the file to attach. The reply is written once you've picked it."), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            a.error?.takeIf { a.status == "FAILED" }?.let { Text(tr("Last attempt failed: %1\$s", it), color = MaterialTheme.colorScheme.error) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.cancel(onBack) }, modifier = Modifier.weight(1f)) { Text(tr("Cancel")) }
                Button(
                    onClick = { haptics.confirm(); vm.approveAndSend(subject.trim(), body.trim()) },
                    enabled = !ui.sending && body.isNotBlank() && (!isDocument || chosen != null),
                    modifier = Modifier.weight(1.4f),
                ) {
                    if (ui.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(tr("Approve & Send"))
                }
            }
            Text(tr("Nothing is sent until you tap Approve & Send."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    viewer?.let { (uri, name) -> DocumentViewer(name, load = { vm.openForViewing(uri, name) }, onClose = { viewer = null }) }
    if (regenerateOpen) RegenerateSheet(onDismiss = { regenerateOpen = false }) { tone, extra -> regenerateOpen = false; vm.draft(tone, extra) }
}

@Composable
private fun FileRow(name: String, size: Long?, label: String, selected: Boolean, onPreview: (() -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "fileBg")
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = bg, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, { onClick() })
            Box(Modifier.size(40.dp).background(Color(0xFFEF4444).copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(name.substringAfterLast('.', "FILE").uppercase().take(4), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF4444))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(label, size?.let { formatSize(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onPreview != null) IconButton(onClick = onPreview) { Icon(Icons.Filled.Visibility, tr("Preview %1\$s", name)) }
        }
    }
}

@Composable
private fun FileRowSkeleton() {
    val brush = com.iamode.app.ui.components.shimmerBrush()
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        com.iamode.app.ui.components.SkeletonBlock(Modifier.size(40.dp), height = 40.dp, brush = brush)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.iamode.app.ui.components.SkeletonBlock(Modifier.fillMaxWidth(0.7f), brush = brush)
            com.iamode.app.ui.components.SkeletonBlock(Modifier.fillMaxWidth(0.35f), height = 10.dp, brush = brush)
        }
    }
}

/** While the AI writes: shimmering lines shaped like an email, instead of a spinner. */
@Composable
private fun DraftingSkeleton() {
    val brush = com.iamode.app.ui.components.shimmerBrush()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("✦ Writing in your style…"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        listOf(0.35f, 1f, 0.95f, 0.8f, 0.4f).forEach { com.iamode.app.ui.components.SkeletonBlock(Modifier.fillMaxWidth(it), brush = brush) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegenerateSheet(onDismiss: () -> Unit, onGo: (String, String?) -> Unit) {
    var tone by remember { mutableStateOf("professional") }
    var extra by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Regenerate reply"), style = MaterialTheme.typography.titleMedium)
            listOf("professional" to tr("Professional"), "friendly" to tr("Friendly"), "formal" to tr("Formal"), "brief" to tr("Brief")).forEach { (k, l) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { tone = k }) {
                    RadioButton(tone == k, { tone = k }); Text(l)
                }
            }
            OutlinedTextField(extra, { extra = it.take(300) }, label = { Text(tr("Anything to add? (optional)")) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onGo(tone, extra.ifBlank { null }) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Regenerate")) }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SentConfirmation(message: String, attachment: String?, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        com.iamode.app.ui.components.SuccessCheck(size = 112.dp)
        Spacer(Modifier.size(20.dp))
        Text(message, style = MaterialTheme.typography.headlineSmall)
        attachment?.let { Text("📎 $it", style = MaterialTheme.typography.bodyMedium) }
        Text(tr("Recorded in AI Actions › Activity."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(24.dp))
        Button(onClick = onDone) { Text(tr("Done")) }
    }
}

private fun candidateNote(c: DocumentCandidateEntity, base: String): String = listOfNotNull(
    base,
    tr("matches its content").takeIf { c.label.contains("_CONTENT") },
    tr("🔒 password-protected").takeIf { c.label.contains("_LOCKED") },
    tr("Google Drive").takeIf { c.uri.startsWith("gdrive://") },
).joinToString(" · ")

fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
