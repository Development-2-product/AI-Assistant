package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.OpportunityEntity
import com.iamode.app.data.mail.CelebrationCoordinator
import com.iamode.app.data.mail.MailIntelligenceRepository
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.domain.mail.OpportunityStatus
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.pressScale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpportunitiesViewModel @Inject constructor(
    repo: MailIntelligenceRepository,
    private val celebrations: CelebrationCoordinator,
) : ViewModel() {
    val opportunities = repo.opportunities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun replay(emailId: String) = viewModelScope.launch { celebrations.replay(emailId) }
}

private val sections = listOf(
    "Selected" to setOf(OpportunityStatus.SELECTED),
    "Interviews" to setOf(OpportunityStatus.INTERVIEW),
    "Job Offers" to setOf(OpportunityStatus.OFFER),
    "Applications" to setOf(OpportunityStatus.APPLIED, OpportunityStatus.REJECTED),
    "Recruiters" to setOf(OpportunityStatus.RECRUITER_CONTACT),
)

private fun OpportunityStatus.category() = when (this) {
    OpportunityStatus.SELECTED -> MailCategory.JOB_SELECTION
    OpportunityStatus.OFFER -> MailCategory.JOB_OFFER
    OpportunityStatus.INTERVIEW -> MailCategory.INTERVIEW_INVITATION
    OpportunityStatus.APPLIED, OpportunityStatus.REJECTED -> MailCategory.APPLICATION
    OpportunityStatus.RECRUITER_CONTACT -> MailCategory.RECRUITMENT
    OpportunityStatus.NONE -> MailCategory.OPPORTUNITY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpportunitiesScreen(
    onBack: () -> Unit, openEmail: (String) -> Unit, openApplications: () -> Unit = {}, vm: OpportunitiesViewModel = hiltViewModel(),
) {
    val all by vm.opportunities.collectAsStateWithLifecycle()
    val pager = rememberPagerState { sections.size }
    val scope = rememberCoroutineScope()
    val seen = androidx.compose.runtime.remember { mutableSetOf<String>() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("🎉 Opportunities")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
            actions = { androidx.compose.material3.TextButton(onClick = openApplications) { Text(tr("All applications")) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp) {
                sections.forEachIndexed { i, (label, statuses) ->
                    val n = all.count { OpportunityStatus.fromApi(it.status) in statuses }
                    Tab(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(if (n > 0) "${tr(label)} · $n" else tr(label)) })
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val (label, statuses) = sections[page]
                val shown = all.filter { OpportunityStatus.fromApi(it.status) in statuses }
                if (shown.isEmpty()) EmptyState(tr("No %1\$s yet", tr(label).lowercase()), tr("IA Mode adds these automatically from your email."))
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(shown, key = { _, it -> it.id }) { i, o ->
                        com.iamode.app.ui.components.Entrance("$page-${o.id}", i, seen) {
                            OpportunityCard(o, { openEmail(o.emailId) }, { vm.replay(o.emailId) }, Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpportunityCard(o: OpportunityEntity, onView: () -> Unit, onReplay: () -> Unit, modifier: Modifier) {
    val status = OpportunityStatus.fromApi(o.status)
    val look = status.category().look()
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(onClick = onView, interactionSource = interaction, modifier = modifier.fillMaxWidth().pressScale(interaction),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(look.accent.copy(alpha = 0.16f), look.accent.copy(alpha = 0.02f))))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(look.glyph, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(status.label), color = look.accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(friendlyTime(o.receivedAt), style = MaterialTheme.typography.labelMedium)
                }
                Text(o.role ?: tr("Role not stated"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(o.company ?: tr("Company not stated"), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = onView) { Text(tr("View email")) }
                    if (status in setOf(OpportunityStatus.SELECTED, OpportunityStatus.OFFER, OpportunityStatus.INTERVIEW)) {
                        OutlinedButton(onClick = onReplay) { Text(tr("🎉 Replay")) }
                    }
                }
            }
        }
    }
}

