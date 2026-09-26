package com.iamode.app.ui.feature.settings

import com.iamode.app.core.i18n.tr

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.iamode.app.data.gmail.GmailAuthManager
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.data.mail.DocumentSources
import kotlinx.coroutines.flow.MutableStateFlow
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.domain.mail.AutomationRuleType
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.ui.components.SectionTitle
import com.iamode.app.ui.components.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EmailIntelligenceViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repo: MailIntelligenceRepository,
    private val documents: DocumentSources,
    private val auth: GmailAuthManager,
    gmail: GmailRepository,
    private val writing: com.iamode.app.data.mail.WritingStyleRepository,
) : ViewModel() {
    val style = writing.style.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val learning = MutableStateFlow(false)

    fun learnStyle() = viewModelScope.launch {
        learning.value = true
        message.value = if (runCatching { writing.refresh() }.getOrNull() != null) tr("Learned your writing style")
        else tr("Not enough sent emails yet (IA Mode needs at least 3 from the last 6 months)")
        learning.value = false
    }

    fun forgetStyle() = viewModelScope.launch { writing.forget(); message.value = tr("Writing style forgotten") }

    val accounts = gmail.connectedAccounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    private var pendingDrive: String? = null

    /** Separate, optional consent (drive.readonly). Gmail keeps working if the user declines. */
    fun connectDrive(email: String, launch: (PendingIntent) -> Unit) = viewModelScope.launch {
        runCatching { auth.beginDriveAuthorization(email) }.onSuccess { r ->
            val pi = r.pendingIntent
            if (r.hasResolution() && pi != null) { pendingDrive = email; launch(pi) }
            else r.accessToken?.let { finishDrive(email, it) } ?: run { message.value = tr("Google Drive access wasn't granted") }
        }.onFailure { message.value = tr("Couldn't start Google Drive sign-in. Is the Drive API enabled for this project?") }
    }

    fun onDriveResult(data: Intent?) = viewModelScope.launch {
        val email = pendingDrive ?: return@launch
        pendingDrive = null
        val token = runCatching { auth.resultFromIntent(data).accessToken }.getOrNull()
        if (token != null) finishDrive(email, token) else message.value = tr("Google Drive access wasn't granted")
    }

    private suspend fun finishDrive(email: String, token: String) {
        auth.remember(email, token, GmailAuthManager.DRIVE_SCOPES)
        documents.addDrive(email)
        message.value = tr("Google Drive connected for %1\$s", email)
    }
    val state = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val autoInterviews = repo.rules.map { r -> r.any { it.id == AutomationRuleType.AUTO_ADD_INTERVIEWS.name && it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val folders = documents.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val muted = repo.muted.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(f: (UserSettings) -> UserSettings) = viewModelScope.launch { settings.update(f) }
    fun setAutoInterviews(on: Boolean) = viewModelScope.launch { repo.setRule(AutomationRuleType.AUTO_ADD_INTERVIEWS, on) }
    fun addFolder(uri: Uri) = viewModelScope.launch { documents.addFolder(uri) }
    fun removeFolder(uri: String) = viewModelScope.launch { documents.removeFolder(uri) }
    fun unmute(address: String) = viewModelScope.launch { repo.unmute(address) }
}

@Composable
fun EmailIntelligenceSection(vm: EmailIntelligenceViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val auto by vm.autoInterviews.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addFolder) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val driveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.onDriveResult(it.data) }

    Column {
        SectionTitle(tr("Email intelligence"))
        SettingRow(tr("Understand my email"), tr("Sorts Gmail into Documents, Interviews, Opportunities and more, and proposes actions for you to approve")) {
            Switch(s.mailIntelligence, { v -> vm.update { it.copy(mailIntelligence = v) } })
        }
        SettingRow(tr(AutomationRuleType.AUTO_ADD_INTERVIEWS.label), AutomationRuleType.AUTO_ADD_INTERVIEWS.description) {
            Switch(auto, vm::setAutoInterviews)
        }
        SettingRow(tr("Mirror to Gmail"), tr("Archive, move back and muting in IA Mode also happen in Gmail")) {
            Switch(s.gmailMirror, { v -> vm.update { it.copy(gmailMirror = v) } })
        }
        SettingRow(tr("IA Mode labels in Gmail"), "Adds labels like \"IA Mode/Interviews\" in Gmail. Never moves or deletes mail") {
            Switch(s.gmailLabels, { v -> vm.update { it.copy(gmailLabels = v) } })
        }
        SectionTitle(tr("Reply language"))
        var langMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        val langs = listOf("auto" to tr("Same as the email"), "en" to tr("English"), "te" to tr("తెలుగు (Telugu)"), "hi" to tr("हिन्दी (Hindi)"),
            "ta" to tr("தமிழ் (Tamil)"), "kn" to tr("ಕನ್ನಡ (Kannada)"), "ml" to tr("മലയാളം (Malayalam)"))
        androidx.compose.foundation.layout.Box {
            SettingRow(tr("Write email replies in"), langs.firstOrNull { it.first == s.mailReplyLanguage }?.second ?: tr("Same as the email"),
                onClick = { langMenu = true })
            androidx.compose.material3.DropdownMenu(langMenu, { langMenu = false }) {
                langs.forEach { (code, label) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = {
                        langMenu = false; vm.update { it.copy(mailReplyLanguage = code) }
                    })
                }
            }
        }

        SectionTitle(tr("Job tracker"))
        SettingRow(tr("Follow-up reminders"), tr("Nudges for follow-ups, assessment deadlines and offer replies. Nothing is sent without you")) {
            Switch(s.jobReminders, { v -> vm.update { it.copy(jobReminders = v) } })
        }

        SectionTitle(tr("Writing style"))
        SettingRow(tr("Write replies in my style"), tr("Greeting, sign-off, length and tone learned on this phone from your sent mail")) {
            Switch(s.matchWritingStyle, { v -> vm.update { it.copy(matchWritingStyle = v) } })
        }
        val style by vm.style.collectAsStateWithLifecycle()
        val learning by vm.learning.collectAsStateWithLifecycle()
        androidx.compose.animation.AnimatedVisibility(s.matchWritingStyle) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    val st = style
                    if (st == null) {
                        Text(tr("Not learned yet"), style = MaterialTheme.typography.titleSmall)
                        Text(tr("IA Mode reads your recent sent emails on this phone and keeps only a short profile."),
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(tr("✦ Learned from %1\$s of your emails", (st.sampleCount)), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        st.greeting?.let { Text("Greets with \"$it …,\"", style = MaterialTheme.typography.bodyMedium) }
                        st.signOff?.let { Text("Signs off \"${it.replace("\n", " ")}\"", style = MaterialTheme.typography.bodyMedium) }
                        Text(tr("About %1\$s words · %2\$s tone", (st.averageWords), (st.formality.name.lowercase())) + if (st.usesEmoji) tr(" · uses emoji") else "",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Row {
                        TextButton(onClick = vm::learnStyle, enabled = !learning) { Text(if (learning) tr("Learning…") else if (style == null) tr("Learn now") else tr("Relearn")) }
                        if (style != null) TextButton(onClick = vm::forgetStyle) { Text(tr("Forget")) }
                    }
                }
            }
        }

        SettingRow(tr("Celebrations"), tr("A celebration when you're selected, get an offer or an interview. Shown once per email")) {
            Switch(s.celebrations, { v -> vm.update { it.copy(celebrations = v) } })
        }
        SettingRow(tr("Celebration sound"), tr("A short sound with the celebration")) {
            Switch(s.celebrationSound, { v -> vm.update { it.copy(celebrationSound = v) } }, enabled = s.celebrations)
        }

        SectionTitle(tr("Folders IA Mode may search for documents"))
        if (folders.isEmpty()) Text(tr("None yet. IA Mode can only suggest files from folders you add here (or files you pick yourself)."),
            style = MaterialTheme.typography.bodySmall)
        folders.forEach { f ->
            SettingRow(f.displayName, tr("Allowed")) { TextButton(onClick = { vm.removeFolder(f.uri) }) { Text(tr("Remove")) } }
        }
        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(tr("Add a folder")) }
        accounts.filter { a -> folders.none { it.uri == "gdrive://${a.email}" } }.forEach { a ->
            OutlinedButton(onClick = {
                vm.connectDrive(a.email) { pi -> driveLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
            }) { Text(tr("Connect Google Drive (%1\$s)", (a.email))) }
        }
        Text(tr("Google Drive is searched by file name and content (Drive's own search). A file is only downloaded when you attach or preview it."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        if (muted.isNotEmpty()) {
            SectionTitle(tr("Muted senders"))
            muted.forEach { m -> SettingRow(m.address, null) { TextButton(onClick = { vm.unmute(m.address) }) { Text(tr("Unmute")) } } }
        }
        Text(tr("Every email, attachment and calendar change needs your approval unless you turn on a rule above. ") +
            tr("Email bodies aren't stored; IA Mode keeps only the summary and details it extracted, for 90 days."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
