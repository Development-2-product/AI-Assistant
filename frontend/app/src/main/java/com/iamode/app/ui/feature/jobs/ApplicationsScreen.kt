package com.iamode.app.ui.feature.jobs

import com.iamode.app.core.i18n.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.JobApplicationEntity
import com.iamode.app.data.jobs.JobTrackerRepository
import com.iamode.app.domain.jobs.ApplicationSource
import com.iamode.app.domain.jobs.ApplicationStage
import com.iamode.app.domain.jobs.Funnel
import com.iamode.app.domain.jobs.SharedJob
import com.iamode.app.domain.jobs.SharedJobParser
import com.iamode.app.ui.components.AnimatedCounter
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.Entrance
import com.iamode.app.ui.components.pressScale
import com.iamode.app.ui.components.rememberHaptics
import com.iamode.app.ui.feature.mail.SenderAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApplicationsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val tracker: JobTrackerRepository,
) : ViewModel() {
    val applications = tracker.applications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val funnel = tracker.funnel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** Pre-filled from the Android share sheet; the user confirms before anything is saved. */
    val shared: SharedJob? = saved.get<String>("shared")?.takeIf { it.isNotBlank() }?.let(SharedJobParser::parse)

    fun add(company: String, role: String, url: String, location: String, stage: ApplicationStage, fromShare: Boolean, then: (String) -> Unit) =
        viewModelScope.launch {
            then(tracker.addManual(company, role, url, if (fromShare) ApplicationSource.SHARED_LINK else ApplicationSource.MANUAL, stage, location))
        }
}

private enum class Filter(val label: String, val stages: Set<ApplicationStage>) {
    ACTIVE("Active", setOf(ApplicationStage.APPLIED, ApplicationStage.ASSESSMENT, ApplicationStage.INTERVIEW, ApplicationStage.SELECTED, ApplicationStage.OFFER)),
    SAVED("Saved", setOf(ApplicationStage.SAVED)),
    INTERVIEWS("Interviews", setOf(ApplicationStage.ASSESSMENT, ApplicationStage.INTERVIEW)),
    OFFERS("Offers", setOf(ApplicationStage.SELECTED, ApplicationStage.OFFER, ApplicationStage.ACCEPTED)),
    CLOSED("Closed", setOf(ApplicationStage.REJECTED, ApplicationStage.GHOSTED, ApplicationStage.DECLINED, ApplicationStage.ACCEPTED)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationsScreen(onBack: () -> Unit, openApplication: (String) -> Unit, vm: ApplicationsViewModel = hiltViewModel()) {
    val apps by vm.applications.collectAsStateWithLifecycle()
    val funnel by vm.funnel.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(Filter.ACTIVE) }
    var adding by remember { mutableStateOf(vm.shared != null) }
    val seen = remember { mutableSetOf<String>() }
    val haptics = rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(tr("Applications"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { haptics.tick(); adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(tr("Add")) })
        },
    ) { padding ->
        val list = apps
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            funnel?.takeIf { it.total > 0 }?.let { f -> item(key = "funnel") { FunnelCard(f) } }
            item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Filter.entries) { fl ->
                        val n = list?.count { JobTrackerRepository.stageOf(it.stage) in fl.stages } ?: 0
                        FilterChip(selected = filter == fl, onClick = { haptics.tick(); filter = fl },
                            label = { Text(if (n > 0) "${tr(fl.label)} · $n" else tr(fl.label)) })
                    }
                }
            }
            when {
                list == null -> Unit
                list.isEmpty() -> item(key = "empty") {
                    EmptyState(tr("No applications yet"),
                        tr("IA Mode adds them from your email (application received, interviews, offers). You can also add one, or share a job from LinkedIn or Naukri to IA Mode."))
                }
                else -> {
                    val shown = list.filter { JobTrackerRepository.stageOf(it.stage) in filter.stages }
                    if (shown.isEmpty()) item(key = "none") {
                        Text(tr("Nothing here."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp))
                    }
                    itemsIndexed(shown, key = { _, it -> it.id }) { i, a ->
                        Box(Modifier.animateItem()) { Entrance("${filter.name}-${a.id}", i, seen) { ApplicationCard(a) { openApplication(a.id) } } }
                    }
                }
            }
        }
    }

    if (adding) AddApplicationSheet(vm.shared, onDismiss = { adding = false }) { company, role, url, location, stage ->
        adding = false
        vm.add(company, role, url, location, stage, vm.shared != null, openApplication)
    }
}

@Composable
private fun FunnelCard(f: Funnel) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCounter(f.total, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(8.dp))
                Text("applications", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val max = maxOf(1, f.applied)
            FunnelBar(tr("Applied"), f.applied, max, ApplicationStage.APPLIED.accent(), 0)
            FunnelBar(tr("Assessment"), f.assessment, max, ApplicationStage.ASSESSMENT.accent(), 1)
            FunnelBar(tr("Interview"), f.interview, max, ApplicationStage.INTERVIEW.accent(), 2)
            FunnelBar(tr("Offer"), f.offer, max, ApplicationStage.OFFER.accent(), 3)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("${f.responseRatePercent}%", tr("got a reply"))
                f.averageDaysToResponse?.let { Stat("%.0f".format(it), tr("days to hear back")) }
                if (f.rejected + f.noResponse > 0) Stat("${f.rejected + f.noResponse}", "closed")
            }
        }
    }
}

/** Bars grow in with a stagger, like the funnel is filling up. */
@Composable
private fun FunnelBar(label: String, value: Int, max: Int, color: Color, index: Int) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(value, max) { kotlinx.coroutines.delay(index * 90L); p.animateTo(value.toFloat() / max, tween(700)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f).height(14.dp).background(color.copy(alpha = 0.10f), RoundedCornerShape(7.dp))) {
            Box(Modifier.fillMaxWidth(p.value.coerceIn(0f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(7.dp)))
        }
        Spacer(Modifier.width(10.dp))
        Text("$value", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
    }
}

@Composable
private fun Stat(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ApplicationCard(a: JobApplicationEntity, onClick: () -> Unit) {
    val stage = JobTrackerRepository.stageOf(a.stage)
    val interaction = remember { MutableInteractionSource() }
    Card(onClick = onClick, interactionSource = interaction, modifier = Modifier.fillMaxWidth().pressScale(interaction),
        shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SenderAvatar(a.company ?: "?", stage.accent())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(a.role ?: tr("Role not set"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.company ?: tr("Company not set"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(a.appliedAt?.let { tr("Applied %1\$s", (daysAgo(it))) }, "updated ${daysAgo(a.lastActivityAt)}").joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.nextStepNote?.let { Text(tr("Next: %1\$s", it), style = MaterialTheme.typography.labelMedium, color = stage.accent(), maxLines = 1,
                    overflow = TextOverflow.Ellipsis) }
            }
            StageChip(stage)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddApplicationSheet(prefill: SharedJob?, onDismiss: () -> Unit, onSave: (String, String, String, String, ApplicationStage) -> Unit) {
    var company by remember { mutableStateOf(prefill?.company.orEmpty()) }
    var role by remember { mutableStateOf(prefill?.role.orEmpty()) }
    var url by remember { mutableStateOf(prefill?.url.orEmpty()) }
    var location by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(if (prefill != null) ApplicationStage.SAVED else ApplicationStage.APPLIED) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (prefill != null) tr("Track this job") else tr("Add an application"), style = MaterialTheme.typography.titleLarge)
            prefill?.source?.let { Text(tr("From %1\$s", (it.replaceFirstChar { c -> c.uppercase() })), style = MaterialTheme.typography.labelMedium) }
            OutlinedTextField(company, { company = it.take(120) }, label = { Text(tr("Company")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(role, { role = it.take(120) }, label = { Text(tr("Role")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(location, { location = it.take(80) }, label = { Text(tr("Location (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it.take(500) }, label = { Text(tr("Job link (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(ApplicationStage.SAVED to tr("Saved"), ApplicationStage.APPLIED to tr("Applied")).forEachIndexed { i, (s, l) ->
                    SegmentedButton(selected = stage == s, onClick = { stage = s }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
            Button(onClick = { onSave(company, role, url, location, stage) }, enabled = company.isNotBlank() || role.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text(tr("Save")) }
        }
    }
}
