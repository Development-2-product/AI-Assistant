package com.iamode.app.ui.components

import com.iamode.app.core.i18n.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.MessageKind
import com.iamode.app.domain.model.Relationship
import com.iamode.app.ui.theme.IAColors
import com.iamode.app.ui.theme.Motion
import com.iamode.app.ui.theme.IAModeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun statusLabel(c: Conversation): Pair<String, Color> = when (c.status) {
    ConversationStatus.ANALYZING -> tr("Reading…") to IAColors.Blue
    ConversationStatus.PENDING_APPROVAL -> (if (c.aiGenerated) tr("Needs approval") else tr("Reply yourself")) to IAColors.Amber
    ConversationStatus.QUEUED -> tr("Sending") to IAColors.Violet
    ConversationStatus.WAITING -> (if (c.autopilot) tr("IA Mode handling") else tr("Waiting for them")) to IAColors.Green
    ConversationStatus.CALLBACK -> tr("Call back") to IAColors.Amber
    ConversationStatus.CRISIS -> tr("Needs you now") to IAColors.Red
    ConversationStatus.ENDED -> tr("Ended") to IAColors.Grey
    ConversationStatus.SKIPPED -> tr("Skipped") to IAColors.Grey
}

fun relationshipColor(r: Relationship): Color = when (r) {
    Relationship.CLIENT, Relationship.BUSINESS -> IAColors.Blue
    Relationship.PARTNER -> Color(0xFFD1467A)
    Relationship.FRIEND -> IAColors.Green
    Relationship.FAMILY -> IAColors.Amber
    Relationship.GROUP -> Color(0xFF0E8C8C)
    Relationship.UNKNOWN -> IAColors.Grey
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Status pill that cross-fades its text and color when the conversation moves to a new state. */
@Composable
fun StatusPill(c: Conversation) {
    val (label, target) = statusLabel(c)
    val color by animateColorAsState(target, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "statusColor")
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            (fadeIn(tween(Motion.MEDIUM, delayMillis = 60)) + slideInVertically(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { it / 2 }) togetherWith
                fadeOut(tween(Motion.SHORT))
        },
        label = "statusText",
    ) { text -> Pill(text, color) }
}

@Composable
fun RelationshipPill(r: Relationship) = Pill(tr(r.label), relationshipColor(r))

/** Initials in the relationship's color: the one visual cue that tells you who's who at a glance. */
@Composable
fun Avatar(name: String, relationship: Relationship, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = relationshipColor(relationship)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .semantics { contentDescription = "${relationship.label}: $name" },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = color, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp, maxLines = 1)
    }
}

fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || words.first().first().let { it.isDigit() || it == '+' }) return "#"
    return words.take(2).joinToString("") { it.first().uppercase() }
}

fun channelIcon(c: Channel, isCall: Boolean = false): ImageVector = when {
    isCall -> Icons.Filled.Phone
    c == Channel.GMAIL -> Icons.Filled.Email
    c == Channel.SMS -> Icons.Filled.Sms
    c == Channel.TELEGRAM -> Icons.AutoMirrored.Filled.Send
    c == Channel.INSTAGRAM -> Icons.Filled.PhotoCamera
    c == Channel.WHATSAPP_BUSINESS -> Icons.Filled.Storefront
    else -> Icons.AutoMirrored.Outlined.Chat
}

/** Undo-window countdown with a progress bar that drains smoothly, frame by frame. */
@Composable
fun Countdown(sendAt: Long?, startedAt: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sendAt) {
        while (sendAt != null && now < sendAt) {
            withFrameMillis { }
            now = System.currentTimeMillis()
        }
    }
    val end = sendAt ?: now
    val total = (end - startedAt).coerceAtLeast(1)
    val remaining = (end - now).coerceAtLeast(0)
    val secs = (remaining + 999) / 1000
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (secs > 0) tr("Sending in %1\$ss", secs) else tr("Sending…"), style = MaterialTheme.typography.labelSmall,
            color = IAColors.Violet, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { remaining.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
            color = IAColors.Violet,
            trackColor = IAColors.Violet.copy(alpha = 0.15f),
        )
    }
}

@Composable
fun ConversationCard(c: Conversation, onClick: () -> Unit, modifier: Modifier = Modifier, onUndo: (() -> Unit)? = null) {
    Card(
        onClick = onClick,
        // Draft, status, and undo controls can appear asynchronously; morph the card rather
        // than abruptly shifting every conversation below it.
        modifier = modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(c.displayName, c.relationship, Modifier.sharedTransition("avatar-${c.id}"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).sharedTransition("name-${c.id}"))
                    Icon(channelIcon(c.channel, c.isCallReply), tr(c.channel.label), Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.weight(1f))
                    Text(time(c.updatedAt), style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
                }
                val preview = c.pendingReply?.takeIf { it.isNotBlank() }?.let { tr("Reply: %1\$s", it) } ?: c.recap ?: c.summary ?: c.reason
                preview?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
                if (c.status == ConversationStatus.QUEUED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Countdown(c.sendAt, c.updatedAt, Modifier.weight(1f))
                        if (onUndo != null) TextButton(onClick = onUndo) { Text(tr("Undo")) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(c)
                        RelationshipPill(c.relationship)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(m: Message, contactName: String, modifier: Modifier = Modifier) {
    if (m.kind == MessageKind.CALL) {
        Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            Pill(tr("📞 Missed call from %1\$s, %2\$s", contactName, (time(m.timestamp))), IAColors.Grey)
        }
        return
    }
    val mine = m.fromMe
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            val tag = when {
                m.kind == MessageKind.CALL_REPLY -> tr(", missed-call reply")
                mine && m.auto -> tr(", sent by IA Mode")
                mine -> tr(", you approved")
                else -> ""
            }
            Text(time(m.timestamp) + tag, style = MaterialTheme.typography.labelSmall, color = IAColors.Grey)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = IAColors.Grey)
    }
}

/** Design-time coverage for every notification-reply channel and the approval/auto-send states. */
@Preview(name = "Chat channels", showBackground = true, widthDp = 390, heightDp = 760)
@Composable
private fun ChatChannelsPreview() {
    val now = 1_735_689_600_000L
    IAModeTheme(dynamic = false) {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                previewConversation(Channel.WHATSAPP, "Ananya", Relationship.FRIEND, ConversationStatus.PENDING_APPROVAL,
                    "Are you free to talk later?", now).let { ConversationCard(it, onClick = {}) }
                previewConversation(Channel.WHATSAPP_BUSINESS, "Acme Studio", Relationship.CLIENT, ConversationStatus.QUEUED,
                    "Please share the updated timeline.", now).let { ConversationCard(it, onClick = {}, onUndo = {}) }
                previewConversation(Channel.TELEGRAM, "Rahul", Relationship.FAMILY, ConversationStatus.WAITING,
                    "Reached home safely.", now).let { ConversationCard(it, onClick = {}) }
                previewConversation(Channel.INSTAGRAM, "Design Club", Relationship.GROUP, ConversationStatus.PENDING_APPROVAL,
                    "@You, can you join the call?", now).let { ConversationCard(it, onClick = {}) }
            }
        }
    }
}

@Preview(name = "Conversation messages", showBackground = true, widthDp = 390)
@Composable
private fun ConversationMessagesPreview() {
    val now = 1_735_689_600_000L
    IAModeTheme(dynamic = false) {
        Surface {
            Column(Modifier.padding(16.dp)) {
                ChatBubble(Message(1, "preview", false, text = "Can we talk after your meeting?", timestamp = now), "Ananya")
                ChatBubble(Message(2, "preview", true, text = "Yes, I’ll call you when I’m free.", timestamp = now + 60_000, auto = true), "Ananya")
                ChatBubble(Message(3, "preview", false, kind = MessageKind.CALL, text = "Missed call", timestamp = now + 120_000), "Ananya")
            }
        }
    }
}

private fun previewConversation(channel: Channel, name: String, relationship: Relationship, status: ConversationStatus, preview: String, now: Long) =
    Conversation(
        id = "preview-${channel.name}", channel = channel, address = name, displayName = name,
        relationship = relationship, status = status, sessionId = "preview", pendingReply = if (status == ConversationStatus.QUEUED) "Thanks, I’ll check and confirm shortly." else null,
        summary = preview, sendAt = if (status == ConversationStatus.QUEUED) now + 8_000 else null,
        createdAt = now - 60_000, updatedAt = now,
    )

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = IAColors.Grey) }
        }
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

fun time(ts: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
