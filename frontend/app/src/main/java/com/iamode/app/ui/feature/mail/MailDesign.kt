package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.domain.mail.MailPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One accent + glyph per category: the same visual language on every mail screen. */
data class CategoryLook(val accent: Color, val glyph: String)

fun MailCategory.look(): CategoryLook = when (this) {
    MailCategory.JOB_SELECTION -> CategoryLook(Color(0xFFF2B233), "🏆")
    MailCategory.JOB_OFFER -> CategoryLook(Color(0xFF12B886), "💼")
    MailCategory.INTERVIEW_INVITATION -> CategoryLook(Color(0xFF7B61FF), "🎯")
    MailCategory.APPLICATION -> CategoryLook(Color(0xFF3B82F6), "📨")
    MailCategory.RECRUITMENT -> CategoryLook(Color(0xFF0EA5E9), "🤝")
    MailCategory.OPPORTUNITY -> CategoryLook(Color(0xFFEC4899), "🎉")
    MailCategory.DOCUMENT_REQUEST -> CategoryLook(Color(0xFFF59E0B), "📎")
    MailCategory.REPLY_NEEDED -> CategoryLook(Color(0xFF2563EB), "↩")
    MailCategory.CALENDAR -> CategoryLook(Color(0xFF14B8A6), "📅")
    MailCategory.CONGRATULATIONS -> CategoryLook(Color(0xFFF97316), "✨")
    MailCategory.IMPORTANT -> CategoryLook(Color(0xFFEF4444), "★")
    MailCategory.SUSPICIOUS -> CategoryLook(Color(0xFFDC2626), "⚠")
    MailCategory.PROMOTION -> CategoryLook(Color(0xFF94A3B8), "📢")
    MailCategory.NOTIFICATION -> CategoryLook(Color(0xFF94A3B8), "🔔")
    MailCategory.NO_REPLY -> CategoryLook(Color(0xFF94A3B8), "🤖")
    MailCategory.ARCHIVE -> CategoryLook(Color(0xFF94A3B8), "🗄")
    MailCategory.GENERAL -> CategoryLook(Color(0xFF64748B), "✉")
}

@Composable
fun SenderAvatar(name: String, accent: Color, size: Int = 44) {
    val initials = name.split(' ', '.', '@').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(size.dp).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(initials.ifBlank { "?" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size / 2.8).sp) }
}

@Composable
fun CategoryChip(category: MailCategory, modifier: Modifier = Modifier) {
    val look = category.look()
    Row(
        modifier.background(look.accent.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(look.glyph, fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Text(tr(category.label), color = look.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EntityChip(text: String) {
    Text(
        text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun PriorityDot(priority: MailPriority) {
    if (priority == MailPriority.NORMAL) return
    val c = if (priority == MailPriority.HIGH) Color(0xFFEF4444) else Color(0xFF94A3B8)
    Box(Modifier.size(8.dp).background(c, CircleShape))
}

/** Concrete reasons only, never an accusation. */
@Composable
fun SuspiciousBanner(reasons: List<String>, onReview: (() -> Unit)? = null) {
    Surface(color = Color(0xFFDC2626).copy(alpha = 0.10f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("⚠  Potentially suspicious"), fontWeight = FontWeight.SemiBold, color = Color(0xFFDC2626))
            reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (onReview != null) OutlinedButton(onClick = onReview, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Review carefully")) }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("MMM d")

fun friendlyTime(millis: Long?): String {
    millis ?: return ""
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) timeFmt.format(t) else dayFmt.format(t)
}
