package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.iamode.app.core.database.entity.MailActionEntity
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.data.mail.MailItem
import com.iamode.app.data.mail.MailSyncWorker
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MailTab
import com.iamode.app.ui.components.AnimatedCounter
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.Entrance
import com.iamode.app.ui.components.MailCardSkeleton
import com.iamode.app.ui.components.pressScale
import com.iamode.app.ui.components.rememberHaptics
import com.iamode.app.ui.components.sharedTransition
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MailHomeState(
    val items: List<MailItem> = emptyList(),
    val actionsByEmail: Map<String, List<MailActionEntity>> = emptyMap(),
    val pendingCount: Int = 0,
    val loaded: Boolean = false,
)

@HiltViewModel
class MailHomeViewModel @Inject constructor(
    private val repo: MailIntelligenceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state = combine(repo.mail, repo.pendingActions) { items, actions ->
        MailHomeState(items, actions.groupBy { it.emailId }, actions.count { it.actionType != MailActionType.REVIEW_EMAIL.name }, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailHomeState())

    private val requested = MutableStateFlow(false)
    /** True while the real sync job runs, so pull-to-refresh reflects actual work, not a fake timer. */
    val syncing = combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MailSyncWorker.NOW).map { l -> l.any { it.state == WorkInfo.State.RUNNING } },
        requested,
    ) { running, req -> running || req }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun refresh() {
        requested.value = true
        MailSyncWorker.syncNow(context)
        viewModelScope.launch { delay(1_200); requested.value = false }
    }

    fun archive(emailId: String, archived: Boolean, done: (String) -> Unit = {}) = viewModelScope.launch { done(repo.setArchived(emailId, archived)) }
}

private val tabs = listOf(
    MailTab.INBOX, MailTab.IMPORTANT, MailTab.REPLY_NEEDED, MailTab.DOCUMENTS, MailTab.INTERVIEWS, MailTab.OPPORTUNITIES,
    MailTab.CALENDAR, MailTab.PROMOTIONS, MailTab.NOTIFICATIONS, MailTab.NO_REPLY, MailTab.SUSPICIOUS, MailTab.ARCHIVE,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailHomeScreen(
    onBack: () -> Unit, openEmail: (String) -> Unit, openActions: () -> Unit, openOpportunities: () -> Unit,
    openDocument: (String) -> Unit, openCalendar: (String) -> Unit, openApplications: () -> Unit = {},
    vm: MailHomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val seen = remember { mutableSetOf<String>() }
    val haptics = rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Mail"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = {
                    IconButton(onClick = openApplications) { Icon(Icons.Filled.Work, tr("Job applications")) }
                    IconButton(onClick = openActions) {
                        BadgedBox(badge = { if (state.pendingCount > 0) Badge { AnimatedCounter(state.pendingCount) } }) {
                            Icon(Icons.Filled.Bolt, tr("AI Actions"))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp) {
                tabs.forEachIndexed { i, tab ->
                    val count = state.items.count { tab in it.tabs }
                    Tab(selected = pager.currentPage == i, onClick = { haptics.tick(); scope.launch { pager.animateScrollToPage(i) } }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(tab.label))
                            if (count > 0 && tab != MailTab.INBOX && tab != MailTab.ARCHIVE) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                                    .padding(horizontal = 7.dp, vertical = 1.dp)) {
                                    AnimatedCounter(count, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                    })
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val tab = tabs[page]
                val shown = state.items.filter { tab in it.tabs }
                PullToRefreshBox(isRefreshing = syncing, onRefresh = { haptics.tick(); vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    when {
                        !state.loaded || (syncing && state.items.isEmpty()) -> LazyColumn(
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { items(4) { MailCardSkeleton() } }
                        shown.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(emptyTitle(tab), emptyBody(tab)) } }
                        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (tab == MailTab.OPPORTUNITIES) item(key = "opp-header") {
                                FilledTonalButton(onClick = openOpportunities, modifier = Modifier.fillMaxWidth()) {
                                    Text(tr("🎉  Open Opportunities: Selected · Interviews · Offers · Applications · Recruiters"))
                                }
                            }
                            itemsIndexed(shown, key = { _, it -> it.emailId }) { index, item ->
                                Box(Modifier.animateItem()) {
                                    Entrance("$page-${item.emailId}", index, seen) {
                                        SwipeToArchive(
                                            archived = item.entity.archived,
                                            onSwiped = {
                                                vm.archive(item.emailId, !item.entity.archived) { msg ->
                                                    scope.launch {
                                                        val r = snackbar.showSnackbar(msg, actionLabel = tr("Undo"), withDismissAction = true)
                                                        if (r == SnackbarResult.ActionPerformed) vm.archive(item.emailId, item.entity.archived)
                                                    }
                                                }
                                            },
                                        ) {
                                            MailCard(
                                                item, state.actionsByEmail[item.emailId].orEmpty(),
                                                onOpen = { openEmail(item.emailId) },
                                                onAction = { a ->
                                                    when (MailActionType.valueOf(a.actionType)) {
                                                        MailActionType.SEND_DOCUMENT_REPLY -> openDocument(a.id)
                                                        MailActionType.CREATE_CALENDAR_EVENT -> openCalendar(a.id)
                                                        else -> openEmail(item.emailId)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Swipe left to archive (or unarchive in Archive). A haptic tick marks the point of no return. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToArchive(archived: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) { onSwiped(); true } else false },
        positionalThreshold = { it * 0.35f },
    )
    LaunchedEffect(state.targetValue) { if (state.targetValue == SwipeToDismissBoxValue.EndToStart) haptics.threshold() }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = state.targetValue == SwipeToDismissBoxValue.EndToStart
            val bg by animateColorAsState(if (active) Color(0xFF12B886) else MaterialTheme.colorScheme.surfaceContainerHigh, label = "bg")
            val iconScale by animateFloatAsState(if (active) 1.2f else 0.85f, label = "icon")
            Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (archived) tr("Move to Inbox") else tr("Archive"), color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null,
                        tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                }
            }
        },
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailCard(item: MailItem, actions: List<MailActionEntity>, onOpen: () -> Unit, onAction: (MailActionEntity) -> Unit, modifier: Modifier = Modifier) {
    val look = item.primary.look()
    val primaryAction = actions.firstOrNull { it.actionType != MailActionType.REVIEW_EMAIL.name }
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onOpen, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().pressScale(interaction).animateContentSize(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.sharedTransition("mail-avatar-${item.emailId}")) { SenderAvatar(item.sender, look.accent) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        PriorityDot(item.priority)
                    }
                    Text(item.entity.subject.orEmpty().ifBlank { tr("(no subject)") }, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sharedTransition("mail-subject-${item.emailId}"))
                }
                Text(friendlyTime(item.entity.receivedAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.entity.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryChip(item.primary)
                item.opportunity?.role?.let { EntityChip(it) }
                item.opportunity?.company?.let { EntityChip(it) }
                item.document?.let { d -> EntityChip("${d.type.label}${d.requestedFormat?.let { " · $it" } ?: ""}") }
            }
            if (item.suspiciousReasons.isNotEmpty()) SuspiciousBanner(item.suspiciousReasons.take(2))
            if (primaryAction != null) {
                FilledTonalButton(onClick = { onAction(primaryAction) }, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel(primaryAction))
                }
            }
        }
    }
}

fun actionLabel(a: MailActionEntity): String {
    val t = MailActionType.valueOf(a.actionType)
    return when (a.status) {
        "FAILED" -> tr("Retry: %1\$s", (tr(t.label).lowercase()))
        "COMPLETED" -> tr("Done: %1\$s", (tr(t.label).lowercase()))
        else -> when (t) {
            MailActionType.SEND_DOCUMENT_REPLY -> tr("📎  Choose document & reply")
            MailActionType.CREATE_CALENDAR_EVENT -> tr("📅  Review & add to calendar")
            MailActionType.SEND_REPLY -> tr("↩  Review suggested reply")
            MailActionType.UNSUBSCRIBE_EMAIL -> tr("Review unsubscribe")
            MailActionType.SEND_FOLLOW_UP -> tr("📨  Review follow-up")
            MailActionType.REVIEW_EMAIL -> tr("Review carefully")
        }
    }
}

private fun emptyTitle(tab: MailTab) = when (tab) {
    MailTab.INBOX -> tr("Nothing new")
    MailTab.SUSPICIOUS -> tr("No suspicious mail")
    else -> tr("No %1\$s yet", (tr(tab.label).lowercase()))
}

private fun emptyBody(tab: MailTab) = when (tab) {
    MailTab.INBOX -> tr("Pull down to check Gmail. IA Mode sorts your last 7 days of mail here. Connect Gmail in Settings if you haven't.")
    MailTab.DOCUMENTS -> tr("When someone asks you for a document, it shows up here with the files that match.")
    MailTab.INTERVIEWS -> tr("Interview invitations appear here with a calendar preview.")
    MailTab.ARCHIVE -> tr("Swipe a mail card left to archive it.")
    else -> tr("Mail in this category will appear here.")
}
