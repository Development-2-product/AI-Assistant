package com.iamode.app.domain.policy

import com.iamode.app.domain.model.AutoTrigger
import com.iamode.app.domain.model.Session
import com.iamode.app.domain.model.StartSource

/**
 * Decides automatic on/off. Rules that keep it predictable:
 * - A session you started yourself is never turned off automatically.
 * - If you switch off an automatic session, that same trigger won't switch it back on
 *   (e.g. the same meeting); the next meeting or drive will.
 * - When the trigger that started it ends, IA Mode turns off, unless another trigger is active.
 */
object AutoModePolicy {

    sealed interface Action {
        data class TurnOn(val trigger: AutoTrigger) : Action
        data class SwitchTrigger(val trigger: AutoTrigger) : Action
        data object TurnOff : Action
        data object None : Action
    }

    data class Result(val action: Action, val clearSuppression: Boolean)

    fun decide(active: List<AutoTrigger>, session: Session?, suppressedKey: String?): Result {
        val clear = suppressedKey != null && active.none { it.key == suppressedKey }
        val suppressed = if (clear) null else suppressedKey
        val usable = active.filter { it.key != suppressed }

        val action = when {
            session == null -> usable.firstOrNull()?.let { Action.TurnOn(it) } ?: Action.None
            session.startedBy == StartSource.MANUAL -> Action.None
            active.any { it.key == session.autoKey } -> Action.None
            else -> usable.firstOrNull()?.let { Action.SwitchTrigger(it) } ?: Action.TurnOff
        }
        return Result(action, clear)
    }
}
