package com.iamode.app.ui.feature.conversation

import com.iamode.app.core.i18n.tr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.util.PhoneNumbers
import com.iamode.app.ui.components.Avatar
import com.iamode.app.ui.components.ChatBubble
import com.iamode.app.ui.components.Countdown
import com.iamode.app.ui.components.Pill
import com.iamode.app.ui.components.RelationshipPill
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.components.sharedTransition
import com.iamode.app.ui.components.statusLabel
import com.iamode.app.ui.theme.IAColors
import com.iamode.app.ui.theme.Motion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(onBack: () -> Unit, openDiagnostics: () -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = state.conversation
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    val name = c?.displayName ?: vm.initialName
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(name, c?.relationship ?: vm.initialRelationship,
                            Modifier.sharedTransition("avatar-${vm.conversationId}"), size = 36.dp)
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                modifier = Modifier.sharedTransition("name-${vm.conversationId}"))
                            AnimatedVisibility(visible = c != null, enter = fadeIn(tween(Motion.MEDIUM, delayMillis = 150))) {
                                Text(c?.let { conv -> conv.channel.label + (conv.subject?.let { ", $it" } ?: "") }.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                actions = {
                    if (c != null) {
                        phoneNumber(c)?.let { n -> IconButton(onClick = { dial(context, n) }) { Icon(Icons.Filled.Call, tr("Call")) } }
                        IconButton(onClick = { openInApp(context, c) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, tr("Open in %1\$s", (c.channel.label))) }
                    }
                },
            )
        },
        bottomBar = { if (c != null) ActionPanel(c, state.busy, vm, context) },
    ) { padding ->
        if (c == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(), state = listState, contentPadding = PaddingValues(12.dp)) {
            item(key = "info") { InfoCard(c, openDiagnostics) }
            items(state.messages, key = { it.id }) { ChatBubble(it, c.displayName, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun InfoCard(c: Conversation, openDiagnostics: () -> Unit) {
    val (label, color) = statusLabel(c)
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RelationshipPill(c.relationship)
                Pill(label, color)
                c.language?.let { Pill(tr(it.label), IAColors.Blue) }
                c.tone?.let { Pill(it, IAColors.Grey) }
            }
            c.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!c.aiGenerated && c.status == ConversationStatus.PENDING_APPROVAL) {
                TextButton(onClick = openDiagnostics) { Text(tr("Find out why: run the connection check")) }
            }
            c.summary?.let { Text(tr("About: %1\$s", it), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey) }
            c.recap?.let { Text(tr("Recap: %1\$s", it), style = MaterialTheme.typography.bodyMedium) }
            if (c.autopilot && c.status.isOpen) {
                Text(tr("IA Mode is handling this chat until it ends (%1\$s auto replies so far).", (c.autoTurns)),
                    style = MaterialTheme.typography.labelSmall, color = IAColors.Green)
            }
        }
    }
}

@Composable
private fun ActionPanel(c: Conversation, busy: Boolean, vm: ConversationViewModel, context: Context) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            // The panel morphs as the conversation changes state (approve -> sending -> waiting…).
            AnimatedContent(
                targetState = c.status,
                transitionSpec = {
                    (fadeIn(tween(Motion.MEDIUM, delayMillis = 60)) +
                        slideInVertically(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { it / 4 }) togetherWith
                        fadeOut(tween(Motion.SHORT)) using SizeTransform(clip = false)
                },
                label = "actionPanel",
            ) { status ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (status) {
                        ConversationStatus.PENDING_APPROVAL -> ApprovalPanel(c, busy, vm)
                        ConversationStatus.QUEUED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Countdown(c.sendAt, c.updatedAt)
                                Text(c.pendingReply.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            }
                            OutlinedButton(onClick = vm::undo) { Text(tr("Undo")) }
                            Button(onClick = vm::sendNow) { Text(tr("Send now")) }
                        }
                        ConversationStatus.CALLBACK -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            phoneNumber(c)?.let { n -> Button(onClick = { dial(context, n) }) { Text(tr("Call back")) } }
                            OutlinedButton(onClick = vm::markCalledBack) { Text(tr("Mark as called")) }
                        }
                        ConversationStatus.CRISIS -> CrisisPanel(c, busy, vm, context)
                        ConversationStatus.WAITING, ConversationStatus.ANALYZING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (c.autopilot) OutlinedButton(onClick = vm::takeOver) { Text(tr("Take over")) }
                            else OutlinedButton(onClick = vm::letIAModeHandle) { Text(tr("Let IA Mode handle")) }
                            TextButton(onClick = vm::endChat) { Text(tr("End chat")) }
                        }
                        ConversationStatus.ENDED, ConversationStatus.SKIPPED ->
                            Text(tr("This conversation has ended."), style = MaterialTheme.typography.bodyMedium, color = IAColors.Grey)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalPanel(c: Conversation, busy: Boolean, vm: ConversationViewModel) {
    var text by rememberSaveable(c.pendingReply) { mutableStateOf(c.pendingReply.orEmpty()) }
    val haptics = LocalHapticFeedback.current
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(tr("Reply")) }, minLines = 2, maxLines = 6)
    if (c.aiGenerated) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ReplyStyle.entries.toList()) { s ->
                FilterChip(selected = c.style == s, onClick = { vm.restyle(s) }, label = { Text(tr(s.label)) }, enabled = !busy)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.approve(text, handleChat = true) }, enabled = text.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
            Text(tr("Approve & handle chat"))
        }
        OutlinedButton(onClick = { vm.approve(text, handleChat = false) }, enabled = text.isNotBlank() && !busy) { Text(tr("Send once")) }
    }
    TextButton(onClick = vm::dontReply, enabled = !busy) { Text(tr("Don't reply")) }
}

@Composable
private fun CrisisPanel(c: Conversation, busy: Boolean, vm: ConversationViewModel, context: Context) {
    var text by rememberSaveable { mutableStateOf("") }
    Text(tr("This message sounds serious, so IA Mode won't reply. Please reach out yourself. If they may be in danger, call 112. ") +
        tr("Tele-MANAS (14416) is India's free 24/7 mental-health helpline."), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        phoneNumber(c)?.let { n -> Button(onClick = { dial(context, n) }) { Text(tr("Call them")) } }
        OutlinedButton(onClick = { dial(context, "14416") }) { Text(tr("Tele-MANAS")) }
    }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(tr("Or write to them yourself")) })
    Button(onClick = { vm.approve(text, handleChat = false) }, enabled = text.isNotBlank() && !busy) { Text(tr("Send my message")) }
}

private fun phoneNumber(c: Conversation): String? = when {
    c.channel == Channel.SMS -> "+${c.address}"
    (c.channel == Channel.WHATSAPP || c.channel == Channel.WHATSAPP_BUSINESS) && PhoneNumbers.looksLikeNumber(c.address) -> c.address
    else -> null
}

private fun dial(context: Context, number: String) {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openInApp(context: Context, c: Conversation) {
    val intent = when (c.channel) {
        Channel.WHATSAPP -> if (PhoneNumbers.looksLikeNumber(c.address))
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${PhoneNumbers.normalize(c.address)}"))
        else context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        Channel.GMAIL -> context.packageManager.getLaunchIntentForPackage("com.google.android.gm")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
        Channel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+${c.address}"))
        Channel.WHATSAPP_BUSINESS -> context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        Channel.TELEGRAM -> context.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
            ?: context.packageManager.getLaunchIntentForPackage("org.telegram.messenger.web")
        Channel.INSTAGRAM -> context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/inbox/"))
    } ?: return
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { }
}
