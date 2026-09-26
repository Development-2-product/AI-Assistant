package com.iamode.app.ui.feature.jobs

import com.iamode.app.core.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamode.app.domain.jobs.ApplicationStage

/** Stage colours and glyphs shared by every tracker screen. */
fun ApplicationStage.accent(): Color = when (this) {
    ApplicationStage.SAVED -> Color(0xFF94A3B8)
    ApplicationStage.APPLIED -> Color(0xFF3B82F6)
    ApplicationStage.ASSESSMENT -> Color(0xFF0EA5E9)
    ApplicationStage.INTERVIEW -> Color(0xFF7B61FF)
    ApplicationStage.SELECTED -> Color(0xFFF2B233)
    ApplicationStage.OFFER, ApplicationStage.ACCEPTED -> Color(0xFF12B886)
    ApplicationStage.DECLINED -> Color(0xFF64748B)
    ApplicationStage.REJECTED -> Color(0xFFEF4444)
    ApplicationStage.GHOSTED -> Color(0xFF94A3B8)
}

fun ApplicationStage.glyph(): String = when (this) {
    ApplicationStage.SAVED -> "🔖"; ApplicationStage.APPLIED -> "📨"; ApplicationStage.ASSESSMENT -> "📝"
    ApplicationStage.INTERVIEW -> "🎯"; ApplicationStage.SELECTED -> "🏆"; ApplicationStage.OFFER -> "💼"
    ApplicationStage.ACCEPTED -> "✅"; ApplicationStage.DECLINED -> "↩"; ApplicationStage.REJECTED -> "✕"; ApplicationStage.GHOSTED -> "…"
}

@Composable
fun StageChip(stage: ApplicationStage, modifier: Modifier = Modifier) {
    Row(
        modifier.background(stage.accent().copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stage.glyph(), fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Text(tr(stage.label), color = stage.accent(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

fun daysAgo(millis: Long?, now: Long = System.currentTimeMillis()): String {
    millis ?: return "—"
    val d = ((now - millis) / 86_400_000L).toInt()
    return when {
        d <= 0 -> "today"
        d == 1 -> "yesterday"
        d < 30 -> tr("%1\$s days ago", d)
        else -> tr("%1\$s mo ago", (d / 30))
    }
}
