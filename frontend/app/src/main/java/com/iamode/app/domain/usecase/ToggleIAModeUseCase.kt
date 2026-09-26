package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.AutoTrigger
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.model.StartSource
import com.iamode.app.domain.repository.AutoModeStateStore
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.ModeLifecycleHooks
import com.iamode.app.domain.repository.Notifier
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.repository.SessionRepository
import javax.inject.Inject

class ToggleIAModeUseCase @Inject constructor(
    private val sessions: SessionRepository,
    private val conversations: ConversationRepository,
    private val scheduler: ReplyScheduler,
    private val hooks: ModeLifecycleHooks,
    private val autoState: AutoModeStateStore,
    private val buildSummary: BuildSessionSummaryUseCase,
    private val notifier: Notifier,
) {
    suspend fun isOn(): Boolean = sessions.current() != null

    /** Manual toggle (home screen, tile, widget, notification). */
    suspend fun toggle(): Boolean = if (isOn()) { turnOff(); false } else { turnOn(); true }

    suspend fun turnOn(trigger: AutoTrigger? = null) {
        if (isOn()) return
        if (trigger == null) sessions.start() else sessions.start(StartSource.AUTO, trigger.key, trigger.reason)
        hooks.onModeStarted()
    }

    /**
     * Turns IA Mode off and posts the "While you were busy" summary.
     * [manual] = the user did it; an automatic session they switch off won't restart for the same trigger.
     */
    suspend fun turnOff(manual: Boolean = true): SessionSummary? {
        val session = sessions.current() ?: return null
        // Nothing is sent after IA Mode is switched off.
        conversations.queued().forEach { c ->
            scheduler.cancel(c.id)
            conversations.upsert(c.copy(status = ConversationStatus.PENDING_APPROVAL, autopilot = false, sendAt = null,
                reason = "IA Mode was turned off before this was sent"))
        }
        sessions.stop()
        if (manual && session.startedBy == StartSource.AUTO) autoState.setSuppressedKey(session.autoKey)
        hooks.onModeStopped()
        val summary = buildSummary(session.id)
        if (summary != null && summary.conversations.isNotEmpty()) notifier.sessionSummary(summary)
        return summary
    }

    suspend fun switchTrigger(trigger: AutoTrigger) = sessions.updateAutoTrigger(trigger.key, trigger.reason)
}
