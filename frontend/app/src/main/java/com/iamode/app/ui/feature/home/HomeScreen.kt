package com.iamode.app.ui.feature.home

import com.iamode.app.core.i18n.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamode.app.core.permissions.AppPermissions
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.SituationStatus
import com.iamode.app.ui.components.ConversationCard
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.theme.IAColors
import com.iamode.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    openConversation: (Conversation) -> Unit,
    openAlerts: () -> Unit,
    openContacts: () -> Unit,
    openSettings: () -> Unit,
    openPermissions: () -> Unit,
    openSummary: (String) -> Unit,
    openDiagnostics: () -> Unit,
    openMail: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { HomeTab.entries.size }
    var showSituationSheet by remember { mutableStateOf(false) }
    var missingPermissions by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { missingPermissions = AppPermissions.missingRequired(context).size }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("IA Mode")) }, actions = {
            IconButton(onClick = openAlerts) {
                BadgedBox(badge = { if (state.alertCount > 0) Badge { Text("${state.alertCount}") } }) {
                    Icon(Icons.Filled.Notifications, tr("Alerts"))
                }
            }
            IconButton(onClick = openContacts) { Icon(Icons.Filled.Contacts, tr("Contacts")) }
            IconButton(onClick = openMail) { Icon(Icons.Filled.Email, tr("Mail")) }
            IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, tr("Settings")) }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Landscape / split-screen: keep the mode card small so the lists stay usable.
            val compact = maxHeight < 520.dp
            Column(Modifier.readableWidth().fillMaxSize()) {
                AnimatedVisibility(
                    visible = missingPermissions > 0,
                    enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeIn(),
                    exit = shrinkVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeOut(),
                ) {
                    Card(
                        onClick = openPermissions,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = IAColors.Amber.copy(alpha = 0.15f)),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = IAColors.Amber)
                            Text(tr("  %1\$s permission(s) missing, so IA Mode can't reply. Tap to fix.", missingPermissions),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                val aiFailing = state.needsYou.any { !it.aiGenerated && it.status == ConversationStatus.PENDING_APPROVAL }
                AnimatedVisibility(visible = aiFailing && missingPermissions == 0) {
                    Card(
                        onClick = openDiagnostics,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = IAColors.Red.copy(alpha = 0.12f)),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = IAColors.Red)
                            Text(tr("  IA Mode can't reach the AI, so replies are waiting for you. Tap to find out why."),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                ModeCard(state, compact, onToggle = vm::toggle, onSituation = { showSituationSheet = true },
                    modifier = Modifier.padding(16.dp))
                val last = state.lastSummary
                AnimatedVisibility(
                    visible = !state.isOn && last != null && last.conversations.isNotEmpty(),
                    enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeIn(),
                    exit = shrinkVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeOut(),
                ) {
                    if (last != null) {
                        Card(
                            onClick = { openSummary(last.session.id) },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(tr("While you were busy"), style = MaterialTheme.typography.titleMedium)
                                Text(last.headline.replaceFirstChar { it.uppercase() } + tr(". Tap to see everything."),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                TabRow(selectedTabIndex = pager.currentPage) {
                    HomeTab.entries.forEachIndexed { i, t ->
                        val count = state.listFor(t).size
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(if (count > 0) "${tr(t.label)} ($count)" else tr(t.label), maxLines = 1) },
                        )
                    }
                }
                // Swipe between tabs; each list animates cards in, out and between positions.
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
                    val tab = HomeTab.entries[page]
                    val list = state.listFor(tab)
                    Crossfade(targetState = list.isEmpty(), animationSpec = tween(Motion.MEDIUM), label = "empty") { empty ->
                        if (empty) {
                            EmptyState(title = emptyTitle(tab, state.isOn), body = emptyBody(tab, state.isOn))
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(list, key = { it.id }) { c ->
                                    ConversationCard(
                                        c,
                                        onClick = { openConversation(c) },
                                        modifier = Modifier.animateItem(),
                                        onUndo = { vm.undo(c.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSituationSheet) {
        ModalBottomSheet(onDismissRequest = { showSituationSheet = false }) {
            Text(tr("What are you doing?"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text(tr("🤖  Detect automatically")) },
                supportingContent = { Text(tr("Driving, calendar events, games and night time")) },
                trailingContent = { if (state.manualSituation == null) Text("✓") },
                modifier = Modifier.clickable { vm.setSituation(null); showSituationSheet = false },
            )
            SituationStatus.entries.filter { it != SituationStatus.AVAILABLE }.forEach { s ->
                ListItem(
                    headlineContent = { Text("${s.emoji}  ${s.label}") },
                    trailingContent = { if (state.manualSituation == s) Text("✓") },
                    modifier = Modifier.clickable { vm.setSituation(s); showSituationSheet = false },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun emptyTitle(tab: HomeTab, on: Boolean) = when {
    !on -> tr("IA Mode is off")
    tab == HomeTab.NEEDS_YOU -> tr("Nothing needs you")
    tab == HomeTab.HANDLING -> tr("No active chats")
    else -> tr("No finished chats yet")
}

private fun emptyBody(tab: HomeTab, on: Boolean) = when {
    !on -> tr("Turn it on when you're busy, or use the IA Mode tile in Quick Settings.")
    tab == HomeTab.NEEDS_YOU -> tr("Approvals, missed calls to return and urgent messages show up here.")
    tab == HomeTab.HANDLING -> tr("Chats IA Mode is replying to appear here as people message or call.")
    else -> tr("Ended conversations and their summaries appear here.")
}

@Composable
private fun ModeCard(state: HomeUiState, compact: Boolean, onToggle: () -> Unit, onSituation: () -> Unit, modifier: Modifier) {
    val on = state.isOn
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        tween(Motion.LONG, easing = Motion.Emphasized), label = "modeCard",
    )
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = on,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(Motion.MEDIUM, delayMillis = 80)) +
                            slideInVertically(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { it / 3 }) togetherWith
                            fadeOut(tween(Motion.SHORT))
                    },
                    label = "modeTitle",
                ) { isOn ->
                    Column {
                        Text(if (isOn) tr("IA Mode is on") else tr("IA Mode is off"), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (isOn && state.session != null) tr("On for %1\$s, replying for you", (elapsed(state.session.startedAt)))
                            else tr("You're handling your own messages"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.session?.autoReason?.takeIf { isOn }?.let {
                            Text(tr("Turned on automatically. %1\$s", it), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
                        }
                    }
                }
                Switch(checked = on, onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                })
            }
            val sit = state.situation
            AssistChip(
                onClick = onSituation,
                label = {
                    AnimatedContent(targetState = sit?.status to state.manualSituation, label = "situation") { (status, manual) ->
                        Text(
                            if (status == null) tr("Detecting…")
                            else "${status.emoji} ${status.label}" + if (manual == null) tr(", auto") else tr(", set by you")
                        )
                    }
                },
            )
            if (!compact) {
                sit?.reason?.takeIf { state.manualSituation == null }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
                }
            }
        }
    }
}

@Composable
private fun elapsed(since: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val mins = ((now - since) / 60_000).toInt()
    return if (mins < 60) "$mins min" else tr("%1\$s h %2\$s min", (mins / 60), (mins % 60))
}
