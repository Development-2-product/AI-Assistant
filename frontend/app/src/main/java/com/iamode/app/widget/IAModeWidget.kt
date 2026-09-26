package com.iamode.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.iamode.app.MainActivity
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun sessions(): SessionRepository
    fun conversations(): ConversationRepository
    fun toggle(): ToggleIAModeUseCase
}

private val NEEDS_YOU = setOf(ConversationStatus.PENDING_APPROVAL, ConversationStatus.CRISIS, ConversationStatus.CALLBACK)

/** Home-screen widget: IA Mode on/off with one tap, and how many chats need you. */
class IAModeWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val session = ep.sessions().current()
        val needsYou = session?.let { s -> ep.conversations().forSession(s.id).count { it.status in NEEDS_YOU } } ?: 0
        provideContent { GlanceTheme { Content(on = session != null, needsYou = needsYou, autoReason = session?.autoReason) } }
    }

    @Composable
    private fun Content(on: Boolean, needsYou: Int, autoReason: String?) {
        Row(
            GlanceModifier.fillMaxSize()
                .background(if (on) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    if (on) "IA Mode is on" else "IA Mode is off",
                    style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                )
                Text(
                    when {
                        !on -> "Tap Turn on when you're busy"
                        needsYou > 0 -> "$needsYou chat${if (needsYou == 1) "" else "s"} need you"
                        autoReason != null -> autoReason
                        else -> "Replying for you"
                    },
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Button(text = if (on) "Turn off" else "Turn on", onClick = actionRunCallback<ToggleModeAction>())
        }
    }

    companion object {
        suspend fun refresh(context: Context) = runCatching { IAModeWidget().updateAll(context) }
    }
}

class ToggleModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        ep.toggle().toggle()
        IAModeWidget.refresh(context)
    }
}

class IAModeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = IAModeWidget()
}
