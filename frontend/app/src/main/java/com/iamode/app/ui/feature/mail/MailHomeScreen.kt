package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.iamode.app.core.database.entity.CalendarExtractionEntity
import com.iamode.app.core.database.dao.GmailAccountDao
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.data.mail.MailItem
import com.iamode.app.data.mail.MailSyncWorker
import com.iamode.app.data.mail.MailSyncStatusStore
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.domain.mail.MailTab
import com.iamode.app.ui.components.AnimatedCounter
import com.iamode.app.ui.components.AccountAvatar
import com.iamode.app.ui.components.AccountMenu
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.Entrance
import com.iamode.app.ui.components.MailCardSkeleton
import com.iamode.app.ui.components.pressScale
import com.iamode.app.ui.components.rememberHaptics
import com.iamode.app.ui.components.sharedTransition
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MailHomeState(
    val items: List<MailItem> = emptyList(),
    val actionsByEmail: Map<String, List<MailActionEntity>> = emptyMap(),
    val events: List<CalendarExtractionEntity> = emptyList(),
    val pendingCount: Int = 0,
    val unreadCount: Int = 0,
    val loaded: Boolean = false,
)

data class MailSyncUiState(
    val active: Boolean = false,
    val running: Boolean = false,
    val queued: Boolean = false,
    val lastSuccessfulAt: Long? = null,
)

@HiltViewModel
class MailHomeViewModel @Inject constructor(
    private val repo: MailIntelligenceRepository,
    gmailAccounts: GmailAccountDao,
    syncStatus: MailSyncStatusStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state = combine(repo.mail, repo.pendingActions, repo.allCalendars) { items, actions, events ->
        MailHomeState(
            items = items,
            actionsByEmail = actions.groupBy { it.emailId },
            events = events,
            pendingCount = actions.count { it.actionType != MailActionType.REVIEW_EMAIL.name },
            unreadCount = items.count { !it.entity.archived && it.entity.viewedAt == null },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailHomeState())

    val gmailAccounts = gmailAccounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reflects WorkManager's actual queued/running state; no timer-based fake completion. */
    val sync = combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MailSyncWorker.NOW),
        syncStatus.status,
    ) { work, status ->
        val running = work.any { it.state == WorkInfo.State.RUNNING }
        val queued = work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        MailSyncUiState(
            active = running || queued,
            running = running,
            queued = queued,
            lastSuccessfulAt = status.lastSuccessfulAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailSyncUiState())

    fun refresh() {
        // A user-requested refresh can use the complete inbox budget; scheduled work remains
        // intentionally smaller to protect battery and Gmail provider quota.
        MailSyncWorker.syncNow(context, limit = 40)
    }

    fun archive(emailId: String, archived: Boolean, done: (String) -> Unit = {}) = viewModelScope.launch { done(repo.setArchived(emailId, archived)) }
    fun markRead(emailId: String, done: (String) -> Unit = {}) = viewModelScope.launch { done(repo.markRead(emailId)) }
    fun markAllRead(done: (String) -> Unit = {}) = viewModelScope.launch {
        done(repo.markAllRead(state.value.items.filter { !it.entity.archived && it.entity.viewedAt == null }.map { it.emailId }))
    }
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
    openSettings: () -> Unit = {},
    initialTab: MailTab = MailTab.INBOX,
    vm: MailHomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val gmailAccounts by vm.gmailAccounts.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(initialTab) { pager.scrollToPage(tabs.indexOf(initialTab).coerceAtLeast(0)) }
    val snackbar = remember { SnackbarHostState() }
    val seen = remember { mutableSetOf<String>() }
    val haptics = rememberHaptics()
    var showMarkAllDialog by remember { mutableStateOf(false) }
    var markingAllRead by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var readOnly by rememberSaveable { mutableStateOf(false) }
    var importantOnly by rememberSaveable { mutableStateOf(false) }
    var replyOnly by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val activeFilterCount = listOf(unreadOnly, readOnly, importantOnly, replyOnly).count { it }

    Scaffold(
        topBar = {
            PremiumMailHeader(
                unreadCount = state.unreadCount,
                pendingCount = state.pendingCount,
                accounts = gmailAccounts,
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                onBack = {
                    // Category pages are pager states, not separate activities. Back returns to
                    // the previously viewed Inbox before it ever leaves Mail for Home.
                    when {
                        searchOpen -> { searchOpen = false; searchQuery = "" }
                        pager.currentPage != tabs.indexOf(MailTab.INBOX) -> scope.launch {
                            pager.animateScrollToPage(tabs.indexOf(MailTab.INBOX))
                        }
                        else -> onBack()
                    }
                },
                onSearchOpen = { searchOpen = !searchOpen },
                onSearchChange = { searchQuery = it },
                onMarkAllRead = { showMarkAllDialog = true },
                onActions = openActions,
                onManageAccounts = openSettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            PremiumMailNavigation(
                onCareer = openApplications,
                onPriority = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.IMPORTANT)) } },
                onSettings = openSettings,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                val tab = tabs[page]
                val shown = state.items.filter { item ->
                    (if (tab == MailTab.INBOX) item.isPriorityInbox() else tab in item.tabs) &&
                        item.matchesMailSearch(searchQuery) &&
                        (!unreadOnly || item.entity.viewedAt == null) &&
                        (!readOnly || item.entity.viewedAt != null) &&
                        (!importantOnly || item.priority.name == "HIGH" || MailTab.IMPORTANT in item.tabs) &&
                        (!replyOnly || MailTab.REPLY_NEEDED in item.tabs)
                }
                PullToRefreshBox(isRefreshing = sync.active, onRefresh = { haptics.tick(); vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    when {
                        !state.loaded || (sync.active && state.items.isEmpty()) -> LazyColumn(
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { items(4) { MailCardSkeleton() } }
                        shown.isEmpty() -> LazyColumn(
                            Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (tab == MailTab.INBOX && searchQuery.isBlank()) item(key = "career-dashboard-empty") {
                                PremiumCareerDashboard(
                                    items = state.items, events = state.events,
                                    openApplications = openApplications,
                                    openInterviews = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.INTERVIEWS)) } },
                                    openOutcomes = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.OPPORTUNITIES)) } },
                                    openDocuments = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.DOCUMENTS)) } },
                                    openImportant = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.IMPORTANT)) } },
                                    openReplyNeeded = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.REPLY_NEEDED)) } },
                                    openEvent = { event -> openEmail(event.emailId) },
                                )
                            }
                            item(key = "category-tabs-empty") {
                                PremiumMailTabs(selected = pager.currentPage, counts = state.items, onSelect = { selectedPage ->
                                    haptics.tick(); scope.launch { pager.animateScrollToPage(selectedPage) }
                                })
                            }
                            if (tab == MailTab.INBOX && searchQuery.isBlank()) item(key = "mail-sync-empty") {
                                MailSyncStatusCard(sync = sync, onRefresh = { haptics.tick(); vm.refresh() })
                            }
                            if (tab == MailTab.INBOX) item(key = "priority-heading-empty") {
                                PremiumInboxHeading(shown.size, activeFilterCount) { showFilters = true }
                            }
                            item { EmptyState(emptyTitle(tab), emptyBody(tab)) }
                        }
                        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (tab == MailTab.INBOX && searchQuery.isBlank()) item(key = "career-dashboard") {
                                PremiumCareerDashboard(
                                    items = state.items,
                                    events = state.events,
                                    openApplications = openApplications,
                                    openInterviews = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.INTERVIEWS)) } },
                                    openOutcomes = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.OPPORTUNITIES)) } },
                                    openDocuments = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.DOCUMENTS)) } },
                                    openImportant = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.IMPORTANT)) } },
                                    openReplyNeeded = { scope.launch { pager.animateScrollToPage(tabs.indexOf(MailTab.REPLY_NEEDED)) } },
                                    openEvent = { event ->
                                        state.actionsByEmail[event.emailId].orEmpty()
                                            .firstOrNull { it.actionType == MailActionType.CREATE_CALENDAR_EVENT.name }
                                            ?.let { openCalendar(it.id) } ?: openEmail(event.emailId)
                                    },
                                )
                            }
                            item(key = "category-tabs") {
                                PremiumMailTabs(selected = pager.currentPage, counts = state.items, onSelect = { selectedPage ->
                                    haptics.tick(); scope.launch { pager.animateScrollToPage(selectedPage) }
                                })
                            }
                            if (tab == MailTab.INBOX && searchQuery.isBlank()) item(key = "mail-sync") {
                                MailSyncStatusCard(sync = sync, onRefresh = { haptics.tick(); vm.refresh() })
                            }
                            if (tab == MailTab.INBOX) item(key = "priority-heading") {
                                PremiumInboxHeading(
                                    count = shown.size,
                                    activeFilters = activeFilterCount,
                                    onFilter = { showFilters = true },
                                )
                            }
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
                                            unread = item.entity.viewedAt == null,
                                            onSwiped = {
                                                vm.archive(item.emailId, !item.entity.archived) { msg ->
                                                    scope.launch {
                                                        val r = snackbar.showSnackbar(msg, actionLabel = tr("Undo"), withDismissAction = true)
                                                        if (r == SnackbarResult.ActionPerformed) {
                                                            // The query is ordered by received time, so the restored item returns
                                                            // to its original chronological position. Remove its entrance key so
                                                            // Undo has a visible return transition rather than silently popping in.
                                                            seen.remove(item.emailId)
                                                            vm.archive(item.emailId, item.entity.archived) { undoMessage ->
                                                                scope.launch { snackbar.showSnackbar(undoMessage) }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            onMarkRead = {
                                                vm.markRead(item.emailId) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
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
    if (showMarkAllDialog) {
        AlertDialog(
            onDismissRequest = { if (!markingAllRead) showMarkAllDialog = false },
            title = { Text(tr("Mark all as read?")) },
            text = { Text(tr("This marks the unread mail currently in IA Mode as read in Gmail or Outlook. It may take a moment for a large inbox.")) },
            confirmButton = {
                FilledTonalButton(onClick = {
                    markingAllRead = true
                    vm.markAllRead { msg ->
                        markingAllRead = false
                        showMarkAllDialog = false
                        scope.launch { snackbar.showSnackbar(msg) }
                    }
                }) { Text(tr("Mark all read")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showMarkAllDialog = false }, enabled = !markingAllRead) { Text(tr("Cancel")) } },
        )
    }
    if (showFilters) {
        MailFilterSheet(
            unread = unreadOnly,
            read = readOnly,
            important = importantOnly,
            replyNeeded = replyOnly,
            onApply = { unread, read, important, reply ->
                unreadOnly = unread; readOnly = read; importantOnly = important; replyOnly = reply
                showFilters = false
            },
            onReset = { unreadOnly = false; readOnly = false; importantOnly = false; replyOnly = false; showFilters = false },
            onDismiss = { showFilters = false },
        )
    }
}

private val premiumBlue = Color(0xFF159DFF)
private val premiumNavy = Color(0xFF061323)
private val premiumPanel = Color(0xFF0B1D32)

private fun MailItem.has(category: MailCategory) = primary == category || category in labels

/** Only mail that merits the dedicated priority queue belongs below the dashboard. */
private fun MailItem.isPriorityInbox(): Boolean =
    priority.name == "HIGH" || entity.requiresReply || document != null || primary in setOf(
        MailCategory.IMPORTANT, MailCategory.REPLY_NEEDED, MailCategory.DOCUMENT_REQUEST,
        MailCategory.INTERVIEW_INVITATION, MailCategory.OPPORTUNITY, MailCategory.JOB_SELECTION,
        MailCategory.JOB_OFFER, MailCategory.APPLICATION, MailCategory.RECRUITMENT,
    )

/** Assignment is derived from the actual classified message text, not from a fabricated counter. */
private fun MailItem.isAssignment(): Boolean {
    val text = listOfNotNull(entity.subject, entity.summary, document?.description).joinToString(" ").lowercase()
    return listOf("assignment", "assessment", "coding test", "online test", "take-home", "take home", "task").any(text::contains)
}

private fun MailItem.matchesMailSearch(query: String): Boolean {
    if (query.isBlank()) return true
    val haystack = listOfNotNull(entity.fromName, entity.fromAddress, entity.subject, entity.summary,
        opportunity?.company, opportunity?.role, document?.type?.label).joinToString(" ")
    return haystack.contains(query.trim(), ignoreCase = true)
}

@Composable
private fun PremiumMailHeader(
    unreadCount: Int,
    pendingCount: Int,
    accounts: List<com.iamode.app.core.database.entity.GmailAccountEntity>,
    searchOpen: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchChange: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onActions: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    var accountMenu by remember { mutableStateOf(false) }
    val activeEmail = accounts.firstOrNull()?.email.orEmpty()
    Surface(color = premiumNavy) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Row {
                        Text("IA ", color = premiumBlue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Mode", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(tr("Mail"), color = Color(0xFF9DB4D9), style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = onSearchOpen) { Icon(Icons.Filled.Search, tr("Search mail"), tint = Color.White) }
                IconButton(onClick = onMarkAllRead, enabled = unreadCount > 0) {
                    BadgedBox(badge = { if (unreadCount > 0) Badge { Text(unreadCount.coerceAtMost(99).toString()) } }) {
                        Icon(Icons.Filled.Notifications, tr("Mark all as read"), tint = Color.White)
                    }
                }
                IconButton(onClick = onActions) {
                    BadgedBox(badge = { if (pendingCount > 0) Badge { Text(pendingCount.coerceAtMost(99).toString()) } }) {
                        Icon(Icons.Filled.Bolt, tr("AI actions"), tint = premiumBlue)
                    }
                }
                Box {
                    IconButton(onClick = { accountMenu = true }, enabled = activeEmail.isNotBlank()) {
                        AccountAvatar(activeEmail, size = 38)
                    }
                    AccountMenu(
                        expanded = accountMenu,
                        activeEmail = activeEmail,
                        accounts = accounts,
                        onDismiss = { accountMenu = false },
                        onManageAccounts = { accountMenu = false; onManageAccounts() },
                    )
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = onSearchChange, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(tr("Search sender, company, role, or subject")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PremiumMailTabs(selected: Int, counts: List<MailItem>, onSelect: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(premiumNavy),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(tabs, key = { it.name }) { tab ->
            val index = tabs.indexOf(tab)
            val selectedTab = index == selected
            val count = counts.count { tab in it.tabs }
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(18.dp),
                color = if (selectedTab) premiumBlue.copy(alpha = .28f) else Color(0xFF10243D),
                border = BorderStroke(if (selectedTab) 1.5.dp else 1.dp, if (selectedTab) premiumBlue else Color(0xFF254363)),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TabCategoryIcon(tab, tint = if (selectedTab) Color.White else Color(0xFFAFC7EC))
                    Spacer(Modifier.width(8.dp))
                    Text(tr(tab.label), color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    if (count > 0 && tab != MailTab.INBOX && tab != MailTab.ARCHIVE) {
                        Spacer(Modifier.width(6.dp)); Text(count.toString(), color = Color(0xFF91B8E9), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCategoryIcon(tab: MailTab, tint: Color) {
    val image: ImageVector = when (tab) {
        MailTab.INBOX -> Icons.Filled.Inbox
        MailTab.IMPORTANT -> Icons.Filled.Star
        MailTab.REPLY_NEEDED -> Icons.Filled.Reply
        MailTab.DOCUMENTS -> Icons.Filled.Description
        MailTab.INTERVIEWS, MailTab.CALENDAR -> Icons.Filled.CalendarMonth
        else -> Icons.Filled.Bolt
    }
    Icon(image, null, tint = tint, modifier = Modifier.width(18.dp))
}

/**
 * Makes sync freshness explicit without implying that Android can deliver background mail
 * instantly. The status comes from the actual WorkManager chain and persisted successful scans.
 */
@Composable
private fun MailSyncStatusCard(sync: MailSyncUiState, onRefresh: () -> Unit) {
    val detail = when {
        sync.running -> tr("Checking connected mailboxes now…")
        sync.queued -> tr("Refresh queued. It will run when Android has network access.")
        sync.lastSuccessfulAt != null -> tr(
            "Last synced %1\$s",
            DateUtils.getRelativeTimeSpanString(
                sync.lastSuccessfulAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
        )
        else -> tr("Not synced yet. Connect an account or refresh to begin.")
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10243D)),
        border = BorderStroke(1.dp, Color(0xFF234A70)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Surface(shape = RoundedCornerShape(50), color = premiumBlue.copy(alpha = .18f)) {
                if (sync.active) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(9.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = premiumBlue,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, null, tint = premiumBlue, modifier = Modifier.padding(9.dp).width(18.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Mailbox sync"), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color(0xFFB7C8E9), style = MaterialTheme.typography.bodySmall)
                Text(
                    tr("New-mail notifications can trigger a scan; Android also uses a 15-minute fallback."),
                    color = Color(0xFF829ABB),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            FilledTonalButton(onClick = onRefresh, enabled = !sync.active) {
                Text(if (sync.active) tr("Updating") else tr("Refresh"))
            }
        }
    }
}

/** Premium overview built only from locally classified mail and extracted calendar data. */
@Composable
private fun PremiumCareerDashboard(
    items: List<MailItem>,
    events: List<CalendarExtractionEntity>,
    openApplications: () -> Unit,
    openInterviews: () -> Unit,
    openOutcomes: () -> Unit,
    openDocuments: () -> Unit,
    openImportant: () -> Unit,
    openReplyNeeded: () -> Unit,
    openEvent: (CalendarExtractionEntity) -> Unit,
) {
    val applications = items.count { it.has(MailCategory.APPLICATION) }
    val interviews = items.count { it.has(MailCategory.INTERVIEW_INVITATION) }
    val offers = items.count { it.has(MailCategory.JOB_SELECTION) || it.has(MailCategory.JOB_OFFER) }
    val assignments = items.count { it.isAssignment() }
    val important = items.count { it.has(MailCategory.IMPORTANT) || it.priority.name == "HIGH" }
    // Reply Needed is a mutable task state.  The tab deliberately follows requiresReply,
    // not merely the AI's original category label, so the counter and list can never disagree.
    val replyNeeded = items.count { MailTab.REPLY_NEEDED in it.tabs }
    val metrics = listOf(
        PremiumMetric("Applications", applications, Icons.Filled.Description, Color(0xFF097FFF), openApplications),
        PremiumMetric("Interviews", interviews, Icons.Filled.CalendarMonth, Color(0xFFB642FF), openInterviews),
        PremiumMetric("Offers", offers, Icons.Filled.Work, Color(0xFF00B77B), openOutcomes),
        PremiumMetric("Assignments", assignments, Icons.Filled.Description, Color(0xFFFF8A18), openDocuments),
        PremiumMetric("Important", important, Icons.Filled.Flag, Color(0xFFFF466D), openImportant),
        PremiumMetric("Reply needed", replyNeeded, Icons.Filled.Star, Color(0xFFFFC400), openReplyNeeded),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.background(Brush.linearGradient(listOf(Color(0xFF28207E), Color(0xFF00385B), Color(0xFF013E37))))
                    .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Career overview"), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(tr("Your job-related emails, kept organized"), color = Color(0xFFBECFFF), style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = openApplications) { Text(tr("View all  ›"), color = Color(0xFF71C9FF)) }
                }
                metrics.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.forEach { metric -> PremiumMetricTile(metric, Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (events.isNotEmpty()) PremiumEvents(events = events, onEvent = openEvent)
    }
}

private data class PremiumMetric(val title: String, val count: Int, val icon: ImageVector, val accent: Color, val click: () -> Unit)

@Composable
private fun PremiumMetricTile(metric: PremiumMetric, modifier: Modifier = Modifier) {
    Surface(onClick = metric.click, modifier = modifier, shape = RoundedCornerShape(16.dp), color = metric.accent.copy(alpha = .18f), border = BorderStroke(1.dp, metric.accent.copy(alpha = .65f))) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(50), color = metric.accent.copy(alpha = .26f)) {
                Icon(metric.icon, null, tint = metric.accent, modifier = Modifier.padding(8.dp).width(20.dp))
            }
            Text(metric.count.toString(), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(tr(metric.title), color = Color(0xFFD9E6FF), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PremiumEvents(events: List<CalendarExtractionEntity>, onEvent: (CalendarExtractionEntity) -> Unit) {
    val realEvents = events.filter { !it.title.isNullOrBlank() }.sortedByDescending { it.updatedAt }.take(3)
    if (realEvents.isEmpty()) return
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = premiumPanel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarMonth, null, tint = Color(0xFFB97BFF)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Upcoming events"), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(tr("Interviews, deadlines and follow-ups"), color = Color(0xFFB7C8E9), style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(realEvents, key = { it.id }) { event ->
                    Surface(onClick = { onEvent(event) }, modifier = Modifier.width(210.dp), shape = RoundedCornerShape(17.dp), color = Color(0xFF10243D), border = BorderStroke(1.dp, Color(0xFF234A70))) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(event.date ?: event.dateText ?: tr("Date to confirm"), color = Color(0xFF8ED1FF), style = MaterialTheme.typography.labelLarge)
                            Text(event.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(event.organizer, event.startTime).joinToString(" · ").ifBlank { tr("Open to review") }, color = Color(0xFFB7C8E9), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumInboxHeading(count: Int, activeFilters: Int, onFilter: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("✦", color = Color(0xFFFFCF33), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Priority inbox"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(tr("Job-related emails, sorted for you"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = premiumBlue.copy(alpha = .18f)) { Text(count.toString(), color = premiumBlue, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.Bold) }
        Box {
            IconButton(onClick = onFilter) {
                BadgedBox(badge = { if (activeFilters > 0) Badge { Text(activeFilters.toString()) } }) {
                    Icon(Icons.Filled.FilterList, tr("Filter priority inbox"), tint = Color(0xFFAFC7EC))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailFilterSheet(
    unread: Boolean,
    read: Boolean,
    important: Boolean,
    replyNeeded: Boolean,
    onApply: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingUnread by remember { mutableStateOf(unread) }
    var pendingRead by remember { mutableStateOf(read) }
    var pendingImportant by remember { mutableStateOf(important) }
    var pendingReply by remember { mutableStateOf(replyNeeded) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF10243D), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Filter priority inbox"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(tr("Message status"), color = Color(0xFF9DB4D9), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pendingUnread, onClick = { pendingUnread = !pendingUnread; if (pendingUnread) pendingRead = false }, label = { Text(tr("Unread")) })
                FilterChip(selected = pendingRead, onClick = { pendingRead = !pendingRead; if (pendingRead) pendingUnread = false }, label = { Text(tr("Read")) })
            }
            Text(tr("Importance and engagement"), color = Color(0xFF9DB4D9), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pendingImportant, onClick = { pendingImportant = !pendingImportant }, label = { Text(tr("Important")) })
                FilterChip(selected = pendingReply, onClick = { pendingReply = !pendingReply }, label = { Text(tr("Reply needed")) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text(tr("Reset")) }
                FilledTonalButton(onClick = { onApply(pendingUnread, pendingRead, pendingImportant, pendingReply) }, modifier = Modifier.weight(1f)) { Text(tr("Apply filters")) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PremiumMailNavigation(onCareer: () -> Unit, onPriority: () -> Unit, onSettings: () -> Unit) {
    NavigationBar(containerColor = premiumNavy) {
        NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Filled.Description, null) }, label = { Text(tr("Inbox")) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = premiumBlue, selectedTextColor = premiumBlue))
        NavigationBarItem(selected = false, onClick = onCareer, icon = { Icon(Icons.Filled.Work, null) }, label = { Text(tr("Career")) })
        NavigationBarItem(selected = false, onClick = onPriority, icon = { Icon(Icons.Filled.StarBorder, null) }, label = { Text(tr("Important")) })
        NavigationBarItem(selected = false, onClick = onSettings, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(tr("Settings")) })
    }
}

/** Swipe left to archive (or unarchive); swipe right to mark an unread mail read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToArchive(archived: Boolean, unread: Boolean, onSwiped: () -> Unit, onMarkRead: () -> Unit, content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.EndToStart -> { onSwiped(); true }
                // Read mail remains in the list, so let the component settle back after firing
                // the asynchronous mailbox operation rather than leaving the card displaced.
                SwipeToDismissBoxValue.StartToEnd -> if (unread) { onMarkRead(); false } else false
                else -> false
            }
        },
        positionalThreshold = { it * 0.35f },
    )
    LaunchedEffect(state.targetValue) { if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.threshold() }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = unread,
        backgroundContent = {
            val markRead = state.targetValue == SwipeToDismissBoxValue.StartToEnd
            val active = state.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(if (active) if (markRead) Color(0xFF2563EB) else Color(0xFF12B886) else MaterialTheme.colorScheme.surfaceContainerHigh, label = "bg")
            val iconScale by animateFloatAsState(if (active) 1.2f else 0.85f, label = "icon")
            Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp), contentAlignment = if (markRead) Alignment.CenterStart else Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (markRead) tr("Mark read") else if (archived) tr("Move to Inbox") else tr("Archive"), color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(if (markRead) Icons.Filled.DoneAll else if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null,
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
        modifier = modifier.fillMaxWidth().pressScale(interaction), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.sharedTransition("mail-avatar-${item.emailId}")) { SenderAvatar(item.sender, look.accent) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.sender, style = MaterialTheme.typography.titleSmall, fontWeight = if (item.entity.viewedAt == null) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        PriorityDot(item.priority)
                    }
                    Text(item.entity.subject.orEmpty().ifBlank { tr("(no subject)") }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (item.entity.viewedAt == null) FontWeight.SemiBold else FontWeight.Normal,
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
