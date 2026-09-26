package com.iamode.app.ui.feature.settings

import com.iamode.app.core.i18n.tr

import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamode.app.core.permissions.AppPermission
import com.iamode.app.core.permissions.AppPermissions
import com.iamode.app.domain.model.AutoSchedule
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Gender
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.MoneyMode
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyMode
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.model.VehicleType
import com.iamode.app.ui.components.ChipRow
import com.iamode.app.ui.components.SectionTitle
import com.iamode.app.ui.components.SettingRow
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.theme.IAColors
import java.time.DayOfWeek
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, openPermissions: () -> Unit, openDiagnostics: () -> Unit, openMailActions: () -> Unit, openMailIntelligence: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.gmailAccounts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var showScheduleEditor by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var permissionRefresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionRefresh++ }
    val drivingHint = remember(permissionRefresh) { permissionHint(context, AppPermission.ACTIVITY_RECOGNITION) }
    val calendarHint = remember(permissionRefresh) { permissionHint(context, AppPermission.CALENDAR) }
    var name by rememberSaveable { mutableStateOf<String?>(null) }

    val gmailLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onGmailAuthResult(it.resultCode, it.data)
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.messageShown() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text(tr("Settings")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
        },
    ) { padding ->
        Column(Modifier.padding(padding).readableWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {

            SectionTitle(tr("Connection"))
            SettingRow(tr("Connection check"), tr("Test the build-configured server, sign-in, AI, Gmail, permissions and recent activity"),
                onClick = openDiagnostics)
            SettingRow(tr("Mail"), tr("Your Gmail sorted: documents, interviews, opportunities and more"), onClick = openMailIntelligence)
            SettingRow(tr("⚡ AI Actions"), tr("Replies, documents and calendar events waiting for your approval"), onClick = openMailActions)

            AppLanguageSection()

            SectionTitle(tr("About you"))
            OutlinedTextField(name ?: s.myName, { name = it; vm.update { st -> st.copy(myName = it) } },
                label = { Text(tr("Your name (email sign-off)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChipRow { Gender.entries.forEach { g -> FilterChip(s.gender == g, { vm.update { it.copy(gender = g) } }, { Text(tr(g.label)) }) } }

            SectionTitle(tr("Turn on automatically"))
            SettingRow(tr("When I start driving or riding"), drivingHint
                ?: tr("Turns off again when you stop")) {
                Switch(s.autoMode.whenDriving, { vm.setAutoDriving(it) })
            }
            SettingRow(tr("During calendar meetings"), calendarHint
                ?: tr("Busy, timed events only; all-day events are ignored")) {
                Switch(s.autoMode.duringMeetings, { vm.setAutoMeetings(it) })
            }
            s.autoMode.schedules.forEachIndexed { i, schedule ->
                SettingRow(tr(schedule.label), tr("Schedule")) {
                    TextButton(onClick = { vm.removeSchedule(i) }) { Text(tr("Remove")) }
                }
            }
            OutlinedButton(onClick = { showScheduleEditor = true }) { Text(tr("Add a schedule")) }
            Text(tr("If you switch IA Mode off during an automatic session, it stays off until that meeting, drive or schedule ends. ") +
                tr("IA Mode never switches off a session you started yourself."),
                style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)

            SectionTitle(tr("Apps"))
            listOf(Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS, Channel.TELEGRAM, Channel.INSTAGRAM).forEach { app ->
                SettingRow(tr(app.label), if (app == Channel.INSTAGRAM) tr("Direct messages only") else null) {
                    Switch(app in s.enabledApps, { vm.setAppEnabled(app, it) })
                }
            }
            SectionTitle(tr("Group chats"))
            SettingRow(tr("Reply when someone mentions me"), tr("Every group reply needs your approval")) {
                Switch(s.groupReplies, { v -> vm.update { it.copy(groupReplies = v) } })
            }
            AnimatedVisibility(visible = s.groupReplies) {
                var names by rememberSaveable { mutableStateOf<String?>(null) }
                OutlinedTextField(names ?: s.groupNames, { names = it; vm.update { st -> st.copy(groupNames = it) } },
                    label = { Text(tr("Other names people use for you")) }, placeholder = { Text(tr("e.g. Kasi anna, KK")) },
                    supportingText = { Text(tr("Your first name always counts. Separate names with commas.")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            SectionTitle(tr("Replies by relationship"))
            Relationship.assignable.forEach { r ->
                SettingRow(tr(r.label), if (s.modeFor(r) == ReplyMode.AUTO) tr("Replies automatically") else tr("Asks you first, then can handle the chat")) {
                    ChipRow {
                        ReplyMode.entries.forEach { m ->
                            FilterChip(s.modeFor(r) == m, { vm.update { it.copy(replyModes = it.replyModes + (r to m)) } },
                                { Text(if (m == ReplyMode.AUTO) tr("Auto") else tr("Approve")) })
                        }
                    }
                }
            }
            Text(tr("Unknown numbers and senders always need your approval."), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
            SettingRow(tr("Saved contacts without a label are treated as")) {}
            ChipRow {
                Relationship.assignable.forEach { r ->
                    FilterChip(s.defaultSavedContactRelationship == r, { vm.update { it.copy(defaultSavedContactRelationship = r) } }, { Text(tr(r.label)) })
                }
            }
            SettingRow(tr("Email safety"), tr("Every email reply, attachment and calendar change requires your approval.")) {}

            SectionTitle(tr("Money and commitments"))
            MoneyMode.entries.forEach { m ->
                SettingRow(tr(m.label), onClick = { vm.update { it.copy(moneyMode = m) } }) {
                    RadioButton(s.moneyMode == m, { vm.update { it.copy(moneyMode = m) } })
                }
            }

            SectionTitle(tr("Safety limits"))
            Text(tr("Max automatic replies per chat: %1\$s", (s.maxAutoReplies)), style = MaterialTheme.typography.bodyMedium)
            Slider(s.maxAutoReplies.toFloat(), { v -> vm.update { it.copy(maxAutoReplies = v.toInt()) } }, valueRange = 2f..15f, steps = 12)
            Text(tr("Undo window: %1\$s seconds", (s.undoSeconds)), style = MaterialTheme.typography.bodyMedium)
            Slider(s.undoSeconds.toFloat(), { v -> vm.update { it.copy(undoSeconds = v.toInt()) } }, valueRange = 3f..30f, steps = 26)
            SettingRow(tr("Approval notifications"), tr("Approve, write or skip right from the notification")) {
                Switch(s.approvalNotifications, { v -> vm.update { it.copy(approvalNotifications = v) } })
            }

            SectionTitle(tr("Missed calls"))
            SettingRow(tr("Text callers I can't pick up"), tr("Written by AI in the caller's language")) {
                Switch(s.missedCallReplies, { v -> vm.update { it.copy(missedCallReplies = v) } })
            }
            SettingRow(tr("Also text unknown numbers")) {
                Switch(s.replyToUnknownCallers, { v -> vm.update { it.copy(replyToUnknownCallers = v) } })
            }
            Text(tr("Default language (when a caller's language is unknown)"), style = MaterialTheme.typography.bodyMedium)
            ChipRow {
                LanguageCode.entries.forEach { l -> FilterChip(s.defaultLanguage == l, { vm.update { it.copy(defaultLanguage = l) } }, { Text(tr(l.label)) }) }
            }
            AnimatedVisibility(visible = s.defaultLanguage != LanguageCode.EN) {
                ChipRow {
                    Script.entries.forEach { sc -> FilterChip(s.defaultScript == sc, { vm.update { it.copy(defaultScript = sc) } }, { Text(tr(sc.label)) }) }
                }
            }

            SectionTitle(tr("Situation detection"))
            SettingRow(tr("I usually travel by"), tr("Android can't tell a car from a bike")) {
                ChipRow {
                    VehicleType.entries.forEach { v -> FilterChip(s.vehicleType == v, { vm.update { it.copy(vehicleType = v) } }, { Text(tr(v.label)) }) }
                }
            }

            SectionTitle(tr("Appearance and notifications"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingRow(tr("Use wallpaper colors"), tr("Match your phone's Material You theme")) {
                    Switch(s.useDynamicColor, { v -> vm.update { it.copy(useDynamicColor = v) } })
                }
            }
            SettingRow("Show \"IA Mode is on\" notification", tr("Silent, with a timer and a Turn off button")) {
                Switch(s.showStatusNotification, { v -> vm.setStatusNotification(v) })
            }

            SectionTitle(tr("Gmail accounts"))
            accounts.forEach { a ->
                SettingRow(a.email, if (a.needsReauth) tr("Needs reconnecting") else tr("Connected")) {
                    if (a.needsReauth) TextButton(onClick = {
                        vm.connectGmail { gmailLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                    }) { Text(tr("Reconnect")) }
                    TextButton(onClick = { vm.removeGmail(a.email) }) { Text(tr("Remove")) }
                }
            }
            OutlinedButton(onClick = {
                vm.connectGmail { gmailLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            }) { Text(tr("Connect a Gmail account")) }
            Text(tr("IA Mode reads your inbox to understand it. Replies always wait for your approval in AI Actions."),
                style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)

            OutlookSection()

            EmailIntelligenceSection()

            SectionTitle(tr("Privacy"))
            SettingRow(tr("Send crash reports"), tr("Helps fix bugs. Never includes messages, names or numbers")) {
                Switch(s.crashReports, { v -> vm.update { it.copy(crashReports = v) } })
            }
            SettingRow(tr("Permissions"), tr("See what IA Mode can access"), onClick = openPermissions)
            Text(tr("Conversations are stored encrypted on this phone and deleted after 30 days. The AI server keeps nothing."),
                style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
            TextButton(onClick = { confirmDelete = true }) { Text(tr("Delete all conversations"), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showScheduleEditor) {
        ScheduleEditor(onDismiss = { showScheduleEditor = false }, onSave = { vm.addSchedule(it); showScheduleEditor = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete everything?")) },
            text = { Text(tr("This turns IA Mode off and deletes all conversations and alerts. Contacts and settings stay.")) },
            confirmButton = { TextButton(onClick = { vm.deleteAllData(); confirmDelete = false }) { Text(tr("Delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun permissionHint(context: android.content.Context, p: AppPermission): String? =
    if (AppPermissions.isGranted(context, p)) null else tr("Needs the %1\$s permission (Privacy › Permissions)", (tr(p.title)))

/** Pick days and a time range; presets cover the common cases in one tap. */
@Composable
private fun ScheduleEditor(onDismiss: () -> Unit, onSave: (AutoSchedule) -> Unit) {
    val context = LocalContext.current
    val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    var days by remember { mutableStateOf(weekdays) }
    var start by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(18, 0)) }

    fun pick(initial: LocalTime, set: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> set(LocalTime.of(h, m)) }, initial.hour, initial.minute,
            android.text.format.DateFormat.is24HourFormat(context)).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add a schedule")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChipRow {
                    AssistChip(onClick = { days = weekdays; start = LocalTime.of(10, 0); end = LocalTime.of(18, 0) },
                        label = { Text(tr("Work hours")) })
                    AssistChip(onClick = { days = DayOfWeek.entries.toSet(); start = LocalTime.of(23, 0); end = LocalTime.of(7, 0) },
                        label = { Text(tr("Bedtime")) })
                }
                ChipRow {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(d in days, { days = if (d in days) days - d else days + d },
                            { Text(d.name.take(3).lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick(start) { start = it } }, modifier = Modifier.weight(1f)) { Text("From ${"%02d:%02d".format(start.hour, start.minute)}") }
                    OutlinedButton(onClick = { pick(end) { end = it } }, modifier = Modifier.weight(1f)) { Text("To ${"%02d:%02d".format(end.hour, end.minute)}") }
                }
                val preview = AutoSchedule(days, start, end)
                Text(if (days.isEmpty()) tr("Pick at least one day") else preview.label + if (preview.overnight) " (overnight)" else "",
                    style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(AutoSchedule(days, start, end)) }, enabled = days.isNotEmpty() && start != end) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
