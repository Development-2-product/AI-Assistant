package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.data.mail.CalendarChoice
import com.iamode.app.data.mail.CalendarWriter
import com.iamode.app.data.mail.MailActionExecutor
import com.iamode.app.data.mail.MailIntelligenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InterviewCalendarViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: MailIntelligenceRepository,
    private val executor: MailActionExecutor,
    private val writer: CalendarWriter,
) : ViewModel() {
    val actionId: String = checkNotNull(saved["actionId"])
    val action = repo.action(actionId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val item = action.filterNotNull().flatMapLatest { repo.mailItem(it.emailId) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val extraction = action.filterNotNull().flatMapLatest { repo.calendar(it.emailId) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val calendars = MutableStateFlow<List<CalendarChoice>>(emptyList())
    val busy = MutableStateFlow(false)
    val result = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun hasPermission() = writer.hasPermission()
    fun loadCalendars() = viewModelScope.launch { calendars.value = writer.writableCalendars() }

    fun add(
        date: LocalDate, start: LocalTime, end: LocalTime?, zone: ZoneId, title: String, location: String?, meetingUrl: String?,
        calendarId: Long?, reminders: List<Int>,
    ) = viewModelScope.launch {
        busy.value = true; error.value = null
        val a = action.value ?: return@launch
        // What the user confirmed on this screen becomes the event. Nothing is guessed.
        repo.confirmCalendar(a.emailId, date.toString(), start.toString(), end?.toString(), zone.id, title, location, meetingUrl)
        if (!repo.approve(actionId)) { error.value = tr("This action can't be approved any more"); busy.value = false; return@launch }
        when (val r = executor.execute(actionId, calendarId, reminders)) {
            is MailActionExecutor.Outcome.Done -> result.value = r.message
            is MailActionExecutor.Outcome.Blocked -> error.value = r.reason
            is MailActionExecutor.Outcome.Failed -> error.value = r.reason
        }
        busy.value = false
    }

    fun notNow(then: () -> Unit) = viewModelScope.launch { repo.reject(actionId); then() }
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMMM d, yyyy")
private val clockFmt = DateTimeFormatter.ofPattern("h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterviewCalendarScreen(onBack: () -> Unit, vm: InterviewCalendarViewModel = hiltViewModel()) {
    val item by vm.item.collectAsStateWithLifecycle()
    val e by vm.extraction.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val x = e

    var date by remember(x?.id) { mutableStateOf(x?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var start by remember(x?.id) { mutableStateOf(x?.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }) }
    var end by remember(x?.id) { mutableStateOf(x?.endTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }) }
    var zone by remember(x?.id) { mutableStateOf(x?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()) }
    var title by remember(x?.id) { mutableStateOf(x?.title.orEmpty()) }
    var location by remember(x?.id) { mutableStateOf(x?.location.orEmpty()) }
    var dayBefore by remember { mutableStateOf(true) }
    var hourBefore by remember { mutableStateOf(true) }
    var calendarId by remember { mutableStateOf<Long?>(null) }
    var confirmed by remember(x?.id) { mutableStateOf(x?.ambiguous == false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<String?>(null) }
    var permission by remember { mutableStateOf(vm.hasPermission()) }
    val haptics = com.iamode.app.ui.components.rememberHaptics()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permission = r.values.all { it }
        if (permission) vm.loadCalendars()
    }
    LaunchedEffect(permission) { if (permission) vm.loadCalendars() }
    LaunchedEffect(calendars) { if (calendarId == null) calendarId = calendars.firstOrNull()?.id }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Interview")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
    }) { padding ->
        if (x == null) return@Scaffold
        if (result != null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                com.iamode.app.ui.components.SuccessCheck(size = 112.dp, color = Color(0xFF7B61FF))
                Text(result!!, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
                Text(listOfNotNull(if (dayBefore) tr("1 day before") else null, if (hourBefore) tr("1 hour before") else null)
                    .joinToString(" and ").ifBlank { tr("No reminders") }.let { tr("Reminders: %1\$s", it) })
                Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) { Text(tr("Done")) }
            }
            return@Scaffold
        }
        val accent = Color(0xFF7B61FF)
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("🎯 Interview invitation"), color = accent, fontWeight = FontWeight.SemiBold)
                    item?.opportunity?.role?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    item?.opportunity?.company?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(
                        if (date != null && start != null) "${dateFmt.format(date)}\n${clockFmt.format(start)}${end?.let { " – " + clockFmt.format(it) } ?: ""} · ${zone.id}"
                        else tr("Date and time to confirm"),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp),
                    )
                    (x.meetingUrl ?: x.location)?.let { Text(if (it.contains("meet.google")) tr("Google Meet") else it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            if (x.ambiguous && !confirmed) {
                Surface(color = Color(0xFFF59E0B).copy(alpha = 0.14f), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("⚠️ Confirmation needed"), fontWeight = FontWeight.SemiBold)
                        x.dateText?.let { Text("The email says: \"$it\"") }
                        Text(x.ambiguityReason ?: tr("IA Mode couldn't determine the exact date."), style = MaterialTheme.typography.bodySmall)
                        Text(tr("Set the date and time below. IA Mode never guesses them."), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedTextField(title, { title = it }, label = { Text(tr("Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) { Text(date?.let { dateFmt.format(it) } ?: tr("Pick date")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime = "start" }, modifier = Modifier.weight(1f)) { Text(start?.let { tr("Starts %1\$s", (clockFmt.format(it))) } ?: tr("Start time")) }
                OutlinedButton(onClick = { pickTime = "end" }, modifier = Modifier.weight(1f)) { Text(end?.let { tr("Ends %1\$s", (clockFmt.format(it))) } ?: tr("End time (optional)")) }
            }
            Text(if (x.timezone == null) tr("Time zone: %1\$s (your phone's, the email didn't say)", (zone.id)) else tr("Time zone: %1\$s", (zone.id)),
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(location, { location = it }, label = { Text(tr("Location or meeting link")) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text(tr("Reminders"), style = MaterialTheme.typography.titleSmall)
            CheckRow(tr("1 day before"), dayBefore) { dayBefore = it }
            CheckRow(tr("1 hour before"), hourBefore) { hourBefore = it }

            Text(tr("Calendar"), style = MaterialTheme.typography.titleSmall)
            if (!permission) {
                OutlinedButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) {
                    Text(tr("Allow calendar access"))
                }
            } else if (calendars.isEmpty()) {
                Text(tr("No calendar on this phone accepts new events. Add your Google account in the Calendar app."), style = MaterialTheme.typography.bodySmall)
            } else calendars.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { calendarId = c.id }) {
                    RadioButton(calendarId == c.id, { calendarId = c.id })
                    Column { Text(c.name); Text(c.account, style = MaterialTheme.typography.bodySmall) }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.notNow(onBack) }, modifier = Modifier.weight(1f)) { Text(tr("Not now")) }
                Button(
                    onClick = {
                        haptics.confirm()
                        vm.add(date!!, start!!, end, zone, title.ifBlank { tr("Interview") }, location.ifBlank { null },
                            x.meetingUrl, calendarId, listOfNotNull(if (dayBefore) 1440 else null, if (hourBefore) 60 else null))
                    },
                    enabled = !busy && permission && calendarId != null && date != null && start != null && (!x.ambiguous || confirmed),
                    modifier = Modifier.weight(1.4f),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(tr("Add to Calendar"))
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); confirmed = start != null }
                pickDate = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(tr("Cancel")) } },
        ) { DatePicker(state) }
    }
    pickTime?.let { which ->
        val initial = (if (which == "start") start else end) ?: start ?: LocalTime.of(10, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            title = { Text(if (which == "start") tr("Start time") else tr("End time")) },
            text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = {
                val t = LocalTime.of(state.hour, state.minute)
                if (which == "start") { start = t; confirmed = date != null } else end = t
                pickTime = null
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked, onChange); Text(label)
    }
