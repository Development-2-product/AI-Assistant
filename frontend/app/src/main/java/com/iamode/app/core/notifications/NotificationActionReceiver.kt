package com.iamode.app.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.usecase.ConversationActionsUseCase
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles Approve / Write reply / Don't reply straight from the notification. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var actions: ConversationActionsUseCase
    @Inject lateinit var toggle: ToggleIAModeUseCase
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TURN_OFF) {
            val pending = goAsync()
            scope.launch { try { toggle.turnOff() } finally { pending.finish() } }
            return
        }
        val id = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_APPROVE -> actions.approve(id, handleChat = true)
                    ACTION_REJECT -> actions.dontReply(id)
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
                        if (!text.isNullOrBlank()) actions.approve(id, editedText = text.trim(), handleChat = true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.iamode.app.APPROVE"
        const val ACTION_REJECT = "com.iamode.app.REJECT"
        const val ACTION_REPLY = "com.iamode.app.REPLY"
        const val ACTION_TURN_OFF = "com.iamode.app.TURN_OFF"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val KEY_TEXT = "reply_text"
    }
}
