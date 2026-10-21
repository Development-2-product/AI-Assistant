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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.data.jobs.JobTrackerRepository
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.domain.jobs.ApplicationSource
import com.iamode.app.domain.jobs.ApplicationStage
import com.iamode.app.domain.jobs.Funnel
import com.iamode.app.domain.jobs.SharedJob
import com.iamode.app.domain.jobs.SharedJobParser
import com.iamode.app.ui.components.AnimatedCounter
import com.iamode.app.ui.components.AccountAvatar
import com.iamode.app.ui.components.AccountMenu
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.Entrance
import com.iamode.app.ui.components.pressScale
import com.iamode.app.ui.components.rememberHaptics
import com.iamode.app.ui.feature.mail.SenderAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ApplicationsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val tracker: JobTrackerRepository,
    gmailAccountDao: GmailAccountDao,
    private val gmail: GmailRepository,
) : ViewModel() {
    val applications = tracker.applications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val funnel = tracker.funnel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val gmailAccounts = gmailAccountDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accountEmail = gmailAccounts.map { it.firstOrNull()?.email.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    /** Pre-filled from the Android share sheet; the user confirms before anything is saved. */
    val shared: SharedJob? = saved.get<String>("shared")?.takeIf { it.isNotBlank() }?.let(SharedJobParser::parse)

    fun add(company: String, role: String, url: String, location: String, stage: ApplicationStage, fromShare: Boolean, then: (String) -> Unit) =
        viewModelScope.launch {
            then(tracker.addManual(company, role, url, if (fromShare) ApplicationSource.SHARED_LINK else ApplicationSource.MANUAL, stage, location))
        }
    fun removeGmail(email: String) = viewModelScope.launch { gmail.removeAccount(email) }
}

private enum class Filter(val label: String, val stages: Set<ApplicationStage>) {
    ALL("All", ApplicationStage.entries.toSet()),
    APPLIED("Applied", setOf(ApplicationStage.APPLIED)),
    ASSESSMENT("Assessment", setOf(ApplicationStage.ASSESSMENT)),
    INTERVIEW("Interview", setOf(ApplicationStage.INTERVIEW)),
    OFFER("Offer", setOf(ApplicationStage.SELECTED, ApplicationStage.OFFER, ApplicationStage.ACCEPTED)),
    CLOSED("Closed", setOf(ApplicationStage.REJECTED, ApplicationStage.GHOSTED, ApplicationStage.DECLINED, ApplicationStage.ACCEPTED)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationsScreen(
    onBack: () -> Unit,
    openApplication: (String) -> Unit,
    openInbox: () -> Unit = {},
    openImportant: () -> Unit = {},
    openSettings: () -> Unit = {},
    vm: ApplicationsViewModel = hiltViewModel(),
) {
    val apps by vm.applications.collectAsStateWithLifecycle()
    val funnel by vm.funnel.collectAsStateWithLifecycle()
    val accountEmail by vm.accountEmail.collectAsStateWithLifecycle()
    val gmailAccounts by vm.gmailAccounts.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(Filter.ALL) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var followUpOnly by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(vm.shared != null) }
    val seen = remember { mutableSetOf<String>() }
    val haptics = rememberHaptics()

    Scaffold(
        containerColor = careerNavy,
        topBar = {
            CareerTopBar(
                accountEmail = accountEmail,
                accounts = gmailAccounts,
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                onBack = onBack,
                onToggleSearch = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" },
                onSearchChange = { searchQuery = it },
                onAddAccount = openSettings,
                onRemoveAccount = vm::removeGmail,
            )
        },
        bottomBar = { CareerBottomNavigation(openInbox, openImportant, openSettings) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { haptics.tick(); adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(tr("Add")) })
        },
    ) { padding ->
        val list = apps.orEmpty()
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "overview") { CareerOverview(funnel = funnel, applications = list) }
            item(key = "summary-cards") { CareerSummaryCards(list) }
            item(key = "milestones") { CareerMilestones(list, openApplication) }
            item(key = "filters") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Applications"), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(tr("Track and manage all your job applications"), color = careerMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(onClick = { followUpOnly = !followUpOnly }, shape = RoundedCornerShape(12.dp), color = careerPanel, border = androidx.compose.foundation.BorderStroke(1.dp, if (followUpOnly) Color(0xFF299CFF) else careerStroke)) {
                        Icon(Icons.Filled.FilterList, if (followUpOnly) tr("Showing follow-ups only") else tr("Filter applications"), tint = if (followUpOnly) Color(0xFF38A7FF) else Color(0xFF9EC8FF), modifier = Modifier.padding(9.dp))
                    }
                }
                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Filter.entries) { fl ->
                        val n = list.count { JobTrackerRepository.stageOf(it.stage) in fl.stages }
                        FilterChip(selected = filter == fl, onClick = { haptics.tick(); filter = fl },
                            label = { Text(if (n > 0) "${tr(fl.label)} · $n" else tr(fl.label)) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0C69D9), selectedLabelColor = Color.White,
                                containerColor = careerPanel, labelColor = Color(0xFFC8D9F5),
                            ), border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = filter == fl, borderColor = careerStroke, selectedBorderColor = Color(0xFF299CFF),
                            ))
                    }
                }
            }
            when {
                list.isEmpty() -> item(key = "empty") {
                    EmptyState(tr("No applications yet"),
                        tr("IA Mode adds them from your email (application received, interviews, offers). You can also add one, or share a job from LinkedIn or Naukri to IA Mode."))
                }
                else -> {
                    val shown = list.filter { JobTrackerRepository.stageOf(it.stage) in filter.stages }
                        .filter { !followUpOnly || !it.nextStepNote.isNullOrBlank() || it.nextStepAt != null }
                        .filter { it.matchesCareerSearch(searchQuery) }
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

private val careerNavy = Color(0xFF041528)
private val careerPanel = Color(0xFF0A203A)
private val careerStroke = Color(0xFF1B4777)
private val careerMuted = Color(0xFF9EB6DA)

@Composable
private fun CareerTopBar(
    accountEmail: String,
    accounts: List<com.iamode.app.core.database.entity.GmailAccountEntity>,
    searchOpen: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
) {
    var accountMenu by remember { mutableStateOf(false) }
    Surface(color = careerNavy) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White) }
                Text(tr("Career"), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.Search, tr("Search applications"), tint = Color.White) }
                Box(Modifier.padding(start = 4.dp)) {
                    IconButton(onClick = { accountMenu = true }) {
                        AccountAvatar(accountEmail, size = 38)
                    }
                    AccountMenu(
                        expanded = accountMenu,
                        activeEmail = accountEmail,
                        accounts = accounts,
                        onDismiss = { accountMenu = false },
                        onAddAccount = { accountMenu = false; onAddAccount() },
                        onRemoveAccount = { email -> accountMenu = false; onRemoveAccount(email) },
                    )
                }
            }
            if (searchOpen) OutlinedTextField(
                value = searchQuery, onValueChange = onSearchChange, singleLine = true,
                placeholder = { Text(tr("Search company, role, or stage")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

private fun JobApplicationEntity.matchesCareerSearch(query: String): Boolean {
    if (query.isBlank()) return true
    val searchable = listOfNotNull(company, role, location, stage, nextStepNote, recruiterEmail).joinToString(" ")
    return searchable.contains(query.trim(), ignoreCase = true)
}

@Composable
private fun CareerOverview(funnel: Funnel?, applications: List<JobApplicationEntity>) {
    val f = funnel
    val total = f?.total ?: applications.size
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Your career journey"), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(tr("Applications, milestones & next steps"), color = Color(0xFFB7CEFF), style = MaterialTheme.typography.bodyLarge)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.background(Brush.linearGradient(listOf(Color(0xFF102D85), Color(0xFF073B76), Color(0xFF07384D)))).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFF126BE5)) {
                    Icon(Icons.Filled.Work, null, tint = Color.White, modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedCounter(total, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(tr("Applications"), color = Color(0xFFD1E1FF), style = MaterialTheme.typography.titleMedium)
                }
            }
                Row(Modifier.fillMaxWidth().height(10.dp).background(Color.White.copy(alpha = .14f), RoundedCornerShape(20.dp))) {
                    val pipelineTotal = maxOf(1, f?.applied ?: applications.size)
                    listOf(
                        (f?.applied ?: applications.count { JobTrackerRepository.stageOf(it.stage) == ApplicationStage.APPLIED }) to Color(0xFF1F9BFF),
                        (f?.assessment ?: 0) to Color(0xFF8D60FF),
                        (f?.interview ?: 0) to Color(0xFFB24AFF),
                        (f?.offer ?: 0) to Color(0xFF22D8A6),
                    ).forEach { (count, color) ->
                        if (count > 0) Box(Modifier.weight(count.toFloat() / pipelineTotal).fillMaxHeight().background(color))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CareerStageCount("Applied", f?.applied ?: applications.count { JobTrackerRepository.stageOf(it.stage) == ApplicationStage.APPLIED })
                    CareerStageCount("Assessment", f?.assessment ?: 0)
                    CareerStageCount("Interview", f?.interview ?: 0)
                    CareerStageCount("Offer", f?.offer ?: 0)
                }
            }
        }
    }
}

@Composable
private fun CareerStageCount(label: String, count: Int) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(count.toString(), color = Color.White, fontWeight = FontWeight.Bold)
    Text(tr(label), color = Color(0xFFD1E1FF), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun CareerSummaryCards(applications: List<JobApplicationEntity>) {
    val active = applications.count { !JobTrackerRepository.stageOf(it.stage).terminal && JobTrackerRepository.stageOf(it.stage) != ApplicationStage.SAVED }
    val interviews = applications.count { JobTrackerRepository.stageOf(it.stage) == ApplicationStage.INTERVIEW }
    val offers = applications.count { JobTrackerRepository.stageOf(it.stage) in setOf(ApplicationStage.SELECTED, ApplicationStage.OFFER) }
    val followUps = applications.count { !it.nextStepNote.isNullOrBlank() || it.nextStepAt != null }
    val cards = listOf(
        Triple("Active", active, Color(0xFF147DFF)), Triple("Interviews", interviews, Color(0xFF8D55FF)),
        Triple("Offers", offers, Color(0xFF18BE8A)), Triple("Follow-ups", followUps, Color(0xFFFF952B)),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(cards) { (label, count, color) ->
            Surface(modifier = Modifier.width(142.dp), shape = RoundedCornerShape(18.dp), color = color.copy(alpha = .16f), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = .7f))) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(if (label == "Interviews") Icons.Filled.CalendarMonth else if (label == "Offers") Icons.Filled.Work else Icons.Filled.Description, null, tint = color)
                    Text(count.toString(), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr(label), color = Color(0xFFD8E7FF), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun CareerMilestones(applications: List<JobApplicationEntity>, onOpen: (String) -> Unit) {
    val milestones = applications.filter { it.nextStepAt != null || !it.nextStepNote.isNullOrBlank() }
        .sortedBy { it.nextStepAt ?: Long.MAX_VALUE }.take(3)
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = careerPanel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarMonth, null, tint = Color(0xFF9E62FF))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Upcoming milestones"), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(tr("Interviews, deadlines and follow-ups"), color = careerMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (milestones.isEmpty()) {
                Text(tr("No dated interview, deadline, or follow-up is available yet."), color = careerMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
            } else milestones.forEach { app ->
                val stage = JobTrackerRepository.stageOf(app.stage)
                val parts = milestoneDate(app.nextStepAt)
                Surface(onClick = { onOpen(app.id) }, shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .035f)) {
                    Row(Modifier.padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = stage.accent().copy(alpha = .25f)) {
                            Column(Modifier.width(48.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(parts.first, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                Text(parts.second, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.nextStepNote ?: stage.label, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(app.company, app.role).joinToString(" · ").ifBlank { tr("Open application") }, color = careerMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StageChip(stage)
                    }
                }
            }
        }
    }
}

private fun milestoneDate(millis: Long?): Pair<String, String> {
    if (millis == null) return "DATE" to "—"
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return date.format(DateTimeFormatter.ofPattern("MMM")).uppercase() to date.format(DateTimeFormatter.ofPattern("dd"))
}

@Composable
private fun CareerProgress(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr(label), color = Color(0xFFD8E7FF), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f).height(7.dp).background(Color.White.copy(alpha = .16f), RoundedCornerShape(20.dp))) {
            Box(Modifier.fillMaxWidth((count.coerceAtMost(12) / 12f).coerceAtLeast(if (count > 0) .08f else 0f)).fillMaxHeight().background(color, RoundedCornerShape(20.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(18.dp))
    }
}

@Composable
private fun CareerBottomNavigation(onInbox: () -> Unit, onImportant: () -> Unit, onSettings: () -> Unit) {
    NavigationBar(containerColor = careerNavy) {
        NavigationBarItem(false, onInbox, { Icon(Icons.Filled.Mail, null) }, label = { Text(tr("Inbox")) })
        NavigationBarItem(true, {}, { Icon(Icons.Filled.Work, null) }, label = { Text(tr("Career")) },
            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF159DFF), selectedTextColor = Color(0xFF159DFF)))
        NavigationBarItem(false, onImportant, { Icon(Icons.Filled.StarBorder, null) }, label = { Text(tr("Important")) })
        NavigationBarItem(false, onSettings, { Icon(Icons.Filled.Settings, null) }, label = { Text(tr("Settings")) })
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
